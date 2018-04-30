/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.cassandra.io.sstable;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;

import com.google.common.collect.Sets;
import org.junit.Assert;
import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.runner.RunWith;

import org.apache.cassandra.OrderedJUnit4ClassRunner;
import org.apache.cassandra.SchemaLoader;
import org.apache.cassandra.Util;
import org.apache.cassandra.cql3.Operator;
import org.apache.cassandra.db.*;
import org.apache.cassandra.db.compaction.CompactionManager;
import org.apache.cassandra.db.compaction.OperationType;
import org.apache.cassandra.db.lifecycle.LifecycleTransaction;
import org.apache.cassandra.db.partitions.UnfilteredPartitionIterators;
import org.apache.cassandra.db.rows.Row;
import org.apache.cassandra.dht.IPartitioner;
import org.apache.cassandra.dht.LocalPartitioner.LocalToken;
import org.apache.cassandra.dht.Range;
import org.apache.cassandra.dht.Token;
import org.apache.cassandra.index.Index;
import org.apache.cassandra.io.sstable.format.SSTableReader;
import org.apache.cassandra.io.util.FileDataInput;
import org.apache.cassandra.io.util.MmappedRegions;
import org.apache.cassandra.schema.CachingParams;
import org.apache.cassandra.schema.KeyspaceParams;
import org.apache.cassandra.schema.TableMetadata;
import org.apache.cassandra.service.CacheService;
import org.apache.cassandra.utils.ByteBufferUtil;
import org.apache.cassandra.utils.FilterFactory;
import static org.apache.cassandra.cql3.QueryProcessor.executeInternal;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

@RunWith(OrderedJUnit4ClassRunner.class)
public class SSTableReaderTest
{
    public static final String KEYSPACE1 = "SSTableReaderTest";
    public static final String CF_STANDARD = "Standard1";
    public static final String CF_STANDARD2 = "Standard2";
    public static final String CF_INDEXED = "Indexed1";
    public static final String CF_STANDARDLOWINDEXINTERVAL = "StandardLowIndexInterval";

    private IPartitioner partitioner;

    Token t(int i)
    {
        return partitioner.getToken(ByteBufferUtil.bytes(String.valueOf(i)));
    }

    @BeforeClass
    public static void defineSchema() throws Exception
    {
        SchemaLoader.prepareServer();
        SchemaLoader.createKeyspace(KEYSPACE1,
                                    KeyspaceParams.simple(1),
                                    SchemaLoader.standardCFMD(KEYSPACE1, CF_STANDARD),
                                    SchemaLoader.standardCFMD(KEYSPACE1, CF_STANDARD2),
                                    SchemaLoader.compositeIndexCFMD(KEYSPACE1, CF_INDEXED, true),
                                    SchemaLoader.standardCFMD(KEYSPACE1, CF_STANDARDLOWINDEXINTERVAL)
                                                .minIndexInterval(8)
                                                .maxIndexInterval(256)
                                                .caching(CachingParams.CACHE_NOTHING));
    }

    @Test
    public void testGetPositionsForRanges()
    {
        Keyspace keyspace = Keyspace.open(KEYSPACE1);
        ColumnFamilyStore store = keyspace.getColumnFamilyStore("Standard2");
        partitioner = store.getPartitioner();

        // insert data and compact to a single sstable
        CompactionManager.instance.disableAutoCompaction();
        for (int j = 0; j < 10; j++)
        {
            new RowUpdateBuilder(store.metadata(), j, String.valueOf(j))
                .clustering("0")
                .add("val", ByteBufferUtil.EMPTY_BYTE_BUFFER)
                .build()
                .applyUnsafe();
        }
        store.forceBlockingFlush();
        CompactionManager.instance.performMaximal(store, false);

        List<Range<Token>> ranges = new ArrayList<Range<Token>>();
        // 1 key
        ranges.add(new Range<>(t(0), t(1)));
        // 2 keys
        ranges.add(new Range<>(t(2), t(4)));
        // wrapping range from key to end
        ranges.add(new Range<>(t(6), partitioner.getMinimumToken()));
        // empty range (should be ignored)
        ranges.add(new Range<>(t(9), t(91)));

        // confirm that positions increase continuously
        SSTableReader sstable = store.getLiveSSTables().iterator().next();
        long previous = -1;
        for (SSTableReader.PartitionPositionBounds section : sstable.getPositionsForRanges(ranges))
        {
            assert previous <= section.lowerPosition : previous + " ! < " + section.lowerPosition;
            assert section.lowerPosition < section.upperPosition : section.lowerPosition + " ! < " + section.upperPosition;
            previous = section.upperPosition;
        }
    }

