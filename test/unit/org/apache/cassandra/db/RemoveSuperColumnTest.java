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
import java.util.concurrent.ExecutionException;
import java.util.Collection;

import org.junit.Test;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertEquals;

import org.apache.cassandra.Util;
import org.apache.cassandra.db.filter.QueryFilter;
import org.apache.cassandra.db.filter.QueryPath;
import static org.apache.cassandra.Util.addMutation;
import static org.apache.cassandra.Util.getBytes;

import org.apache.cassandra.CleanupHelper;
import static junit.framework.Assert.assertNotNull;

public class RemoveSuperColumnTest extends CleanupHelper
{
    @Test
    public void testRemoveSuperColumn() throws IOException, ExecutionException, InterruptedException
    {
        ColumnFamilyStore store = Table.open("Keyspace1").getColumnFamilyStore("Super1");
        RowMutation rm;
        DecoratedKey dk = Util.dk("key1");

        // add data
        rm = new RowMutation("Keyspace1", dk.key);
        addMutation(rm, "Super1", "SC1", 1, "val1", new TimestampClock(0));
        rm.apply();
        store.forceBlockingFlush();

        // remove
        rm = new RowMutation("Keyspace1", dk.key);
        rm.delete(new QueryPath("Super1", "SC1".getBytes()), new TimestampClock(1));
        rm.apply();

        validateRemoveTwoSources(dk);

        store.forceBlockingFlush();
        validateRemoveTwoSources(dk);

        CompactionManager.instance.submitMajor(store).get();
        assertEquals(1, store.getSSTables().size());
        validateRemoveCompacted(dk);
    }

    @Test
    public void testRemoveDeletedSubColumn() throws IOException, ExecutionException, InterruptedException
    {
        ColumnFamilyStore store = Table.open("Keyspace1").getColumnFamilyStore("Super3");
        RowMutation rm;
        DecoratedKey dk = Util.dk("key1");

        // add data
        rm = new RowMutation("Keyspace1", dk.key);
        addMutation(rm, "Super3", "SC1", 1, "val1", new TimestampClock(0));
        addMutation(rm, "Super3", "SC1", 2, "val1", new TimestampClock(0));
        rm.apply();
        store.forceBlockingFlush();

        // remove
        rm = new RowMutation("Keyspace1", dk.key);
        rm.delete(new QueryPath("Super3", "SC1".getBytes(), Util.getBytes(1)), new TimestampClock(1));
        rm.apply();

        validateRemoveSubColumn(dk);

        store.forceBlockingFlush();
        validateRemoveSubColumn(dk);
    }

    private void validateRemoveSubColumn(DecoratedKey dk) throws IOException
    {
        ColumnFamilyStore store = Table.open("Keyspace1").getColumnFamilyStore("Super3");
        assertNull(store.getColumnFamily(QueryFilter.getNamesFilter(dk, new QueryPath("Super3", "SC1".getBytes()), Util.getBytes(1)), Integer.MAX_VALUE));
        assertNotNull(store.getColumnFamily(QueryFilter.getNamesFilter(dk, new QueryPath("Super3", "SC1".getBytes()), Util.getBytes(2)), Integer.MAX_VALUE));
    }

    private void validateRemoveTwoSources(DecoratedKey dk) throws IOException
    {
        ColumnFamilyStore store = Table.open("Keyspace1").getColumnFamilyStore("Super1");
        ColumnFamily resolved = store.getColumnFamily(QueryFilter.getNamesFilter(dk, new QueryPath("Super1"), "SC1".getBytes()));
        assert ((TimestampClock)resolved.getSortedColumns().iterator().next().getMarkedForDeleteAt()).timestamp() == 1 : resolved;
        assert resolved.getSortedColumns().iterator().next().getSubColumns().size() == 0 : resolved;
        assertNull(ColumnFamilyStore.removeDeleted(resolved, Integer.MAX_VALUE));
        assertNull(store.getColumnFamily(QueryFilter.getNamesFilter(dk, new QueryPath("Super1"), "SC1".getBytes()), Integer.MAX_VALUE));
        assertNull(store.getColumnFamily(QueryFilter.getIdentityFilter(dk, new QueryPath("Super1")), Integer.MAX_VALUE));
        assertNull(ColumnFamilyStore.removeDeleted(store.getColumnFamily(QueryFilter.getIdentityFilter(dk, new QueryPath("Super1"))), Integer.MAX_VALUE));
    }

