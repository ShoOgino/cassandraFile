/**
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

package org.apache.cassandra.db;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

import org.apache.commons.lang.ArrayUtils;

import org.apache.cassandra.config.DatabaseDescriptor;
import org.apache.cassandra.dht.IPartitioner;
import org.apache.cassandra.io.util.DataOutputBuffer;
import org.apache.cassandra.io.SSTableReader;
import org.apache.cassandra.io.SSTableWriter;
import org.apache.cassandra.service.StorageService;
import org.apache.cassandra.utils.DestructivePQIterator;
import org.apache.cassandra.db.filter.*;
import org.apache.cassandra.db.marshal.AbstractType;
import org.cliffc.high_scale_lib.NonBlockingHashMap;

import org.apache.log4j.Logger;

public class Memtable implements Comparable<Memtable>, IFlushable<DecoratedKey>
{
    private static final Logger logger = Logger.getLogger(Memtable.class);

    private boolean isFrozen;

    private final int THRESHOLD = DatabaseDescriptor.getMemtableThroughput() * 1024*1024; // not static since we might want to change at runtime
    private final int THRESHOLD_COUNT = (int)(DatabaseDescriptor.getMemtableOperations() * 1024*1024);

    private final AtomicInteger currentThroughput = new AtomicInteger(0);
    private final AtomicInteger currentOperations = new AtomicInteger(0);

    private final String table;
    private final String columnfamilyName;
    private final long creationTime;
    private final NonBlockingHashMap<DecoratedKey, ColumnFamily> columnFamilies = new NonBlockingHashMap<DecoratedKey, ColumnFamily>();
    private final IPartitioner partitioner = StorageService.getPartitioner();

    Memtable(String table, String cfName)
    {
        this.table = table;
        columnfamilyName = cfName;
        creationTime = System.currentTimeMillis();
    }

    /**
     * Compares two Memtable based on creation time.
     * @param rhs Memtable to compare to.
     * @return a negative integer, zero, or a positive integer as this object
     * is less than, equal to, or greater than the specified object.
     */
    public int compareTo(Memtable rhs)
    {
    	long diff = creationTime - rhs.creationTime;
    	if ( diff > 0 )
    		return 1;
    	else if ( diff < 0 )
    		return -1;
    	else
    		return 0;
    }

    public int getCurrentThroughput()
    {
        return currentThroughput.get();
    }
    
    public int getCurrentOperations()
    {
        return currentOperations.get();
    }

    boolean isThresholdViolated()
    {
        return currentThroughput.get() >= this.THRESHOLD || currentOperations.get() >= this.THRESHOLD_COUNT;
    }

    boolean isFrozen()
    {
        return isFrozen;
    }

    void freeze()
    {
        isFrozen = true;
    }

    /**
     * Should only be called by ColumnFamilyStore.apply.  NOT a public API.
     * (CFS handles locking to avoid submitting an op
     *  to a flushing memtable.  Any other way is unsafe.)
    */
    void put(String key, ColumnFamily columnFamily)
    {
        assert !isFrozen; // not 100% foolproof but hell, it's an assert
        resolve(key, columnFamily);
    }

    private void resolve(String key, ColumnFamily cf)
    {
        currentThroughput.addAndGet(cf.size());
        currentOperations.addAndGet(cf.getColumnCount());

        DecoratedKey decoratedKey = partitioner.decorateKey(key);
        ColumnFamily oldCf = columnFamilies.putIfAbsent(decoratedKey, cf);
        if (oldCf == null)
            return;

        oldCf.resolve(cf);
    }

    // for debugging
    public String contents()
    {
        StringBuilder builder = new StringBuilder();
        builder.append("{");
        for (Map.Entry<DecoratedKey, ColumnFamily> entry : columnFamilies.entrySet())
        {
            builder.append(entry.getKey()).append(": ").append(entry.getValue()).append(", ");
        }
        builder.append("}");
        return builder.toString();
    }

    public List<DecoratedKey> getSortedKeys()
    {
        logger.info("Sorting " + this);
        // sort keys in the order they would be in when decorated
        ArrayList<DecoratedKey> orderedKeys = new ArrayList<DecoratedKey>(columnFamilies.keySet());
        Collections.sort(orderedKeys);
        return orderedKeys;
    }

    public SSTableReader writeSortedContents(List<DecoratedKey> sortedKeys) throws IOException
    {
        logger.info("Writing " + this);
        ColumnFamilyStore cfStore = Table.open(table).getColumnFamilyStore(columnfamilyName);
        SSTableWriter writer = new SSTableWriter(cfStore.getTempSSTablePath(), columnFamilies.size(), StorageService.getPartitioner());

        DataOutputBuffer buffer = new DataOutputBuffer();
        for (DecoratedKey key : sortedKeys)
        {
            buffer.reset();
            ColumnFamily columnFamily = columnFamilies.get(key);
            /* serialize the cf with column indexes */
            ColumnFamily.serializer().serializeWithIndexes(columnFamily, buffer);
            /* Now write the key and value to disk */
            writer.append(key, buffer);
        }

        SSTableReader ssTable = writer.closeAndOpenReader(DatabaseDescriptor.getKeysCachedFraction(table));
        logger.info("Completed flushing " + ssTable.getFilename());
        return ssTable;
    }

    public String toString()
    {
        return "Memtable(" + columnfamilyName + ")@" + hashCode();
    }

    public Iterator<DecoratedKey> getKeyIterator()
    {
        // even though we are using NBHM, it is okay to use size() twice here, since size() will never decrease
        // w/in a single memtable's lifetime
        if (columnFamilies.size() == 0)
        {
            // cannot create a PQ of size zero (wtf?)
            return Arrays.asList(new DecoratedKey[0]).iterator();
        }
        PriorityQueue<DecoratedKey> pq = new PriorityQueue<DecoratedKey>(columnFamilies.size());
        pq.addAll(columnFamilies.keySet());
        return new DestructivePQIterator<DecoratedKey>(pq);
    }

    public boolean isClean()
    {
        return columnFamilies.isEmpty();
    }

    /**
     * obtain an iterator of columns in this memtable in the specified order starting from a given column.
     */
    public ColumnIterator getSliceIterator(ColumnFamily cf, SliceQueryFilter filter, AbstractType typeComparator)
    {
        final ColumnFamily columnFamily = cf == null ? ColumnFamily.create(table, filter.getColumnFamilyName()) : cf.cloneMeShallow();

        final IColumn columns[] = (cf == null ? columnFamily : cf).getSortedColumns().toArray(new IColumn[columnFamily.getSortedColumns().size()]);
        // TODO if we are dealing with supercolumns, we need to clone them while we have the read lock since they can be modified later
        if (filter.reversed)
            ArrayUtils.reverse(columns);
        IColumn startIColumn;
        final boolean isStandard = DatabaseDescriptor.getColumnFamilyType(table, filter.getColumnFamilyName()).equals("Standard");
        if (isStandard)
            startIColumn = new Column(filter.start);
        else
            startIColumn = new SuperColumn(filter.start, null); // ok to not have subcolumnComparator since we won't be adding columns to this object

        // can't use a ColumnComparatorFactory comparator since those compare on both name and time (and thus will fail to match
        // our dummy column, since the time there is arbitrary).
        Comparator<IColumn> comparator = filter.getColumnComparator(typeComparator);
        int index;
        if (filter.start.length == 0 && filter.reversed)
        {
            /* scan from the largest column in descending order */
            index = 0;
        }
        else
        {
            index = Arrays.binarySearch(columns, startIColumn, comparator);
        }
        final int startIndex = index < 0 ? -(index + 1) : index;

        return new AbstractColumnIterator()
        {
            private int curIndex_ = startIndex;

            public ColumnFamily getColumnFamily()
            {
                return columnFamily;
            }

            public boolean hasNext()
            {
                return curIndex_ < columns.length;
            }

            public IColumn next()
            {
                // clone supercolumns so caller can freely removeDeleted or otherwise mutate it
                return isStandard ? columns[curIndex_++] : ((SuperColumn)columns[curIndex_++]).cloneMe();
            }
        };
    }

    public ColumnIterator getNamesIterator(final ColumnFamily cf, final NamesQueryFilter filter)
    {
        final ColumnFamily columnFamily = cf == null ? ColumnFamily.create(table, filter.getColumnFamilyName()) : cf.cloneMeShallow();
        final boolean isStandard = DatabaseDescriptor.getColumnFamilyType(table, filter.getColumnFamilyName()).equals("Standard");

        return new SimpleAbstractColumnIterator()
        {
            private Iterator<byte[]> iter = filter.columns.iterator();
            private byte[] current;

            public ColumnFamily getColumnFamily()
            {
                return columnFamily;
            }

            protected IColumn computeNext()
            {
                if (cf == null)
                {
                    return endOfData();
                }
                while (iter.hasNext())
                {
                    current = iter.next();
                    IColumn column = cf.getColumn(current);
                    if (column != null)
                        // clone supercolumns so caller can freely removeDeleted or otherwise mutate it
                        return isStandard ? column : ((SuperColumn)column).cloneMe();
                }
                return endOfData();
            }
        };
    }

    public ColumnFamily getColumnFamily(String key)
    {
        return columnFamilies.get(partitioner.decorateKey(key));
    }

    void clearUnsafe()
    {
        columnFamilies.clear();
    }

    public boolean isExpired()
    {
        return System.currentTimeMillis() > creationTime + DatabaseDescriptor.getMemtableLifetimeMS();
    }
}