    @Test
    public void testSpannedIndexPositions() throws IOException
    {
        int originalMaxSegmentSize = MmappedRegions.MAX_SEGMENT_SIZE;
        MmappedRegions.MAX_SEGMENT_SIZE = 40; // each index entry is ~11 bytes, so this will generate lots of segments

        try
        {
            Keyspace keyspace = Keyspace.open(KEYSPACE1);
            ColumnFamilyStore store = keyspace.getColumnFamilyStore("Standard1");
            partitioner = store.getPartitioner();

            // insert a bunch of data and compact to a single sstable
            CompactionManager.instance.disableAutoCompaction();
            for (int j = 0; j < 100; j += 2)
            {
                new RowUpdateBuilder(store.metadata(), j, String.valueOf(j))
                .clustering("0")
                .add("val", ByteBufferUtil.EMPTY_BYTE_BUFFER)
                .build()
                .applyUnsafe();
            }
            store.forceBlockingFlush();
            CompactionManager.instance.performMaximal(store, false);

            // check that all our keys are found correctly
            SSTableReader sstable = store.getLiveSSTables().iterator().next();
            for (int j = 0; j < 100; j += 2)
            {
                DecoratedKey dk = Util.dk(String.valueOf(j));
                FileDataInput file = sstable.getFileDataInput(sstable.getPosition(dk, SSTableReader.Operator.EQ).position);
                DecoratedKey keyInDisk = sstable.decorateKey(ByteBufferUtil.readWithShortLength(file));
                assert keyInDisk.equals(dk) : String.format("%s != %s in %s", keyInDisk, dk, file.getPath());
            }

            // check no false positives
            for (int j = 1; j < 110; j += 2)
            {
                DecoratedKey dk = Util.dk(String.valueOf(j));
                assert sstable.getPosition(dk, SSTableReader.Operator.EQ) == null;
            }
        }
        finally
        {
            MmappedRegions.MAX_SEGMENT_SIZE = originalMaxSegmentSize;
        }
    }

    @Test
    public void testPersistentStatistics()
    {

        Keyspace keyspace = Keyspace.open(KEYSPACE1);
        ColumnFamilyStore store = keyspace.getColumnFamilyStore("Standard1");
        partitioner = store.getPartitioner();

        for (int j = 0; j < 100; j += 2)
        {
            new RowUpdateBuilder(store.metadata(), j, String.valueOf(j))
            .clustering("0")
            .add("val", ByteBufferUtil.EMPTY_BYTE_BUFFER)
            .build()
            .applyUnsafe();
        }
        store.forceBlockingFlush();

        clearAndLoad(store);
        assert store.metric.maxPartitionSize.getValue() != 0;
    }

    private void clearAndLoad(ColumnFamilyStore cfs)
    {
        cfs.clearUnsafe();
        cfs.loadNewSSTables();
    }

    @Test
    public void testReadRateTracking()
    {
        // try to make sure CASSANDRA-8239 never happens again
        Keyspace keyspace = Keyspace.open(KEYSPACE1);
        ColumnFamilyStore store = keyspace.getColumnFamilyStore("Standard1");
        partitioner = store.getPartitioner();

        for (int j = 0; j < 10; j++)
        {
            new RowUpdateBuilder(store.metadata(), j, String.valueOf(j))
            .clustering("0")
            .add("val", ByteBufferUtil.EMPTY_BYTE_BUFFER)
            .build()
            .applyUnsafe();
        }

        store.forceBlockingFlush();

        SSTableReader sstable = store.getLiveSSTables().iterator().next();
        assertEquals(0, sstable.getReadMeter().count());

        DecoratedKey key = sstable.decorateKey(ByteBufferUtil.bytes("4"));
        Util.getAll(Util.cmd(store, key).build());
        assertEquals(1, sstable.getReadMeter().count());

        Util.getAll(Util.cmd(store, key).includeRow("0").build());
        assertEquals(2, sstable.getReadMeter().count());
    }