    private void validateRemoveCompacted(DecoratedKey dk) throws IOException
    {
        ColumnFamilyStore store = Table.open("Keyspace1").getColumnFamilyStore("Super1");
        ColumnFamily resolved = store.getColumnFamily(QueryFilter.getNamesFilter(dk, new QueryPath("Super1"), "SC1".getBytes()));
        assert ((TimestampClock)resolved.getSortedColumns().iterator().next().getMarkedForDeleteAt()).timestamp() == 1;
        Collection<IColumn> subColumns = resolved.getSortedColumns().iterator().next().getSubColumns();
        assert subColumns.size() == 0;
    }

    @Test
    public void testRemoveSuperColumnWithNewData() throws IOException, ExecutionException, InterruptedException
    {
        ColumnFamilyStore store = Table.open("Keyspace1").getColumnFamilyStore("Super2");
        RowMutation rm;
        DecoratedKey dk = Util.dk("key1");

        // add data
        rm = new RowMutation("Keyspace1", dk.key);
        addMutation(rm, "Super2", "SC1", 1, "val1", new TimestampClock(0));
        rm.apply();
        store.forceBlockingFlush();

        // remove
        rm = new RowMutation("Keyspace1", dk.key);
        rm.delete(new QueryPath("Super2", "SC1".getBytes()), new TimestampClock(1));
        rm.apply();

        // new data
        rm = new RowMutation("Keyspace1", dk.key);
        addMutation(rm, "Super2", "SC1", 2, "val2", new TimestampClock(2));
        rm.apply();

        validateRemoveWithNewData(dk);

        store.forceBlockingFlush();
        validateRemoveWithNewData(dk);

        CompactionManager.instance.submitMajor(store).get();
        assertEquals(1, store.getSSTables().size());
        validateRemoveWithNewData(dk);
    }

    private void validateRemoveWithNewData(DecoratedKey dk) throws IOException
    {
        ColumnFamilyStore store = Table.open("Keyspace1").getColumnFamilyStore("Super2");
        ColumnFamily resolved = store.getColumnFamily(QueryFilter.getNamesFilter(dk, new QueryPath("Super2", "SC1".getBytes()), getBytes(2)), Integer.MAX_VALUE);
        Collection<IColumn> subColumns = resolved.getSortedColumns().iterator().next().getSubColumns();
        assert subColumns.size() == 1;
        assert ((TimestampClock)subColumns.iterator().next().clock()).timestamp() == 2;
    }

    @Test
    public void testRemoveSuperColumnResurrection() throws IOException, ExecutionException, InterruptedException
    {
        ColumnFamilyStore store = Table.open("Keyspace1").getColumnFamilyStore("Super2");
        RowMutation rm;
        DecoratedKey key = Util.dk("keyC");

        // add data
        rm = new RowMutation("Keyspace1", key.key);
        addMutation(rm, "Super2", "SC1", 1, "val1", new TimestampClock(0));
        rm.apply();

        // remove
        rm = new RowMutation("Keyspace1", key.key);
        rm.delete(new QueryPath("Super2", "SC1".getBytes()), new TimestampClock(1));
        rm.apply();
        assertNull(store.getColumnFamily(QueryFilter.getNamesFilter(key, new QueryPath("Super2"), "SC1".getBytes()), Integer.MAX_VALUE));

        // resurrect
        rm = new RowMutation("Keyspace1", key.key);
        addMutation(rm, "Super2", "SC1", 1, "val2", new TimestampClock(2));
        rm.apply();

        // validate
        ColumnFamily resolved = store.getColumnFamily(QueryFilter.getNamesFilter(key, new QueryPath("Super2"), "SC1".getBytes()), Integer.MAX_VALUE);
        Collection<IColumn> subColumns = resolved.getSortedColumns().iterator().next().getSubColumns();
        assert subColumns.size() == 1;
        assert ((TimestampClock)subColumns.iterator().next().clock()).timestamp() == 2;
    }
}
