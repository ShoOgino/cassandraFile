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
package org.apache.cassandra.db.compaction;

import java.io.*;
import java.nio.ByteBuffer;
import java.util.*;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import com.google.common.base.Function;
import com.google.common.collect.Iterables;
import com.google.common.collect.SetMultimap;
import com.google.common.collect.Sets;
import org.junit.Test;
import org.junit.runner.RunWith;

import org.apache.cassandra.OrderedJUnit4ClassRunner;
import org.apache.cassandra.SchemaLoader;
import org.apache.cassandra.Util;
import org.apache.cassandra.db.*;
import org.apache.cassandra.db.columniterator.IdentityQueryFilter;
import org.apache.cassandra.db.columniterator.OnDiskAtomIterator;
import org.apache.cassandra.db.filter.QueryFilter;
import org.apache.cassandra.db.marshal.CompositeType;
import org.apache.cassandra.io.sstable.Component;
import org.apache.cassandra.io.sstable.SSTableReader;
import org.apache.cassandra.io.sstable.SSTableScanner;
import org.apache.cassandra.utils.ByteBufferUtil;
import org.apache.cassandra.utils.FBUtilities;
import org.apache.cassandra.utils.Pair;

import static junit.framework.Assert.*;

@RunWith(OrderedJUnit4ClassRunner.class)
public class CompactionsTest extends SchemaLoader
{
    public static final String TABLE1 = "Keyspace1";

    public ColumnFamilyStore testSingleSSTableCompaction(String strategyClassName) throws Exception
    {
        Table table = Table.open(TABLE1);
        ColumnFamilyStore store = table.getColumnFamilyStore("Standard1");
        store.clearUnsafe();
        store.metadata.gcGraceSeconds(1);
        store.setCompactionStrategyClass(strategyClassName);

        // disable compaction while flushing
        store.disableAutoCompaction();

        long timestamp = System.currentTimeMillis();
        for (int i = 0; i < 10; i++)
        {
            DecoratedKey key = Util.dk(Integer.toString(i));
            RowMutation rm = new RowMutation(TABLE1, key.key);
            for (int j = 0; j < 10; j++)
                rm.add("Standard1", ByteBufferUtil.bytes(Integer.toString(j)),
                       ByteBufferUtil.EMPTY_BYTE_BUFFER,
                       timestamp,
                       j > 0 ? 3 : 0); // let first column never expire, since deleting all columns does not produce sstable
            rm.apply();
        }
        store.forceBlockingFlush();
        assertEquals(1, store.getSSTables().size());
        long originalSize = store.getSSTables().iterator().next().uncompressedLength();

        // wait enough to force single compaction
        TimeUnit.SECONDS.sleep(5);

        // enable compaction, submit background and wait for it to complete
        store.enableAutoCompaction();
        FBUtilities.waitOnFutures(CompactionManager.instance.submitBackground(store));
        while (CompactionManager.instance.getPendingTasks() > 0 || CompactionManager.instance.getActiveCompactions() > 0)
            TimeUnit.SECONDS.sleep(1);

        // and sstable with ttl should be compacted
        assertEquals(1, store.getSSTables().size());
        long size = store.getSSTables().iterator().next().uncompressedLength();
        assertTrue("should be less than " + originalSize + ", but was " + size, size < originalSize);

        // make sure max timestamp of compacted sstables is recorded properly after compaction.
        assertMaxTimestamp(store, timestamp);

        return store;
    }

    /**
     * Test to see if sstable has enough expired columns, it is compacted itself.
     */
    @Test
    public void testSingleSSTableCompactionWithSizeTieredCompaction() throws Exception
    {
        testSingleSSTableCompaction(SizeTieredCompactionStrategy.class.getCanonicalName());
    }

    @Test
    public void testSingleSSTableCompactionWithLeveledCompaction() throws Exception
    {
        ColumnFamilyStore store = testSingleSSTableCompaction(LeveledCompactionStrategy.class.getCanonicalName());
        LeveledCompactionStrategy strategy = (LeveledCompactionStrategy) store.getCompactionStrategy();
        // tombstone removal compaction should not promote level
        assert strategy.getLevelSize(0) == 1;
    }