    @Test
    public void testGetPositionsForRangesWithKeyCache()
    {
        Keyspace keyspace = Keyspace.open(KEYSPACE1);
        ColumnFamilyStore store = keyspace.getColumnFamilyStore("Standard2");
        partitioner = store.getPartitioner();
        CacheService.instance.keyCache.setCapacity(100);

        // insert data and compact to a single sstable
        CompactionManager.instance.disableAutoCompaction();
        for (int j = 0; j < 10; j++)
        {

            new RowUpdateBuilder(store.metadata(), j, String.valueOf(j))
            .clustering("0")
            .add("val", ByteBufferUtil.EMPTY_BYTE_BUFFER)
            .build()
            .applyUnsafe();

        }
        store.forceBlockingFlush();
        CompactionManager.instance.performMaximal(store, false);

        SSTableReader sstable = store.getLiveSSTables().iterator().next();
        long p2 = sstable.getPosition(k(2), SSTableReader.Operator.EQ).position;
        long p3 = sstable.getPosition(k(3), SSTableReader.Operator.EQ).position;
        long p6 = sstable.getPosition(k(6), SSTableReader.Operator.EQ).position;
        long p7 = sstable.getPosition(k(7), SSTableReader.Operator.EQ).position;

        SSTableReader.PartitionPositionBounds p = sstable.getPositionsForRanges(makeRanges(t(2), t(6))).get(0);

        // range are start exclusive so we should start at 3
        assert p.lowerPosition == p3;

        // to capture 6 we have to stop at the start of 7
        assert p.upperPosition == p7;
    }

    @Test
    public void testPersistentStatisticsWithSecondaryIndex()
    {
        // Create secondary index and flush to disk
        Keyspace keyspace = Keyspace.open(KEYSPACE1);
        ColumnFamilyStore store = keyspace.getColumnFamilyStore(CF_INDEXED);
        partitioner = store.getPartitioner();

        new RowUpdateBuilder(store.metadata(), System.currentTimeMillis(), "k1")
            .clustering("0")
            .add("birthdate", 1L)
            .build()
            .applyUnsafe();

        store.forceBlockingFlush();

        // check if opening and querying works
        assertIndexQueryWorks(store);
    }
    public void testGetPositionsKeyCacheStats()
    {
        Keyspace keyspace = Keyspace.open(KEYSPACE1);
        ColumnFamilyStore store = keyspace.getColumnFamilyStore("Standard2");
        partitioner = store.getPartitioner();
        CacheService.instance.keyCache.setCapacity(1000);

        // insert data and compact to a single sstable
        CompactionManager.instance.disableAutoCompaction();
        for (int j = 0; j < 10; j++)
        {
            new RowUpdateBuilder(store.metadata(), j, String.valueOf(j))
            .clustering("0")
            .add("val", ByteBufferUtil.EMPTY_BYTE_BUFFER)
            .build()
            .applyUnsafe();
        }
        store.forceBlockingFlush();
        CompactionManager.instance.performMaximal(store, false);

        SSTableReader sstable = store.getLiveSSTables().iterator().next();
        sstable.getPosition(k(2), SSTableReader.Operator.EQ);
        assertEquals(0, sstable.getKeyCacheHit());
        assertEquals(1, sstable.getBloomFilterTruePositiveCount());
        sstable.getPosition(k(2), SSTableReader.Operator.EQ);
        assertEquals(1, sstable.getKeyCacheHit());
        assertEquals(2, sstable.getBloomFilterTruePositiveCount());
        sstable.getPosition(k(15), SSTableReader.Operator.EQ);
        assertEquals(1, sstable.getKeyCacheHit());
        assertEquals(2, sstable.getBloomFilterTruePositiveCount());

    }


