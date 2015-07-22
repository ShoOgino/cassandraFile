/*
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
 */
package org.apache.cassandra.db;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.ByteBuffer;
import java.util.*;
import java.util.concurrent.ExecutionException;

import java.io.File;
import java.io.IOError;
import java.io.IOException;
import java.io.RandomAccessFile;

import org.apache.cassandra.Util;
import org.apache.cassandra.UpdateBuilder;
import org.apache.cassandra.cql3.QueryProcessor;
import org.apache.cassandra.db.partitions.*;
import org.apache.cassandra.db.marshal.BytesType;
import org.apache.cassandra.db.marshal.UUIDType;
import org.apache.cassandra.db.compaction.OperationType;
import org.apache.cassandra.db.lifecycle.LifecycleTransaction;
import org.apache.cassandra.db.rows.Row;
import org.apache.cassandra.exceptions.ConfigurationException;
import org.apache.cassandra.exceptions.RequestExecutionException;
import org.apache.cassandra.io.compress.CompressionMetadata;
import org.apache.cassandra.io.sstable.format.SSTableFormat;
import org.apache.cassandra.io.sstable.format.SSTableReader;
import org.apache.cassandra.schema.KeyspaceParams;
import org.apache.commons.lang3.StringUtils;
import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.runner.RunWith;

import org.apache.cassandra.config.*;
import org.apache.cassandra.cql3.Operator;
import org.apache.cassandra.cql3.UntypedResultSet;
import org.apache.cassandra.db.compaction.CompactionManager;
import org.apache.cassandra.db.compaction.Scrubber;
import org.apache.cassandra.db.index.SecondaryIndex;
import org.apache.cassandra.db.marshal.*;
import org.apache.cassandra.exceptions.WriteTimeoutException;
import org.apache.cassandra.io.sstable.Component;
import org.apache.cassandra.io.sstable.Descriptor;
import org.apache.cassandra.io.sstable.SSTableRewriter;
import org.apache.cassandra.OrderedJUnit4ClassRunner;
import org.apache.cassandra.SchemaLoader;
import org.apache.cassandra.service.StorageService;
import org.apache.cassandra.utils.ByteBufferUtil;

import static org.junit.Assert.*;
import static org.junit.Assume.assumeTrue;

@RunWith(OrderedJUnit4ClassRunner.class)
public class ScrubTest
{
    public static final String KEYSPACE = "Keyspace1";
    public static final String CF = "Standard1";
    public static final String CF2 = "Standard2";
    public static final String CF3 = "Standard3";
    public static final String COUNTER_CF = "Counter1";
    public static final String CF_UUID = "UUIDKeys";
    public static final String CF_INDEX1 = "Indexed1";
    public static final String CF_INDEX2 = "Indexed2";

    public static final String COL_INDEX = "birthdate";
    public static final String COL_NON_INDEX = "notbirthdate";

    public static final Integer COMPRESSION_CHUNK_LENGTH = 4096;

    @BeforeClass
    public static void defineSchema() throws ConfigurationException
    {
        SchemaLoader.loadSchema();
        SchemaLoader.createKeyspace(KEYSPACE,
                                    KeyspaceParams.simple(1),
                                    SchemaLoader.standardCFMD(KEYSPACE, CF),
                                    SchemaLoader.standardCFMD(KEYSPACE, CF2),
                                    SchemaLoader.standardCFMD(KEYSPACE, CF3),
                                    SchemaLoader.counterCFMD(KEYSPACE, COUNTER_CF)
                                                .compressionParameters(SchemaLoader.getCompressionParameters(COMPRESSION_CHUNK_LENGTH)),
                                    SchemaLoader.standardCFMD(KEYSPACE, CF_UUID, 0, UUIDType.instance),
                                    SchemaLoader.keysIndexCFMD(KEYSPACE, CF_INDEX1, true),
                                    SchemaLoader.compositeIndexCFMD(KEYSPACE, CF_INDEX2, true));
    }

