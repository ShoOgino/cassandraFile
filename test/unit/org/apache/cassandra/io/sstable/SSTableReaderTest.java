package org.apache.cassandra.io.sstable;
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


import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.*;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadPoolExecutor;

import org.junit.Assert;
import com.google.common.collect.Sets;
import org.junit.Test;
import org.junit.runner.RunWith;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.apache.cassandra.OrderedJUnit4ClassRunner;
import org.apache.cassandra.SchemaLoader;
import org.apache.cassandra.Util;
import org.apache.cassandra.config.DatabaseDescriptor;
import org.apache.cassandra.db.*;
import org.apache.cassandra.db.columniterator.IdentityQueryFilter;
import org.apache.cassandra.db.composites.Composites;
import org.apache.cassandra.db.compaction.CompactionManager;
import org.apache.cassandra.db.compaction.ICompactionScanner;
import org.apache.cassandra.dht.IPartitioner;
import org.apache.cassandra.dht.LocalPartitioner;
import org.apache.cassandra.dht.LocalToken;
import org.apache.cassandra.dht.Range;
import org.apache.cassandra.dht.Token;
import org.apache.cassandra.service.CacheService;
import org.apache.cassandra.io.util.FileDataInput;
import org.apache.cassandra.io.util.MmappedSegmentedFile;
import org.apache.cassandra.io.util.SegmentedFile;
import org.apache.cassandra.service.StorageService;
import org.apache.cassandra.utils.ByteBufferUtil;
import org.apache.cassandra.utils.Pair;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.apache.cassandra.Util.cellname;

@RunWith(OrderedJUnit4ClassRunner.class)
public class SSTableReaderTest extends SchemaLoader
{
    private static final Logger logger = LoggerFactory.getLogger(SSTableReaderTest.class);

    static Token t(int i)
    {
        return StorageService.getPartitioner().getToken(ByteBufferUtil.bytes(String.valueOf(i)));
    }

    @Test
    public void testGetPositionsForRanges() throws IOException, ExecutionException, InterruptedException
    {
        Keyspace keyspace = Keyspace.open("Keyspace1");
        ColumnFamilyStore store = keyspace.getColumnFamilyStore("Standard2");

        // insert data and compact to a single sstable
        CompactionManager.instance.disableAutoCompaction();
        for (int j = 0; j < 10; j++)
        {
            ByteBuffer key = ByteBufferUtil.bytes(String.valueOf(j));
            Mutation rm = new Mutation("Keyspace1", key);
            rm.add("Standard2", cellname("0"), ByteBufferUtil.EMPTY_BYTE_BUFFER, j);
            rm.apply();
        }
        store.forceBlockingFlush();
        CompactionManager.instance.performMaximal(store);

        List<Range<Token>> ranges = new ArrayList<Range<Token>>();
        // 1 key
        ranges.add(new Range<Token>(t(0), t(1)));
        // 2 keys
        ranges.add(new Range<Token>(t(2), t(4)));
        // wrapping range from key to end
        ranges.add(new Range<Token>(t(6), StorageService.getPartitioner().getMinimumToken()));
        // empty range (should be ignored)
        ranges.add(new Range<Token>(t(9), t(91)));

        // confirm that positions increase continuously
        SSTableReader sstable = store.getSSTables().iterator().next();
        long previous = -1;
        for (Pair<Long,Long> section : sstable.getPositionsForRanges(ranges))
        {
            assert previous <= section.left : previous + " ! < " + section.left;
            assert section.left < section.right : section.left + " ! < " + section.right;
            previous = section.right;
        }
    }

    @Test
    public void testSpannedIndexPositions() throws IOException, ExecutionException, InterruptedException
    {
        MmappedSegmentedFile.MAX_SEGMENT_SIZE = 40; // each index entry is ~11 bytes, so this will generate lots of segments

        Keyspace keyspace = Keyspace.open("Keyspace1");
        ColumnFamilyStore store = keyspace.getColumnFamilyStore("Standard1");

        // insert a bunch of data and compact to a single sstable
        CompactionManager.instance.disableAutoCompaction();
        for (int j = 0; j < 100; j += 2)
        {
            ByteBuffer key = ByteBufferUtil.bytes(String.valueOf(j));
            Mutation rm = new Mutation("Keyspace1", key);
            rm.add("Standard1", cellname("0"), ByteBufferUtil.EMPTY_BYTE_BUFFER, j);
            rm.apply();
        }
        store.forceBlockingFlush();
        CompactionManager.instance.performMaximal(store);

        // check that all our keys are found correctly
        SSTableReader sstable = store.getSSTables().iterator().next();
        for (int j = 0; j < 100; j += 2)
        {
            DecoratedKey dk = Util.dk(String.valueOf(j));
            FileDataInput file = sstable.getFileDataInput(sstable.getPosition(dk, SSTableReader.Operator.EQ).position);
            DecoratedKey keyInDisk = sstable.partitioner.decorateKey(ByteBufferUtil.readWithShortLength(file));
            assert keyInDisk.equals(dk) : String.format("%s != %s in %s", keyInDisk, dk, file.getPath());
        }

        // check no false positives
        for (int j = 1; j < 110; j += 2)
        {
            DecoratedKey dk = Util.dk(String.valueOf(j));
            assert sstable.getPosition(dk, SSTableReader.Operator.EQ) == null;
        }
    }

