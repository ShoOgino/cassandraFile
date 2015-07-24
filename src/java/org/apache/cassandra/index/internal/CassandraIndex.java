package org.apache.cassandra.index.internal;

import java.nio.ByteBuffer;
import java.util.*;
import java.util.concurrent.Callable;
import java.util.concurrent.Future;
import java.util.function.BiFunction;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.apache.cassandra.config.CFMetaData;
import org.apache.cassandra.config.ColumnDefinition;
import org.apache.cassandra.cql3.Operator;
import org.apache.cassandra.cql3.statements.IndexTarget;
import org.apache.cassandra.db.*;
import org.apache.cassandra.db.compaction.CompactionManager;
import org.apache.cassandra.db.filter.RowFilter;
import org.apache.cassandra.db.lifecycle.SSTableSet;
import org.apache.cassandra.db.lifecycle.View;
import org.apache.cassandra.db.marshal.AbstractType;
import org.apache.cassandra.db.marshal.CollectionType;
import org.apache.cassandra.db.partitions.PartitionIterator;
import org.apache.cassandra.db.partitions.PartitionUpdate;
import org.apache.cassandra.db.rows.*;
import org.apache.cassandra.dht.LocalPartitioner;
import org.apache.cassandra.exceptions.InvalidRequestException;
import org.apache.cassandra.index.Index;
import org.apache.cassandra.index.IndexRegistry;
import org.apache.cassandra.index.SecondaryIndexBuilder;
import org.apache.cassandra.index.internal.composites.CompositesSearcher;
import org.apache.cassandra.index.internal.keys.KeysSearcher;
import org.apache.cassandra.index.transactions.IndexTransaction;
import org.apache.cassandra.index.transactions.UpdateTransaction;
import org.apache.cassandra.io.sstable.ReducingKeyIterator;
import org.apache.cassandra.io.sstable.format.SSTableReader;
import org.apache.cassandra.schema.IndexMetadata;
import org.apache.cassandra.utils.FBUtilities;
import org.apache.cassandra.utils.concurrent.OpOrder;
import org.apache.cassandra.utils.concurrent.Refs;

/**
 * Index implementation which indexes the values for a single column in the base
 * table and which stores its index data in a local, hidden table.
 */
public abstract class CassandraIndex implements Index
{
    private static final Logger logger = LoggerFactory.getLogger(CassandraIndex.class);

    public final ColumnFamilyStore baseCfs;
    protected IndexMetadata metadata;
    protected ColumnFamilyStore indexCfs;
    protected ColumnDefinition indexedColumn;
    protected CassandraIndexFunctions functions;

    public CassandraIndex(ColumnFamilyStore baseCfs, IndexMetadata indexDef)
    {
        this.baseCfs = baseCfs;
        setMetadata(indexDef);
    }

    /**
     * Returns true if an index of this type can support search predicates of the form [column] OPERATOR [value]
     * @param indexedColumn
     * @param operator
     * @return
     */
    protected boolean supportsOperator(ColumnDefinition indexedColumn, Operator operator)
    {
        return operator.equals(Operator.EQ);
    }

    /**
     * Used to construct an the clustering for an entry in the index table based on values from the base data.
     * The clustering columns in the index table encode the values required to retrieve the correct data from the base
     * table and varies depending on the kind of the indexed column. See indexCfsMetadata for more details
     * Used whenever a row in the index table is written or deleted.
     * @param metadata
     * @param partitionKey from the base data being indexed
     * @param prefix from the base data being indexed
     * @param path from the base data being indexed
     * @return a clustering prefix to be used to insert into the index table
     */
    protected abstract CBuilder buildIndexClusteringPrefix(ByteBuffer partitionKey,
                                                           ClusteringPrefix prefix,
                                                           CellPath path);

    /**
     * Used at search time to convert a row in the index table into a simple struct containing the values required
     * to retrieve the corresponding row from the base table.
     * @param metadata
     * @param indexedValue the partition key of the indexed table (i.e. the value that was indexed)
     * @param indexEntry a row from the index table
     * @return
     */
    public abstract IndexEntry decodeEntry(DecoratedKey indexedValue,
                                           Row indexEntry);

