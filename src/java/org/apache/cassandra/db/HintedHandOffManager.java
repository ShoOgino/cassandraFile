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

import java.net.UnknownHostException;
import java.util.Collection;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.ExecutorService;
import java.io.IOException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.apache.cassandra.concurrent.JMXEnabledThreadPoolExecutor;
import org.apache.cassandra.config.DatabaseDescriptor;
import org.apache.cassandra.db.filter.QueryFilter;
import org.apache.cassandra.gms.FailureDetector;
import org.apache.cassandra.gms.Gossiper;

import java.net.InetAddress;

import org.apache.commons.lang.ArrayUtils;

import org.apache.cassandra.net.Message;
import org.apache.cassandra.net.MessagingService;
import org.apache.cassandra.service.*;
import org.apache.cassandra.thrift.InvalidRequestException;
import org.apache.cassandra.db.filter.QueryPath;
import org.apache.cassandra.utils.FBUtilities;
import static org.apache.cassandra.utils.FBUtilities.UTF8;
import org.apache.cassandra.utils.WrappedRunnable;


/**
 * For each endpoint for which we have hints, there is a row in the system hints CF.
 * SuperColumns in that row are keys for which we have hinted data.
 * Subcolumns names within that supercolumn are keyspace+CF, concatenated with SEPARATOR.
 * Subcolumn values are always empty; instead, we store the row data "normally"
 * in the application table it belongs in.
 *
 * When FailureDetector signals that a node that was down is back up, we read its
 * hints row to see what rows we need to forward data for, then reach each row in its
 * entirety and send it over.
 * (TODO handle rows that have incrementally grown too large for a single message.)
 *
 * deliverHints is also exposed to JMX so it can be run manually if FD ever misses
 * its cue somehow.
 *
 * HHM never deletes the row from Application tables; there is no way to distinguish that
 * from hinted tombstones!  instead, rely on cleanup compactions to remove data
 * that doesn't belong on this node.  (Cleanup compactions may be started manually
 * -- on a per node basis -- with "nodeprobe cleanup.")
 *
 * TODO this avoids our hint rows from growing excessively large by offloading the
 * message data into application tables.  But, this means that cleanup compactions
 * will nuke HH data.  Probably better would be to store the RowMutation messages
 * in a HHData (non-super) CF, modifying the above to store a UUID value in the
 * HH subcolumn value, which we use as a key to a [standard] HHData system CF
 * that would contain the message bytes.
 */

public class HintedHandOffManager
{
    public static final HintedHandOffManager instance = new HintedHandOffManager();

    private static final Logger logger_ = LoggerFactory.getLogger(HintedHandOffManager.class);
    public static final String HINTS_CF = "HintsColumnFamily";
    private static final int PAGE_SIZE = 10000;
    private static final String SEPARATOR = "-";

    private final ExecutorService executor_ = new JMXEnabledThreadPoolExecutor("HINTED-HANDOFF-POOL");

    private static boolean sendMessage(InetAddress endpoint, String tableName, String cfName, byte[] key) throws IOException
    {
        if (!Gossiper.instance.isKnownEndpoint(endpoint))
        {
            logger_.warn("Hints found for endpoint " + endpoint + " which is not part of the gossip network.  discarding.");
            return true;
        }
        if (!FailureDetector.instance.isAlive(endpoint))
        {
            return false;
        }

        Table table = Table.open(tableName);
        RowMutation rm = new RowMutation(tableName, key);
        DecoratedKey dkey = StorageService.getPartitioner().decorateKey(key);
        ColumnFamilyStore cfs = table.getColumnFamilyStore(cfName);
        ColumnFamily cf = cfs.getColumnFamily(QueryFilter.getIdentityFilter(dkey, new QueryPath(cfs.getColumnFamilyName())));
        if (cf != null)
            rm.add(cf);
        Message message = rm.makeRowMutationMessage();
        WriteResponseHandler responseHandler = new WriteResponseHandler(endpoint);
        MessagingService.instance.sendRR(message, new InetAddress[] { endpoint }, responseHandler);

        try
        {
            responseHandler.get();
        }
        catch (TimeoutException e)
        {
            return false;
        }
        return true;
    }

    private static void deleteHintKey(byte[] endpointAddress, byte[] key, byte[] tableCF) throws IOException
    {
        RowMutation rm = new RowMutation(Table.SYSTEM_TABLE, endpointAddress);
        rm.delete(new QueryPath(HINTS_CF, key, tableCF), new TimestampClock(System.currentTimeMillis()));
        rm.apply();
    }

    public static void deleteHintsForEndPoint(InetAddress endpoint)
    {
        ColumnFamilyStore hintStore = Table.open(Table.SYSTEM_TABLE).getColumnFamilyStore(HINTS_CF);
        RowMutation rm = new RowMutation(Table.SYSTEM_TABLE, endpoint.getAddress());
        rm.delete(new QueryPath(HINTS_CF), new TimestampClock(System.currentTimeMillis()));
        try {
            logger_.info("Deleting any stored hints for " + endpoint);
            rm.apply();
            hintStore.forceFlush();
            CompactionManager.instance.submitMajor(hintStore, 0, Integer.MAX_VALUE).get();
        }
        catch (Exception e)
        {
            logger_.warn("Could not delete hints for " + endpoint + ": " + e);
        }
    }