    @Test
    public void testScrubOneRow() throws ExecutionException, InterruptedException
    {
        CompactionManager.instance.disableAutoCompaction();
        Keyspace keyspace = Keyspace.open(KEYSPACE);
        ColumnFamilyStore cfs = keyspace.getColumnFamilyStore(CF);
        cfs.clearUnsafe();

        // insert data and verify we get it back w/ range query
        fillCF(cfs, 1);
        assertOrderedAll(cfs, 1);

        CompactionManager.instance.performScrub(cfs, false, true);

        // check data is still there
        assertOrderedAll(cfs, 1);
    }

    @Test
    public void testScrubCorruptedCounterRow() throws IOException, WriteTimeoutException
    {
        // When compression is enabled, for testing corrupted chunks we need enough partitions to cover
        // at least 3 chunks of size COMPRESSION_CHUNK_LENGTH
        int numPartitions = 1000;

        CompactionManager.instance.disableAutoCompaction();
        Keyspace keyspace = Keyspace.open(KEYSPACE);
        ColumnFamilyStore cfs = keyspace.getColumnFamilyStore(COUNTER_CF);
        cfs.clearUnsafe();

        fillCounterCF(cfs, numPartitions);

        assertOrderedAll(cfs, numPartitions);

        assertEquals(1, cfs.getSSTables().size());

        SSTableReader sstable = cfs.getSSTables().iterator().next();

        //make sure to override at most 1 chunk when compression is enabled
        overrideWithGarbage(sstable, ByteBufferUtil.bytes("0"), ByteBufferUtil.bytes("1"));

        // with skipCorrupted == false, the scrub is expected to fail
        try (LifecycleTransaction txn = cfs.getTracker().tryModify(Arrays.asList(sstable), OperationType.SCRUB);
             Scrubber scrubber = new Scrubber(cfs, txn, false, false, true);)
        {
            scrubber.scrub();
            fail("Expected a CorruptSSTableException to be thrown");
        }
        catch (IOError err) {}

        // with skipCorrupted == true, the corrupt rows will be skipped
        Scrubber.ScrubResult scrubResult;
        try (LifecycleTransaction txn = cfs.getTracker().tryModify(Arrays.asList(sstable), OperationType.SCRUB);
             Scrubber scrubber = new Scrubber(cfs, txn, true, false, true);)
        {
            scrubResult = scrubber.scrubWithResult();
        }

        assertNotNull(scrubResult);

        boolean compression = Boolean.parseBoolean(System.getProperty("cassandra.test.compression", "false"));
        if (compression)
        {
            assertEquals(0, scrubResult.emptyRows);
            assertEquals(numPartitions, scrubResult.badRows + scrubResult.goodRows);
            //because we only corrupted 1 chunk and we chose enough partitions to cover at least 3 chunks
            assertTrue(scrubResult.goodRows >= scrubResult.badRows * 2);
        }
        else
        {
            assertEquals(0, scrubResult.emptyRows);
            assertEquals(1, scrubResult.badRows);
            assertEquals(numPartitions-1, scrubResult.goodRows);
        }
        assertEquals(1, cfs.getSSTables().size());

        assertOrderedAll(cfs, scrubResult.goodRows);
    }