    @Test
    public void testOpeningSSTable() throws Exception
    {
        String ks = KEYSPACE1;
        String cf = "Standard1";

        // clear and create just one sstable for this test
        Keyspace keyspace = Keyspace.open(ks);
        ColumnFamilyStore store = keyspace.getColumnFamilyStore(cf);
        store.clearUnsafe();
        store.disableAutoCompaction();

        DecoratedKey firstKey = null, lastKey = null;
        long timestamp = System.currentTimeMillis();
        for (int i = 0; i < store.metadata().params.minIndexInterval; i++)
        {
            DecoratedKey key = Util.dk(String.valueOf(i));
            if (firstKey == null)
                firstKey = key;
            if (lastKey == null)
                lastKey = key;
            if (store.metadata().partitionKeyType.compare(lastKey.getKey(), key.getKey()) < 0)
                lastKey = key;


            new RowUpdateBuilder(store.metadata(), timestamp, key.getKey())
                .clustering("col")
                .add("val", ByteBufferUtil.EMPTY_BYTE_BUFFER)
                .build()
                .applyUnsafe();
        }
        store.forceBlockingFlush();

        SSTableReader sstable = store.getLiveSSTables().iterator().next();
        Descriptor desc = sstable.descriptor;

        // test to see if sstable can be opened as expected
        SSTableReader target = SSTableReader.open(desc);
        assert target.first.equals(firstKey);
        assert target.last.equals(lastKey);

        executeInternal(String.format("ALTER TABLE \"%s\".\"%s\" WITH bloom_filter_fp_chance = 0.3", ks, cf));

        File summaryFile = new File(desc.filenameFor(Component.SUMMARY));
        Path bloomPath = new File(desc.filenameFor(Component.FILTER)).toPath();
        Path summaryPath = summaryFile.toPath();

        long bloomModified = Files.getLastModifiedTime(bloomPath).toMillis();
        long summaryModified = Files.getLastModifiedTime(summaryPath).toMillis();

        TimeUnit.MILLISECONDS.sleep(1000); // sleep to ensure modified time will be different

        // Offline tests
        // check that bloomfilter/summary ARE NOT regenerated
        target = SSTableReader.openNoValidation(desc, store.metadata);

        assertEquals(bloomModified, Files.getLastModifiedTime(bloomPath).toMillis());
        assertEquals(summaryModified, Files.getLastModifiedTime(summaryPath).toMillis());

        target.selfRef().release();

        // check that bloomfilter/summary ARE NOT regenerated and BF=AlwaysPresent when filter component is missing
        Set<Component> components = SSTable.discoverComponentsFor(desc);
        components.remove(Component.FILTER);
        target = SSTableReader.openNoValidation(desc, components, store);

        assertEquals(bloomModified, Files.getLastModifiedTime(bloomPath).toMillis());
        assertEquals(summaryModified, Files.getLastModifiedTime(summaryPath).toMillis());
        assertEquals(FilterFactory.AlwaysPresent, target.getBloomFilter());

        target.selfRef().release();

        // #### online tests ####
        // check that summary & bloomfilter are not regenerated when SSTable is opened and BFFP has been changed
        target = SSTableReader.open(desc, store.metadata);

        assertEquals(bloomModified, Files.getLastModifiedTime(bloomPath).toMillis());
        assertEquals(summaryModified, Files.getLastModifiedTime(summaryPath).toMillis());

        target.selfRef().release();

        // check that bloomfilter is recreated when it doesn't exist and this causes the summary to be recreated
        components = SSTable.discoverComponentsFor(desc);
        components.remove(Component.FILTER);

        target = SSTableReader.open(desc, components, store.metadata);

        assertTrue("Bloomfilter was not recreated", bloomModified < Files.getLastModifiedTime(bloomPath).toMillis());
        assertTrue("Summary was not recreated", summaryModified < Files.getLastModifiedTime(summaryPath).toMillis());

        target.selfRef().release();

        // check that only the summary is regenerated when it is deleted
        components.add(Component.FILTER);
        summaryModified = Files.getLastModifiedTime(summaryPath).toMillis();
        summaryFile.delete();

        TimeUnit.MILLISECONDS.sleep(1000); // sleep to ensure modified time will be different
        bloomModified = Files.getLastModifiedTime(bloomPath).toMillis();

        target = SSTableReader.open(desc, components, store.metadata);

        assertEquals(bloomModified, Files.getLastModifiedTime(bloomPath).toMillis());
        assertTrue("Summary was not recreated", summaryModified < Files.getLastModifiedTime(summaryPath).toMillis());

        target.selfRef().release();

        // check that summary and bloomfilter is not recreated when the INDEX is missing
        components.add(Component.SUMMARY);
        components.remove(Component.PRIMARY_INDEX);

        summaryModified = Files.getLastModifiedTime(summaryPath).toMillis();
        target = SSTableReader.open(desc, components, store.metadata, false, false);

        TimeUnit.MILLISECONDS.sleep(1000); // sleep to ensure modified time will be different
        assertEquals(bloomModified, Files.getLastModifiedTime(bloomPath).toMillis());
        assertEquals(summaryModified, Files.getLastModifiedTime(summaryPath).toMillis());

        target.selfRef().release();
    }

