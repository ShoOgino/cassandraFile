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
package org.apache.cassandra.db.filter;

import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Comparator;
import java.util.Iterator;
import java.util.SortedSet;
import java.util.TreeSet;

import org.apache.commons.lang.StringUtils;

import org.apache.cassandra.db.*;
import org.apache.cassandra.db.columniterator.ISSTableColumnIterator;
import org.apache.cassandra.db.columniterator.OnDiskAtomIterator;
import org.apache.cassandra.db.columniterator.SSTableNamesIterator;
import org.apache.cassandra.db.marshal.AbstractType;
import org.apache.cassandra.io.IVersionedSerializer;
import org.apache.cassandra.io.sstable.SSTableReader;
import org.apache.cassandra.io.util.FileDataInput;
import org.apache.cassandra.utils.ByteBufferUtil;
import org.apache.cassandra.utils.FBUtilities;

public class NamesQueryFilter implements IDiskAtomFilter
{
    public static final Serializer serializer = new Serializer();

    public final SortedSet<ByteBuffer> columns;

    public NamesQueryFilter(SortedSet<ByteBuffer> columns)
    {
        this.columns = columns;
    }

    public NamesQueryFilter(ByteBuffer column)
    {
        this(FBUtilities.singleton(column));
    }

    public OnDiskAtomIterator getMemtableColumnIterator(ColumnFamily cf, DecoratedKey key)
    {
        return Memtable.getNamesIterator(key, cf, this);
    }

    public ISSTableColumnIterator getSSTableColumnIterator(SSTableReader sstable, DecoratedKey key)
    {
        return new SSTableNamesIterator(sstable, key, columns);
    }

    public ISSTableColumnIterator getSSTableColumnIterator(SSTableReader sstable, FileDataInput file, DecoratedKey key, RowIndexEntry indexEntry)
    {
        return new SSTableNamesIterator(sstable, file, key, columns, indexEntry);
    }

    public SuperColumn filterSuperColumn(SuperColumn superColumn, int gcBefore)
    {
        for (IColumn column : superColumn.getSubColumns())
        {
            if (!columns.contains(column.name()) || !QueryFilter.isRelevant(column, superColumn, gcBefore))
            {
                superColumn.remove(column.name());
            }
        }
        return superColumn;
    }

    public void collectReducedColumns(IColumnContainer container, Iterator<IColumn> reducedColumns, int gcBefore)
    {
        while (reducedColumns.hasNext())
        {
            IColumn column = reducedColumns.next();
            if (QueryFilter.isRelevant(column, container, gcBefore))
                container.addColumn(column);
        }
    }

    public Comparator<IColumn> getColumnComparator(AbstractType<?> comparator)
    {
        return comparator.columnComparator;
    }

    @Override
    public String toString()
    {
        return "NamesQueryFilter(" +
               "columns=" + StringUtils.join(columns, ",") +
               ')';
    }

    public boolean isReversed()
    {
        return false;
    }

    public void updateColumnsLimit(int newLimit)
    {
    }

    public static class Serializer implements IVersionedSerializer<NamesQueryFilter>
    {
        public void serialize(NamesQueryFilter f, DataOutput dos, int version) throws IOException
        {
            dos.writeInt(f.columns.size());
            for (ByteBuffer cName : f.columns)
            {
                ByteBufferUtil.writeWithShortLength(cName, dos);
            }
        }

        public NamesQueryFilter deserialize(DataInput dis, int version) throws IOException
        {
            throw new UnsupportedOperationException();
        }

        public NamesQueryFilter deserialize(DataInput dis, int version, AbstractType comparator) throws IOException
        {
            int size = dis.readInt();
            SortedSet<ByteBuffer> columns = new TreeSet<ByteBuffer>(comparator);
            for (int i = 0; i < size; ++i)
                columns.add(ByteBufferUtil.readWithShortLength(dis));
            return new NamesQueryFilter(columns);
        }

        public long serializedSize(NamesQueryFilter f, int version)
        {
            TypeSizes sizes = TypeSizes.NATIVE;
            int size = sizes.sizeof(f.columns.size());
            for (ByteBuffer cName : f.columns)
            {
                int cNameSize = cName.remaining();
                size += sizes.sizeof((short) cNameSize) + cNameSize;
            }
            return size;
        }
    }
}