    @Test
    public void testScrubCorruptedRowInSmallFile() throws IOException, WriteTimeoutException
    {
        // cannot test this with compression
        assumeTrue(!Boolean.parseBoolean(System.getProperty("cassandra.test.compression", "false")));

        CompactionManager.instance.disableAutoCompaction();
        Keyspace keyspace = Keyspace.open(KEYSPACE);
        ColumnFamilyStore cfs = keyspace.getColumnFamilyStore(COUNTER_CF);
        cfs.clearUnsafe();

        fillCounterCF(cfs, 2);

        assertOrderedAll(cfs, 2);

        SSTableReader sstable = cfs.getSSTables().iterator().next();

        // overwrite one row with garbage
        overrideWithGarbage(sstable, ByteBufferUtil.bytes("0"), ByteBufferUtil.bytes("1"));

        // with skipCorrupted == false, the scrub is expected to fail
        try (LifecycleTransaction txn = cfs.getTracker().tryModify(Arrays.asList(sstable), OperationType.SCRUB);
             Scrubber scrubber = new Scrubber(cfs, txn, false, false, true))
        {
            // with skipCorrupted == true, the corrupt row will be skipped
            scrubber.scrub();
            fail("Expected a CorruptSSTableException to be thrown");
        }
        catch (IOError err) {}

        try (LifecycleTransaction txn = cfs.getTracker().tryModify(Arrays.asList(sstable), OperationType.SCRUB);
             Scrubber scrubber = new Scrubber(cfs, txn, true, false, true))
        {
            // with skipCorrupted == true, the corrupt row will be skipped
            scrubber.scrub();
            scrubber.close();
        }

        assertEquals(1, cfs.getSSTables().size());
        // verify that we can read all of the rows, and there is now one less row
        assertOrderedAll(cfs, 1);
    }

    @Test
    public void testScrubOneRowWithCorruptedKey() throws IOException, ExecutionException, InterruptedException, ConfigurationException
    {
        // cannot test this with compression
        assumeTrue(!Boolean.parseBoolean(System.getProperty("cassandra.test.compression", "false")));

        CompactionManager.instance.disableAutoCompaction();
        Keyspace keyspace = Keyspace.open(KEYSPACE);
        ColumnFamilyStore cfs = keyspace.getColumnFamilyStore(CF);
        cfs.clearUnsafe();

        // insert data and verify we get it back w/ range query
        fillCF(cfs, 4);
        assertOrderedAll(cfs, 4);

        SSTableReader sstable = cfs.getSSTables().iterator().next();
        overrideWithGarbage(sstable, 0, 2);

        CompactionManager.instance.performScrub(cfs, false, true);

        // check data is still there
        assertOrderedAll(cfs, 4);
    }

    @Test
    public void testScrubCorruptedCounterRowNoEarlyOpen() throws IOException, WriteTimeoutException
    {
        boolean oldDisabledVal = SSTableRewriter.disableEarlyOpeningForTests;
        try
        {
            SSTableRewriter.disableEarlyOpeningForTests = true;
            testScrubCorruptedCounterRow();
        }
        finally
        {
            SSTableRewriter.disableEarlyOpeningForTests = oldDisabledVal;
        }
    }

    @Test
    public void testScrubMultiRow() throws ExecutionException, InterruptedException
    {
        CompactionManager.instance.disableAutoCompaction();
        Keyspace keyspace = Keyspace.open(KEYSPACE);
        ColumnFamilyStore cfs = keyspace.getColumnFamilyStore(CF);
        cfs.clearUnsafe();

        // insert data and verify we get it back w/ range query
        fillCF(cfs, 10);
        assertOrderedAll(cfs, 10);

        CompactionManager.instance.performScrub(cfs, false, true);

        // check data is still there
        assertOrderedAll(cfs, 10);
    }

    @Test
    public void testScrubNoIndex() throws IOException, ExecutionException, InterruptedException, ConfigurationException
    {
        CompactionManager.instance.disableAutoCompaction();
        Keyspace keyspace = Keyspace.open(KEYSPACE);
        ColumnFamilyStore cfs = keyspace.getColumnFamilyStore(CF);
        cfs.clearUnsafe();

        // insert data and verify we get it back w/ range query
        fillCF(cfs, 10);
        assertOrderedAll(cfs, 10);

        for (SSTableReader sstable : cfs.getSSTables())
            new File(sstable.descriptor.filenameFor(Component.PRIMARY_INDEX)).delete();

        CompactionManager.instance.performScrub(cfs, false, true, true);

        // check data is still there
        assertOrderedAll(cfs, 10);
    }