    @Test
    public void testLoadingSummaryUsesCorrectPartitioner() throws Exception
    {
        Keyspace keyspace = Keyspace.open(KEYSPACE1);
        ColumnFamilyStore store = keyspace.getColumnFamilyStore("Indexed1");

        new RowUpdateBuilder(store.metadata(), System.currentTimeMillis(), "k1")
        .clustering("0")
        .add("birthdate", 1L)
        .build()
        .applyUnsafe();

        store.forceBlockingFlush();

        for(ColumnFamilyStore indexCfs : store.indexManager.getAllIndexColumnFamilyStores())
        {
            assert indexCfs.isIndex();
            SSTableReader sstable = indexCfs.getLiveSSTables().iterator().next();
            assert sstable.first.getToken() instanceof LocalToken;

            sstable.saveSummary();
            SSTableReader reopened = SSTableReader.open(sstable.descriptor);
            assert reopened.first.getToken() instanceof LocalToken;
            reopened.selfRef().release();
        }
    }

    /** see CASSANDRA-5407 */
    @Test
    public void testGetScannerForNoIntersectingRanges() throws Exception
    {
        Keyspace keyspace = Keyspace.open(KEYSPACE1);
        ColumnFamilyStore store = keyspace.getColumnFamilyStore("Standard1");
        partitioner = store.getPartitioner();

        new RowUpdateBuilder(store.metadata(), 0, "k1")
            .clustering("xyz")
            .add("val", "abc")
            .build()
            .applyUnsafe();

        store.forceBlockingFlush();
        boolean foundScanner = false;
        for (SSTableReader s : store.getLiveSSTables())
        {
            try (ISSTableScanner scanner = s.getScanner(new Range<Token>(t(0), t(1))))
            {
                scanner.next(); // throws exception pre 5407
                foundScanner = true;
            }
        }
        assertTrue(foundScanner);
    }

    @Test
    public void testGetPositionsForRangesFromTableOpenedForBulkLoading() throws IOException
    {
        Keyspace keyspace = Keyspace.open(KEYSPACE1);
        ColumnFamilyStore store = keyspace.getColumnFamilyStore("Standard2");
        partitioner = store.getPartitioner();

        // insert data and compact to a single sstable. The
        // number of keys inserted is greater than index_interval
        // to ensure multiple segments in the index file
        CompactionManager.instance.disableAutoCompaction();
        for (int j = 0; j < 130; j++)
        {

            new RowUpdateBuilder(store.metadata(), j, String.valueOf(j))
            .clustering("0")
            .add("val", ByteBufferUtil.EMPTY_BYTE_BUFFER)
            .build()
            .applyUnsafe();

        }
        store.forceBlockingFlush();
        CompactionManager.instance.performMaximal(store, false);

        // construct a range which is present in the sstable, but whose
        // keys are not found in the first segment of the index.
        List<Range<Token>> ranges = new ArrayList<Range<Token>>();
        ranges.add(new Range<Token>(t(98), t(99)));

        SSTableReader sstable = store.getLiveSSTables().iterator().next();
        List<SSTableReader.PartitionPositionBounds> sections = sstable.getPositionsForRanges(ranges);
        assert sections.size() == 1 : "Expected to find range in sstable" ;

        // re-open the same sstable as it would be during bulk loading
        Set<Component> components = Sets.newHashSet(Component.DATA, Component.PRIMARY_INDEX);
        if (sstable.components.contains(Component.COMPRESSION_INFO))
            components.add(Component.COMPRESSION_INFO);
        SSTableReader bulkLoaded = SSTableReader.openForBatch(sstable.descriptor, components, store.metadata);
        sections = bulkLoaded.getPositionsForRanges(ranges);
        assert sections.size() == 1 : "Expected to find range in sstable opened for bulk loading";
        bulkLoaded.selfRef().release();
    }