    /**
     * Check whether a value retrieved from an index is still valid by comparing it to current row from the base table.
     * Used at read time to identify out of date index entries so that they can be excluded from search results and
     * repaired
     * @param metadata required to get the indexed column definition
     * @param row the current row from the primary data table
     * @param indexValue the value we retrieved from the index
     * @param nowInSec
     * @return true if the index is out of date and the entry should be dropped
     */
    public abstract boolean isStale(Row row, ByteBuffer indexValue, int nowInSec);

    /**
     * Extract the value to be inserted into the index from the components of the base data
     * @param metadata
     * @param partitionKey from the primary data
     * @param clustering from the primary data
     * @param path from the primary data
     * @param cellValue from the primary data
     * @return a ByteBuffer containing the value to be inserted in the index. This will be used to make the partition
     * key in the index table
     */
    protected abstract ByteBuffer getIndexedValue(ByteBuffer partitionKey,
                                                  Clustering clustering,
                                                  CellPath path,
                                                  ByteBuffer cellValue);

    public ColumnDefinition getIndexedColumn()
    {
        return indexedColumn;
    }

    public ClusteringComparator getIndexComparator()
    {
        return indexCfs.metadata.comparator;
    }

    public ColumnFamilyStore getIndexCfs()
    {
        return indexCfs;
    }

    public void register(IndexRegistry registry)
    {
        registry.registerIndex(this);
    }

    public Callable<?> getInitializationTask()
    {
        // if we're just linking in the index on an already-built index post-restart
        // we've nothing to do. Otherwise, submit for building via SecondaryIndexBuilder
        return isBuilt() ? null : getBuildIndexTask();
    }

    public IndexMetadata getIndexMetadata()
    {
        return metadata;
    }

    public String getIndexName()
    {
        // should return metadata.name, see CASSANDRA-10127
        return indexCfs.name;
    }

    public Optional<ColumnFamilyStore> getBackingTable()
    {
        return indexCfs == null ? Optional.empty() : Optional.of(indexCfs);
    }

    public Callable<Void> getBlockingFlushTask()
    {
        return () -> {
            indexCfs.forceBlockingFlush();
            return null;
        };
    }

    public Callable<?> getInvalidateTask()
    {
        return () -> {
            markRemoved();
            invalidate();
            return null;
        };
    }

    public Callable<?> getMetadataReloadTask(IndexMetadata indexDef)
    {
        setMetadata(indexDef);
        return () -> {
            indexCfs.metadata.reloadIndexMetadataProperties(baseCfs.metadata);
            indexCfs.reload();
            return null;
        };
    }

    private void setMetadata(IndexMetadata indexDef)
    {
        metadata = indexDef;
        functions = getFunctions(baseCfs.metadata, indexDef);
        CFMetaData cfm = indexCfsMetadata(baseCfs.metadata, indexDef);
        indexCfs = ColumnFamilyStore.createColumnFamilyStore(baseCfs.keyspace,
                                                             cfm.cfName,
                                                             cfm,
                                                             baseCfs.getTracker().loadsstables);
        assert indexDef.columns.size() == 1 : "Build in indexes on multiple target columns are not supported";
        indexedColumn = indexDef.indexedColumn(baseCfs.metadata);
    }

    public Callable<?> getTruncateTask(final long truncatedAt)
    {
        return () -> {
            indexCfs.discardSSTables(truncatedAt);
            return null;
        };
    }

    public boolean indexes(PartitionColumns columns)
    {
        // if we have indexes on the partition key or clustering columns, return true
        return isPrimaryKeyIndex() || columns.contains(indexedColumn);
    }

    public boolean supportsExpression(ColumnDefinition column, Operator operator)
    {
        return indexedColumn.name.equals(column.name)
               && supportsOperator(indexedColumn, operator);
    }

    private boolean supportsExpression(RowFilter.Expression expression)
    {
        return supportsExpression(expression.column(), expression.operator());
    }

    public long getEstimatedResultRows()
    {
        return indexCfs.getMeanColumns();
    }

    /**
     * No post processing of query results, just return them unchanged
     */
    public BiFunction<PartitionIterator, ReadCommand, PartitionIterator> postProcessorFor(ReadCommand command)
    {
        return (partitionIterator, readCommand) -> partitionIterator;
    }