    private static boolean pagingFinished(ColumnFamily hintColumnFamily, byte[] startColumn)
    {
        // done if no hints found or the start column (same as last column processed in previous iteration) is the only one
        return hintColumnFamily == null
               || (hintColumnFamily.getSortedColumns().size() == 1 && hintColumnFamily.getColumn(startColumn) != null);
    }

    public static byte[] makeCombinedName(String tableName, String columnFamily)
    {
        byte[] withsep = ArrayUtils.addAll(tableName.getBytes(FBUtilities.UTF8), SEPARATOR.getBytes());
        return ArrayUtils.addAll(withsep, columnFamily.getBytes(FBUtilities.UTF8));
    }

    private static String[] getTableAndCFNames(byte[] joined)
    {
        int index;
        index = ArrayUtils.lastIndexOf(joined, SEPARATOR.getBytes()[0]);
        if (index < 1)
            throw new RuntimeException("Corrupted hint name " + joined.toString());
        String[] parts = new String[2];
        parts[0] = new String(ArrayUtils.subarray(joined, 0, index));
        parts[1] = new String(ArrayUtils.subarray(joined, index+1, joined.length));
        return parts;

    }
            
    private static void deliverHintsToEndpoint(InetAddress endpoint) throws IOException, DigestMismatchException, InvalidRequestException, TimeoutException
    {
        if (logger_.isDebugEnabled())
          logger_.debug("Started hinted handoff for endpoint " + endpoint);

        // 1. Get the key of the endpoint we need to handoff
        // 2. For each column read the list of rows: subcolumns are KS + SEPARATOR + CF
        // 3. Delete the subcolumn if the write was successful
        // 4. Force a flush
        // 5. Do major compaction to clean up all deletes etc.
        DecoratedKey epkey =  StorageService.getPartitioner().decorateKey(endpoint.getAddress());
        ColumnFamilyStore hintStore = Table.open(Table.SYSTEM_TABLE).getColumnFamilyStore(HINTS_CF);
        byte[] startColumn = ArrayUtils.EMPTY_BYTE_ARRAY;
        while (true)
        {
            QueryFilter filter = QueryFilter.getSliceFilter(epkey, new QueryPath(HINTS_CF), startColumn, ArrayUtils.EMPTY_BYTE_ARRAY, null, false, PAGE_SIZE);
            ColumnFamily hintColumnFamily = ColumnFamilyStore.removeDeleted(hintStore.getColumnFamily(filter), Integer.MAX_VALUE);
            if (pagingFinished(hintColumnFamily, startColumn))
                break;
            Collection<IColumn> keyColumns = hintColumnFamily.getSortedColumns();
            for (IColumn keyColumn : keyColumns)
            {
                startColumn = keyColumn.name();
                Collection<IColumn> tableCFs = keyColumn.getSubColumns();
                for (IColumn tableCF : tableCFs)
                {
                    String[] parts = getTableAndCFNames(tableCF.name());
                    if (sendMessage(endpoint, parts[0], parts[1], keyColumn.name()))
                        deleteHintKey(endpoint.getAddress(), keyColumn.name(), tableCF.name());
                }
            }
        }
        hintStore.forceFlush();
        try
        {
            CompactionManager.instance.submitMajor(hintStore, 0, Integer.MAX_VALUE).get();
        }
        catch (Exception e)
        {
            throw new RuntimeException(e);
        }

        if (logger_.isDebugEnabled())
          logger_.debug("Finished hinted handoff for endpoint " + endpoint);
    }

    /** called when a keyspace is dropped or rename. newTable==null in the case of a drop. */
    public static void renameHints(String oldTable, String newTable) throws IOException
    {
        DecoratedKey oldTableKey = StorageService.getPartitioner().decorateKey(oldTable.getBytes(UTF8));
        // we're basically going to fetch, drop and add the scf for the old and new table. we need to do it piecemeal 
        // though since there could be GB of data.
        ColumnFamilyStore hintStore = Table.open(Table.SYSTEM_TABLE).getColumnFamilyStore(HINTS_CF);
        byte[] startCol = ArrayUtils.EMPTY_BYTE_ARRAY;
        long now = System.currentTimeMillis();
        while (true)
        {
            QueryFilter filter = QueryFilter.getSliceFilter(oldTableKey, new QueryPath(HINTS_CF), startCol, ArrayUtils.EMPTY_BYTE_ARRAY, null, false, PAGE_SIZE);
            ColumnFamily cf = ColumnFamilyStore.removeDeleted(hintStore.getColumnFamily(filter), Integer.MAX_VALUE);
            if (pagingFinished(cf, startCol))
                break;
            if (newTable != null)
            {
                RowMutation insert = new RowMutation(Table.SYSTEM_TABLE, newTable.getBytes(UTF8));
                insert.add(cf);
                insert.apply();
            }
            RowMutation drop = new RowMutation(Table.SYSTEM_TABLE, oldTableKey.key);
            for (byte[] key : cf.getColumnNames())
            {
                drop.delete(new QueryPath(HINTS_CF, key), new TimestampClock(now));
                startCol = key;
            }
            drop.apply();
        }
    }

    /*
     * This method is used to deliver hints to a particular endpoint.
     * When we learn that some endpoint is back up we deliver the data
     * to him via an event driven mechanism.
    */
    public void deliverHints(final InetAddress to)
    {
        Runnable r = new WrappedRunnable()
        {
            public void runMayThrow() throws Exception
            {
                deliverHintsToEndpoint(to);
            }
        };
    	executor_.submit(r);
    }

    public void deliverHints(String to) throws UnknownHostException
    {
        deliverHints(InetAddress.getByName(to));
    }
}
