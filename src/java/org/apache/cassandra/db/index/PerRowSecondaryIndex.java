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
package org.apache.cassandra.db.index;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;

import org.apache.cassandra.db.rows.UnfilteredRowIterator;
import org.apache.cassandra.db.partitions.PartitionUpdate;
import org.apache.cassandra.utils.concurrent.OpOrder;
import org.apache.cassandra.utils.ByteBufferUtil;

/**
 *  Base class for Secondary indexes that implement a unique index per row
 */
public abstract class PerRowSecondaryIndex extends SecondaryIndex
{
    /**
     * Index the given partition.
     */
    public abstract void index(ByteBuffer key, UnfilteredRowIterator atoms);

    /**
     * cleans up deleted columns from cassandra cleanup compaction
     *
     * @param key
     */
    public abstract void delete(ByteBuffer key, OpOrder.Group opGroup);

    public String getNameForSystemKeyspace(ByteBuffer columnName)
    {
        try
        {
            return getIndexName()+ByteBufferUtil.string(columnName);
        }
        catch (CharacterCodingException e)
        {
            throw new RuntimeException(e);
        }
    }
}