    @Test
    public void testPersistentStatistics() throws IOException, ExecutionException, InterruptedException
    {

        Keyspace keyspace = Keyspace.open("Keyspace1");
        ColumnFamilyStore store = keyspace.getColumnFamilyStore("Standard1");

        for (int j = 0; j < 100; j += 2)
        {
            ByteBuffer key = ByteBufferUtil.bytes(String.valueOf(j));
            Mutation rm = new Mutation("Keyspace1", key);
            rm.add("Standard1", cellname("0"), ByteBufferUtil.EMPTY_BYTE_BUFFER, j);
            rm.apply();
        }
        store.forceBlockingFlush();

        clearAndLoad(store);
        assert store.getMaxRowSize() != 0;
    }

    private void clearAndLoad(ColumnFamilyStore cfs) throws IOException
    {
        cfs.clearUnsafe();
        cfs.loadNewSSTables();
    }

    @Test
    public void testGetPositionsForRangesWithKeyCache() throws IOException, ExecutionException, InterruptedException
    {
        Keyspace keyspace = Keyspace.open("Keyspace1");
        ColumnFamilyStore store = keyspace.getColumnFamilyStore("Standard2");
        CacheService.instance.keyCache.setCapacity(100);

        // insert data and compact to a single sstable
        CompactionManager.instance.disableAutoCompaction();
        for (int j = 0; j < 10; j++)
        {
            ByteBuffer key = ByteBufferUtil.bytes(String.valueOf(j));
            Mutation rm = new Mutation("Keyspace1", key);
            rm.add("Standard2", cellname("0"), ByteBufferUtil.EMPTY_BYTE_BUFFER, j);
            rm.apply();
        }
        store.forceBlockingFlush();
        CompactionManager.instance.performMaximal(store);

        SSTableReader sstable = store.getSSTables().iterator().next();
        long p2 = sstable.getPosition(k(2), SSTableReader.Operator.EQ).position;
        long p3 = sstable.getPosition(k(3), SSTableReader.Operator.EQ).position;
        long p6 = sstable.getPosition(k(6), SSTableReader.Operator.EQ).position;
        long p7 = sstable.getPosition(k(7), SSTableReader.Operator.EQ).position;

        Pair<Long, Long> p = sstable.getPositionsForRanges(makeRanges(t(2), t(6))).iterator().next();

        // range are start exclusive so we should start at 3
        assert p.left == p3;

        // to capture 6 we have to stop at the start of 7
        assert p.right == p7;
    }

    @Test
    public void testPersistentStatisticsWithSecondaryIndex() throws IOException, ExecutionException, InterruptedException
    {
        // Create secondary index and flush to disk
        Keyspace keyspace = Keyspace.open("Keyspace1");
        ColumnFamilyStore store = keyspace.getColumnFamilyStore("Indexed1");
        ByteBuffer key = ByteBufferUtil.bytes(String.valueOf("k1"));
        Mutation rm = new Mutation("Keyspace1", key);
        rm.add("Indexed1", cellname("birthdate"), ByteBufferUtil.bytes(1L), System.currentTimeMillis());
        rm.apply();
        store.forceBlockingFlush();

        // check if opening and querying works
        assertIndexQueryWorks(store);
    }

