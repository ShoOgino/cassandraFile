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

package org.apache.cassandra.db.lifecycle;

import java.io.File;
import java.io.IOException;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import org.apache.commons.lang3.StringUtils;
import org.junit.BeforeClass;
import org.junit.Test;

import junit.framework.Assert;
import org.apache.cassandra.MockSchema;
import org.apache.cassandra.SchemaLoader;
import org.apache.cassandra.config.CFMetaData;
import org.apache.cassandra.config.DatabaseDescriptor;
import org.apache.cassandra.config.Schema;
import org.apache.cassandra.db.ColumnFamilyStore;
import org.apache.cassandra.db.Directories;
import org.apache.cassandra.db.Keyspace;
import org.apache.cassandra.db.SerializationHeader;
import org.apache.cassandra.db.compaction.AbstractCompactionStrategy;
import org.apache.cassandra.db.compaction.CompactionController;
import org.apache.cassandra.db.compaction.CompactionIterator;
import org.apache.cassandra.db.compaction.OperationType;
import org.apache.cassandra.io.sstable.CQLSSTableWriter;
import org.apache.cassandra.io.sstable.Descriptor;
import org.apache.cassandra.io.sstable.SSTableRewriter;
import org.apache.cassandra.io.sstable.format.SSTableReader;
import org.apache.cassandra.io.sstable.format.SSTableWriter;
import org.apache.cassandra.schema.KeyspaceParams;
import org.apache.cassandra.service.StorageService;
import org.apache.cassandra.utils.FBUtilities;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * Tests to simulate real transactions such as compactions and flushing
 * using SSTableRewriter, ColumnFamilyStore, LifecycleTransaction, TransactionLogs, etc
 */
public class RealTransactionsTest extends SchemaLoader
{
    private static final String KEYSPACE = "TransactionLogsTest";
    private static final String REWRITE_FINISHED_CF = "RewriteFinished";
    private static final String REWRITE_ABORTED_CF = "RewriteAborted";
    private static final String FLUSH_CF = "Flush";

    @BeforeClass
    public static void setUp()
    {
        MockSchema.cleanup();

        SchemaLoader.prepareServer();
        SchemaLoader.createKeyspace(KEYSPACE,
                                    KeyspaceParams.simple(1),
                                    SchemaLoader.standardCFMD(KEYSPACE, REWRITE_FINISHED_CF),
                                    SchemaLoader.standardCFMD(KEYSPACE, REWRITE_ABORTED_CF),
                                    SchemaLoader.standardCFMD(KEYSPACE, FLUSH_CF));
    }

    @Test
    public void testRewriteFinished() throws IOException
    {
        Keyspace keyspace = Keyspace.open(KEYSPACE);
        ColumnFamilyStore cfs = keyspace.getColumnFamilyStore(REWRITE_FINISHED_CF);

        SSTableReader oldSSTable = getSSTable(cfs, 1);
        LifecycleTransaction txn = cfs.getTracker().tryModify(oldSSTable, OperationType.COMPACTION);
        SSTableReader newSSTable = replaceSSTable(cfs, txn, false);
        TransactionLogs.waitForDeletions();

        assertFiles(txn.logs().getDataFolder(), new HashSet<>(newSSTable.getAllFilePaths()));
        assertFiles(txn.logs().getLogsFolder(), Collections.<String>emptySet());
    }

    @Test
    public void testRewriteAborted() throws IOException
    {
        Keyspace keyspace = Keyspace.open(KEYSPACE);
        ColumnFamilyStore cfs = keyspace.getColumnFamilyStore(REWRITE_ABORTED_CF);

        SSTableReader oldSSTable = getSSTable(cfs, 1);
        LifecycleTransaction txn = cfs.getTracker().tryModify(oldSSTable, OperationType.COMPACTION);

        replaceSSTable(cfs, txn, true);
        TransactionLogs.waitForDeletions();

        assertFiles(txn.logs().getDataFolder(), new HashSet<>(oldSSTable.getAllFilePaths()));
        assertFiles(txn.logs().getLogsFolder(), Collections.<String>emptySet());
    }

