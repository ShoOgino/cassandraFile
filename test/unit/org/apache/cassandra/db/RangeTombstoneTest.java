/*
* Licensed to the Apache Software Foundation (ASF) under one
* or more contributor license agreements.  See the NOTICE file
* distributed with this work for additional information
* regarding copyright ownership.  The ASF licenses this file
* to you under the Apache License, Version 2.0 (the
* "License"); you may not use this file except in compliance
* with the License.  You may obtain a copy of the License at
*
*    http://www.apache.org/licenses/LICENSE-2.0
*
* Unless required by applicable law or agreed to in writing,
* software distributed under the License is distributed on an
* "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
* KIND, either express or implied.  See the License for the
* specific language governing permissions and limitations
* under the License.
*/
package org.apache.cassandra.db;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;

import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Iterators;

import org.apache.cassandra.io.sstable.format.SSTableReader;
import org.junit.BeforeClass;
import org.junit.Test;

import org.apache.cassandra.AbstractReadCommandBuilder;
import org.apache.cassandra.SchemaLoader;
import org.apache.cassandra.config.*;
import org.apache.cassandra.Util;
import org.apache.cassandra.UpdateBuilder;
import org.apache.cassandra.db.compaction.CompactionManager;
import org.apache.cassandra.db.compaction.SizeTieredCompactionStrategy;
import org.apache.cassandra.db.filter.*;
import org.apache.cassandra.db.rows.*;
import org.apache.cassandra.db.partitions.*;
import org.apache.cassandra.db.index.PerColumnSecondaryIndex;
import org.apache.cassandra.db.index.SecondaryIndex;
import org.apache.cassandra.db.index.SecondaryIndexSearcher;
import org.apache.cassandra.db.marshal.UTF8Type;
import org.apache.cassandra.db.marshal.Int32Type;
import org.apache.cassandra.exceptions.ConfigurationException;
import org.apache.cassandra.locator.SimpleStrategy;
import org.apache.cassandra.io.sstable.metadata.StatsMetadata;
import org.apache.cassandra.utils.ByteBufferUtil;
import org.apache.cassandra.utils.FBUtilities;
import org.apache.cassandra.utils.concurrent.OpOrder;

