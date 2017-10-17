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

package org.apache.cassandra.db.commitlog;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Random;

import org.junit.Assert;
import org.junit.Test;
import org.junit.runner.RunWith;

import org.apache.cassandra.SchemaLoader;
import org.apache.cassandra.config.CFMetaData;
import org.apache.cassandra.config.Config;
import org.apache.cassandra.config.DatabaseDescriptor;
import org.apache.cassandra.db.ColumnFamilyStore;
import org.apache.cassandra.db.Keyspace;
import org.apache.cassandra.db.Mutation;
import org.apache.cassandra.db.RowUpdateBuilder;
import org.apache.cassandra.db.compaction.CompactionManager;
import org.apache.cassandra.db.marshal.AsciiType;
import org.apache.cassandra.db.marshal.BytesType;
import org.apache.cassandra.db.rows.SerializationHelper;
import org.apache.cassandra.io.util.DataInputBuffer;
import org.apache.cassandra.io.util.RebufferingInputStream;
import org.apache.cassandra.schema.KeyspaceParams;
import org.jboss.byteman.contrib.bmunit.BMRule;
import org.jboss.byteman.contrib.bmunit.BMUnitRunner;

/**
 * Tests the commitlog to make sure we can replay it - explicitly for the case where we update the chained markers
 * in the commit log segment but do not flush the file to disk.
 */
@RunWith(BMUnitRunner.class)
public class CommitLogChainedMarkersTest
{
    private static final String KEYSPACE1 = "CommitLogTest";
    private static final String STANDARD1 = "CommitLogChainedMarkersTest";

    @Test
    @BMRule(name = "force all calls to sync() to not flush to disk",
    targetClass = "CommitLogSegment",
    targetMethod = "sync(boolean)",
    action = "$flush = false")
    public void replayCommitLogWithoutFlushing() throws IOException
    {
        DatabaseDescriptor.setCommitLogSegmentSize(5);
        DatabaseDescriptor.setCommitLogSync(Config.CommitLogSync.periodic);
        DatabaseDescriptor.setCommitLogSyncPeriod(10000 * 1000);
        DatabaseDescriptor.setCommitLogMarkerPeriod(1);
        SchemaLoader.prepareServer();
        SchemaLoader.createKeyspace(KEYSPACE1,
                                    KeyspaceParams.simple(1),
                                    SchemaLoader.standardCFMD(KEYSPACE1, STANDARD1, 0, AsciiType.instance, BytesType.instance));

        CompactionManager.instance.disableAutoCompaction();

        ColumnFamilyStore cfs1 = Keyspace.open(KEYSPACE1).getColumnFamilyStore(STANDARD1);

        byte[] entropy = new byte[1024];
        new Random().nextBytes(entropy);
        final Mutation m = new RowUpdateBuilder(cfs1.metadata, 0, "k")
                           .clustering("bytes")
                           .add("val", ByteBuffer.wrap(entropy))
                           .build();

        int samples = 10000;
        for (int i = 0; i < samples; i++)
            CommitLog.instance.add(m);

        CommitLog.instance.sync(false, true);

        Replayer replayer = new Replayer(cfs1.metadata);
        File commitLogDir = new File(DatabaseDescriptor.getCommitLogLocation());
        replayer.recover(commitLogDir.listFiles());
        Assert.assertEquals(samples, replayer.count);
    }

    private static class Replayer extends CommitLogReplayer
    {
        private final CFMetaData cfm;
        private int count;

        Replayer(CFMetaData cfm)
        {
            super(CommitLog.instance, ReplayPosition.NONE, null, ReplayFilter.create());
            this.cfm = cfm;
        }

        @Override
        void replayMutation(byte[] inputBuffer, int size, final int entryLocation, final CommitLogDescriptor desc)
        {
            RebufferingInputStream bufIn = new DataInputBuffer(inputBuffer, 0, size);
            try
            {
                Mutation mutation = Mutation.serializer.deserialize(bufIn,
                                                           desc.getMessagingVersion(),
                                                           SerializationHelper.Flag.LOCAL);

                if (cfm == null || mutation.get(cfm) != null)
                    count++;
            }
            catch (IOException e)
            {
                // Test fails.
                throw new AssertionError(e);
            }
        }
    }
}