    public RowFilter getPostIndexQueryFilter(RowFilter filter)
    {
        return getTargetExpression(filter.getExpressions()).map(filter::without)
                                                           .orElse(filter);
    }

    private Optional<RowFilter.Expression> getTargetExpression(List<RowFilter.Expression> expressions)
    {
        return expressions.stream().filter(this::supportsExpression).findFirst();
    }

    public Index.Searcher searcherFor(ReadCommand command)
    {
        Optional<RowFilter.Expression> target = getTargetExpression(command.rowFilter().getExpressions());

        if (target.isPresent())
        {
            target.get().validateForIndexing();
            switch (getIndexMetadata().indexType)
            {
                case COMPOSITES:
                    return new CompositesSearcher(command, target.get(), this);
                case KEYS:
                    return new KeysSearcher(command, target.get(), this);
                default:
                    throw new IllegalStateException(String.format("Unsupported index type %s for index %s on %s",
                                                                  metadata.indexType,
                                                                  metadata.name,
                                                                  indexedColumn.name.toString()));
            }
        }

        return null;

    }

    public void validate(PartitionUpdate update) throws InvalidRequestException
    {
        switch (indexedColumn.kind)
        {
            case PARTITION_KEY:
                validatePartitionKey(update.partitionKey());
                break;
            case CLUSTERING:
                validateClusterings(update);
                break;
            case REGULAR:
                validateRows(update);
                break;
            case STATIC:
                validateRows(Collections.singleton(update.staticRow()));
                break;
        }
    }

    public Indexer indexerFor(final DecoratedKey key,
                              final int nowInSec,
                              final OpOrder.Group opGroup,
                              final IndexTransaction.Type transactionType)
    {
        return new Indexer()
        {
            public void begin()
            {
            }

            public void partitionDelete(DeletionTime deletionTime)
            {
            }

            public void rangeTombstone(RangeTombstone tombstone)
            {
            }

            public void insertRow(Row row)
            {
                if (isPrimaryKeyIndex())
                {
                    indexPrimaryKey(row.clustering(),
                                    getPrimaryKeyIndexLiveness(row),
                                    row.deletion());
                }
                else
                {
                    if (indexedColumn.isComplex())
                        indexCells(row.clustering(), row.getComplexColumnData(indexedColumn));
                    else
                        indexCell(row.clustering(), row.getCell(indexedColumn));
                }
            }

            public void removeRow(Row row)
            {
                if (isPrimaryKeyIndex())
                    indexPrimaryKey(row.clustering(), row.primaryKeyLivenessInfo(), row.deletion());

                if (indexedColumn.isComplex())
                    removeCells(row.clustering(), row.getComplexColumnData(indexedColumn));
                else
                    removeCell(row.clustering(), row.getCell(indexedColumn));
            }


            public void updateRow(Row oldRow, Row newRow)
            {
                if (isPrimaryKeyIndex())
                    indexPrimaryKey(newRow.clustering(),
                                    newRow.primaryKeyLivenessInfo(),
                                    newRow.deletion());

                if (indexedColumn.isComplex())
                {
                    indexCells(newRow.clustering(), newRow.getComplexColumnData(indexedColumn));
                    removeCells(oldRow.clustering(), oldRow.getComplexColumnData(indexedColumn));
                }
                else
                {
                    indexCell(newRow.clustering(), newRow.getCell(indexedColumn));
                    removeCell(oldRow.clustering(), oldRow.getCell(indexedColumn));
                }
            }

            public void finish()
            {
            }

            private void indexCells(Clustering clustering, Iterable<Cell> cells)
            {
                if (cells == null)
                    return;

                for (Cell cell : cells)
                    indexCell(clustering, cell);
            }

            private void indexCell(Clustering clustering, Cell cell)
            {
                if (cell == null || !cell.isLive(nowInSec))
                    return;

                insert(key.getKey(),
                       clustering,
                       cell,
                       LivenessInfo.create(cell.timestamp(), cell.ttl(), cell.localDeletionTime()),
                       opGroup);
            }

            private void removeCells(Clustering clustering, Iterable<Cell> cells)
            {
                if (cells == null)
                    return;

                for (Cell cell : cells)
                    removeCell(clustering, cell);
            }

            private void removeCell(Clustering clustering, Cell cell)
            {
                if (cell == null || !cell.isLive(nowInSec))
                    return;

                delete(key.getKey(), clustering, cell, opGroup, nowInSec);
            }

            private void indexPrimaryKey(final Clustering clustering,
                                         final LivenessInfo liveness,
                                         final DeletionTime deletion)
            {
                if (liveness.timestamp() != LivenessInfo.NO_TIMESTAMP)
                    insert(key.getKey(), clustering, null, liveness, opGroup);

                if (!deletion.isLive())
                    delete(key.getKey(), clustering, deletion, opGroup);
            }

            private LivenessInfo getPrimaryKeyIndexLiveness(Row row)
            {
                long timestamp = row.primaryKeyLivenessInfo().timestamp();
                int ttl = row.primaryKeyLivenessInfo().ttl();
                for (Cell cell : row.cells())
                {
                    long cellTimestamp = cell.timestamp();
                    if (cell.isLive(nowInSec))
                    {
                        if (cellTimestamp > timestamp)
                        {
                            timestamp = cellTimestamp;
                            ttl = cell.ttl();
                        }
                    }
                }
                return LivenessInfo.create(baseCfs.metadata, timestamp, ttl, nowInSec);
            }
        };
    }

