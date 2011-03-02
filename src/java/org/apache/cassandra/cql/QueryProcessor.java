/*
 * 
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 * 
 *   http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 * 
 */

package org.apache.cassandra.cql;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.*;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeoutException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.antlr.runtime.*;
import org.apache.cassandra.auth.Permission;
import org.apache.cassandra.concurrent.Stage;
import org.apache.cassandra.concurrent.StageManager;
import org.apache.cassandra.config.CFMetaData;
import org.apache.cassandra.config.ColumnDefinition;
import org.apache.cassandra.config.ConfigurationException;
import org.apache.cassandra.config.DatabaseDescriptor;
import org.apache.cassandra.config.KSMetaData;
import org.apache.cassandra.db.*;
import org.apache.cassandra.db.filter.QueryPath;
import org.apache.cassandra.db.migration.AddColumnFamily;
import org.apache.cassandra.db.migration.AddKeyspace;
import org.apache.cassandra.db.migration.DropColumnFamily;
import org.apache.cassandra.db.migration.DropKeyspace;
import org.apache.cassandra.db.migration.Migration;
import org.apache.cassandra.db.migration.UpdateColumnFamily;
import org.apache.cassandra.db.migration.avro.CfDef;
import org.apache.cassandra.db.marshal.AbstractType;
import org.apache.cassandra.db.marshal.MarshalException;
import org.apache.cassandra.dht.AbstractBounds;
import org.apache.cassandra.dht.Bounds;
import org.apache.cassandra.dht.IPartitioner;
import org.apache.cassandra.locator.AbstractReplicationStrategy;
import org.apache.cassandra.service.ClientState;
import org.apache.cassandra.service.StorageProxy;
import org.apache.cassandra.service.StorageService;
import org.apache.cassandra.thrift.Column;
import org.apache.cassandra.thrift.*;

import com.google.common.base.Predicates;
import com.google.common.collect.Maps;

import static org.apache.cassandra.thrift.ThriftValidation.validateKey;
import static org.apache.cassandra.thrift.ThriftValidation.validateColumnFamily;

public class QueryProcessor
{
    private static final Logger logger = LoggerFactory.getLogger(QueryProcessor.class);
    
    private static List<org.apache.cassandra.db.Row> getSlice(String keyspace, SelectStatement select)
    throws InvalidRequestException, TimedOutException, UnavailableException
    {
        List<org.apache.cassandra.db.Row> rows = null;
        QueryPath queryPath = new QueryPath(select.getColumnFamily());
        List<ReadCommand> commands = new ArrayList<ReadCommand>();
        
        assert select.getKeys().size() == 1;
        
        ByteBuffer key = select.getKeys().get(0).getByteBuffer();
        validateKey(key);
        
        // ...of a list of column names
        if (!select.isColumnRange())
        {
            Collection<ByteBuffer> columnNames = new ArrayList<ByteBuffer>();
            for (Term column : select.getColumnNames())
                columnNames.add(column.getByteBuffer());
            
            validateColumns(keyspace, select.getColumnFamily(), columnNames);
            commands.add(new SliceByNamesReadCommand(keyspace, key, queryPath, columnNames));
        }
        // ...a range (slice) of column names
        else
        {
            validateColumns(keyspace,
                            select.getColumnFamily(),
                            select.getColumnStart().getByteBuffer(),
                            select.getColumnFinish().getByteBuffer());
            commands.add(new SliceFromReadCommand(keyspace,
                                                  key,
                                                  queryPath,
                                                  select.getColumnStart().getByteBuffer(),
                                                  select.getColumnFinish().getByteBuffer(),
                                                  select.isColumnsReversed(),
                                                  select.getColumnsLimit()));
        }

        try
        {
            rows = StorageProxy.read(commands, select.getConsistencyLevel());
        }
        catch (TimeoutException e)
        {
            throw new TimedOutException();
        }
        catch (IOException e)
        {
            throw new RuntimeException(e);
        }
        
        return rows;
    }
    