    @Test
    public void testSuperColumnTombstones() throws IOException, ExecutionException, InterruptedException
    {
        Table table = Table.open(TABLE1);
        ColumnFamilyStore cfs = table.getColumnFamilyStore("Super1");
        cfs.disableAutoCompaction();

        DecoratedKey key = Util.dk("tskey");
        ByteBuffer scName = ByteBufferUtil.bytes("TestSuperColumn");

        // a subcolumn
        RowMutation rm = new RowMutation(TABLE1, key.key);
        rm.add("Super1", CompositeType.build(scName, ByteBufferUtil.bytes(0)),
               ByteBufferUtil.EMPTY_BYTE_BUFFER,
               FBUtilities.timestampMicros());
        rm.apply();
        cfs.forceBlockingFlush();

        // shadow the subcolumn with a supercolumn tombstone
        rm = new RowMutation(TABLE1, key.key);
        rm.deleteRange("Super1", SuperColumns.startOf(scName), SuperColumns.endOf(scName), FBUtilities.timestampMicros());
        rm.apply();
        cfs.forceBlockingFlush();

        CompactionManager.instance.performMaximal(cfs);
        assertEquals(1, cfs.getSSTables().size());

        // check that the shadowed column is gone
        SSTableReader sstable = cfs.getSSTables().iterator().next();
        SSTableScanner scanner = sstable.getScanner(new QueryFilter(null, "Super1", new IdentityQueryFilter(), System.currentTimeMillis()), key);
        OnDiskAtomIterator iter = scanner.next();
        assertEquals(key, iter.getKey());
        assert iter.next() instanceof RangeTombstone;
        assert !iter.hasNext();
    }

    public static void assertMaxTimestamp(ColumnFamilyStore cfs, long maxTimestampExpected)
    {
        long maxTimestampObserved = Long.MIN_VALUE;
        for (SSTableReader sstable : cfs.getSSTables())
            maxTimestampObserved = Math.max(sstable.getMaxTimestamp(), maxTimestampObserved);
        assertEquals(maxTimestampExpected, maxTimestampObserved);
    }

    @Test
    public void testEchoedRow() throws IOException, ExecutionException, InterruptedException
    {
        // This test check that EchoedRow doesn't skipp rows: see CASSANDRA-2653

        Table table = Table.open(TABLE1);
        ColumnFamilyStore cfs = table.getColumnFamilyStore("Standard2");

        // disable compaction while flushing
        cfs.disableAutoCompaction();

        // Insert 4 keys in two sstables. We need the sstables to have 2 rows
        // at least to trigger what was causing CASSANDRA-2653
        for (int i=1; i < 5; i++)
        {
            DecoratedKey key = Util.dk(String.valueOf(i));
            RowMutation rm = new RowMutation(TABLE1, key.key);
            rm.add("Standard2", ByteBufferUtil.bytes(String.valueOf(i)), ByteBufferUtil.EMPTY_BYTE_BUFFER, i);
            rm.apply();

            if (i % 2 == 0)
                cfs.forceBlockingFlush();
        }
        Collection<SSTableReader> toCompact = cfs.getSSTables();
        assert toCompact.size() == 2;

        // Reinserting the same keys. We will compact only the previous sstable, but we need those new ones
        // to make sure we use EchoedRow, otherwise it won't be used because purge can be done.
        for (int i=1; i < 5; i++)
        {
            DecoratedKey key = Util.dk(String.valueOf(i));
            RowMutation rm = new RowMutation(TABLE1, key.key);
            rm.add("Standard2", ByteBufferUtil.bytes(String.valueOf(i)), ByteBufferUtil.EMPTY_BYTE_BUFFER, i);
            rm.apply();
        }
        cfs.forceBlockingFlush();
        SSTableReader tmpSSTable = null;
        for (SSTableReader sstable : cfs.getSSTables())
            if (!toCompact.contains(sstable))
                tmpSSTable = sstable;
        assert tmpSSTable != null;

        // Force compaction on first sstables. Since each row is in only one sstable, we will be using EchoedRow.
        Util.compact(cfs, toCompact);
        assertEquals(2, cfs.getSSTables().size());

        // Now, we remove the sstable that was just created to force the use of EchoedRow (so that it doesn't hide the problem)
        cfs.markObsolete(Collections.singleton(tmpSSTable), OperationType.UNKNOWN);
        assertEquals(1, cfs.getSSTables().size());

        // Now assert we do have the 4 keys
        assertEquals(4, Util.getRangeSlice(cfs).size());
    }

    @Test
    public void testDontPurgeAccidentaly() throws IOException, ExecutionException, InterruptedException
    {
        testDontPurgeAccidentaly("test1", "Super5");

        // Use CF with gc_grace=0, see last bug of CASSANDRA-2786
        testDontPurgeAccidentaly("test1", "SuperDirectGC");
    }

