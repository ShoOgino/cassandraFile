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

import org.apache.cassandra.io.sstable.SSTableReader;
import org.apache.cassandra.utils.ReducingIterator;
import org.apache.cassandra.db.*;
import org.apache.cassandra.db.marshal.AbstractType;

public class QueryFilter
{
    public final String key;
    public final QueryPath path;
    private final IFilter filter;
    private final IFilter superFilter;

    protected QueryFilter(String key, QueryPath path, IFilter filter)
    {
        this.key = key;
        this.path = path;
        this.filter = filter;
        superFilter = path.superColumnName == null ? null : new NamesQueryFilter(key, path.superColumnName);
    }

    public ColumnIterator getMemtableColumnIterator(Memtable memtable, AbstractType comparator, int gcBefore)
    {
        ColumnFamily cf = memtable.getColumnFamily(key);
        if (cf == null)
            return null;
        return getMemtableColumnIterator(cf, comparator, gcBefore);
    }

    // TODO move gcBefore into a field
    public ColumnIterator getMemtableColumnIterator(ColumnFamily cf, AbstractType comparator, int gcBefore)
    {
        assert cf != null;
        if (path.superColumnName == null)
            return filter.getMemtableColumnIterator(cf, comparator);
        return superFilter.getMemtableColumnIterator(cf, comparator);
    }

    // TODO move gcBefore into a field
    public ColumnIterator getSSTableColumnIterator(SSTableReader sstable, int gcBefore)
    {
        if (path.superColumnName == null)
            return filter.getSSTableColumnIterator(sstable);
        return superFilter.getSSTableColumnIterator(sstable);
    }

    public static Comparator<IColumn> getColumnComparator(final AbstractType comparator)
    {
        return new Comparator<IColumn>()
        {
            public int compare(IColumn c1, IColumn c2)
            {
                return comparator.compare(c1.name(), c2.name());
            }
        };
    }
    
    public void collectCollatedColumns(final ColumnFamily returnCF, Iterator<IColumn> collatedColumns, final int gcBefore)
    {
        // define a 'reduced' iterator that merges columns w/ the same name, which
        // greatly simplifies computing liveColumns in the presence of tombstones.
        ReducingIterator<IColumn, IColumn> reduced = new ReducingIterator<IColumn, IColumn>(collatedColumns)
        {
            ColumnFamily curCF = returnCF.cloneMeShallow();

            protected boolean isEqual(IColumn o1, IColumn o2)
            {
                return Arrays.equals(o1.name(), o2.name());
            }

            public void reduce(IColumn current)
            {
                curCF.addColumn(current);
            }

            protected IColumn getReduced()
            {
                IColumn c = curCF.getSortedColumns().iterator().next();
                if (superFilter != null)
                    c = filter.filterSuperColumn((SuperColumn)c, gcBefore);
                curCF.clear();
                return c;
            }
        };

        (superFilter == null ? filter : superFilter).collectReducedColumns(returnCF, reduced, gcBefore);
    }

    public String getColumnFamilyName()
    {
        return path.columnFamilyName;
    }

    public static boolean isRelevant(IColumn column, IColumnContainer container, int gcBefore)
    {
        // the column itself must be not gc-able (it is live, or a still relevant tombstone, or has live subcolumns), (1)
        // and if its container is deleted, the column must be changed more recently than the container tombstone (2)
        // (since otherwise, the only thing repair cares about is the container tombstone)
        long maxChange = column.mostRecentLiveChangeAt();
        return (!column.isMarkedForDelete() || column.getLocalDeletionTime() > gcBefore || maxChange > column.getMarkedForDeleteAt()) // (1)
               && (!container.isMarkedForDelete() || maxChange > container.getMarkedForDeleteAt()); // (2)
    }

    /**
     * @return a QueryFilter object to satisfy the given slice criteria:
     * @param key the row to slice
     * @param path path to the level to slice at (CF or SuperColumn)
     * @param start column to start slice at, inclusive; empty for "the first column"
     * @param finish column to stop slice at, inclusive; empty for "the last column"
     * @param bitmasks we should probably remove this
     * @param reversed true to start with the largest column (as determined by configured sort order) instead of smallest
     * @param limit maximum number of non-deleted columns to return
     */
    public static QueryFilter getSliceFilter(String key, QueryPath path, byte[] start, byte[] finish, List<byte[]> bitmasks, boolean reversed, int limit)
    {
        return new QueryFilter(key, path, new SliceQueryFilter(key, start, finish, bitmasks, reversed, limit));
    }

    /**
     * return a QueryFilter object that includes every column in the row.
     * This is dangerous on large rows; avoid except for test code.
     */
    public static QueryFilter getIdentityFilter(String key, QueryPath path)
    {
        return new QueryFilter(key, path, new IdentityQueryFilter(key));
    }

    /**
     * @return a QueryFilter object that will return columns matching the given names
     * @param key the row to slice
     * @param path path to the level to slice at (CF or SuperColumn)
     * @param columns the column names to restrict the results to
     */
    public static QueryFilter getNamesFilter(String key, QueryPath path, SortedSet<byte[]> columns)
    {
        return new QueryFilter(key, path, new NamesQueryFilter(key, columns));
    }

    /**
     * convenience method for creating a name filter matching a single column
     */
    public static QueryFilter getNamesFilter(String key, QueryPath path, byte[] column)
    {
        return new QueryFilter(key, path, new NamesQueryFilter(key, column));
    }
}