    private static List<org.apache.cassandra.db.Row> multiRangeSlice(String keyspace, SelectStatement select)
    throws TimedOutException, UnavailableException, InvalidRequestException
    {
        List<org.apache.cassandra.db.Row> rows = null;
        
        ByteBuffer startKey = (select.getKeyStart() != null) ? select.getKeyStart().getByteBuffer() : (new Term()).getByteBuffer();
        ByteBuffer finishKey = (select.getKeyFinish() != null) ? select.getKeyFinish().getByteBuffer() : (new Term()).getByteBuffer();
        IPartitioner<?> p = StorageService.getPartitioner();
        AbstractBounds bounds = new Bounds(p.getToken(startKey), p.getToken(finishKey));
        
        // XXX: Our use of Thrift structs internally makes me Sad. :(
        SlicePredicate thriftSlicePredicate = slicePredicateFromSelect(select);
        validateSlicePredicate(keyspace, select.getColumnFamily(), thriftSlicePredicate);

        try
        {
            rows = StorageProxy.getRangeSlice(new RangeSliceCommand(keyspace,
                                                                    select.getColumnFamily(),
                                                                    null,
                                                                    thriftSlicePredicate,
                                                                    bounds,
                                                                    select.getNumRecords()),
                                              select.getConsistencyLevel());
        }
        catch (IOException e)
        {
            throw new RuntimeException(e);
        }
        catch (org.apache.cassandra.thrift.UnavailableException e)
        {
            throw new UnavailableException();
        }
        catch (TimeoutException e)
        {
            throw new TimedOutException();
        }
        
        return rows;
    }
    
    private static List<org.apache.cassandra.db.Row> getIndexedSlices(String keyspace, SelectStatement select)
    throws TimedOutException, UnavailableException, InvalidRequestException
    {
        // XXX: Our use of Thrift structs internally (still) makes me Sad. :~(
        SlicePredicate thriftSlicePredicate = slicePredicateFromSelect(select);
        validateSlicePredicate(keyspace, select.getColumnFamily(), thriftSlicePredicate);
        
        List<IndexExpression> expressions = new ArrayList<IndexExpression>();
        for (Relation columnRelation : select.getColumnRelations())
        {
            expressions.add(new IndexExpression(columnRelation.getEntity().getByteBuffer(),
                                                IndexOperator.valueOf(columnRelation.operator().toString()),
                                                columnRelation.getValue().getByteBuffer()));
        }
        
        ByteBuffer startKey = (!select.isKeyRange()) ? (new Term()).getByteBuffer() : select.getKeyStart().getByteBuffer();
        IndexClause thriftIndexClause = new IndexClause(expressions, startKey, select.getNumRecords());
        
        List<org.apache.cassandra.db.Row> rows;
        try
        {
            rows = StorageProxy.scan(keyspace,
                                     select.getColumnFamily(),
                                     thriftIndexClause,
                                     thriftSlicePredicate,
                                     select.getConsistencyLevel());
        }
        catch (IOException e)
        {
            throw new RuntimeException(e);
        }
        catch (TimeoutException e)
        {
            throw new TimedOutException();
        }
        
        return rows;
    }
    
    private static void batchUpdate(ClientState clientState, List<UpdateStatement> updateStatements, ConsistencyLevel consistency)
    throws InvalidRequestException, UnavailableException, TimedOutException
    {
        String keyspace = clientState.getKeyspace();
        List<RowMutation> rowMutations = new ArrayList<RowMutation>();
        List<String> cfamsSeen = new ArrayList<String>();

        for (UpdateStatement update : updateStatements)
        {
            // Avoid unnecessary authorizations.
            if (!(cfamsSeen.contains(update.getColumnFamily())))
            {
                clientState.hasColumnFamilyAccess(update.getColumnFamily(), Permission.WRITE);
                cfamsSeen.add(update.getColumnFamily());
            }
            
            ByteBuffer key = update.getKey().getByteBuffer();
            validateKey(key);
            validateColumnFamily(keyspace, update.getColumnFamily());
            
            RowMutation rm = new RowMutation(keyspace, key);
            for (Map.Entry<Term, Term> column : update.getColumns().entrySet())
            {
                validateColumn(keyspace, update.getColumnFamily(), column.getKey().getByteBuffer());
                rm.add(new QueryPath(update.getColumnFamily(), null, column.getKey().getByteBuffer()),
                       column.getValue().getByteBuffer(),
                       System.currentTimeMillis());
            }
            
            rowMutations.add(rm);
        }
        
        try
        {
            StorageProxy.mutate(rowMutations, consistency);
        }
        catch (org.apache.cassandra.thrift.UnavailableException e)
        {
            throw new UnavailableException();
        }
        catch (TimeoutException e)
        {
            throw new TimedOutException();
        }
    }
    