    /**
     * Specific to internal indexes, this is called by a
     * searcher when it encounters a stale entry in the index
     * @param indexKey the partition key in the index table
     * @param indexClustering the clustering in the index table
     * @param deletion deletion timestamp etc
     * @param opGroup the operation under which to perform the deletion
     */
    public void deleteStaleEntry(DecoratedKey indexKey,
                                 Clustering indexClustering,
                                 DeletionTime deletion,
                                 OpOrder.Group opGroup)
    {
        doDelete(indexKey, indexClustering, deletion, opGroup);
        logger.debug("Removed index entry for stale value {}", indexKey);
    }

    /**
     * Called when adding a new entry to the index
     */
    private void insert(ByteBuffer rowKey,
                        Clustering clustering,
                        Cell cell,
                        LivenessInfo info,
                        OpOrder.Group opGroup)
    {
        DecoratedKey valueKey = getIndexKeyFor(getIndexedValue(rowKey,
                                                               clustering,
                                                               cell));
        Row row = BTreeRow.noCellLiveRow(buildIndexClustering(rowKey, clustering, cell), info);
        PartitionUpdate upd = partitionUpdate(valueKey, row);
        indexCfs.apply(upd, UpdateTransaction.NO_OP, opGroup, null);
        logger.debug("Inserted entry into index for value {}", valueKey);
    }

    /**
     * Called when deleting entries on non-primary key columns
     */
    private void delete(ByteBuffer rowKey,
                        Clustering clustering,
                        Cell cell,
                        OpOrder.Group opGroup,
                        int nowInSec)
    {
        DecoratedKey valueKey = getIndexKeyFor(getIndexedValue(rowKey,
                                                               clustering,
                                                               cell));
        doDelete(valueKey,
                 buildIndexClustering(rowKey, clustering, cell),
                 new DeletionTime(cell.timestamp(), nowInSec),
                 opGroup);
    }

    /**
     * Called when deleting entries from indexes on primary key columns
     */
    private void delete(ByteBuffer rowKey,
                        Clustering clustering,
                        DeletionTime deletion,
                        OpOrder.Group opGroup)
    {
        DecoratedKey valueKey = getIndexKeyFor(getIndexedValue(rowKey,
                                                               clustering,
                                                               null));
        doDelete(valueKey,
                 buildIndexClustering(rowKey, clustering, null),
                 deletion,
                 opGroup);
    }

    private void doDelete(DecoratedKey indexKey,
                          Clustering indexClustering,
                          DeletionTime deletion,
                          OpOrder.Group opGroup)
    {
        Row row = BTreeRow.emptyDeletedRow(indexClustering, deletion);
        PartitionUpdate upd = partitionUpdate(indexKey, row);
        indexCfs.apply(upd, UpdateTransaction.NO_OP, opGroup, null);
        logger.debug("Removed index entry for value {}", indexKey);
    }

    private void validatePartitionKey(DecoratedKey partitionKey) throws InvalidRequestException
    {
        assert indexedColumn.isPartitionKey();
        validateIndexedValue(getIndexedValue(partitionKey.getKey(), null, null ));
    }