    @Test
    public void testScrubOutOfOrder() throws Exception
    {
        CompactionManager.instance.disableAutoCompaction();
        Keyspace keyspace = Keyspace.open(KEYSPACE);
        String columnFamily = CF3;
        ColumnFamilyStore cfs = keyspace.getColumnFamilyStore(columnFamily);
        cfs.clearUnsafe();

        /*
         * Code used to generate an outOfOrder sstable. The test for out-of-order key in BigTableWriter must also be commented out.
         * The test also assumes an ordered partitioner.
        List<String> keys = Arrays.asList("t", "a", "b", "z", "c", "y", "d");
        String filename = cfs.getTempSSTablePath(new File(System.getProperty("corrupt-sstable-root")));
        SSTableWriter writer = SSTableWriter.create(cfs.metadata,
                                                    Descriptor.fromFilename(filename),
                                                    keys.size(),
                                                    0L,
                                                    0,
                                                    DatabaseDescriptor.getPartitioner(),
                                                    SerializationHeader.make(cfs.metadata, Collections.emptyList()));

        for (String k : keys)
        {
            RowUpdateBuilder builder = new RowUpdateBuilder(cfs.metadata, 0L, Util.dk(k));
            PartitionUpdate update = builder.clustering("someName")
                                            .add("val", "someValue")
                                            .buildUpdate();

            writer.append(update.unfilteredIterator());
        }
        writer.finish(false);
        */

        String root = System.getProperty("corrupt-sstable-root");
        assert root != null;

        File rootDir = new File(root);
        assert rootDir.isDirectory();
        Descriptor desc = new Descriptor("la", rootDir, KEYSPACE, columnFamily, 1, Descriptor.Type.FINAL, SSTableFormat.Type.BIG);
        CFMetaData metadata = Schema.instance.getCFMetaData(desc.ksname, desc.cfname);

        try
        {
            SSTableReader.open(desc, metadata);
            fail("SSTR validation should have caught the out-of-order rows");
        }
        catch (IllegalStateException ise) { /* this is expected */ }

        // open without validation for scrubbing
        Set<Component> components = new HashSet<>();
        //components.add(Component.COMPRESSION_INFO);
        components.add(Component.DATA);
        components.add(Component.PRIMARY_INDEX);
        components.add(Component.FILTER);
        components.add(Component.STATS);
        components.add(Component.SUMMARY);
        components.add(Component.TOC);

        SSTableReader sstable = SSTableReader.openNoValidation(desc, components, cfs);
        if (sstable.last.compareTo(sstable.first) < 0)
            sstable.last = sstable.first;

        try (LifecycleTransaction txn = LifecycleTransaction.offline(OperationType.SCRUB, sstable);
             Scrubber scrubber = new Scrubber(cfs, txn, false, true, true);)
        {
            scrubber.scrub();
        }
        cfs.loadNewSSTables();
        assertOrderedAll(cfs, 7);
        sstable.selfRef().release();
    }

    private void overrideWithGarbage(SSTableReader sstable, ByteBuffer key1, ByteBuffer key2) throws IOException
    {
        boolean compression = Boolean.parseBoolean(System.getProperty("cassandra.test.compression", "false"));
        long startPosition, endPosition;

        if (compression)
        { // overwrite with garbage the compression chunks from key1 to key2
            CompressionMetadata compData = CompressionMetadata.create(sstable.getFilename());

            CompressionMetadata.Chunk chunk1 = compData.chunkFor(
                    sstable.getPosition(PartitionPosition.ForKey.get(key1, sstable.partitioner), SSTableReader.Operator.EQ).position);
            CompressionMetadata.Chunk chunk2 = compData.chunkFor(
                    sstable.getPosition(PartitionPosition.ForKey.get(key2, sstable.partitioner), SSTableReader.Operator.EQ).position);

            startPosition = Math.min(chunk1.offset, chunk2.offset);
            endPosition = Math.max(chunk1.offset + chunk1.length, chunk2.offset + chunk2.length);

            compData.close();
        }
        else
        { // overwrite with garbage from key1 to key2
            long row0Start = sstable.getPosition(PartitionPosition.ForKey.get(key1, sstable.partitioner), SSTableReader.Operator.EQ).position;
            long row1Start = sstable.getPosition(PartitionPosition.ForKey.get(key2, sstable.partitioner), SSTableReader.Operator.EQ).position;
            startPosition = Math.min(row0Start, row1Start);
            endPosition = Math.max(row0Start, row1Start);
        }

        overrideWithGarbage(sstable, startPosition, endPosition);
    }

