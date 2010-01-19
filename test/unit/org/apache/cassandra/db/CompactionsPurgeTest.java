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

import java.io.IOException;
import java.net.InetAddress;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;

import org.junit.Test;

import org.apache.cassandra.CleanupHelper;
import org.apache.cassandra.db.filter.IdentityQueryFilter;
import org.apache.cassandra.db.filter.QueryPath;
import org.apache.cassandra.io.SSTableReader;
import org.apache.cassandra.utils.FBUtilities;

import static junit.framework.Assert.assertEquals;

public class CompactionsPurgeTest extends CleanupHelper
{
    public static final String TABLE1 = "Keyspace1";

    @Test
    public void testCompactionPurge() throws IOException, ExecutionException, InterruptedException
    {
        CompactionManager.instance.disableAutoCompaction();

        Table table = Table.open(TABLE1);
        String cfName = "Standard1";
        ColumnFamilyStore store = table.getColumnFamilyStore(cfName);

        String key = "key1";
        RowMutation rm;

        // inserts
        rm = new RowMutation(TABLE1, key);
        for (int i = 0; i < 10; i++)
        {
            rm.add(new QueryPath(cfName, null, String.valueOf(i).getBytes()), new byte[0], 0);
        }
        rm.apply();
        store.forceBlockingFlush();

        // deletes
        for (int i = 0; i < 10; i++)
        {
            rm = new RowMutation(TABLE1, key);
            rm.delete(new QueryPath(cfName, null, String.valueOf(i).getBytes()), 1);
            rm.apply();
        }
        store.forceBlockingFlush();

        // resurrect one column
        rm = new RowMutation(TABLE1, key);
        rm.add(new QueryPath(cfName, null, String.valueOf(5).getBytes()), new byte[0], 2);
        rm.apply();
        store.forceBlockingFlush();

        // verify that non-major compaction does no GC to ensure correctness (see CASSANDRA-604)
        Collection<SSTableReader> sstablesIncomplete = store.getSSTables();
        rm = new RowMutation(TABLE1, key + "x");
        rm.add(new QueryPath(cfName, null, "0".getBytes()), new byte[0], 0);
        rm.apply();
        store.forceBlockingFlush();
        CompactionManager.instance.doCompaction(store, sstablesIncomplete, CompactionManager.getDefaultGCBefore());
        ColumnFamily cf = table.getColumnFamilyStore(cfName).getColumnFamily(new IdentityQueryFilter(key, new QueryPath(cfName)));
        assert cf.getColumnCount() == 10;

        // major compact and test that all columns but the resurrected one is completely gone
        CompactionManager.instance.submitMajor(store, 0, Integer.MAX_VALUE).get();
        cf = table.getColumnFamilyStore(cfName).getColumnFamily(new IdentityQueryFilter(key, new QueryPath(cfName)));
        assert cf.getColumnCount() == 1;
        assert cf.getColumn(String.valueOf(5).getBytes()) != null;
    }

    @Test
    public void testCompactionPurgeOneFile() throws IOException, ExecutionException, InterruptedException
    {
        CompactionManager.instance.disableAutoCompaction();

        Table table = Table.open(TABLE1);
        String cfName = "Standard2";
        ColumnFamilyStore store = table.getColumnFamilyStore(cfName);

        String key = "key1";
        RowMutation rm;

        // inserts
        rm = new RowMutation(TABLE1, key);
        for (int i = 0; i < 5; i++)
        {
            rm.add(new QueryPath(cfName, null, String.valueOf(i).getBytes()), new byte[0], 0);
        }
        rm.apply();

        // deletes
        for (int i = 0; i < 5; i++)
        {
            rm = new RowMutation(TABLE1, key);
            rm.delete(new QueryPath(cfName, null, String.valueOf(i).getBytes()), 1);
            rm.apply();
        }
        store.forceBlockingFlush();

        assert store.getSSTables().size() == 1 : store.getSSTables(); // inserts & deletes were in the same memtable -> only deletes in sstable

        // compact and test that the row is completely gone
        CompactionManager.instance.submitMajor(store, 0, Integer.MAX_VALUE).get();
        assert store.getSSTables().isEmpty();
        ColumnFamily cf = table.getColumnFamilyStore(cfName).getColumnFamily(new IdentityQueryFilter(key, new QueryPath(cfName)));
        assert cf == null : cf;
    }
}