    @Test
    public void testIndexSummaryReplacement() throws IOException, ExecutionException, InterruptedException
    {
        Keyspace keyspace = Keyspace.open(KEYSPACE1);
        final ColumnFamilyStore store = keyspace.getColumnFamilyStore("StandardLowIndexInterval"); // index interval of 8, no key caching
        CompactionManager.instance.disableAutoCompaction();

        final int NUM_PARTITIONS = 512;
        for (int j = 0; j < NUM_PARTITIONS; j++)
        {
            new RowUpdateBuilder(store.metadata(), j, String.format("%3d", j))
            .clustering("0")
            .add("val", String.format("%3d", j))
            .build()
            .applyUnsafe();

        }
        store.forceBlockingFlush();
        CompactionManager.instance.performMaximal(store, false);

        Collection<SSTableReader> sstables = store.getLiveSSTables();
        assert sstables.size() == 1;
        final SSTableReader sstable = sstables.iterator().next();

        ThreadPoolExecutor executor = new ScheduledThreadPoolExecutor(5);
        List<Future> futures = new ArrayList<>(NUM_PARTITIONS * 2);
        for (int i = 0; i < NUM_PARTITIONS; i++)
        {
            final ByteBuffer key = ByteBufferUtil.bytes(String.format("%3d", i));
            final int index = i;

            futures.add(executor.submit(new Runnable()
            {
                public void run()
                {
                    Row row = Util.getOnlyRowUnfiltered(Util.cmd(store, key).build());
                    assertEquals(0, ByteBufferUtil.compare(String.format("%3d", index).getBytes(), row.cells().iterator().next().value()));
                }
            }));

            futures.add(executor.submit(new Runnable()
            {
                public void run()
                {
                    Iterable<DecoratedKey> results = store.keySamples(
                            new Range<>(sstable.getPartitioner().getMinimumToken(), sstable.getPartitioner().getToken(key)));
                    assertTrue(results.iterator().hasNext());
                }
            }));
        }

        SSTableReader replacement;
        try (LifecycleTransaction txn = store.getTracker().tryModify(Arrays.asList(sstable), OperationType.UNKNOWN))
        {
            replacement = sstable.cloneWithNewSummarySamplingLevel(store, 1);
            txn.update(replacement, true);
            txn.finish();
        }
        for (Future future : futures)
            future.get();

        assertEquals(sstable.estimatedKeys(), replacement.estimatedKeys(), 1);
    }

    @Test
    public void testIndexSummaryUpsampleAndReload() throws Exception
    {
        int originalMaxSegmentSize = MmappedRegions.MAX_SEGMENT_SIZE;
        MmappedRegions.MAX_SEGMENT_SIZE = 40; // each index entry is ~11 bytes, so this will generate lots of segments

        try
        {
            testIndexSummaryUpsampleAndReload0();
        }
        finally
        {
            MmappedRegions.MAX_SEGMENT_SIZE = originalMaxSegmentSize;
        }
    }

    private void testIndexSummaryUpsampleAndReload0() throws Exception
    {
        Keyspace keyspace = Keyspace.open(KEYSPACE1);
        final ColumnFamilyStore store = keyspace.getColumnFamilyStore("StandardLowIndexInterval"); // index interval of 8, no key caching
        CompactionManager.instance.disableAutoCompaction();

        final int NUM_PARTITIONS = 512;
        for (int j = 0; j < NUM_PARTITIONS; j++)
        {
            new RowUpdateBuilder(store.metadata(), j, String.format("%3d", j))
            .clustering("0")
            .add("val", String.format("%3d", j))
            .build()
            .applyUnsafe();

        }
        store.forceBlockingFlush();
        CompactionManager.instance.performMaximal(store, false);

        Collection<SSTableReader> sstables = store.getLiveSSTables();
        assert sstables.size() == 1;
        final SSTableReader sstable = sstables.iterator().next();

        try (LifecycleTransaction txn = store.getTracker().tryModify(Arrays.asList(sstable), OperationType.UNKNOWN))
        {
            SSTableReader replacement = sstable.cloneWithNewSummarySamplingLevel(store, sstable.getIndexSummarySamplingLevel() + 1);
            txn.update(replacement, true);
            txn.finish();
        }
        SSTableReader reopen = SSTableReader.open(sstable.descriptor);
        assert reopen.getIndexSummarySamplingLevel() == sstable.getIndexSummarySamplingLevel() + 1;
    }

    private void assertIndexQueryWorks(ColumnFamilyStore indexedCFS)
    {
        assert "Indexed1".equals(indexedCFS.name);

        // make sure all sstables including 2ary indexes load from disk
        for (ColumnFamilyStore cfs : indexedCFS.concatWithIndexes())
            clearAndLoad(cfs);


        // query using index to see if sstable for secondary index opens
        ReadCommand rc = Util.cmd(indexedCFS).fromKeyIncl("k1").toKeyIncl("k3")
                                             .columns("birthdate")
                                             .filterOn("birthdate", Operator.EQ, 1L)
                                             .build();
        Index.Searcher searcher = rc.getIndex(indexedCFS).searcherFor(rc);
        assertNotNull(searcher);
        try (ReadExecutionController executionController = rc.executionController())
        {
            assertEquals(1, Util.size(UnfilteredPartitionIterators.filter(searcher.search(executionController), rc.nowInSec())));
        }
    }

    private List<Range<Token>> makeRanges(Token left, Token right)
    {
        return Arrays.asList(new Range<>(left, right));
    }

    private DecoratedKey k(int i)
    {
        return new BufferDecoratedKey(t(i), ByteBufferUtil.bytes(String.valueOf(i)));
    }
}