    private void overrideWithGarbage(SSTableReader sstable, long startPosition, long endPosition) throws IOException
    {
        RandomAccessFile file = new RandomAccessFile(sstable.getFilename(), "rw");
        file.seek(startPosition);
        file.writeBytes(StringUtils.repeat('z', (int) (endPosition - startPosition)));
        file.close();
    }

    private static void assertOrderedAll(ColumnFamilyStore cfs, int expectedSize)
    {
        assertOrdered(Util.cmd(cfs).build(), expectedSize);
    }

    private static void assertOrdered(ReadCommand cmd, int expectedSize)
    {
        int size = 0;
        DecoratedKey prev = null;
        for (Partition partition : Util.getAllUnfiltered(cmd))
        {
            DecoratedKey current = partition.partitionKey();
            assertTrue("key " + current + " does not sort after previous key " + prev, prev == null || prev.compareTo(current) < 0);
            prev = current;
            ++size;
        }
        assertEquals(expectedSize, size);
    }

    protected void fillCF(ColumnFamilyStore cfs, int partitionsPerSSTable)
    {
        for (int i = 0; i < partitionsPerSSTable; i++)
        {
            PartitionUpdate update = UpdateBuilder.create(cfs.metadata, String.valueOf(i))
                                                  .newRow("r1").add("val", "1")
                                                  .newRow("r1").add("val", "1")
                                                  .build();

            new Mutation(update).applyUnsafe();
        }

        cfs.forceBlockingFlush();
    }

    public static void fillIndexCF(ColumnFamilyStore cfs, boolean composite, long ... values)
    {
        assertTrue(values.length % 2 == 0);
        for (int i = 0; i < values.length; i +=2)
        {
            UpdateBuilder builder = UpdateBuilder.create(cfs.metadata, String.valueOf(i));
            if (composite)
            {
                builder.newRow("c" + i)
                       .add(COL_INDEX, values[i])
                       .add(COL_NON_INDEX, values[i + 1]);
            }
            else
            {
                builder.newRow()
                       .add(COL_INDEX, values[i])
                       .add(COL_NON_INDEX, values[i + 1]);
            }
            new Mutation(builder.build()).applyUnsafe();
        }

        cfs.forceBlockingFlush();
    }

    protected void fillCounterCF(ColumnFamilyStore cfs, int partitionsPerSSTable) throws WriteTimeoutException
    {
        for (int i = 0; i < partitionsPerSSTable; i++)
        {
            PartitionUpdate update = UpdateBuilder.create(cfs.metadata, String.valueOf(i))
                                                  .newRow("r1").add("val", 100L)
                                                  .build();
            new CounterMutation(new Mutation(update), ConsistencyLevel.ONE).apply();
        }

        cfs.forceBlockingFlush();
    }