    @Test
    public void testUserDefinedCompaction() throws Exception
    {
        Table table = Table.open(TABLE1);
        final String cfname = "Standard3"; // use clean(no sstable) CF
        ColumnFamilyStore cfs = table.getColumnFamilyStore(cfname);

        // disable compaction while flushing
        cfs.disableAutoCompaction();

        final int ROWS_PER_SSTABLE = 10;
        for (int i = 0; i < ROWS_PER_SSTABLE; i++) {
            DecoratedKey key = Util.dk(String.valueOf(i));
            RowMutation rm = new RowMutation(TABLE1, key.key);
            rm.add(cfname, ByteBufferUtil.bytes("col"),
                   ByteBufferUtil.EMPTY_BYTE_BUFFER,
                   System.currentTimeMillis());
            rm.apply();
        }
        cfs.forceBlockingFlush();
        Collection<SSTableReader> sstables = cfs.getSSTables();

        assert sstables.size() == 1;
        SSTableReader sstable = sstables.iterator().next();

        int prevGeneration = sstable.descriptor.generation;
        String file = new File(sstable.descriptor.filenameFor(Component.DATA)).getName();
        // submit user defined compaction on flushed sstable
        CompactionManager.instance.forceUserDefinedCompaction(file);
        // wait until user defined compaction finishes
        do
        {
            Thread.sleep(100);
        } while (CompactionManager.instance.getPendingTasks() > 0 || CompactionManager.instance.getActiveCompactions() > 0);
        // CF should have only one sstable with generation number advanced
        sstables = cfs.getSSTables();
        assert sstables.size() == 1;
        assert sstables.iterator().next().descriptor.generation == prevGeneration + 1;
    }

    @Test
    public void testCompactionLog() throws Exception
    {
        SystemTable.discardCompactionsInProgress();

        String cf = "Standard4";
        ColumnFamilyStore cfs = Table.open(TABLE1).getColumnFamilyStore(cf);
        insertData(TABLE1, cf, 0, 1);
        cfs.forceBlockingFlush();

        Collection<SSTableReader> sstables = cfs.getSSTables();
        assert !sstables.isEmpty();
        Set<Integer> generations = Sets.newHashSet(Iterables.transform(sstables, new Function<SSTableReader, Integer>()
        {
            public Integer apply(SSTableReader sstable)
            {
                return sstable.descriptor.generation;
            }
        }));
        UUID taskId = SystemTable.startCompaction(cfs, sstables);
        SetMultimap<Pair<String, String>, Integer> compactionLogs = SystemTable.getUnfinishedCompactions();
        Set<Integer> unfinishedCompactions = compactionLogs.get(Pair.create(TABLE1, cf));
        assert unfinishedCompactions.containsAll(generations);

        SystemTable.finishCompaction(taskId);
        compactionLogs = SystemTable.getUnfinishedCompactions();
        assert !compactionLogs.containsKey(Pair.create(TABLE1, cf));
    }

    private void testDontPurgeAccidentaly(String k, String cfname) throws IOException, ExecutionException, InterruptedException
    {
        // This test catches the regression of CASSANDRA-2786
        Table table = Table.open(TABLE1);
        ColumnFamilyStore cfs = table.getColumnFamilyStore(cfname);

        // disable compaction while flushing
        cfs.clearUnsafe();
        cfs.disableAutoCompaction();

        // Add test row
        DecoratedKey key = Util.dk(k);
        RowMutation rm = new RowMutation(TABLE1, key.key);
        rm.add(cfname, CompositeType.build(ByteBufferUtil.bytes("sc"), ByteBufferUtil.bytes("c")), ByteBufferUtil.EMPTY_BYTE_BUFFER, 0);
        rm.apply();

        cfs.forceBlockingFlush();

        Collection<SSTableReader> sstablesBefore = cfs.getSSTables();

        QueryFilter filter = QueryFilter.getIdentityFilter(key, cfname, System.currentTimeMillis());
        assert !(cfs.getColumnFamily(filter).getColumnCount() == 0);

        // Remove key
        rm = new RowMutation(TABLE1, key.key);
        rm.delete(cfname, 2);
        rm.apply();

        ColumnFamily cf = cfs.getColumnFamily(filter);
        assert cf == null || cf.getColumnCount() == 0 : "should be empty: " + cf;

        // Sleep one second so that the removal is indeed purgeable even with gcgrace == 0
        Thread.sleep(1000);

        cfs.forceBlockingFlush();

        Collection<SSTableReader> sstablesAfter = cfs.getSSTables();
        Collection<SSTableReader> toCompact = new ArrayList<SSTableReader>();
        for (SSTableReader sstable : sstablesAfter)
            if (!sstablesBefore.contains(sstable))
                toCompact.add(sstable);

        Util.compact(cfs, toCompact);

        cf = cfs.getColumnFamily(filter);
        assert cf == null || cf.getColumnCount() == 0 : "should be empty: " + cf;
    }
}