    private static SlicePredicate slicePredicateFromSelect(SelectStatement select) throws InvalidRequestException
    {
        SlicePredicate thriftSlicePredicate = new SlicePredicate();
        
        if (select.isColumnRange() || select.getColumnNames().size() == 0)
        {
            SliceRange sliceRange = new SliceRange();
            sliceRange.start = select.getColumnStart().getByteBuffer();
            sliceRange.finish = select.getColumnFinish().getByteBuffer();
            sliceRange.reversed = select.isColumnsReversed();
            sliceRange.count = select.getColumnsLimit();
            thriftSlicePredicate.slice_range = sliceRange;
        }
        else
        {
            List<ByteBuffer> columnNames = new ArrayList<ByteBuffer>();
            for (Term column : select.getColumnNames())
                columnNames.add(column.getByteBuffer());
            thriftSlicePredicate.column_names = columnNames;
        }
        
        return thriftSlicePredicate;
    }
    
    /* Test for SELECT-specific taboos */
    private static void validateSelect(String keyspace, SelectStatement select) throws InvalidRequestException
    {
        if (select.isCountOperation() && (select.isKeyRange() || select.getKeys().size() < 1))
            throw new InvalidRequestException("Counts can only be performed for a single record (Hint: KEY=term)");
        
        // Finish key w/o start key (KEY < foo)
        if (!select.isKeyRange() && (select.getKeyFinish() != null))
            throw new InvalidRequestException("Key range clauses must include a start key (i.e. KEY > term)");
        
        // Key range and by-key(s) combined (KEY > foo AND KEY = bar)
        if (select.isKeyRange() && select.getKeys().size() > 0)
            throw new InvalidRequestException("You cannot combine key range and by-key clauses in a SELECT");
        
        // Start and finish keys, *and* column relations (KEY > foo AND KEY < bar and name1 = value1).
        if (select.isKeyRange() && (select.getKeyFinish() != null) && (select.getColumnRelations().size() > 0))
            throw new InvalidRequestException("You cannot combine key range and by-column clauses in a SELECT");
        
        // Multiget scenario (KEY = foo AND KEY = bar ...)
        if (select.getKeys().size() > 1)
            throw new InvalidRequestException("SELECTs can contain only by by-key clause");
        
        if (select.getColumnRelations().size() > 0)
        {
            Set<ByteBuffer> indexed = Table.open(keyspace).getColumnFamilyStore(select.getColumnFamily()).getIndexedColumns();
            for (Relation relation : select.getColumnRelations())
            {
                if ((relation.operator().equals(RelationType.EQ)) && indexed.contains(relation.getEntity().getByteBuffer()))
                    return;
            }
            throw new InvalidRequestException("No indexed columns present in by-columns clause with \"equals\" operator");
        }
    }
    
    // Copypasta from o.a.c.thrift.CassandraDaemon
    private static void applyMigrationOnStage(final Migration m) throws InvalidRequestException
    {
        Future f = StageManager.getStage(Stage.MIGRATION).submit(new Callable()
        {
            public Object call() throws Exception
            {
                m.apply();
                m.announce();
                return null;
            }
        });
        try
        {
            f.get();
        }
        catch (InterruptedException e)
        {
            throw new RuntimeException(e);
        }
        catch (ExecutionException e)
        {
            // this means call() threw an exception. deal with it directly.
            if (e.getCause() != null)
            {
                InvalidRequestException ex = new InvalidRequestException(e.getCause().getMessage());
                ex.initCause(e.getCause());
                throw ex;
            }
            else
            {
                InvalidRequestException ex = new InvalidRequestException(e.getMessage());
                ex.initCause(e);
                throw ex;
            }
        }
    }

    private static void validateColumns(String keyspace, String columnFamily, Iterable<ByteBuffer> columns)
    throws InvalidRequestException
    {
        AbstractType comparator = ColumnFamily.getComparatorFor(keyspace, columnFamily, null);
        for (ByteBuffer name : columns)
        {
            if (name.remaining() > IColumn.MAX_NAME_LENGTH)
                throw new InvalidRequestException();
            if (name.remaining() == 0)
                throw new InvalidRequestException();
            try
            {
                comparator.validate(name);
            }
            catch (MarshalException e)
            {
                throw new InvalidRequestException(e.getMessage());
            }
        }
    }
    
