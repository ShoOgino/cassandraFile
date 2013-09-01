/**
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

package org.apache.cassandra.db;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.text.DecimalFormat;
import java.text.NumberFormat;
import java.util.*;
import java.io.IOException;

import com.google.common.collect.Iterables;
import org.apache.commons.lang3.StringUtils;
import org.junit.Test;

import static org.junit.Assert.*;
import org.apache.cassandra.SchemaLoader;
import org.apache.cassandra.config.DatabaseDescriptor;
import org.apache.cassandra.db.compaction.CompactionManager;
import org.apache.cassandra.db.filter.QueryFilter;
import org.apache.cassandra.db.marshal.BytesType;
import org.apache.cassandra.db.marshal.CompositeType;
import org.apache.cassandra.db.marshal.IntegerType;
import org.apache.cassandra.utils.WrappedRunnable;
import static org.apache.cassandra.Util.column;
import static org.apache.cassandra.Util.expiringColumn;
import org.apache.cassandra.Util;
import org.apache.cassandra.io.sstable.SSTableReader;
import org.apache.cassandra.utils.ByteBufferUtil;


public class KeyspaceTest extends SchemaLoader
{
    private static final DecoratedKey TEST_KEY = Util.dk("key1");
    private static final DecoratedKey TEST_SLICE_KEY = Util.dk("key1-slicerange");

    public static void reTest(ColumnFamilyStore cfs, Runnable verify) throws Exception
    {
        verify.run();
        cfs.forceBlockingFlush();
        verify.run();
    }

    @Test
    public void testGetRowNoColumns() throws Throwable
    {
        final Keyspace keyspace = Keyspace.open("Keyspace2");
        final ColumnFamilyStore cfStore = keyspace.getColumnFamilyStore("Standard3");

        ColumnFamily cf = TreeMapBackedSortedColumns.factory.create("Keyspace2", "Standard3");
        cf.addColumn(column("col1","val1", 1L));
        RowMutation rm = new RowMutation("Keyspace2", TEST_KEY.key, cf);
        rm.apply();

        Runnable verify = new WrappedRunnable()
        {
            public void runMayThrow() throws Exception
            {
                ColumnFamily cf;

                cf = cfStore.getColumnFamily(QueryFilter.getNamesFilter(TEST_KEY,
                                                                        "Standard3",
                                                                        new TreeSet<ByteBuffer>(),
                                                                        System.currentTimeMillis()));
                assertColumns(cf);

                cf = cfStore.getColumnFamily(QueryFilter.getSliceFilter(TEST_KEY,
                                                                        "Standard3",
                                                                        ByteBufferUtil.EMPTY_BYTE_BUFFER,
                                                                        ByteBufferUtil.EMPTY_BYTE_BUFFER,
                                                                        false,
                                                                        0,
                                                                        System.currentTimeMillis()));
                assertColumns(cf);

                cf = cfStore.getColumnFamily(QueryFilter.getNamesFilter(TEST_KEY,
                                                                        "Standard3",
                                                                        ByteBufferUtil.bytes("col99"),
                                                                        System.currentTimeMillis()));
                assertColumns(cf);
            }
        };
        reTest(keyspace.getColumnFamilyStore("Standard3"), verify);
    }

    @Test
    public void testGetRowSingleColumn() throws Throwable
    {
        final Keyspace keyspace = Keyspace.open("Keyspace1");
        final ColumnFamilyStore cfStore = keyspace.getColumnFamilyStore("Standard1");

        ColumnFamily cf = TreeMapBackedSortedColumns.factory.create("Keyspace1", "Standard1");
        cf.addColumn(column("col1","val1", 1L));
        cf.addColumn(column("col2","val2", 1L));
        cf.addColumn(column("col3","val3", 1L));
        RowMutation rm = new RowMutation("Keyspace1", TEST_KEY.key, cf);
        rm.apply();

        Runnable verify = new WrappedRunnable()
        {
            public void runMayThrow() throws Exception
            {
                ColumnFamily cf;

                cf = cfStore.getColumnFamily(QueryFilter.getNamesFilter(TEST_KEY,
                                                                        "Standard1",
                                                                        ByteBufferUtil.bytes("col1"),
                                                                        System.currentTimeMillis()));
                assertColumns(cf, "col1");

                cf = cfStore.getColumnFamily(QueryFilter.getNamesFilter(TEST_KEY,
                                                                        "Standard1",
                                                                        ByteBufferUtil.bytes("col3"),
                                                                        System.currentTimeMillis()));
                assertColumns(cf, "col3");
            }
        };
        reTest(keyspace.getColumnFamilyStore("Standard1"), verify);
    }

    @Test
    public void testGetRowSliceByRange() throws Throwable
    {
    	DecoratedKey key = TEST_SLICE_KEY;
    	Keyspace keyspace = Keyspace.open("Keyspace1");
        ColumnFamilyStore cfStore = keyspace.getColumnFamilyStore("Standard1");
        ColumnFamily cf = TreeMapBackedSortedColumns.factory.create("Keyspace1", "Standard1");
        // First write "a", "b", "c"
        cf.addColumn(column("a", "val1", 1L));
        cf.addColumn(column("b", "val2", 1L));
        cf.addColumn(column("c", "val3", 1L));
        RowMutation rm = new RowMutation("Keyspace1", key.key, cf);
        rm.apply();

        cf = cfStore.getColumnFamily(key, ByteBufferUtil.bytes("b"), ByteBufferUtil.bytes("c"), false, 100, System.currentTimeMillis());
        assertEquals(2, cf.getColumnCount());

        cf = cfStore.getColumnFamily(key, ByteBufferUtil.bytes("b"), ByteBufferUtil.bytes("b"), false, 100, System.currentTimeMillis());
        assertEquals(1, cf.getColumnCount());

        cf = cfStore.getColumnFamily(key, ByteBufferUtil.bytes("b"), ByteBufferUtil.bytes("c"), false, 1, System.currentTimeMillis());
        assertEquals(1, cf.getColumnCount());
    }

    @Test
    public void testGetSliceNoMatch() throws Throwable
    {
        Keyspace keyspace = Keyspace.open("Keyspace1");
        ColumnFamily cf = TreeMapBackedSortedColumns.factory.create("Keyspace1", "Standard2");
        cf.addColumn(column("col1", "val1", 1));
        RowMutation rm = new RowMutation("Keyspace1", ByteBufferUtil.bytes("row1000"), cf);
        rm.apply();

        validateGetSliceNoMatch(keyspace);
        keyspace.getColumnFamilyStore("Standard2").forceBlockingFlush();
        validateGetSliceNoMatch(keyspace);

        Collection<SSTableReader> ssTables = keyspace.getColumnFamilyStore("Standard2").getSSTables();
        assertEquals(1, ssTables.size());
        ssTables.iterator().next().forceFilterFailures();
        validateGetSliceNoMatch(keyspace);
    }

    @Test
    public void testGetSliceWithCutoff() throws Throwable
    {
        // tests slicing against data from one row in a memtable and then flushed to an sstable
        final Keyspace keyspace = Keyspace.open("Keyspace1");
        final ColumnFamilyStore cfStore = keyspace.getColumnFamilyStore("Standard1");
        final DecoratedKey ROW = Util.dk("row4");
        final NumberFormat fmt = new DecimalFormat("000");

        ColumnFamily cf = TreeMapBackedSortedColumns.factory.create("Keyspace1", "Standard1");
        // at this rate, we're getting 78-79 cos/block, assuming the blocks are set to be about 4k.
        // so if we go to 300, we'll get at least 4 blocks, which is plenty for testing.
        for (int i = 0; i < 300; i++)
            cf.addColumn(column("col" + fmt.format(i), "omg!thisisthevalue!"+i, 1L));
        RowMutation rm = new RowMutation("Keyspace1", ROW.key, cf);
        rm.apply();

        Runnable verify = new WrappedRunnable()
        {
            public void runMayThrow() throws Exception
            {
                ColumnFamily cf;

                // blocks are partitioned like this: 000-097, 098-193, 194-289, 290-299, assuming a 4k column index size.
                assert DatabaseDescriptor.getColumnIndexSize() == 4096 : "Unexpected column index size, block boundaries won't be where tests expect them.";

                // test forward, spanning a segment.
                cf = cfStore.getColumnFamily(ROW, ByteBufferUtil.bytes("col096"), ByteBufferUtil.bytes("col099"), false, 4, System.currentTimeMillis());
                assertColumns(cf, "col096", "col097", "col098", "col099");

                // test reversed, spanning a segment.
                cf = cfStore.getColumnFamily(ROW, ByteBufferUtil.bytes("col099"), ByteBufferUtil.bytes("col096"), true, 4, System.currentTimeMillis());
                assertColumns(cf, "col096", "col097", "col098", "col099");

                // test forward, within a segment.
                cf = cfStore.getColumnFamily(ROW, ByteBufferUtil.bytes("col100"), ByteBufferUtil.bytes("col103"), false, 4, System.currentTimeMillis());
                assertColumns(cf, "col100", "col101", "col102", "col103");

                // test reversed, within a segment.
                cf = cfStore.getColumnFamily(ROW, ByteBufferUtil.bytes("col103"), ByteBufferUtil.bytes("col100"), true, 4, System.currentTimeMillis());
                assertColumns(cf, "col100", "col101", "col102", "col103");

                // test forward from beginning, spanning a segment.
                String[] strCols = new String[100]; // col000-col099
                for (int i = 0; i < 100; i++)
                    strCols[i] = "col" + fmt.format(i);
                cf = cfStore.getColumnFamily(ROW, ByteBufferUtil.EMPTY_BYTE_BUFFER, ByteBufferUtil.bytes("col099"), false, 100, System.currentTimeMillis());
                assertColumns(cf, strCols);

                // test reversed, from end, spanning a segment.
                cf = cfStore.getColumnFamily(ROW, ByteBufferUtil.EMPTY_BYTE_BUFFER, ByteBufferUtil.bytes("col288"), true, 12, System.currentTimeMillis());
                assertColumns(cf, "col288", "col289", "col290", "col291", "col292", "col293", "col294", "col295", "col296", "col297", "col298", "col299");
            }
        };

        reTest(keyspace.getColumnFamilyStore("Standard1"), verify);
    }

    @Test
    public void testReversedWithFlushing()
    {
        final Keyspace keyspace = Keyspace.open("Keyspace1");
        final ColumnFamilyStore cfs = keyspace.getColumnFamilyStore("StandardLong1");
        final DecoratedKey ROW = Util.dk("row4");

        for (int i = 0; i < 10; i++)
        {
            ColumnFamily cf = TreeMapBackedSortedColumns.factory.create("Keyspace1", "StandardLong1");
            cf.addColumn(new Column(ByteBufferUtil.bytes((long)i), ByteBufferUtil.EMPTY_BYTE_BUFFER, 0));
            RowMutation rm = new RowMutation("Keyspace1", ROW.key, cf);
            rm.apply();
        }

        cfs.forceBlockingFlush();

        for (int i = 10; i < 20; i++)
        {
            ColumnFamily cf = TreeMapBackedSortedColumns.factory.create("Keyspace1", "StandardLong1");
            cf.addColumn(new Column(ByteBufferUtil.bytes((long)i), ByteBufferUtil.EMPTY_BYTE_BUFFER, 0));
            RowMutation rm = new RowMutation("Keyspace1", ROW.key, cf);
            rm.apply();

            cf = cfs.getColumnFamily(ROW, ByteBufferUtil.EMPTY_BYTE_BUFFER, ByteBufferUtil.EMPTY_BYTE_BUFFER, true, 1, System.currentTimeMillis());
            assertEquals(1, Iterables.size(cf.getColumnNames()));
            assertEquals(i, cf.getColumnNames().iterator().next().getLong());
        }
    }

    private void validateGetSliceNoMatch(Keyspace keyspace)
    {
        ColumnFamilyStore cfStore = keyspace.getColumnFamilyStore("Standard2");
        ColumnFamily cf;

        // key before the rows that exists
        cf = cfStore.getColumnFamily(Util.dk("a"), ByteBufferUtil.EMPTY_BYTE_BUFFER, ByteBufferUtil.EMPTY_BYTE_BUFFER, false, 1, System.currentTimeMillis());
        assertColumns(cf);

        // key after the rows that exist
        cf = cfStore.getColumnFamily(Util.dk("z"), ByteBufferUtil.EMPTY_BYTE_BUFFER, ByteBufferUtil.EMPTY_BYTE_BUFFER, false, 1, System.currentTimeMillis());
        assertColumns(cf);
    }

    @Test
    public void testGetSliceFromBasic() throws Throwable
    {
        // tests slicing against data from one row in a memtable and then flushed to an sstable
        final Keyspace keyspace = Keyspace.open("Keyspace1");
        final ColumnFamilyStore cfStore = keyspace.getColumnFamilyStore("Standard1");
        final DecoratedKey ROW = Util.dk("row1");

        ColumnFamily cf = TreeMapBackedSortedColumns.factory.create("Keyspace1", "Standard1");
        cf.addColumn(column("col1", "val1", 1L));
        cf.addColumn(column("col3", "val3", 1L));
        cf.addColumn(column("col4", "val4", 1L));
        cf.addColumn(column("col5", "val5", 1L));
        cf.addColumn(column("col7", "val7", 1L));
        cf.addColumn(column("col9", "val9", 1L));
        RowMutation rm = new RowMutation("Keyspace1", ROW.key, cf);
        rm.apply();

        rm = new RowMutation("Keyspace1", ROW.key);
        rm.delete("Standard1", ByteBufferUtil.bytes("col4"), 2L);
        rm.apply();

        Runnable verify = new WrappedRunnable()
        {
            public void runMayThrow() throws Exception
            {
                ColumnFamily cf;

                cf = cfStore.getColumnFamily(ROW, ByteBufferUtil.bytes("col5"), ByteBufferUtil.EMPTY_BYTE_BUFFER, false, 2, System.currentTimeMillis());
                assertColumns(cf, "col5", "col7");

                cf = cfStore.getColumnFamily(ROW, ByteBufferUtil.bytes("col4"), ByteBufferUtil.EMPTY_BYTE_BUFFER, false, 2, System.currentTimeMillis());
                assertColumns(cf, "col4", "col5", "col7");
                assertColumns(ColumnFamilyStore.removeDeleted(cf, Integer.MAX_VALUE), "col5", "col7");

                cf = cfStore.getColumnFamily(ROW, ByteBufferUtil.bytes("col5"), ByteBufferUtil.EMPTY_BYTE_BUFFER, true, 2, System.currentTimeMillis());
                assertColumns(cf, "col3", "col4", "col5");

                cf = cfStore.getColumnFamily(ROW, ByteBufferUtil.bytes("col6"), ByteBufferUtil.EMPTY_BYTE_BUFFER, true, 2, System.currentTimeMillis());
                assertColumns(cf, "col3", "col4", "col5");

                cf = cfStore.getColumnFamily(ROW, ByteBufferUtil.EMPTY_BYTE_BUFFER, ByteBufferUtil.EMPTY_BYTE_BUFFER, true, 2, System.currentTimeMillis());
                assertColumns(cf, "col7", "col9");

                cf = cfStore.getColumnFamily(ROW, ByteBufferUtil.bytes("col95"), ByteBufferUtil.EMPTY_BYTE_BUFFER, false, 2, System.currentTimeMillis());
                assertColumns(cf);

                cf = cfStore.getColumnFamily(ROW, ByteBufferUtil.bytes("col0"), ByteBufferUtil.EMPTY_BYTE_BUFFER, true, 2, System.currentTimeMillis());
                assertColumns(cf);
            }
        };

        reTest(keyspace.getColumnFamilyStore("Standard1"), verify);
    }

    @Test
    public void testGetSliceWithExpiration() throws Throwable
    {
        // tests slicing against data from one row with expiring column in a memtable and then flushed to an sstable
        final Keyspace keyspace = Keyspace.open("Keyspace1");
        final ColumnFamilyStore cfStore = keyspace.getColumnFamilyStore("Standard1");
        final DecoratedKey ROW = Util.dk("row5");

        ColumnFamily cf = TreeMapBackedSortedColumns.factory.create("Keyspace1", "Standard1");
        cf.addColumn(column("col1", "val1", 1L));
        cf.addColumn(expiringColumn("col2", "val2", 1L, 60)); // long enough not to be tombstoned
        cf.addColumn(column("col3", "val3", 1L));
        RowMutation rm = new RowMutation("Keyspace1", ROW.key, cf);
        rm.apply();

        Runnable verify = new WrappedRunnable()
        {
            public void runMayThrow() throws Exception
            {
                ColumnFamily cf;

                cf = cfStore.getColumnFamily(ROW, ByteBufferUtil.EMPTY_BYTE_BUFFER, ByteBufferUtil.EMPTY_BYTE_BUFFER, false, 2, System.currentTimeMillis());
                assertColumns(cf, "col1", "col2");
                assertColumns(ColumnFamilyStore.removeDeleted(cf, Integer.MAX_VALUE), "col1");

                cf = cfStore.getColumnFamily(ROW, ByteBufferUtil.bytes("col2"), ByteBufferUtil.EMPTY_BYTE_BUFFER, false, 1, System.currentTimeMillis());
                assertColumns(cf, "col2");
                assertColumns(ColumnFamilyStore.removeDeleted(cf, Integer.MAX_VALUE));
            }
        };

        reTest(keyspace.getColumnFamilyStore("Standard1"), verify);
    }

    @Test
    public void testGetSliceFromAdvanced() throws Throwable
    {
        // tests slicing against data from one row spread across two sstables
        final Keyspace keyspace = Keyspace.open("Keyspace1");
        final ColumnFamilyStore cfStore = keyspace.getColumnFamilyStore("Standard1");
        final DecoratedKey ROW = Util.dk("row2");

        ColumnFamily cf = TreeMapBackedSortedColumns.factory.create("Keyspace1", "Standard1");
        cf.addColumn(column("col1", "val1", 1L));
        cf.addColumn(column("col2", "val2", 1L));
        cf.addColumn(column("col3", "val3", 1L));
        cf.addColumn(column("col4", "val4", 1L));
        cf.addColumn(column("col5", "val5", 1L));
        cf.addColumn(column("col6", "val6", 1L));
        RowMutation rm = new RowMutation("Keyspace1", ROW.key, cf);
        rm.apply();
        cfStore.forceBlockingFlush();

        cf = TreeMapBackedSortedColumns.factory.create("Keyspace1", "Standard1");
        cf.addColumn(column("col1", "valx", 2L));
        cf.addColumn(column("col2", "valx", 2L));
        cf.addColumn(column("col3", "valx", 2L));
        rm = new RowMutation("Keyspace1", ROW.key, cf);
        rm.apply();

        Runnable verify = new WrappedRunnable()
        {
            public void runMayThrow() throws Exception
            {
                ColumnFamily cf;

                cf = cfStore.getColumnFamily(ROW, ByteBufferUtil.bytes("col2"), ByteBufferUtil.EMPTY_BYTE_BUFFER, false, 3, System.currentTimeMillis());
                assertColumns(cf, "col2", "col3", "col4");

                ByteBuffer col = cf.getColumn(ByteBufferUtil.bytes("col2")).value();
                assertEquals(ByteBufferUtil.string(col), "valx");

                col = cf.getColumn(ByteBufferUtil.bytes("col3")).value();
                assertEquals(ByteBufferUtil.string(col), "valx");

                col = cf.getColumn(ByteBufferUtil.bytes("col4")).value();
                assertEquals(ByteBufferUtil.string(col), "val4");
            }
        };

        reTest(keyspace.getColumnFamilyStore("Standard1"), verify);
    }

    @Test
    public void testGetSliceFromLarge() throws Throwable
    {
        // tests slicing against 1000 columns in an sstable
        Keyspace keyspace = Keyspace.open("Keyspace1");
        ColumnFamilyStore cfStore = keyspace.getColumnFamilyStore("Standard1");
        DecoratedKey key = Util.dk("row3");
        ColumnFamily cf = TreeMapBackedSortedColumns.factory.create("Keyspace1", "Standard1");
        for (int i = 1000; i < 2000; i++)
            cf.addColumn(column("col" + i, ("v" + i), 1L));
        RowMutation rm = new RowMutation("Keyspace1", key.key, cf);
        rm.apply();
        cfStore.forceBlockingFlush();

        validateSliceLarge(cfStore);

        // compact so we have a big row with more than the minimum index count
        if (cfStore.getSSTables().size() > 1)
        {
            CompactionManager.instance.performMaximal(cfStore);
        }
        // verify that we do indeed have multiple index entries
        SSTableReader sstable = cfStore.getSSTables().iterator().next();
        RowIndexEntry indexEntry = sstable.getPosition(key, SSTableReader.Operator.EQ);
        assert indexEntry.columnsIndex().size() > 2;

        validateSliceLarge(cfStore);
    }

    @Test
    public void testLimitSSTables() throws CharacterCodingException
    {
        Keyspace keyspace = Keyspace.open("Keyspace1");
        ColumnFamilyStore cfStore = keyspace.getColumnFamilyStore("Standard1");
        cfStore.disableAutoCompaction();
        DecoratedKey key = Util.dk("row_maxmin");
        for (int j = 0; j < 10; j++)
        {
            ColumnFamily cf = TreeMapBackedSortedColumns.factory.create("Keyspace1", "Standard1");
            for (int i = 1000 + (j*100); i < 1000 + ((j+1)*100); i++)
            {
                cf.addColumn(column("col" + i, ("v" + i), i));
            }
            RowMutation rm = new RowMutation("Keyspace1", key.key, cf);
            rm.apply();
            cfStore.forceBlockingFlush();
        }
        cfStore.metric.sstablesPerReadHistogram.clear();
        ColumnFamily cf = cfStore.getColumnFamily(key, ByteBufferUtil.bytes(""), ByteBufferUtil.bytes("col1499"), false, 1000, System.currentTimeMillis());
        assertEquals(cfStore.metric.sstablesPerReadHistogram.max(), 5, 0.1);
        int i = 0;
        for (Column c : cf.getSortedColumns())
        {
            assertEquals(ByteBufferUtil.string(c.name), "col" + (1000 + i++));
        }
        assertEquals(i, 500);
        cfStore.metric.sstablesPerReadHistogram.clear();
        cf = cfStore.getColumnFamily(key, ByteBufferUtil.bytes("col1500"), ByteBufferUtil.bytes("col2000"), false, 1000, System.currentTimeMillis());
        assertEquals(cfStore.metric.sstablesPerReadHistogram.max(), 5, 0.1);

        for (Column c : cf.getSortedColumns())
        {
            assertEquals(ByteBufferUtil.string(c.name), "col"+(1000 + i++));
        }
        assertEquals(i, 1000);

        // reverse
        cfStore.metric.sstablesPerReadHistogram.clear();
        cf = cfStore.getColumnFamily(key, ByteBufferUtil.bytes("col2000"), ByteBufferUtil.bytes("col1500"), true, 1000, System.currentTimeMillis());
        assertEquals(cfStore.metric.sstablesPerReadHistogram.max(), 5, 0.1);
        i = 500;
        for (Column c : cf.getSortedColumns())
        {
            assertEquals(ByteBufferUtil.string(c.name), "col"+(1000 + i++));
        }
        assertEquals(i, 1000);

    }

    @Test
    public void testLimitSSTablesComposites()
    {
        /*
        creates 10 sstables, composite columns like this:
        ---------------------
        k   |a0:0|a1:1|..|a9:9
        ---------------------
        ---------------------
        k   |a0:10|a1:11|..|a9:19
        ---------------------
        ...
        ---------------------
        k   |a0:90|a1:91|..|a9:99
        ---------------------
        then we slice out col1 = a5 and col2 > 85 -> which should let us just check 2 sstables and get 2 columns
         */
        Keyspace keyspace = Keyspace.open("Keyspace1");

        ColumnFamilyStore cfs = keyspace.getColumnFamilyStore("StandardComposite2");
        cfs.disableAutoCompaction();

        CompositeType ct = CompositeType.getInstance(BytesType.instance, IntegerType.instance);
        DecoratedKey key = Util.dk("k");
        for (int j = 0; j < 10; j++)
        {
            for (int i = 0; i < 10; i++)
            {
                RowMutation rm = new RowMutation("Keyspace1", key.key);
                ByteBuffer colName = ct.builder().add(ByteBufferUtil.bytes("a" + i)).add(ByteBufferUtil.bytes(j*10 + i)).build();
                rm.add("StandardComposite2", colName, ByteBufferUtil.EMPTY_BYTE_BUFFER, 0);
                rm.apply();
            }
            cfs.forceBlockingFlush();
        }
        ByteBuffer start = ct.builder().add(ByteBufferUtil.bytes("a5")).add(ByteBufferUtil.bytes(85)).build();
        ByteBuffer finish = ct.builder().add(ByteBufferUtil.bytes("a5")).buildAsEndOfRange();
        cfs.metric.sstablesPerReadHistogram.clear();
        ColumnFamily cf = cfs.getColumnFamily(key, start, finish, false, 1000, System.currentTimeMillis());
        int colCount = 0;
        for (Column c : cf)
            colCount++;
        assertEquals(2, colCount);
        assertEquals(2, cfs.metric.sstablesPerReadHistogram.max(), 0.1);
    }

    private void validateSliceLarge(ColumnFamilyStore cfStore) throws IOException
    {
        DecoratedKey key = Util.dk("row3");
        ColumnFamily cf;
        cf = cfStore.getColumnFamily(key, ByteBufferUtil.bytes("col1000"), ByteBufferUtil.EMPTY_BYTE_BUFFER, false, 3, System.currentTimeMillis());
        assertColumns(cf, "col1000", "col1001", "col1002");

        ByteBuffer col;
        col = cf.getColumn(ByteBufferUtil.bytes("col1000")).value();
        assertEquals(ByteBufferUtil.string(col), "v1000");
        col = cf.getColumn(ByteBufferUtil.bytes("col1001")).value();
        assertEquals(ByteBufferUtil.string(col), "v1001");
        col = cf.getColumn(ByteBufferUtil.bytes("col1002")).value();
        assertEquals(ByteBufferUtil.string(col), "v1002");

        cf = cfStore.getColumnFamily(key, ByteBufferUtil.bytes("col1195"), ByteBufferUtil.EMPTY_BYTE_BUFFER, false, 3, System.currentTimeMillis());
        assertColumns(cf, "col1195", "col1196", "col1197");

        col = cf.getColumn(ByteBufferUtil.bytes("col1195")).value();
        assertEquals(ByteBufferUtil.string(col), "v1195");
        col = cf.getColumn(ByteBufferUtil.bytes("col1196")).value();
        assertEquals(ByteBufferUtil.string(col), "v1196");
        col = cf.getColumn(ByteBufferUtil.bytes("col1197")).value();
        assertEquals(ByteBufferUtil.string(col), "v1197");


        cf = cfStore.getColumnFamily(key, ByteBufferUtil.bytes("col1996"), ByteBufferUtil.EMPTY_BYTE_BUFFER, true, 1000, System.currentTimeMillis());
        Column[] columns = cf.getSortedColumns().toArray(new Column[0]);
        for (int i = 1000; i < 1996; i++)
        {
            String expectedName = "col" + i;
            Column column = columns[i - 1000];
            assertEquals(ByteBufferUtil.string(column.name()), expectedName);
            assertEquals(ByteBufferUtil.string(column.value()), ("v" + i));
        }

        cf = cfStore.getColumnFamily(key, ByteBufferUtil.bytes("col1990"), ByteBufferUtil.EMPTY_BYTE_BUFFER, false, 3, System.currentTimeMillis());
        assertColumns(cf, "col1990", "col1991", "col1992");
        col = cf.getColumn(ByteBufferUtil.bytes("col1990")).value();
        assertEquals(ByteBufferUtil.string(col), "v1990");
        col = cf.getColumn(ByteBufferUtil.bytes("col1991")).value();
        assertEquals(ByteBufferUtil.string(col), "v1991");
        col = cf.getColumn(ByteBufferUtil.bytes("col1992")).value();
        assertEquals(ByteBufferUtil.string(col), "v1992");

        cf = cfStore.getColumnFamily(key, ByteBufferUtil.EMPTY_BYTE_BUFFER, ByteBufferUtil.EMPTY_BYTE_BUFFER, true, 3, System.currentTimeMillis());
        assertColumns(cf, "col1997", "col1998", "col1999");
        col = cf.getColumn(ByteBufferUtil.bytes("col1997")).value();
        assertEquals(ByteBufferUtil.string(col), "v1997");
        col = cf.getColumn(ByteBufferUtil.bytes("col1998")).value();
        assertEquals(ByteBufferUtil.string(col), "v1998");
        col = cf.getColumn(ByteBufferUtil.bytes("col1999")).value();
        assertEquals(ByteBufferUtil.string(col), "v1999");

        cf = cfStore.getColumnFamily(key, ByteBufferUtil.bytes("col9000"), ByteBufferUtil.EMPTY_BYTE_BUFFER, true, 3, System.currentTimeMillis());
        assertColumns(cf, "col1997", "col1998", "col1999");

        cf = cfStore.getColumnFamily(key, ByteBufferUtil.bytes("col9000"), ByteBufferUtil.EMPTY_BYTE_BUFFER, false, 3, System.currentTimeMillis());
        assertColumns(cf);
    }

    public static void assertColumns(ColumnFamily container, String... columnNames)
    {
        Collection<Column> columns = container == null ? new TreeSet<Column>() : container.getSortedColumns();
        List<String> L = new ArrayList<String>();
        for (Column column : columns)
        {
            try
            {
                L.add(ByteBufferUtil.string(column.name()));
            }
            catch (CharacterCodingException e)
            {
                throw new AssertionError(e);
            }
        }

        List<String> names = new ArrayList<String>(columnNames.length);

        names.addAll(Arrays.asList(columnNames));

        String[] columnNames1 = names.toArray(new String[0]);
        String[] la = L.toArray(new String[columns.size()]);

        assert Arrays.equals(la, columnNames1)
                : String.format("Columns [%s])] is not expected [%s]",
                                ((container == null) ? "" : container.getComparator().getColumnsString(columns)),
                                StringUtils.join(columnNames1, ","));
    }

    public static void assertColumn(ColumnFamily cf, String name, String value, long timestamp)
    {
        assertColumn(cf.getColumn(ByteBufferUtil.bytes(name)), value, timestamp);
    }

    public static void assertColumn(Column column, String value, long timestamp)
    {
        assertNotNull(column);
        assertEquals(0, ByteBufferUtil.compareUnsigned(column.value(), ByteBufferUtil.bytes(value)));
        assertEquals(timestamp, column.timestamp());
    }
}
