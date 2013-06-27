package org.apache.cassandra.db;
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
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutionException;

import org.apache.cassandra.cache.KeyCacheKey;
import org.apache.cassandra.db.filter.QueryFilter;
import org.apache.cassandra.service.CacheService;
import org.apache.cassandra.thrift.ColumnParent;

import org.junit.AfterClass;
import org.junit.Test;

import org.apache.cassandra.SchemaLoader;
import org.apache.cassandra.Util;
import org.apache.cassandra.db.compaction.CompactionManager;
import org.apache.cassandra.utils.ByteBufferUtil;
import static junit.framework.Assert.assertEquals;

public class KeyCacheTest extends SchemaLoader
{
    private static final String KEYSPACE1 = "KeyCacheSpace";
    private static final String COLUMN_FAMILY1 = "Standard1";
    private static final String COLUMN_FAMILY2 = "Standard2";

    @AfterClass
    public static void cleanup()
    {
        cleanupSavedCaches();
    }

    @Test
    public void testKeyCacheLoad() throws Exception
    {
        CompactionManager.instance.disableAutoCompaction();

        ColumnFamilyStore store = Keyspace.open(KEYSPACE1).getColumnFamilyStore(COLUMN_FAMILY2);

        // empty the cache
        CacheService.instance.invalidateKeyCache();
        assert CacheService.instance.keyCache.size() == 0;

        // insert data and force to disk
        insertData(KEYSPACE1, COLUMN_FAMILY2, 0, 100);
        store.forceBlockingFlush();

        // populate the cache
        readData(KEYSPACE1, COLUMN_FAMILY2, 0, 100);
        assertEquals(100, CacheService.instance.keyCache.size());

        // really? our caches don't implement the map interface? (hence no .addAll)
        Map<KeyCacheKey, RowIndexEntry> savedMap = new HashMap<KeyCacheKey, RowIndexEntry>();
        for (KeyCacheKey k : CacheService.instance.keyCache.getKeySet())
        {
            savedMap.put(k, CacheService.instance.keyCache.get(k));
        }

        // force the cache to disk
        CacheService.instance.keyCache.submitWrite(Integer.MAX_VALUE).get();

        CacheService.instance.invalidateKeyCache();
        assert CacheService.instance.keyCache.size() == 0;
    }

    @Test
    public void testKeyCache() throws IOException, ExecutionException, InterruptedException
    {
        CompactionManager.instance.disableAutoCompaction();

        Keyspace keyspace = Keyspace.open(KEYSPACE1);
        ColumnFamilyStore cfs = keyspace.getColumnFamilyStore(COLUMN_FAMILY1);

        // just to make sure that everything is clean
        CacheService.instance.invalidateKeyCache();

        // KeyCache should start at size 0 if we're caching X% of zero data.
        assertEquals(0, CacheService.instance.keyCache.size());

        DecoratedKey key1 = Util.dk("key1");
        DecoratedKey key2 = Util.dk("key2");
        RowMutation rm;

        // inserts
        rm = new RowMutation(KEYSPACE1, key1.key);
        rm.add(COLUMN_FAMILY1, ByteBufferUtil.bytes("1"), ByteBufferUtil.EMPTY_BYTE_BUFFER, 0);
        rm.apply();
        rm = new RowMutation(KEYSPACE1, key2.key);
        rm.add(COLUMN_FAMILY1, ByteBufferUtil.bytes("2"), ByteBufferUtil.EMPTY_BYTE_BUFFER, 0);
        rm.apply();

        // to make sure we have SSTable
        cfs.forceBlockingFlush();

        // reads to cache key position
        cfs.getColumnFamily(QueryFilter.getSliceFilter(key1,
                                                       COLUMN_FAMILY1,
                                                       ByteBufferUtil.EMPTY_BYTE_BUFFER,
                                                       ByteBufferUtil.EMPTY_BYTE_BUFFER,
                                                       false,
                                                       10,
                                                       System.currentTimeMillis()));

        cfs.getColumnFamily(QueryFilter.getSliceFilter(key2,
                                                       COLUMN_FAMILY1,
                                                       ByteBufferUtil.EMPTY_BYTE_BUFFER,
                                                       ByteBufferUtil.EMPTY_BYTE_BUFFER,
                                                       false,
                                                       10,
                                                       System.currentTimeMillis()));

        assertEquals(2, CacheService.instance.keyCache.size());

        Util.compactAll(cfs).get();
        // after compaction cache should have entries for
        // new SSTables, if we had 2 keys in cache previously it should become 4
        assertEquals(4, CacheService.instance.keyCache.size());

        // re-read same keys to verify that key cache didn't grow further
        cfs.getColumnFamily(QueryFilter.getSliceFilter(key1,
                                                       COLUMN_FAMILY1,
                                                       ByteBufferUtil.EMPTY_BYTE_BUFFER,
                                                       ByteBufferUtil.EMPTY_BYTE_BUFFER,
                                                       false,
                                                       10,
                                                       System.currentTimeMillis()));

        cfs.getColumnFamily(QueryFilter.getSliceFilter(key2,
                                                       COLUMN_FAMILY1,
                                                       ByteBufferUtil.EMPTY_BYTE_BUFFER,
                                                       ByteBufferUtil.EMPTY_BYTE_BUFFER,
                                                       false,
                                                       10,
                                                       System.currentTimeMillis()));

        assert CacheService.instance.keyCache.size() == 4;
    }
}