    @Test
    public void testScrubColumnValidation() throws InterruptedException, RequestExecutionException, ExecutionException
    {
        QueryProcessor.process(String.format("CREATE TABLE \"%s\".test_compact_static_columns (a bigint, b timeuuid, c boolean static, d text, PRIMARY KEY (a, b))", KEYSPACE), ConsistencyLevel.ONE);

        Keyspace keyspace = Keyspace.open(KEYSPACE);
        ColumnFamilyStore cfs = keyspace.getColumnFamilyStore("test_compact_static_columns");

        QueryProcessor.executeInternal(String.format("INSERT INTO \"%s\".test_compact_static_columns (a, b, c, d) VALUES (123, c3db07e8-b602-11e3-bc6b-e0b9a54a6d93, true, 'foobar')", KEYSPACE));
        cfs.forceBlockingFlush();
        CompactionManager.instance.performScrub(cfs, false, true);

        QueryProcessor.process("CREATE TABLE \"Keyspace1\".test_scrub_validation (a text primary key, b int)", ConsistencyLevel.ONE);
        ColumnFamilyStore cfs2 = keyspace.getColumnFamilyStore("test_scrub_validation");

        new Mutation(UpdateBuilder.create(cfs2.metadata, "key").newRow().add("b", LongType.instance.decompose(1L)).build()).apply();
        cfs2.forceBlockingFlush();

        CompactionManager.instance.performScrub(cfs2, false, false);
    }

    /**
     * For CASSANDRA-6892 too, check that for a compact table with one cluster column, we can insert whatever
     * we want as value for the clustering column, including something that would conflict with a CQL column definition.
     */
    @Test
    public void testValidationCompactStorage() throws Exception
    {
        QueryProcessor.process(String.format("CREATE TABLE \"%s\".test_compact_dynamic_columns (a int, b text, c text, PRIMARY KEY (a, b)) WITH COMPACT STORAGE", KEYSPACE), ConsistencyLevel.ONE);

        Keyspace keyspace = Keyspace.open(KEYSPACE);
        ColumnFamilyStore cfs = keyspace.getColumnFamilyStore("test_compact_dynamic_columns");

        QueryProcessor.executeInternal(String.format("INSERT INTO \"%s\".test_compact_dynamic_columns (a, b, c) VALUES (0, 'a', 'foo')", KEYSPACE));
        QueryProcessor.executeInternal(String.format("INSERT INTO \"%s\".test_compact_dynamic_columns (a, b, c) VALUES (0, 'b', 'bar')", KEYSPACE));
        QueryProcessor.executeInternal(String.format("INSERT INTO \"%s\".test_compact_dynamic_columns (a, b, c) VALUES (0, 'c', 'boo')", KEYSPACE));
        cfs.forceBlockingFlush();
        CompactionManager.instance.performScrub(cfs, true, true);

        // Scrub is silent, but it will remove broken records. So reading everything back to make sure nothing to "scrubbed away"
        UntypedResultSet rs = QueryProcessor.executeInternal(String.format("SELECT * FROM \"%s\".test_compact_dynamic_columns", KEYSPACE));
        assertEquals(3, rs.size());

        Iterator<UntypedResultSet.Row> iter = rs.iterator();
        assertEquals("foo", iter.next().getString("c"));
        assertEquals("bar", iter.next().getString("c"));
        assertEquals("boo", iter.next().getString("c"));
    }

    @Test /* CASSANDRA-5174 */
    public void testScrubKeysIndex_preserveOrder() throws IOException, ExecutionException, InterruptedException
    {
        //If the partitioner preserves the order then SecondaryIndex uses BytesType comparator,
        // otherwise it uses LocalByPartitionerType
        setKeyComparator(BytesType.instance);
        testScrubIndex(CF_INDEX1, COL_INDEX, false, true);
    }

    @Test /* CASSANDRA-5174 */
    public void testScrubCompositeIndex_preserveOrder() throws IOException, ExecutionException, InterruptedException
    {
        setKeyComparator(BytesType.instance);
        testScrubIndex(CF_INDEX2, COL_INDEX, true, true);
    }

    @Test /* CASSANDRA-5174 */
    public void testScrubKeysIndex() throws IOException, ExecutionException, InterruptedException
    {
        setKeyComparator(new LocalByPartionerType(StorageService.getPartitioner()));
        testScrubIndex(CF_INDEX1, COL_INDEX, false, true);
    }

