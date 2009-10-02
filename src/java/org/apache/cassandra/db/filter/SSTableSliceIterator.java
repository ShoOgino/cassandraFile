package org.apache.cassandra.db.filter;
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


import java.util.*;
import java.io.IOException;

import org.apache.cassandra.db.DecoratedKey;
import org.apache.cassandra.db.IColumn;
import org.apache.cassandra.db.ColumnFamily;
import org.apache.cassandra.db.marshal.AbstractType;
import org.apache.cassandra.io.*;
import org.apache.cassandra.config.DatabaseDescriptor;
import com.google.common.collect.AbstractIterator;

/**
 *  A Column Iterator over SSTable
 */
class SSTableSliceIterator extends AbstractIterator<IColumn> implements ColumnIterator
{
    private final boolean reversed;
    private final byte[] startColumn;
    private final AbstractType comparator;
    private ColumnGroupReader reader;

    public SSTableSliceIterator(SSTableReader ssTable, String key, byte[] startColumn, boolean reversed)
    throws IOException
    {
        // TODO push finishColumn down here too, so we can tell when we're done and optimize away the slice when the index + start/stop shows there's nothing to scan for
        this.reversed = reversed;

        /* Morph key into actual key based on the partition type. */
        DecoratedKey decoratedKey = ssTable.getPartitioner().decorateKey(key);
        long position = ssTable.getPosition(decoratedKey);
        this.comparator = ssTable.getColumnComparator();
        this.startColumn = startColumn;
        if (position >= 0)
            reader = new ColumnGroupReader(ssTable, decoratedKey, position);
    }

    private boolean isColumnNeeded(IColumn column)
    {
        return reversed
               ? startColumn.length == 0 || comparator.compare(column.name(), startColumn) <= 0
               : comparator.compare(column.name(), startColumn) >= 0;
    }

    public ColumnFamily getColumnFamily()
    {
        return reader.getEmptyColumnFamily();
    }

    protected IColumn computeNext()
    {
        if (reader == null)
            return endOfData();

        while (true)
        {
            IColumn column = reader.pollColumn();
            if (column == null)
                return endOfData();
            if (isColumnNeeded(column))
                return column;
        }
    }

    public void close() throws IOException
    {
        if (reader != null)
            reader.close();
    }

    /**
     *  This is a reader that finds the block for a starting column and returns
     *  blocks before/after it for each next call. This function assumes that
     *  the CF is sorted by name and exploits the name index.
     */
    class ColumnGroupReader
    {
        private final ColumnFamily emptyColumnFamily;

        private final List<IndexHelper.IndexInfo> indexes;
        private final long columnStartPosition;
        private final BufferedRandomAccessFile file;

        private int curRangeIndex;
        private Deque<IColumn> blockColumns = new ArrayDeque<IColumn>();

        public ColumnGroupReader(SSTableReader ssTable, DecoratedKey key, long position) throws IOException
        {
            this.file = new BufferedRandomAccessFile(ssTable.getFilename(), "r", DatabaseDescriptor.getSlicedReadBufferSizeInKB() * 1024);

            file.seek(position);
            DecoratedKey keyInDisk = ssTable.getPartitioner().convertFromDiskFormat(file.readUTF());
            assert keyInDisk.equals(key);

            file.readInt(); // row size
            IndexHelper.skipBloomFilter(file);
            indexes = IndexHelper.deserializeIndex(file);

            emptyColumnFamily = ColumnFamily.serializer().deserializeFromSSTableNoColumns(ssTable.makeColumnFamily(), file);
            file.readInt(); // column count

            columnStartPosition = file.getFilePointer();
            curRangeIndex = IndexHelper.indexFor(startColumn, indexes, comparator, reversed);
            if (reversed && curRangeIndex == indexes.size())
                curRangeIndex--;
        }

        public ColumnFamily getEmptyColumnFamily()
        {
            return emptyColumnFamily;
        }

        public IColumn pollColumn()
        {
            IColumn column = blockColumns.poll();
            if (column == null)
            {
                try
                {
                    if (getNextBlock())
                        column = blockColumns.poll();
                }
                catch (IOException e)
                {
                    throw new RuntimeException(e);
                }
            }
            return column;
        }

        public boolean getNextBlock() throws IOException
        {
            if (curRangeIndex < 0 || curRangeIndex >= indexes.size())
                return false;

            /* seek to the correct offset to the data, and calculate the data size */
            IndexHelper.IndexInfo curColPostion = indexes.get(curRangeIndex);
            file.seek(columnStartPosition + curColPostion.offset);
            while (file.getFilePointer() < columnStartPosition + curColPostion.offset + curColPostion.width)
            {
                IColumn column = emptyColumnFamily.getColumnSerializer().deserialize(file);
                if (reversed)
                    blockColumns.addFirst(column);
                else
                    blockColumns.addLast(column);
            }

            if (reversed)
                curRangeIndex--;
            else
                curRangeIndex++;
            return true;
        }

        public void close() throws IOException
        {
            file.close();
        }
    }
}
