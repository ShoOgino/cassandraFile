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


import java.io.IOException;
import java.util.*;

import org.apache.cassandra.db.ColumnFamily;
import org.apache.cassandra.db.IColumn;
import org.apache.cassandra.db.marshal.AbstractType;
import org.apache.cassandra.io.*;
import org.apache.cassandra.config.DatabaseDescriptor;
import org.apache.cassandra.utils.BloomFilter;

public class SSTableNamesIterator extends SimpleAbstractColumnIterator
{
    private ColumnFamily cf;
    private Iterator<IColumn> iter;
    public final SortedSet<byte[]> columns;

    public SSTableNamesIterator(SSTableReader ssTable, String key, SortedSet<byte[]> columnNames) throws IOException
    {
        assert columnNames != null;
        this.columns = columnNames;

        String decoratedKey = ssTable.getPartitioner().decorateKey(key);
        long position = ssTable.getPosition(decoratedKey);
        if (position < 0)
            return;

        BufferedRandomAccessFile file = new BufferedRandomAccessFile(ssTable.getFilename(), "r", DatabaseDescriptor.getIndexedReadBufferSizeInKB() * 1024);
        try
        {
            file.seek(position);

            String keyInDisk = file.readUTF();
            assert keyInDisk.equals(decoratedKey) : keyInDisk;
            file.readInt(); // data size

            /* Read the bloom filter summarizing the columns */
            BloomFilter bf = IndexHelper.defreezeBloomFilter(file);
            List<byte[]> filteredColumnNames = new ArrayList<byte[]>(columnNames.size());
            for (byte[] name : columnNames)
            {
                if (bf.isPresent(name))
                {
                    filteredColumnNames.add(name);
                }
            }
            if (filteredColumnNames.isEmpty())
            {
                return;
            }

            List<IndexHelper.IndexInfo> indexList = IndexHelper.deserializeIndex(file);

            cf = ColumnFamily.serializer().deserializeFromSSTableNoColumns(ssTable.makeColumnFamily(), file);
            file.readInt(); // column count

            /* get the various column ranges we have to read */
            AbstractType comparator = ssTable.getColumnComparator();
            SortedSet<IndexHelper.IndexInfo> ranges = new TreeSet<IndexHelper.IndexInfo>(IndexHelper.getComparator(comparator));
            for (byte[] name : filteredColumnNames)
            {
                int index = IndexHelper.indexFor(name, indexList, comparator, false);
                if (index == indexList.size())
                    continue;
                IndexHelper.IndexInfo indexInfo = indexList.get(index);
                if (comparator.compare(name, indexInfo.firstName) < 0)
                   continue;
                ranges.add(indexInfo);
            }

            /* seek to the correct offset to the data */
            long columnBegin = file.getFilePointer();
            /* now read all the columns from the ranges */
            for (IndexHelper.IndexInfo indexInfo : ranges)
            {
                file.seek(columnBegin + indexInfo.offset);
                // TODO only completely deserialize columns we are interested in
                while (file.getFilePointer() < columnBegin + indexInfo.offset + indexInfo.width)
                {
                    final IColumn column = cf.getColumnSerializer().deserialize(file);
                    // we check vs the original Set, not the filtered List, for efficiency
                    if (columnNames.contains(column.name()))
                    {
                        cf.addColumn(column);
                    }
                }
            }
        }
        finally
        {
            file.close();
        }

        iter = cf.getSortedColumns().iterator();
    }

    public ColumnFamily getColumnFamily()
    {
        return cf;
    }

    protected IColumn computeNext()
    {
        if (iter == null || !iter.hasNext())
            return endOfData();
        return iter.next();
    }
}