    @Test /* CASSANDRA-5174 */
    public void testScrubCompositeIndex() throws IOException, ExecutionException, InterruptedException
    {
        setKeyComparator(new LocalByPartionerType(StorageService.getPartitioner()));
        testScrubIndex(CF_INDEX2, COL_INDEX, true, true);
    }

    @Test /* CASSANDRA-5174 */
    public void testFailScrubKeysIndex() throws IOException, ExecutionException, InterruptedException
    {
        testScrubIndex(CF_INDEX1, COL_INDEX, false, false);
    }

    @Test /* CASSANDRA-5174 */
    public void testFailScrubCompositeIndex() throws IOException, ExecutionException, InterruptedException
    {
        testScrubIndex(CF_INDEX2, COL_INDEX, true, false);
    }

    @Test /* CASSANDRA-5174 */
    public void testScrubTwice() throws IOException, ExecutionException, InterruptedException
    {
        testScrubIndex(CF_INDEX1, COL_INDEX, false, true, true);
    }

    /** The SecondaryIndex class is used for custom indexes so to avoid
     * making a public final field into a private field with getters
     * and setters, we resort to this hack in order to test it properly
     * since it can have two values which influence the scrubbing behavior.
     * @param comparator - the key comparator we want to test
     */
    private void setKeyComparator(AbstractType<?> comparator)
    {
        try
        {
            Field keyComparator = SecondaryIndex.class.getDeclaredField("keyComparator");
            keyComparator.setAccessible(true);
            int modifiers = keyComparator.getModifiers();
            Field modifierField = keyComparator.getClass().getDeclaredField("modifiers");
            modifiers = modifiers & ~Modifier.FINAL;
            modifierField.setAccessible(true);
            modifierField.setInt(keyComparator, modifiers);

            keyComparator.set(null, comparator);
        }
        catch (Exception ex)
        {
            fail("Failed to change key comparator in secondary index : " + ex.getMessage());
            ex.printStackTrace();
        }
    }

    private void testScrubIndex(String cfName, String colName, boolean composite, boolean ... scrubs)
            throws IOException, ExecutionException, InterruptedException
    {
        CompactionManager.instance.disableAutoCompaction();
        Keyspace keyspace = Keyspace.open(KEYSPACE);
        ColumnFamilyStore cfs = keyspace.getColumnFamilyStore(cfName);
        cfs.clearUnsafe();

        int numRows = 1000;
        long[] colValues = new long [numRows * 2]; // each row has two columns
        for (int i = 0; i < colValues.length; i+=2)
        {
            colValues[i] = (i % 4 == 0 ? 1L : 2L); // index column
            colValues[i+1] = 3L; //other column
        }
        fillIndexCF(cfs, composite, colValues);

        // check index

        assertOrdered(Util.cmd(cfs).filterOn(colName, Operator.EQ, 1L).build(), numRows / 2);

        // scrub index
        Set<ColumnFamilyStore> indexCfss = cfs.indexManager.getIndexesBackedByCfs();
        assertTrue(indexCfss.size() == 1);
        for(ColumnFamilyStore indexCfs : indexCfss)
        {
            for (int i = 0; i < scrubs.length; i++)
            {
                boolean failure = !scrubs[i];
                if (failure)
                { //make sure the next scrub fails
                    overrideWithGarbage(indexCfs.getSSTables().iterator().next(), ByteBufferUtil.bytes(1L), ByteBufferUtil.bytes(2L));
                }
                CompactionManager.AllSSTableOpStatus result = indexCfs.scrub(false, false, true, true);
                assertEquals(failure ?
                             CompactionManager.AllSSTableOpStatus.ABORTED :
                             CompactionManager.AllSSTableOpStatus.SUCCESSFUL,
                                result);
            }
        }


        // check index is still working
        assertOrdered(Util.cmd(cfs).filterOn(colName, Operator.EQ, 1L).build(), numRows / 2);
    }
}