    @Test
    public void testFlush() throws IOException
    {
        Keyspace keyspace = Keyspace.open(KEYSPACE);
        ColumnFamilyStore cfs = keyspace.getColumnFamilyStore(FLUSH_CF);

        SSTableReader ssTableReader = getSSTable(cfs, 100);

        String dataFolder = cfs.getSSTables().iterator().next().descriptor.directory.getPath();
        String transactionLogsFolder = StringUtils.join(dataFolder, File.separator, Directories.TRANSACTIONS_SUBDIR);

        assertTrue(new File(transactionLogsFolder).exists());
        assertFiles(transactionLogsFolder, Collections.<String>emptySet());

        assertFiles(dataFolder, new HashSet<>(ssTableReader.getAllFilePaths()));
    }

    private SSTableReader getSSTable(ColumnFamilyStore cfs, int numPartitions) throws IOException
    {
        createSSTable(cfs, numPartitions);

        Set<SSTableReader> sstables = new HashSet<>(cfs.getSSTables());
        assertEquals(1, sstables.size());
        return sstables.iterator().next();
    }

    private void createSSTable(ColumnFamilyStore cfs, int numPartitions) throws IOException
    {
        cfs.truncateBlocking();

        String schema = "CREATE TABLE %s.%s (key ascii, name ascii, val ascii, val1 ascii, PRIMARY KEY (key, name))";
        String query = "INSERT INTO %s.%s (key, name, val) VALUES (?, ?, ?)";

        try (CQLSSTableWriter writer = CQLSSTableWriter.builder()
                                                       .withPartitioner(StorageService.getPartitioner())
                                                       .inDirectory(cfs.directories.getDirectoryForNewSSTables())
                                                       .forTable(String.format(schema, cfs.keyspace.getName(), cfs.name))
                                                       .using(String.format(query, cfs.keyspace.getName(), cfs.name))
                                                       .build())
        {
            for (int j = 0; j < numPartitions; j ++)
                writer.addRow(String.format("key%d", j), "col1", "0");
        }

        cfs.loadNewSSTables();
    }

    private SSTableReader replaceSSTable(ColumnFamilyStore cfs, LifecycleTransaction txn, boolean fail)
    {
        List<SSTableReader> newsstables = null;
        int nowInSec = FBUtilities.nowInSeconds();
        try (CompactionController controller = new CompactionController(cfs, txn.originals(), cfs.gcBefore(FBUtilities.nowInSeconds())))
        {
            try (SSTableRewriter rewriter = new SSTableRewriter(cfs, txn, 1000, false);
                 AbstractCompactionStrategy.ScannerList scanners = cfs.getCompactionStrategyManager().getScanners(txn.originals());
                 CompactionIterator ci = new CompactionIterator(txn.opType(), scanners.scanners, controller, nowInSec, txn.opId())
            )
            {
                long lastCheckObsoletion = System.nanoTime();
                File directory = txn.originals().iterator().next().descriptor.directory;
                Descriptor desc = Descriptor.fromFilename(cfs.getSSTablePath(directory));
                CFMetaData metadata = Schema.instance.getCFMetaData(desc);
                rewriter.switchWriter(SSTableWriter.create(metadata,
                                                           desc,
                                                           0,
                                                           0,
                                                           0,
                                                           DatabaseDescriptor.getPartitioner(),
                                                           SerializationHeader.make(cfs.metadata, txn.originals()),
                                                           txn));
                while (ci.hasNext())
                {
                    rewriter.append(ci.next());

                    if (System.nanoTime() - lastCheckObsoletion > TimeUnit.MINUTES.toNanos(1L))
                    {
                        controller.maybeRefreshOverlaps();
                        lastCheckObsoletion = System.nanoTime();
                    }
                }

                if (!fail)
                    newsstables = rewriter.finish();
                else
                    rewriter.abort();
            }
        }

        assertTrue(fail || newsstables != null);

        if (newsstables != null)
        {
            Assert.assertEquals(1, newsstables.size());
            return newsstables.iterator().next();
        }

        return null;
    }

    private void assertFiles(String dirPath, Set<String> expectedFiles)
    {
        File dir = new File(dirPath);
        for (File file : dir.listFiles())
        {
            if (file.isDirectory())
                continue;

            String filePath = file.getPath();
            assertTrue(filePath, expectedFiles.contains(filePath));
            expectedFiles.remove(filePath);
        }

        assertTrue(expectedFiles.isEmpty());
    }
}