import static org.apache.cassandra.Util.dk;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class RangeTombstoneTest
{
    private static final String KSNAME = "RangeTombstoneTest";
    private static final String CFNAME = "StandardInteger1";

    @BeforeClass
    public static void defineSchema() throws ConfigurationException
    {
        SchemaLoader.prepareServer();
        SchemaLoader.createKeyspace(KSNAME,
                                    SimpleStrategy.class,
                                    KSMetaData.optsWithRF(1),
                                    SchemaLoader.standardCFMD(KSNAME, CFNAME, 0, UTF8Type.instance, Int32Type.instance, Int32Type.instance));
    }

    @Test
    public void simpleQueryWithRangeTombstoneTest() throws Exception
    {
        Keyspace keyspace = Keyspace.open(KSNAME);
        ColumnFamilyStore cfs = keyspace.getColumnFamilyStore(CFNAME);

        // Inserting data
        String key = "k1";

        UpdateBuilder builder;

        builder = UpdateBuilder.create(cfs.metadata, key).withTimestamp(0);
        for (int i = 0; i < 40; i += 2)
            builder.newRow(i).add("val", i);
        builder.applyUnsafe();
        cfs.forceBlockingFlush();

        new RowUpdateBuilder(cfs.metadata, 1, key).addRangeTombstone(10, 22).build().applyUnsafe();

        builder = UpdateBuilder.create(cfs.metadata, key).withTimestamp(2);
        for (int i = 1; i < 40; i += 2)
            builder.newRow(i).add("val", i);
        builder.applyUnsafe();

        new RowUpdateBuilder(cfs.metadata, 3, key).addRangeTombstone(19, 27).build().applyUnsafe();
        // We don't flush to test with both a range tomsbtone in memtable and in sstable

        // Queries by name
        int[] live = new int[]{ 4, 9, 11, 17, 28 };
        int[] dead = new int[]{ 12, 19, 21, 24, 27 };

        AbstractReadCommandBuilder.SinglePartitionBuilder cmdBuilder = Util.cmd(cfs, key);
        for (int i : live)
            cmdBuilder.includeRow(i);
        for (int i : dead)
            cmdBuilder.includeRow(i);

        Partition partition = Util.getOnlyPartitionUnfiltered(cmdBuilder.build());

        for (int i : live)
            assertTrue("Row " + i + " should be live", partition.getRow(new SimpleClustering(bb(i))).hasLiveData(FBUtilities.nowInSeconds()));
        for (int i : dead)
            assertTrue("Row " + i + " shouldn't be live", !partition.getRow(new SimpleClustering(bb(i))).hasLiveData(FBUtilities.nowInSeconds()));

        // Queries by slices
        partition = Util.getOnlyPartitionUnfiltered(Util.cmd(cfs, key).fromIncl(7).toIncl(30).build());

        for (int i : new int[]{ 7, 8, 9, 11, 13, 15, 17, 28, 29, 30 })
            assertTrue("Row " + i + " should be live", partition.getRow(new SimpleClustering(bb(i))).hasLiveData(FBUtilities.nowInSeconds()));
        for (int i : new int[]{ 10, 12, 14, 16, 18, 19, 20, 21, 22, 23, 24, 25, 26, 27 })
            assertTrue("Row " + i + " shouldn't be live", partition.getRow(new SimpleClustering(bb(i))) == null);
    }

    @Test
    public void rangeTombstoneFilteringTest() throws Exception
    {
        CompactionManager.instance.disableAutoCompaction();
        Keyspace keyspace = Keyspace.open(KSNAME);
        ColumnFamilyStore cfs = keyspace.getColumnFamilyStore(CFNAME);

        // Inserting data
        String key = "k111";

        UpdateBuilder builder = UpdateBuilder.create(cfs.metadata, key).withTimestamp(0);
        for (int i = 0; i < 40; i += 2)
            builder.newRow(i).add("val", i);
        builder.applyUnsafe();

        new RowUpdateBuilder(cfs.metadata, 1, key).addRangeTombstone(5, 10).build().applyUnsafe();

        new RowUpdateBuilder(cfs.metadata, 2, key).addRangeTombstone(15, 20).build().applyUnsafe();

        ArrayBackedPartition partition;

        partition = Util.getOnlyPartitionUnfiltered(Util.cmd(cfs, key).fromIncl(11).toIncl(14).build());
        Collection<RangeTombstone> rt = rangeTombstones(partition);
        assertEquals(0, rt.size());

        partition = Util.getOnlyPartitionUnfiltered(Util.cmd(cfs, key).fromIncl(11).toIncl(15).build());
        rt = rangeTombstones(partition);
        assertEquals(1, rt.size());

        partition = Util.getOnlyPartitionUnfiltered(Util.cmd(cfs, key).fromIncl(20).toIncl(25).build());
        rt = rangeTombstones(partition);
        assertEquals(1, rt.size());

        partition = Util.getOnlyPartitionUnfiltered(Util.cmd(cfs, key).fromIncl(12).toIncl(25).build());
        rt = rangeTombstones(partition);
        assertEquals(1, rt.size());

        partition = Util.getOnlyPartitionUnfiltered(Util.cmd(cfs, key).fromIncl(25).toIncl(35).build());
        rt = rangeTombstones(partition);
        assertEquals(0, rt.size());

        partition = Util.getOnlyPartitionUnfiltered(Util.cmd(cfs, key).fromIncl(1).toIncl(40).build());
        rt = rangeTombstones(partition);
        assertEquals(2, rt.size());

        partition = Util.getOnlyPartitionUnfiltered(Util.cmd(cfs, key).fromIncl(7).toIncl(17).build());
        rt = rangeTombstones(partition);
        assertEquals(2, rt.size());

        partition = Util.getOnlyPartitionUnfiltered(Util.cmd(cfs, key).fromIncl(5).toIncl(20).build());
        rt = rangeTombstones(partition);
        assertEquals(2, rt.size());

        partition = Util.getOnlyPartitionUnfiltered(Util.cmd(cfs, key).fromIncl(5).toIncl(20).build());
        rt = rangeTombstones(partition);
        assertEquals(2, rt.size());

        partition = Util.getOnlyPartitionUnfiltered(Util.cmd(cfs, key).fromIncl(1).toIncl(2).build());
        rt = rangeTombstones(partition);
        assertEquals(0, rt.size());

        partition = Util.getOnlyPartitionUnfiltered(Util.cmd(cfs, key).fromIncl(1).toIncl(5).build());
        rt = rangeTombstones(partition);
        assertEquals(1, rt.size());

        partition = Util.getOnlyPartitionUnfiltered(Util.cmd(cfs, key).fromIncl(1).toIncl(10).build());
        rt = rangeTombstones(partition);
        assertEquals(1, rt.size());

        partition = Util.getOnlyPartitionUnfiltered(Util.cmd(cfs, key).fromIncl(5).toIncl(6).build());
        rt = rangeTombstones(partition);
        assertEquals(1, rt.size());

        partition = Util.getOnlyPartitionUnfiltered(Util.cmd(cfs, key).fromIncl(17).toIncl(20).build());
        rt = rangeTombstones(partition);
        assertEquals(1, rt.size());

        partition = Util.getOnlyPartitionUnfiltered(Util.cmd(cfs, key).fromIncl(17).toIncl(18).build());
        rt = rangeTombstones(partition);
        assertEquals(1, rt.size());

        Slices.Builder sb = new Slices.Builder(cfs.getComparator());
        sb.add(Slice.Bound.create(cfs.getComparator(), true, true, 1), Slice.Bound.create(cfs.getComparator(), false, true, 10));
        sb.add(Slice.Bound.create(cfs.getComparator(), true, true, 16), Slice.Bound.create(cfs.getComparator(), false, true, 20));

        partition = Util.getOnlyPartitionUnfiltered(SinglePartitionSliceCommand.create(cfs.metadata, FBUtilities.nowInSeconds(), Util.dk(key), sb.build()));
        rt = rangeTombstones(partition);
        assertEquals(2, rt.size());
    }

    private Collection<RangeTombstone> rangeTombstones(ArrayBackedPartition partition)
    {
        List<RangeTombstone> tombstones = new ArrayList<RangeTombstone>();
        Iterators.addAll(tombstones, partition.deletionInfo().rangeIterator(false));
        return tombstones;
    }

    @Test
    public void testTrackTimesPartitionTombstone() throws ExecutionException, InterruptedException
    {
        Keyspace ks = Keyspace.open(KSNAME);
        ColumnFamilyStore cfs = ks.getColumnFamilyStore(CFNAME);
        cfs.truncateBlocking();
        String key = "rt_times";

        int nowInSec = FBUtilities.nowInSeconds();
        new Mutation(PartitionUpdate.fullPartitionDelete(cfs.metadata, Util.dk(key), 1000, nowInSec)).apply();
        cfs.forceBlockingFlush();

        SSTableReader sstable = cfs.getSSTables().iterator().next();
        assertTimes(sstable.getSSTableMetadata(), 1000, 1000, nowInSec);
        cfs.forceMajorCompaction();
        sstable = cfs.getSSTables().iterator().next();
        assertTimes(sstable.getSSTableMetadata(), 1000, 1000, nowInSec);
    }

    @Test
    public void testTrackTimesPartitionTombstoneWithData() throws ExecutionException, InterruptedException
    {
        Keyspace ks = Keyspace.open(KSNAME);
        ColumnFamilyStore cfs = ks.getColumnFamilyStore(CFNAME);
        cfs.truncateBlocking();
        String key = "rt_times";

        UpdateBuilder.create(cfs.metadata, key).withTimestamp(999).newRow(5).add("val", 5).apply();

        key = "rt_times2";
        int nowInSec = FBUtilities.nowInSeconds();
        new Mutation(PartitionUpdate.fullPartitionDelete(cfs.metadata, Util.dk(key), 1000, nowInSec)).apply();
        cfs.forceBlockingFlush();

        SSTableReader sstable = cfs.getSSTables().iterator().next();
        assertTimes(sstable.getSSTableMetadata(), 999, 1000, Integer.MAX_VALUE);
        cfs.forceMajorCompaction();
        sstable = cfs.getSSTables().iterator().next();
        assertTimes(sstable.getSSTableMetadata(), 999, 1000, Integer.MAX_VALUE);
    }

    @Test
    public void testTrackTimesRangeTombstone() throws ExecutionException, InterruptedException
    {
        Keyspace ks = Keyspace.open(KSNAME);
        ColumnFamilyStore cfs = ks.getColumnFamilyStore(CFNAME);
        cfs.truncateBlocking();
        String key = "rt_times";

        int nowInSec = FBUtilities.nowInSeconds();
        new RowUpdateBuilder(cfs.metadata, nowInSec, 1000L, key).addRangeTombstone(1, 2).build().apply();
        cfs.forceBlockingFlush();

        SSTableReader sstable = cfs.getSSTables().iterator().next();
        assertTimes(sstable.getSSTableMetadata(), 1000, 1000, nowInSec);
        cfs.forceMajorCompaction();
        sstable = cfs.getSSTables().iterator().next();
        assertTimes(sstable.getSSTableMetadata(), 1000, 1000, nowInSec);
    }

    @Test
    public void testTrackTimesRangeTombstoneWithData() throws ExecutionException, InterruptedException
    {
        Keyspace ks = Keyspace.open(KSNAME);
        ColumnFamilyStore cfs = ks.getColumnFamilyStore(CFNAME);
        cfs.truncateBlocking();
        String key = "rt_times";

        UpdateBuilder.create(cfs.metadata, key).withTimestamp(999).newRow(5).add("val", 5).apply();

        key = "rt_times2";
        int nowInSec = FBUtilities.nowInSeconds();
        new Mutation(PartitionUpdate.fullPartitionDelete(cfs.metadata, Util.dk(key), 1000, nowInSec)).apply();
        cfs.forceBlockingFlush();

        cfs.forceBlockingFlush();
        SSTableReader sstable = cfs.getSSTables().iterator().next();
        assertTimes(sstable.getSSTableMetadata(), 999, 1000, Integer.MAX_VALUE);
        cfs.forceMajorCompaction();
        sstable = cfs.getSSTables().iterator().next();
        assertTimes(sstable.getSSTableMetadata(), 999, 1000, Integer.MAX_VALUE);
    }

    private void assertTimes(StatsMetadata metadata, long min, long max, int localDeletionTime)
    {
        assertEquals(min, metadata.minTimestamp);
        assertEquals(max, metadata.maxTimestamp);
        assertEquals(localDeletionTime, metadata.maxLocalDeletionTime);
    }

    @Test
    public void test7810() throws ExecutionException, InterruptedException
    {
        Keyspace ks = Keyspace.open(KSNAME);
        ColumnFamilyStore cfs = ks.getColumnFamilyStore(CFNAME);
        cfs.metadata.gcGraceSeconds(2);

        String key = "7810";

        UpdateBuilder builder = UpdateBuilder.create(cfs.metadata, key).withTimestamp(0);
        for (int i = 10; i < 20; i ++)
            builder.newRow(i).add("val", i);
        builder.apply();
        cfs.forceBlockingFlush();

        new RowUpdateBuilder(cfs.metadata, 1, key).addRangeTombstone(10, 11).build().apply();
        cfs.forceBlockingFlush();

        Thread.sleep(5);
        cfs.forceMajorCompaction();
        assertEquals(8, Util.getOnlyPartitionUnfiltered(Util.cmd(cfs, key).build()).rowCount());
    }

    @Test
    public void test7808_1() throws ExecutionException, InterruptedException
    {
        Keyspace ks = Keyspace.open(KSNAME);
        ColumnFamilyStore cfs = ks.getColumnFamilyStore(CFNAME);
        cfs.metadata.gcGraceSeconds(2);

        String key = "7808_1";
        UpdateBuilder builder = UpdateBuilder.create(cfs.metadata, key).withTimestamp(0);
        for (int i = 0; i < 40; i += 2)
            builder.newRow(i).add("val", i);
        builder.apply();
        cfs.forceBlockingFlush();

        new Mutation(PartitionUpdate.fullPartitionDelete(cfs.metadata, Util.dk(key), 1, 1)).apply();
        cfs.forceBlockingFlush();
        Thread.sleep(5);
        cfs.forceMajorCompaction();
    }

    @Test
    public void test7808_2() throws ExecutionException, InterruptedException
    {
        Keyspace ks = Keyspace.open(KSNAME);
        ColumnFamilyStore cfs = ks.getColumnFamilyStore(CFNAME);
        cfs.metadata.gcGraceSeconds(2);

        String key = "7808_2";
        UpdateBuilder builder = UpdateBuilder.create(cfs.metadata, key).withTimestamp(0);
        for (int i = 10; i < 20; i ++)
            builder.newRow(i).add("val", i);
        builder.apply();
        cfs.forceBlockingFlush();

        new Mutation(PartitionUpdate.fullPartitionDelete(cfs.metadata, Util.dk(key), 0, 0)).apply();

        UpdateBuilder.create(cfs.metadata, key).withTimestamp(1).newRow(5).add("val", 5).apply();

        cfs.forceBlockingFlush();
        Thread.sleep(5);
        cfs.forceMajorCompaction();
        assertEquals(1, Util.getOnlyPartitionUnfiltered(Util.cmd(cfs, key).build()).rowCount());
    }

    @Test
    public void overlappingRangeTest() throws Exception
    {
        CompactionManager.instance.disableAutoCompaction();
        Keyspace keyspace = Keyspace.open(KSNAME);
        ColumnFamilyStore cfs = keyspace.getColumnFamilyStore(CFNAME);

        // Inserting data
        String key = "k2";

        UpdateBuilder builder = UpdateBuilder.create(cfs.metadata, key).withTimestamp(0);
        for (int i = 0; i < 20; i++)
            builder.newRow(i).add("val", i);
        builder.applyUnsafe();
        cfs.forceBlockingFlush();

        new RowUpdateBuilder(cfs.metadata, 1, key).addRangeTombstone(5, 15).build().applyUnsafe();
        cfs.forceBlockingFlush();

        new RowUpdateBuilder(cfs.metadata, 1, key).addRangeTombstone(5, 10).build().applyUnsafe();
        cfs.forceBlockingFlush();

        new RowUpdateBuilder(cfs.metadata, 2, key).addRangeTombstone(5, 8).build().applyUnsafe();
        cfs.forceBlockingFlush();

        Partition partition = Util.getOnlyPartitionUnfiltered(Util.cmd(cfs, key).build());

        for (int i = 0; i < 5; i++)
            assertTrue("Row " + i + " should be live", partition.getRow(new SimpleClustering(bb(i))).hasLiveData(FBUtilities.nowInSeconds()));
        for (int i = 16; i < 20; i++)
            assertTrue("Row " + i + " should be live", partition.getRow(new SimpleClustering(bb(i))).hasLiveData(FBUtilities.nowInSeconds()));
        for (int i = 5; i <= 15; i++)
            assertTrue("Row " + i + " shouldn't be live", partition.getRow(new SimpleClustering(bb(i))) == null);

        // Compact everything and re-test
        CompactionManager.instance.performMaximal(cfs, false);
        partition = Util.getOnlyPartitionUnfiltered(Util.cmd(cfs, key).build());

        for (int i = 0; i < 5; i++)
            assertTrue("Row " + i + " should be live", partition.getRow(new SimpleClustering(bb(i))).hasLiveData(FBUtilities.nowInSeconds()));
        for (int i = 16; i < 20; i++)
            assertTrue("Row " + i + " should be live", partition.getRow(new SimpleClustering(bb(i))).hasLiveData(FBUtilities.nowInSeconds()));
        for (int i = 5; i <= 15; i++)
            assertTrue("Row " + i + " shouldn't be live", partition.getRow(new SimpleClustering(bb(i))) == null);
    }

    @Test
    public void reverseQueryTest() throws Exception
    {
        Keyspace table = Keyspace.open(KSNAME);
        ColumnFamilyStore cfs = table.getColumnFamilyStore(CFNAME);

        // Inserting data
        String key = "k3";

        UpdateBuilder.create(cfs.metadata, key).withTimestamp(0).newRow(2).add("val", 2).applyUnsafe();
        cfs.forceBlockingFlush();

        new RowUpdateBuilder(cfs.metadata, 1, key).addRangeTombstone(0, 10).build().applyUnsafe();
        UpdateBuilder.create(cfs.metadata, key).withTimestamp(2).newRow(1).add("val", 1).applyUnsafe();
        cfs.forceBlockingFlush();

        // Get the last value of the row
        FilteredPartition partition = Util.getOnlyPartition(Util.cmd(cfs, key).build());
        assertTrue(partition.rowCount() > 0);

        int last = i(partition.unfilteredIterator(ColumnFilter.all(cfs.metadata), Slices.ALL, true).next().clustering().get(0));
        assertEquals("Last column should be column 1 since column 2 has been deleted", 1, last);
    }

    @Test
    public void testRowWithRangeTombstonesUpdatesSecondaryIndex() throws Exception
    {
        runCompactionWithRangeTombstoneAndCheckSecondaryIndex();
    }

    @Test
    public void testRangeTombstoneCompaction() throws Exception
    {
        Keyspace table = Keyspace.open(KSNAME);
        ColumnFamilyStore cfs = table.getColumnFamilyStore(CFNAME);
        ByteBuffer key = ByteBufferUtil.bytes("k4");

        // remove any existing sstables before starting
        cfs.truncateBlocking();
        cfs.disableAutoCompaction();
        cfs.setCompactionStrategyClass(SizeTieredCompactionStrategy.class.getCanonicalName());

        UpdateBuilder builder = UpdateBuilder.create(cfs.metadata, key).withTimestamp(0);
        for (int i = 0; i < 10; i += 2)
            builder.newRow(i).add("val", i);
        builder.applyUnsafe();
        cfs.forceBlockingFlush();

        new RowUpdateBuilder(cfs.metadata, 0, key).addRangeTombstone(0, 7).build().applyUnsafe();
        cfs.forceBlockingFlush();

        // there should be 2 sstables
        assertEquals(2, cfs.getSSTables().size());

        // compact down to single sstable
        CompactionManager.instance.performMaximal(cfs, false);
        assertEquals(1, cfs.getSSTables().size());

        // test the physical structure of the sstable i.e. rt & columns on disk
        SSTableReader sstable = cfs.getSSTables().iterator().next();
        try (UnfilteredPartitionIterator scanner = sstable.getScanner())
        {
            try (UnfilteredRowIterator iter = scanner.next())
            {
                // after compaction, we should have a single RT with a single row (the row 8)
                Unfiltered u1 = iter.next();
                assertTrue("Expecting open marker, got " + u1.toString(cfs.metadata), u1 instanceof RangeTombstoneMarker);
                Unfiltered u2 = iter.next();
                assertTrue("Expecting close marker, got " + u2.toString(cfs.metadata), u2 instanceof RangeTombstoneMarker);
                Unfiltered u3 = iter.next();
                assertTrue("Expecting row, got " + u3.toString(cfs.metadata), u3 instanceof Row);
            }
        }
    }

    @Test
    public void testOverwritesToDeletedColumns() throws Exception
    {
        Keyspace table = Keyspace.open(KSNAME);
        ColumnFamilyStore cfs = table.getColumnFamilyStore(CFNAME);
        ByteBuffer key = ByteBufferUtil.bytes("k6");
        ByteBuffer indexedColumnName = ByteBufferUtil.bytes("val");

        cfs.truncateBlocking();
        cfs.disableAutoCompaction();
        cfs.setCompactionStrategyClass(SizeTieredCompactionStrategy.class.getCanonicalName());

        ColumnDefinition cd = cfs.metadata.getColumnDefinition(indexedColumnName).copy();
        cd.setIndex("test_index", IndexType.CUSTOM, ImmutableMap.of(SecondaryIndex.CUSTOM_INDEX_OPTION_NAME, TestIndex.class.getName()));
        Future<?> rebuild = cfs.indexManager.addIndexedColumn(cd);
        // If rebuild there is, wait for the rebuild to finish so it doesn't race with the following insertions
        if (rebuild != null)
            rebuild.get();

        TestIndex index = ((TestIndex)cfs.indexManager.getIndexForColumn(cd));
        index.resetCounts();

        UpdateBuilder.create(cfs.metadata, key).withTimestamp(0).newRow(1).add("val", 1).applyUnsafe();

        // add a RT which hides the column we just inserted
        new RowUpdateBuilder(cfs.metadata, 1, key).addRangeTombstone(0, 1).build().applyUnsafe();

        // now re-insert that column
        UpdateBuilder.create(cfs.metadata, key).withTimestamp(2).newRow(1).add("val", 1).applyUnsafe();

        cfs.forceBlockingFlush();

        // We should have 1 insert and 1 update to the indexed "1" column
        // CASSANDRA-6640 changed index update to just update, not insert then delete
        assertEquals(1, index.inserts.size());
        assertEquals(1, index.updates.size());
    }

    private void runCompactionWithRangeTombstoneAndCheckSecondaryIndex() throws Exception
    {
        Keyspace table = Keyspace.open(KSNAME);
        ColumnFamilyStore cfs = table.getColumnFamilyStore(CFNAME);
        ByteBuffer key = ByteBufferUtil.bytes("k5");
        ByteBuffer indexedColumnName = ByteBufferUtil.bytes("val");

        cfs.truncateBlocking();
        cfs.disableAutoCompaction();
        cfs.setCompactionStrategyClass(SizeTieredCompactionStrategy.class.getCanonicalName());


        ColumnDefinition cd = cfs.metadata.getColumnDefinition(indexedColumnName).copy();
        cd.setIndex("test_index", IndexType.CUSTOM, ImmutableMap.of(SecondaryIndex.CUSTOM_INDEX_OPTION_NAME, TestIndex.class.getName()));
        Future<?> rebuild = cfs.indexManager.addIndexedColumn(cd);
        // If rebuild there is, wait for the rebuild to finish so it doesn't race with the following insertions
        if (rebuild != null)
            rebuild.get();

        TestIndex index = ((TestIndex)cfs.indexManager.getIndexForColumn(cd));
        index.resetCounts();

        UpdateBuilder builder = UpdateBuilder.create(cfs.metadata, key).withTimestamp(0);
        for (int i = 0; i < 10; i++)
            builder.newRow(i).add("val", i);
        builder.applyUnsafe();
        cfs.forceBlockingFlush();

        new RowUpdateBuilder(cfs.metadata, 0, key).addRangeTombstone(0, 7).build().applyUnsafe();
        cfs.forceBlockingFlush();

        assertEquals(10, index.inserts.size());

        CompactionManager.instance.performMaximal(cfs, false);

        // compacted down to single sstable
        assertEquals(1, cfs.getSSTables().size());

        assertEquals(10, index.deletes.size());
    }

    private static ByteBuffer bb(int i)
    {
        return ByteBufferUtil.bytes(i);
    }

    private static int i(ByteBuffer bb)
    {
        return ByteBufferUtil.toInt(bb);
    }

    public static class TestIndex extends PerColumnSecondaryIndex
    {
        public List<Cell> inserts = new ArrayList<>();
        public List<Cell> deletes = new ArrayList<>();
        public List<Cell> updates = new ArrayList<>();

        public void resetCounts()
        {
            inserts.clear();
            deletes.clear();
            updates.clear();
        }

        public void delete(ByteBuffer rowKey, Clustering clustering, Cell cell, OpOrder.Group opGroup, int nowInSec)
        {
            deletes.add(cell);
        }

        @Override
        public void deleteForCleanup(ByteBuffer rowKey, Clustering clustering, Cell cell, OpOrder.Group opGroup, int nowInSec) {}

        public void insert(ByteBuffer rowKey, Clustering clustering, Cell cell, OpOrder.Group opGroup)
        {
            inserts.add(cell);
        }

        public void update(ByteBuffer rowKey, Clustering clustering, Cell oldCell, Cell cell, OpOrder.Group opGroup, int nowInSec)
        {
            updates.add(cell);
        }

        public void init(){}

        public void reload(){}

        public void validateOptions() throws ConfigurationException{}

        public String getIndexName(){ return "TestIndex";}

        protected SecondaryIndexSearcher createSecondaryIndexSearcher(Set<ColumnDefinition> columns) { return null; };

        public void forceBlockingFlush(){}

        public ColumnFamilyStore getIndexCfs(){ return null; }

        public void removeIndex(ByteBuffer columnName){}

        public void invalidate(){}

        public void validate(DecoratedKey key) {}
        public void validate(ByteBuffer value, CellPath path) {}
        public void validate(Clustering clustering) {}

        public void truncateBlocking(long truncatedAt) { }

        public boolean indexes(ColumnDefinition name) { return true; }

        @Override
        public long estimateResultRows()
        {
            return 0;
        }
    }
}