    private void validateClusterings(PartitionUpdate update) throws InvalidRequestException
    {
        assert indexedColumn.isClusteringColumn();
        for (Row row : update)
            validateIndexedValue(getIndexedValue(null, row.clustering(), null));
    }

    private void validateRows(Iterable<Row> rows)
    {
        assert !indexedColumn.isPrimaryKeyColumn();
        for (Row row : rows)
        {
            if (indexedColumn.isComplex())
            {
                ComplexColumnData data = row.getComplexColumnData(indexedColumn);
                if (data != null)
                {
                    for (Cell cell : data)
                    {
                        validateIndexedValue(getIndexedValue(null, null, cell.path(), cell.value()));
                    }
                }
            }
            else
            {
                validateIndexedValue(getIndexedValue(null, null, row.getCell(indexedColumn)));
            }
        }
    }

    private void validateIndexedValue(ByteBuffer value)
    {
        if (value != null && value.remaining() >= FBUtilities.MAX_UNSIGNED_SHORT)
            throw new InvalidRequestException(String.format(
                                                           "Cannot index value of size %d for index %s on %s.%s(%s) (maximum allowed size=%d)",
                                                           value.remaining(),
                                                           getIndexName(),
                                                           baseCfs.metadata.ksName,
                                                           baseCfs.metadata.cfName,
                                                           indexedColumn.name.toString(),
                                                           FBUtilities.MAX_UNSIGNED_SHORT));
    }

    private ByteBuffer getIndexedValue(ByteBuffer rowKey,
                                       Clustering clustering,
                                       Cell cell)
    {
        return getIndexedValue(rowKey,
                               clustering,
                               cell == null ? null : cell.path(),
                               cell == null ? null : cell.value()
        );
    }

    private Clustering buildIndexClustering(ByteBuffer rowKey,
                                            Clustering clustering,
                                            Cell cell)
    {
        return buildIndexClusteringPrefix(rowKey,
                                          clustering,
                                          cell == null ? null : cell.path()).build();
    }

    private DecoratedKey getIndexKeyFor(ByteBuffer value)
    {
        return indexCfs.decorateKey(value);
    }

    private PartitionUpdate partitionUpdate(DecoratedKey valueKey, Row row)
    {
        return PartitionUpdate.singleRowUpdate(indexCfs.metadata, valueKey, row);
    }

    private void invalidate()
    {
        // interrupt in-progress compactions
        Collection<ColumnFamilyStore> cfss = Collections.singleton(indexCfs);
        CompactionManager.instance.interruptCompactionForCFs(cfss, true);
        CompactionManager.instance.waitForCessation(cfss);
        indexCfs.keyspace.writeOrder.awaitNewBarrier();
        indexCfs.forceBlockingFlush();
        indexCfs.readOrdering.awaitNewBarrier();
        indexCfs.invalidate();
    }

    private boolean isBuilt()
    {
        return SystemKeyspace.isIndexBuilt(baseCfs.keyspace.getName(), getIndexName());
    }

    private void markBuilt()
    {
        SystemKeyspace.setIndexBuilt(baseCfs.keyspace.getName(), getIndexName());
    }

    private void markRemoved()
    {
        SystemKeyspace.setIndexRemoved(baseCfs.keyspace.getName(), getIndexName());
    }

    private boolean isPrimaryKeyIndex()
    {
        return indexedColumn.isPrimaryKeyColumn();
    }

    private Callable<?> getBuildIndexTask()
    {
        return () -> {
            buildBlocking();
            return null;
        };
    }

    private void buildBlocking()
    {
        baseCfs.forceBlockingFlush();

        try (ColumnFamilyStore.RefViewFragment viewFragment = baseCfs.selectAndReference(View.select(SSTableSet.CANONICAL));
             Refs<SSTableReader> sstables = viewFragment.refs)
        {
            if (sstables.isEmpty())
            {
                logger.info("No SSTable data for {}.{} to build index {} from, marking empty index as built",
                            baseCfs.metadata.ksName,
                            baseCfs.metadata.cfName,
                            getIndexName());
                markBuilt();
                return;
            }

            logger.info("Submitting index build of {} for data in {}",
                        getIndexName(),
                        getSSTableNames(sstables));

            SecondaryIndexBuilder builder = new SecondaryIndexBuilder(baseCfs,
                                                                      Collections.singleton(this),
                                                                      new ReducingKeyIterator(sstables));
            Future<?> future = CompactionManager.instance.submitIndexBuild(builder);
            FBUtilities.waitOnFuture(future);
            indexCfs.forceBlockingFlush();
            markBuilt();
        }
        logger.info("Index build of {} complete", getIndexName());
    }

