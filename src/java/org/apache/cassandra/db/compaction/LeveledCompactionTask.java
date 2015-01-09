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
package org.apache.cassandra.db.compaction;

import java.util.Set;

import org.apache.cassandra.db.ColumnFamilyStore;
import org.apache.cassandra.db.compaction.writers.CompactionAwareWriter;
import org.apache.cassandra.db.compaction.writers.MajorLeveledCompactionWriter;
import org.apache.cassandra.db.compaction.writers.MaxSSTableSizeWriter;
import org.apache.cassandra.io.sstable.format.SSTableReader;

public class LeveledCompactionTask extends CompactionTask
{
    private final int level;
    private final long maxSSTableBytes;
    private final boolean majorCompaction;

    public LeveledCompactionTask(ColumnFamilyStore cfs, Iterable<SSTableReader> sstables, int level, final int gcBefore, long maxSSTableBytes, boolean majorCompaction)
    {
        super(cfs, sstables, gcBefore, false);
        this.level = level;
        this.maxSSTableBytes = maxSSTableBytes;
        this.majorCompaction = majorCompaction;
    }

    @Override
    public CompactionAwareWriter getCompactionAwareWriter(ColumnFamilyStore cfs, Set<SSTableReader> allSSTables, Set<SSTableReader> nonExpiredSSTables)
    {
        if (majorCompaction)
            return new MajorLeveledCompactionWriter(cfs, sstables, nonExpiredSSTables, maxSSTableBytes, false, compactionType);
        return new MaxSSTableSizeWriter(cfs, sstables, nonExpiredSSTables, maxSSTableBytes, getLevel(), false, compactionType);
    }

    @Override
    protected boolean partialCompactionsAcceptable()
    {
        return false;
    }

    protected int getLevel()
    {
        return level;
    }
}