    private static void validateColumns(String keyspace, String columnFamily, ByteBuffer start, ByteBuffer end)
    throws InvalidRequestException
    {
        validateColumns(keyspace, columnFamily, Arrays.asList(start, end));
    }
    
    private static void validateColumn(String keyspace, String columnFamily, ByteBuffer column)
    throws InvalidRequestException
    {
        validateColumns(keyspace, columnFamily, Arrays.asList(column));
    }
    
    private static void validateSlicePredicate(String keyspace, String columnFamily, SlicePredicate predicate)
    throws InvalidRequestException
    {
        if (predicate.slice_range != null)
            validateColumns(keyspace, columnFamily, predicate.slice_range.start, predicate.slice_range.finish);
        else
            validateColumns(keyspace, columnFamily, predicate.column_names);
    }
    
    // Copypasta from CassandraServer (where it is private).
    private static void validateSchemaAgreement() throws InvalidRequestException
    {
        // unreachable hosts don't count towards disagreement
        Map<String, List<String>> versions = Maps.filterKeys(StorageProxy.describeSchemaVersions(),
                                                             Predicates.not(Predicates.equalTo(StorageProxy.UNREACHABLE)));
        if (versions.size() > 1)
            throw new InvalidRequestException("Cluster schema does not yet agree");
    }