    @Test
    public void testOpeningSSTable() throws Exception
    {
        String ks = "Keyspace1";
        String cf = "Standard1";

        // clear and create just one sstable for this test
        Keyspace keyspace = Keyspace.open(ks);
        ColumnFamilyStore store = keyspace.getColumnFamilyStore(cf);
        store.clearUnsafe();
        store.disableAutoCompaction();

        DecoratedKey firstKey = null, lastKey = null;
        long timestamp = System.currentTimeMillis();
        for (int i = 0; i < store.metadata.getIndexInterval(); i++)
        {
            DecoratedKey key = Util.dk(String.valueOf(i));
            if (firstKey == null)
                firstKey = key;
            if (lastKey == null)
                lastKey = key;
            if (store.metadata.getKeyValidator().compare(lastKey.key, key.key) < 0)
                lastKey = key;
            Mutation rm = new Mutation(ks, key.key);
            rm.add(cf, cellname("col"),
                   ByteBufferUtil.EMPTY_BYTE_BUFFER, timestamp);
            rm.apply();
        }
        store.forceBlockingFlush();

        SSTableReader sstable = store.getSSTables().iterator().next();
        Descriptor desc = sstable.descriptor;

        // test to see if sstable can be opened as expected
        SSTableReader target = SSTableReader.open(desc);
        Assert.assertEquals(target.getIndexSummarySize(), 1);
        Assert.assertArrayEquals(ByteBufferUtil.getArray(firstKey.key), target.getIndexSummaryKey(0));
        assert target.first.equals(firstKey);
        assert target.last.equals(lastKey);
    }

    @Test
    public void testLoadingSummaryUsesCorrectPartitioner() throws Exception
    {
        Keyspace keyspace = Keyspace.open("Keyspace1");
        ColumnFamilyStore store = keyspace.getColumnFamilyStore("Indexed1");
        ByteBuffer key = ByteBufferUtil.bytes(String.valueOf("k1"));
        Mutation rm = new Mutation("Keyspace1", key);
        rm.add("Indexed1", cellname("birthdate"), ByteBufferUtil.bytes(1L), System.currentTimeMillis());
        rm.apply();
        store.forceBlockingFlush();

        ColumnFamilyStore indexCfs = store.indexManager.getIndexForColumn(ByteBufferUtil.bytes("birthdate")).getIndexCfs();
        assert indexCfs.partitioner instanceof LocalPartitioner;
        SSTableReader sstable = indexCfs.getSSTables().iterator().next();
        assert sstable.first.token instanceof LocalToken;

        SegmentedFile.Builder ibuilder = SegmentedFile.getBuilder(DatabaseDescriptor.getIndexAccessMode());
        SegmentedFile.Builder dbuilder = sstable.compression
                                          ? SegmentedFile.getCompressedBuilder()
                                          : SegmentedFile.getBuilder(DatabaseDescriptor.getDiskAccessMode());
        sstable.saveSummary(ibuilder, dbuilder);

        SSTableReader reopened = SSTableReader.open(sstable.descriptor);
        assert reopened.first.token instanceof LocalToken;
    }

    /** see CASSANDRA-5407 */
    @Test
    public void testGetScannerForNoIntersectingRanges()
    {
        Keyspace keyspace = Keyspace.open("Keyspace1");
        ColumnFamilyStore store = keyspace.getColumnFamilyStore("Standard1");
        ByteBuffer key = ByteBufferUtil.bytes(String.valueOf("k1"));
        Mutation rm = new Mutation("Keyspace1", key);
        rm.add("Standard1", cellname("xyz"), ByteBufferUtil.bytes("abc"), 0);
        rm.apply();
        store.forceBlockingFlush();
        boolean foundScanner = false;
        for (SSTableReader s : store.getSSTables())
        {
            ICompactionScanner scanner = s.getScanner(new Range<Token>(t(0), t(1), s.partitioner), null);
            scanner.next(); // throws exception pre 5407
            foundScanner = true;
        }
        assertTrue(foundScanner);
    }

    @Test
    public void testGetPositionsForRangesFromTableOpenedForBulkLoading() throws IOException, ExecutionException, InterruptedException
    {
        Keyspace keyspace = Keyspace.open("Keyspace1");
        ColumnFamilyStore store = keyspace.getColumnFamilyStore("Standard2");

        // insert data and compact to a single sstable. The
        // number of keys inserted is greater than index_interval
        // to ensure multiple segments in the index file
        CompactionManager.instance.disableAutoCompaction();
        for (int j = 0; j < 130; j++)
        {
            ByteBuffer key = ByteBufferUtil.bytes(String.valueOf(j));
            Mutation rm = new Mutation("Keyspace1", key);
            rm.add("Standard2", cellname("0"), ByteBufferUtil.EMPTY_BYTE_BUFFER, j);
            rm.apply();
        }
        store.forceBlockingFlush();
        CompactionManager.instance.performMaximal(store);

        // construct a range which is present in the sstable, but whose
        // keys are not found in the first segment of the index.
        List<Range<Token>> ranges = new ArrayList<Range<Token>>();
        ranges.add(new Range<Token>(t(98), t(99)));

        SSTableReader sstable = store.getSSTables().iterator().next();
        List<Pair<Long,Long>> sections = sstable.getPositionsForRanges(ranges);
        assert sections.size() == 1 : "Expected to find range in sstable" ;

        // re-open the same sstable as it would be during bulk loading
        Set<Component> components = Sets.newHashSet(Component.DATA, Component.PRIMARY_INDEX);
        SSTableReader bulkLoaded = SSTableReader.openForBatch(sstable.descriptor, components, store.metadata, sstable.partitioner);
        sections = bulkLoaded.getPositionsForRanges(ranges);
        assert sections.size() == 1 : "Expected to find range in sstable opened for bulk loading";
    }

