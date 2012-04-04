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
package org.apache.cassandra.cache;

import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Arrays;

import org.apache.cassandra.config.Schema;
import org.apache.cassandra.db.DBConstants;
import org.apache.cassandra.db.DecoratedKey;
import org.apache.cassandra.utils.ByteBufferUtil;
import org.apache.cassandra.utils.FBUtilities;
import org.apache.cassandra.utils.Pair;

public class RowCacheKey implements CacheKey, Comparable<RowCacheKey>
{
    public final int cfId;
    public final byte[] key;

    public RowCacheKey(int cfId, DecoratedKey key)
    {
        this(cfId, key.key);
    }

    public RowCacheKey(int cfId, ByteBuffer key)
    {
        this.cfId = cfId;
        this.key = ByteBufferUtil.getArray(key);
        assert this.key != null;
    }

    public void write(DataOutputStream out) throws IOException
    {
        ByteBufferUtil.writeWithLength(key, out);
    }

    public Pair<String, String> getPathInfo()
    {
        return Schema.instance.getCF(cfId);
    }

    public int serializedSize()
    {
        return key.length + DBConstants.intSize;
    }

    @Override
    public boolean equals(Object o)
    {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;

        RowCacheKey that = (RowCacheKey) o;

        if (cfId != that.cfId) return false;
        return Arrays.equals(key, that.key);
    }

    @Override
    public int hashCode()
    {
        int result = cfId;
        result = 31 * result + (key != null ? Arrays.hashCode(key) : 0);
        return result;
    }

    public int compareTo(RowCacheKey otherKey)
    {
        return (cfId < otherKey.cfId) ? -1 : ((cfId == otherKey.cfId) ?  FBUtilities.compareUnsigned(key, otherKey.key, 0, 0, key.length, otherKey.key.length) : 1);
    }

    @Override
    public String toString()
    {
        return String.format("RowCacheKey(cfId:%d, key:%s)", cfId, key);
    }
}