    public static CqlResult process(String queryString, ClientState clientState)
    throws RecognitionException, UnavailableException, InvalidRequestException, TimedOutException
    {
        logger.trace("CQL QUERY: {}", queryString);
        
        CqlParser parser = getParser(queryString);
        CQLStatement statement = parser.query();
        parser.throwLastRecognitionError();
        String keyspace = null;
        
        // Chicken-and-egg; No keyspace to get when we're setting one. 
        if (statement.type != StatementType.USE)
            keyspace = clientState.getKeyspace();
        
        CqlResult avroResult = new CqlResult();
        
        logger.debug("CQL statement type: {}", statement.type.toString());
        
        switch (statement.type)
        {
            case SELECT:
                SelectStatement select = (SelectStatement)statement.statement;
                clientState.hasColumnFamilyAccess(select.getColumnFamily(), Permission.READ);
                validateColumnFamily(keyspace, select.getColumnFamily());
                validateSelect(keyspace, select);
                
                List<org.apache.cassandra.db.Row> rows = null;
                
                // By-key
                if (!select.isKeyRange() && (select.getKeys().size() > 0))
                {
                    rows = getSlice(keyspace, select);
                    
                    // Only return the column count, (of the at-most 1 row).
                    if (select.isCountOperation())
                    {
                        avroResult.type = CqlResultType.INT;
                        if (rows.size() > 0)
                            avroResult.setNum(rows.get(0).cf != null ? rows.get(0).cf.getSortedColumns().size() : 0);
                        else
                            avroResult.setNum(0);
                        return avroResult;
                    }
                }
                else
                {
                    // Range query
                    if ((select.getKeyFinish() != null) || (select.getColumnRelations().size() == 0))
                    {
                        rows = multiRangeSlice(keyspace, select);
                    }
                    // Index scan
                    else
                    {
                        rows = getIndexedSlices(keyspace, select);
                    }
                }
                
                List<CqlRow> avroRows = new ArrayList<CqlRow>();
                avroResult.type = CqlResultType.ROWS;
                
                // Create the result set
                for (org.apache.cassandra.db.Row row : rows)
                {
                    /// No results for this row
                    if (row.cf == null)
                        continue;
                    
                    List<Column> avroColumns = new ArrayList<Column>();
                    
                    for (IColumn column : row.cf.getSortedColumns())
                    {
                        if (column.isMarkedForDelete())
                            continue;
                        Column avroColumn = new Column();
                        avroColumn.name = column.name();
                        avroColumn.value = column.value();
                        avroColumn.timestamp = column.timestamp();
                        avroColumns.add(avroColumn);
                    }
                    
                    // Create a new row, add the columns to it, and then add it to the list of rows
                    CqlRow avroRow = new CqlRow();
                    avroRow.key = row.key.key;
                    avroRow.columns = avroColumns;
                    if (select.isColumnsReversed())
                        Collections.reverse(avroRow.columns);
                    avroRows.add(avroRow);
                }
                
                avroResult.rows = avroRows;
                return avroResult;
                
            case UPDATE:
                UpdateStatement update = (UpdateStatement)statement.statement;
                batchUpdate(clientState, Collections.singletonList(update), update.getConsistencyLevel());
                avroResult.type = CqlResultType.VOID;
                return avroResult;
                
            case BATCH_UPDATE:
                BatchUpdateStatement batch = (BatchUpdateStatement)statement.statement;
                
                for (UpdateStatement up : batch.getUpdates())
                    if (up.isSetConsistencyLevel())
                        throw new InvalidRequestException(
                                "Consistency level must be set on the BATCH, not individual UPDATE statements");
                
                batchUpdate(clientState, batch.getUpdates(), batch.getConsistencyLevel());
                avroResult.type = CqlResultType.VOID;
                return avroResult;
                
            case USE:
                clientState.setKeyspace((String)statement.statement);
                avroResult.type = CqlResultType.VOID;
                
                return avroResult;
            
            case TRUNCATE:
                String columnFamily = (String)statement.statement;
                clientState.hasColumnFamilyAccess(columnFamily, Permission.WRITE);
                
                try
                {
                    StorageProxy.truncateBlocking(keyspace, columnFamily);
                }
                catch (TimeoutException e)
                {
                    throw (UnavailableException) new UnavailableException().initCause(e);
                }
                catch (IOException e)
                {
                    throw (UnavailableException) new UnavailableException().initCause(e);
                }
                
                avroResult.type = CqlResultType.VOID;
                return avroResult;
            
            case DELETE:
                DeleteStatement delete = (DeleteStatement)statement.statement;
                clientState.hasColumnFamilyAccess(delete.getColumnFamily(), Permission.WRITE);
                
                List<RowMutation> rowMutations = new ArrayList<RowMutation>();
                for (Term key : delete.getKeys())
                {
                    RowMutation rm = new RowMutation(keyspace, key.getByteBuffer());
                    if (delete.getColumns().size() < 1)     // No columns, delete the row
                        rm.delete(new QueryPath(delete.getColumnFamily()), System.currentTimeMillis());
                    else    // Delete specific columns
                    {
                        for (Term column : delete.getColumns())
                        {
                            validateColumn(keyspace, delete.getColumnFamily(), column.getByteBuffer());
                            rm.delete(new QueryPath(delete.getColumnFamily(), null, column.getByteBuffer()),
                                      System.currentTimeMillis());
                        }
                    }
                    rowMutations.add(rm);
                }
                
                try
                {
                    StorageProxy.mutate(rowMutations, delete.getConsistencyLevel());
                }
                catch (TimeoutException e)
                {
                    throw new TimedOutException();
                }
                
                avroResult.type = CqlResultType.VOID;
                return avroResult;
                
            case CREATE_KEYSPACE:
                CreateKeyspaceStatement create = (CreateKeyspaceStatement)statement.statement;
                create.validate();
                clientState.hasKeyspaceListAccess(Permission.WRITE);
                validateSchemaAgreement();
                
                try
                {
                    KSMetaData ksm = new KSMetaData(create.getName(),
                                                    AbstractReplicationStrategy.getClass(create.getStrategyClass()),
                                                    create.getStrategyOptions(),
                                                    create.getReplicationFactor());
                    applyMigrationOnStage(new AddKeyspace(ksm));
                }
                catch (ConfigurationException e)
                {
                    InvalidRequestException ex = new InvalidRequestException(e.getMessage());
                    ex.initCause(e);
                    throw ex;
                }
                catch (IOException e)
                {
                    InvalidRequestException ex = new InvalidRequestException(e.getMessage());
                    ex.initCause(e);
                    throw ex;
                }
                
                avroResult.type = CqlResultType.VOID;
                return avroResult;
               
            case CREATE_COLUMNFAMILY:
                CreateColumnFamilyStatement createCf = (CreateColumnFamilyStatement)statement.statement;
                clientState.hasColumnFamilyListAccess(Permission.WRITE);
                validateSchemaAgreement();
                
                try
                {
                    applyMigrationOnStage(new AddColumnFamily(createCf.getCFMetaData(keyspace)));
                }
                catch (ConfigurationException e)
                {
                    InvalidRequestException ex = new InvalidRequestException(e.toString());
                    ex.initCause(e);
                    throw ex;
                }
                catch (IOException e)
                {
                    InvalidRequestException ex = new InvalidRequestException(e.toString());
                    ex.initCause(e);
                    throw ex;
                }
                
                avroResult.type = CqlResultType.VOID;
                return avroResult;
                
            case CREATE_INDEX:
                CreateIndexStatement createIdx = (CreateIndexStatement)statement.statement;
                clientState.hasColumnFamilyListAccess(Permission.WRITE);
                validateSchemaAgreement();
                CFMetaData oldCfm = DatabaseDescriptor.getCFMetaData(CFMetaData.getId(keyspace,
                                                                                      createIdx.getColumnFamily()));
                if (oldCfm == null)
                    throw new InvalidRequestException("No such column family: " + createIdx.getColumnFamily());
                
                ByteBuffer columnName = createIdx.getColumnName().getByteBuffer();
                ColumnDefinition columnDef = oldCfm.getColumn_metadata().get(columnName);
                
                // Meta-data for this column already exists
                if (columnDef != null)
                {
                    // This column is already indexed, stop, drop, and roll.
                    if (columnDef.getIndexType() != null)
                        throw new InvalidRequestException("Index exists");
                    // Add index attrs to the existing definition
                    columnDef.setIndexName(createIdx.getIndexName());
                    columnDef.setIndexType(org.apache.cassandra.thrift.IndexType.KEYS);
                }
                // No meta-data, create a new column definition from scratch.
                else
                {
                    try
                    {
                        columnDef = new ColumnDefinition(columnName,
                                                         null,
                                                         org.apache.cassandra.thrift.IndexType.KEYS,
                                                         createIdx.getIndexName());
                    }
                    catch (ConfigurationException e)
                    {
                        // This should never happen
                        throw new RuntimeException("Unexpected error creating ColumnDefinition", e);
                    }
                }
                
                CfDef cfamilyDef = CFMetaData.convertToAvro(oldCfm);
                cfamilyDef.column_metadata.add(columnDef.deflate());
                
                try
                {
                    applyMigrationOnStage(new UpdateColumnFamily(cfamilyDef));
                }
                catch (ConfigurationException e)
                {
                    InvalidRequestException ex = new InvalidRequestException(e.toString());
                    ex.initCause(e);
                    throw ex;
                }
                catch (IOException e)
                {
                    InvalidRequestException ex = new InvalidRequestException(e.toString());
                    ex.initCause(e);
                    throw ex;
                }
                
                avroResult.type = CqlResultType.VOID;
                return avroResult;
                
            case DROP_KEYSPACE:
                String deleteKeyspace = (String)statement.statement;
                clientState.hasKeyspaceListAccess(Permission.WRITE);
                validateSchemaAgreement();
                
                try
                {
                    applyMigrationOnStage(new DropKeyspace(deleteKeyspace));
                }
                catch (ConfigurationException e)
                {
                    InvalidRequestException ex = new InvalidRequestException(e.getMessage());
                    ex.initCause(e);
                    throw ex;
                }
                catch (IOException e)
                {
                    InvalidRequestException ex = new InvalidRequestException(e.getMessage());
                    ex.initCause(e);
                    throw ex;
                }
                
                avroResult.type = CqlResultType.VOID;
                return avroResult;
            
            case DROP_COLUMNFAMILY:
                String deleteColumnFamily = (String)statement.statement;
                clientState.hasColumnFamilyListAccess(Permission.WRITE);
                validateSchemaAgreement();
                    
                try
                {
                    applyMigrationOnStage(new DropColumnFamily(keyspace, deleteColumnFamily));
                }
                catch (ConfigurationException e)
                {
                    InvalidRequestException ex = new InvalidRequestException(e.getMessage());
                    ex.initCause(e);
                    throw ex;
                }
                catch (IOException e)
                {
                    InvalidRequestException ex = new InvalidRequestException(e.getMessage());
                    ex.initCause(e);
                    throw ex;
                }
                
                avroResult.type = CqlResultType.VOID;
                return avroResult;
                
        }
        
        return null;    // We should never get here.
    }
    
    private static CqlParser getParser(String queryStr)
    {
        CharStream stream = new ANTLRStringStream(queryStr);
        CqlLexer lexer = new CqlLexer(stream);
        TokenStream tokenStream = new CommonTokenStream(lexer);
        return new CqlParser(tokenStream);
    }
}