    @Test
    public void testIndexSummaryReplacement() throws IOException, ExecutionException, InterruptedException
    {
        Keyspace keyspace = Keyspace.open("Keyspace1");
        final ColumnFamilyStore store = keyspace.getColumnFamilyStore("StandardLowIndexInterval"); // index interval of 8, no key caching
        CompactionManager.instance.disableAutoCompaction();

        final int NUM_ROWS = 1000;
        for (int j = 0; j < NUM_ROWS; j++)
        {
            ByteBuffer key = ByteBufferUtil.bytes(String.format("%3d", j));
            Mutation rm = new Mutation("Keyspace1", key);
            rm.add("StandardLowIndexInterval", Util.cellname("0"), ByteBufferUtil.bytes(String.format("%3d", j)), j);
            rm.apply();
        }
        store.forceBlockingFlush();
        CompactionManager.instance.performMaximal(store);

        Collection<SSTableReader> sstables = store.getSSTables();
        assert sstables.size() == 1;
        final SSTableReader sstable = sstables.iterator().next();

        ThreadPoolExecutor executor = new ScheduledThreadPoolExecutor(5);
        List<Future> futures = new ArrayList<>(NUM_ROWS * 2);
        for (int i = 0; i < NUM_ROWS; i++)
        {
            final ByteBuffer key = ByteBufferUtil.bytes(String.format("%3d", i));
            final int index = i;

            futures.add(executor.submit(new Runnable()
            {
                public void run()
                {
                    ColumnFamily result = store.getColumnFamily(sstable.partitioner.decorateKey(key), Composites.EMPTY, Composites.EMPTY, false, 100, 100);
                    assertFalse(result.isEmpty());
                    assertEquals(0, ByteBufferUtil.compare(String.format("%3d", index).getBytes(), result.getColumn(Util.cellname("0")).value()));
                }
            }));

            futures.add(executor.submit(new Runnable()
            {
                public void run()
                {
                    Iterable<DecoratedKey> results = store.keySamples(
                            new Range<>(sstable.partitioner.getMinimumToken(), sstable.partitioner.getToken(key)));
                    assertTrue(results.iterator().hasNext());
                }
            }));
        }

        SSTableReader replacement = sstable.cloneWithNewSummarySamplingLevel(Downsampling.MIN_SAMPLING_LEVEL);
        store.getDataTracker().replaceReaders(Arrays.asList(sstable), Arrays.asList(replacement));
        for (Future future : futures)
            future.get();

        assertEquals(sstable.estimatedKeys(), replacement.estimatedKeys(), 1);
    }

    private void assertIndexQueryWorks(ColumnFamilyStore indexedCFS) throws IOException
    {
        assert "Indexed1".equals(indexedCFS.name);

        // make sure all sstables including 2ary indexes load from disk
        for (ColumnFamilyStore cfs : indexedCFS.concatWithIndexes())
            clearAndLoad(cfs);

        // query using index to see if sstable for secondary index opens
        IndexExpression expr = new IndexExpression(ByteBufferUtil.bytes("birthdate"), IndexExpression.Operator.EQ, ByteBufferUtil.bytes(1L));
        List<IndexExpression> clause = Arrays.asList(expr);
        IPartitioner p = StorageService.getPartitioner();
        Range<RowPosition> range = Util.range("", "");
        List<Row> rows = indexedCFS.search(range, clause, new IdentityQueryFilter(), 100);
        assert rows.size() == 1;
    }

    private List<Range<Token>> makeRanges(Token left, Token right)
    {
        return Arrays.asList(new Range<>(left, right));
    }

    private DecoratedKey k(int i)
    {
        return new DecoratedKey(t(i), ByteBufferUtil.bytes(String.valueOf(i)));
    }
}