    private static String getSSTableNames(Collection<SSTableReader> sstables)
    {
        return StreamSupport.stream(sstables.spliterator(), false)
                            .map(SSTableReader::toString)
                            .collect(Collectors.joining(", "));
    }

    /**
     * Construct the CFMetadata for an index table, the clustering columns in the index table
     * vary dependent on the kind of the indexed value.
     * @param baseCfsMetadata
     * @param indexMetadata
     * @return
     */
    public static final CFMetaData indexCfsMetadata(CFMetaData baseCfsMetadata, IndexMetadata indexMetadata)
    {
        CassandraIndexFunctions utils = getFunctions(baseCfsMetadata, indexMetadata);
        ColumnDefinition indexedColumn = indexMetadata.indexedColumn(baseCfsMetadata);
        AbstractType<?> indexedValueType = utils.getIndexedValueType(indexedColumn);
        CFMetaData.Builder builder = CFMetaData.Builder.create(baseCfsMetadata.ksName,
                                                               baseCfsMetadata.indexColumnFamilyName(indexMetadata))
                                                       .withId(baseCfsMetadata.cfId)
                                                       .withPartitioner(new LocalPartitioner(indexedValueType))
                                                       .addPartitionKey(indexedColumn.name, indexedColumn.type);

        builder.addClusteringColumn("partition_key", baseCfsMetadata.partitioner.partitionOrdering());
        builder = utils.addIndexClusteringColumns(builder, baseCfsMetadata, indexedColumn);
        return builder.build().reloadIndexMetadataProperties(baseCfsMetadata);
    }

    /**
     * Factory method for new CassandraIndex instances
     * @param baseCfs
     * @param indexMetadata
     * @return
     */
    public static final CassandraIndex newIndex(ColumnFamilyStore baseCfs, IndexMetadata indexMetadata)
    {
        return getFunctions(baseCfs.metadata, indexMetadata).newIndexInstance(baseCfs, indexMetadata);
    }

    private static CassandraIndexFunctions getFunctions(CFMetaData baseCfMetadata, IndexMetadata indexDef)
    {
        if (indexDef.isKeys())
            return CassandraIndexFunctions.KEYS_INDEX_FUNCTIONS;

        ColumnDefinition indexedColumn = indexDef.indexedColumn(baseCfMetadata);
        if (indexedColumn.type.isCollection() && indexedColumn.type.isMultiCell())
        {
            switch (((CollectionType)indexedColumn.type).kind)
            {
                case LIST:
                    return CassandraIndexFunctions.COLLECTION_VALUE_INDEX_FUNCTIONS;
                case SET:
                    return CassandraIndexFunctions.COLLECTION_KEY_INDEX_FUNCTIONS;
                case MAP:
                    if (indexDef.options.containsKey(IndexTarget.INDEX_KEYS_OPTION_NAME))
                        return CassandraIndexFunctions.COLLECTION_KEY_INDEX_FUNCTIONS;
                    else if (indexDef.options.containsKey(IndexTarget.INDEX_ENTRIES_OPTION_NAME))
                        return CassandraIndexFunctions.COLLECTION_ENTRY_INDEX_FUNCTIONS;
                    else
                        return CassandraIndexFunctions.COLLECTION_VALUE_INDEX_FUNCTIONS;
            }
        }

        switch (indexedColumn.kind)
        {
            case CLUSTERING:
                return CassandraIndexFunctions.CLUSTERING_COLUMN_INDEX_FUNCTIONS;
            case REGULAR:
                return CassandraIndexFunctions.REGULAR_COLUMN_INDEX_FUNCTIONS;
            case PARTITION_KEY:
                return CassandraIndexFunctions.PARTITION_KEY_INDEX_FUNCTIONS;
            //case COMPACT_VALUE:
            //    return new CompositesIndexOnCompactValue();
        }
        throw new AssertionError();
    }
}
