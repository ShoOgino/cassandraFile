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
package org.apache.cassandra.service;

import java.io.IOError;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.Callable;
import java.util.concurrent.Future;
import java.lang.management.ManagementFactory;

import org.apache.commons.lang.StringUtils;

import org.apache.cassandra.config.DatabaseDescriptor;
import org.apache.cassandra.db.*;
import org.apache.cassandra.io.DataInputBuffer;
import java.net.InetAddress;
import org.apache.cassandra.net.IAsyncResult;
import org.apache.cassandra.net.Message;
import org.apache.cassandra.net.MessagingService;
import org.apache.cassandra.utils.TimedStatsDeque;
import org.apache.cassandra.utils.FBUtilities;
import org.apache.cassandra.locator.TokenMetadata;
import org.apache.cassandra.dht.IPartitioner;
import org.apache.cassandra.gms.FailureDetector;
import org.apache.cassandra.concurrent.StageManager;

import org.apache.log4j.Logger;

import javax.management.MBeanServer;
import javax.management.ObjectName;


public class StorageProxy implements StorageProxyMBean
{
    private static Logger logger = Logger.getLogger(StorageProxy.class);

    // mbean stuff
    private static TimedStatsDeque readStats = new TimedStatsDeque(60000);
    private static TimedStatsDeque rangeStats = new TimedStatsDeque(60000);
    private static TimedStatsDeque writeStats = new TimedStatsDeque(60000);

    private StorageProxy() {}
    static
    {
        MBeanServer mbs = ManagementFactory.getPlatformMBeanServer();
        try
        {
            mbs.registerMBean(new StorageProxy(), new ObjectName("org.apache.cassandra.service:type=StorageProxy"));
        }
        catch (Exception e)
        {
            throw new RuntimeException(e);
        }
    }

    private static Comparator<String> keyComparator = new Comparator<String>()
    {
        public int compare(String o1, String o2)
        {
            IPartitioner p = StorageService.getPartitioner();
            return p.getDecoratedKeyComparator().compare(p.decorateKey(o1), p.decorateKey(o2));
        }
    };

    private static Comparator<Row> rowComparator = new Comparator<Row>()
    {
        public int compare(Row r1, Row r2)
        {
            return keyComparator.compare(r1.key(), r2.key());
        }
    };

    /**
     * Use this method to have this RowMutation applied
     * across all replicas. This method will take care
     * of the possibility of a replica being down and hint
     * the data across to some other replica.
     *
     * This is the ZERO consistency level. We do not wait for replies.
     *
     * @param rm the mutation to be applied across the replicas
    */
    public static void insert(final RowMutation rm)
    {
        long startTime = System.currentTimeMillis();
        try
        {
            List<InetAddress> naturalEndpoints = StorageService.instance().getNaturalEndpoints(rm.key());
            Map<InetAddress, InetAddress> endpointMap = StorageService.instance().getHintedEndpointMap(rm.key(), naturalEndpoints);
            Message unhintedMessage = null; // lazy initialize for non-local, unhinted writes

            // 3 cases:
            // 1. local, unhinted write: run directly on write stage
            // 2. non-local, unhinted write: send row mutation message
            // 3. hinted write: add hint header, and send message
            for (Map.Entry<InetAddress, InetAddress> entry : endpointMap.entrySet())
            {
                InetAddress target = entry.getKey();
                InetAddress hintedTarget = entry.getValue();
                if (target.equals(hintedTarget))
                {
                    if (target.equals(FBUtilities.getLocalAddress()))
                    {
                        if (logger.isDebugEnabled())
                            logger.debug("insert writing local key " + rm.key());
                        Runnable runnable = new Runnable()
                        {
                            public void run()
                            {
                                try
                                {
                                    rm.apply();
                                }
                                catch (IOException e)
                                {
                                    throw new IOError(e);
                                }
                            }
                        };
                        StageManager.getStage(StageManager.mutationStage_).execute(runnable);
                    }
                    else
                    {
                        if (unhintedMessage == null)
                            unhintedMessage = rm.makeRowMutationMessage();
                        if (logger.isDebugEnabled())
                            logger.debug("insert writing key " + rm.key() + " to " + unhintedMessage.getMessageId() + "@" + target);
                        MessagingService.instance().sendOneWay(unhintedMessage, target);
                    }
                }
                else
                {
                    Message hintedMessage = rm.makeRowMutationMessage();
                    hintedMessage.addHeader(RowMutation.HINT, target.getAddress());
                    if (logger.isDebugEnabled())
                        logger.debug("insert writing key " + rm.key() + " to " + unhintedMessage.getMessageId() + "@" + hintedTarget + " for " + target);
                    MessagingService.instance().sendOneWay(hintedMessage, hintedTarget);
                }
            }
        }
        catch (IOException e)
        {
            throw new RuntimeException("error inserting key " + rm.key(), e);
        }
        finally
        {
            writeStats.add(System.currentTimeMillis() - startTime);
        }
    }
    
    public static void insertBlocking(final RowMutation rm, int consistency_level) throws UnavailableException
    {
        long startTime = System.currentTimeMillis();
        try
        {
            List<InetAddress> naturalEndpoints = StorageService.instance().getNaturalEndpoints(rm.key());
            Map<InetAddress, InetAddress> endpointMap = StorageService.instance().getHintedEndpointMap(rm.key(), naturalEndpoints);
            int blockFor = determineBlockFor(naturalEndpoints.size(), endpointMap.size(), consistency_level);

            // avoid starting a write we know can't achieve the required consistency
            int liveNodes = 0;
            for (Map.Entry<InetAddress, InetAddress> entry : endpointMap.entrySet())
            {
                if (entry.getKey().equals(entry.getValue()))
                {
                    liveNodes++;
                }
            }
            if (liveNodes < blockFor)
            {
                throw new UnavailableException();
            }

            // send out the writes, as in insert() above, but this time with a callback that tracks responses
            final WriteResponseHandler responseHandler = StorageService.instance().getWriteResponseHandler(blockFor, consistency_level);
            Message unhintedMessage = null;
            for (Map.Entry<InetAddress, InetAddress> entry : endpointMap.entrySet())
            {
                InetAddress target = entry.getKey();
                InetAddress hintedTarget = entry.getValue();

                if (target.equals(hintedTarget))
                {
                    if (target.equals(FBUtilities.getLocalAddress()))
                    {
                        if (logger.isDebugEnabled())
                            logger.debug("insert writing local key " + rm.key());
                        Runnable runnable = new Runnable()
                        {
                            public void run()
                            {
                                try
                                {
                                    rm.apply();
                                    responseHandler.localResponse();
                                }
                                catch (IOException e)
                                {
                                    throw new IOError(e);
                                }
                            }
                        };
                        StageManager.getStage(StageManager.mutationStage_).execute(runnable);
                    }
                    else
                    {
                        if (unhintedMessage == null)
                        {
                            unhintedMessage = rm.makeRowMutationMessage();
                            MessagingService.instance().addCallback(responseHandler, unhintedMessage.getMessageId());
                        }
                        if (logger.isDebugEnabled())
                            logger.debug("insert writing key " + rm.key() + " to " + unhintedMessage.getMessageId() + "@" + target);
                        MessagingService.instance().sendOneWay(unhintedMessage, target);
                    }
                }
                else
                {
                    // (hints aren't part of the callback since they don't count towards consistency until they are on the final destination node)
                    Message hintedMessage = rm.makeRowMutationMessage();
                    hintedMessage.addHeader(RowMutation.HINT, target.getAddress());
                    if (logger.isDebugEnabled())
                        logger.debug("insert writing key " + rm.key() + " to " + hintedMessage.getMessageId() + "@" + hintedTarget + " for " + target);
                    MessagingService.instance().sendOneWay(hintedMessage, hintedTarget);
                }
            }

            // wait for writes.  throws timeoutexception if necessary
            responseHandler.get();
        }
        catch (TimeoutException e)
        {
            throw new UnavailableException();
        }
        catch (IOException e)
        {
            throw new RuntimeException("error writing key " + rm.key(), e);
        }
        finally
        {
            writeStats.add(System.currentTimeMillis() - startTime);
        }
    }

    private static int determineBlockFor(int naturalTargets, int hintedTargets, int consistency_level)
    {
        assert naturalTargets >= 1;
        assert hintedTargets >= naturalTargets;

        int bootstrapTargets = hintedTargets - naturalTargets;
        int blockFor;
        if (consistency_level == ConsistencyLevel.ONE)
        {
            blockFor = 1 + bootstrapTargets;
        }
        else if (consistency_level == ConsistencyLevel.QUORUM)
        {
            blockFor = (naturalTargets / 2) + 1 + bootstrapTargets;
        }
        else if (consistency_level == ConsistencyLevel.DCQUORUM || consistency_level == ConsistencyLevel.DCQUORUMSYNC)
        {
            // TODO this is broken
            blockFor = naturalTargets;
        }
        else if (consistency_level == ConsistencyLevel.ALL)
        {
            blockFor = naturalTargets + bootstrapTargets;
        }
        else
        {
            throw new UnsupportedOperationException("invalid consistency level " + consistency_level);
        }
        return blockFor;
    }

    public static void insertBlocking(RowMutation rm) throws UnavailableException
    {
        insertBlocking(rm, ConsistencyLevel.QUORUM);
    }
    

    /**
     * Read the data from one replica.  If there is no reply, read the data from another.  In the event we get
     * the data we perform consistency checks and figure out if any repairs need to be done to the replicas.
     * @param commands a set of commands to perform reads
     * @return the row associated with command.key
     * @throws Exception
     */
    private static List<Row> weakReadRemote(List<ReadCommand> commands) throws IOException, UnavailableException
    {
        if (logger.isDebugEnabled())
            logger.debug("weakreadremote reading " + StringUtils.join(commands, ", "));

        List<Row> rows = new ArrayList<Row>();
        List<IAsyncResult> iars = new ArrayList<IAsyncResult>();
        int commandIndex = 0;

        for (ReadCommand command: commands)
        {
            InetAddress endPoint = StorageService.instance().findSuitableEndPoint(command.key);
            Message message = command.makeReadMessage();

            if (logger.isDebugEnabled())
                logger.debug("weakreadremote reading " + command + " from " + message.getMessageId() + "@" + endPoint);
            message.addHeader(ReadCommand.DO_REPAIR, ReadCommand.DO_REPAIR.getBytes());
            iars.add(MessagingService.instance().sendRR(message, endPoint));
        }

        for (IAsyncResult iar: iars)
        {
            byte[] body;
            try
            {
                body = iar.get(DatabaseDescriptor.getRpcTimeout(), TimeUnit.MILLISECONDS);
            }
            catch (TimeoutException e)
            {
                throw new RuntimeException("error reading key " + commands.get(commandIndex).key, e);
                // TODO retry to a different endpoint?
            }
            DataInputBuffer bufIn = new DataInputBuffer();
            bufIn.reset(body, body.length);
            ReadResponse response = ReadResponse.serializer().deserialize(bufIn);
            if (response.row() != null)
                rows.add(response.row());
            commandIndex++;
        }
        return rows;
    }

    /**
     * Performs the actual reading of a row out of the StorageService, fetching
     * a specific set of column names from a given column family.
     */
    public static List<Row> readProtocol(List<ReadCommand> commands, int consistency_level)
    throws IOException, TimeoutException, UnavailableException
    {
        long startTime = System.currentTimeMillis();

        List<Row> rows = new ArrayList<Row>();

        if (consistency_level == ConsistencyLevel.ONE)
        {
            List<ReadCommand> localCommands = new ArrayList<ReadCommand>();
            List<ReadCommand> remoteCommands = new ArrayList<ReadCommand>();

            for (ReadCommand command: commands)
            {
                List<InetAddress> endpoints = StorageService.instance().getNaturalEndpoints(command.key);
                boolean foundLocal = endpoints.contains(FBUtilities.getLocalAddress());
                //TODO: Throw InvalidRequest if we're in bootstrap mode?
                if (foundLocal && !StorageService.instance().isBootstrapMode())
                {
                    localCommands.add(command);
                }
                else
                {
                    remoteCommands.add(command);
                }
            }
            if (localCommands.size() > 0)
                rows.addAll(weakReadLocal(localCommands));

            if (remoteCommands.size() > 0)
                rows.addAll(weakReadRemote(remoteCommands));
        }
        else
        {
            assert consistency_level >= ConsistencyLevel.QUORUM;
            rows = strongRead(commands, consistency_level);
        }

        readStats.add(System.currentTimeMillis() - startTime);

        return rows;
    }

    /*
     * This function executes the read protocol.
        // 1. Get the N nodes from storage service where the data needs to be
        // replicated
        // 2. Construct a message for read\write
         * 3. Set one of the messages to get the data and the rest to get the digest
        // 4. SendRR ( to all the nodes above )
        // 5. Wait for a response from at least X nodes where X <= N and the data node
         * 6. If the digest matches return the data.
         * 7. else carry out read repair by getting data from all the nodes.
        // 5. return success
     */
    private static List<Row> strongRead(List<ReadCommand> commands, int consistency_level) throws IOException, TimeoutException, UnavailableException
    {
        List<QuorumResponseHandler<Row>> quorumResponseHandlers = new ArrayList<QuorumResponseHandler<Row>>();
        List<InetAddress[]> commandEndPoints = new ArrayList<InetAddress[]>();
        List<Row> rows = new ArrayList<Row>();

        int commandIndex = 0;

        for (ReadCommand command: commands)
        {
            // TODO: throw a thrift exception if we do not have N nodes
            assert !command.isDigestQuery();
            ReadCommand readMessageDigestOnly = command.copy();
            readMessageDigestOnly.setDigestQuery(true);
            Message message = command.makeReadMessage();
            Message messageDigestOnly = readMessageDigestOnly.makeReadMessage();

            InetAddress dataPoint = StorageService.instance().findSuitableEndPoint(command.key);
            List<InetAddress> endpointList = StorageService.instance().getNaturalEndpoints(command.key);

            InetAddress[] endPoints = new InetAddress[endpointList.size()];
            Message messages[] = new Message[endpointList.size()];
            /*
             * data-request message is sent to dataPoint, the node that will actually get
             * the data for us. The other replicas are only sent a digest query.
            */
            int n = 0;
            for (InetAddress endpoint : endpointList)
            {
                if (!FailureDetector.instance().isAlive(endpoint))
                    continue;
                Message m = endpoint.equals(dataPoint) ? message : messageDigestOnly;
                endPoints[n] = endpoint;
                messages[n++] = m;
                if (logger.isDebugEnabled())
                    logger.debug("strongread reading " + (m == message ? "data" : "digest") + " for " + command + " from " + m.getMessageId() + "@" + endpoint);
            }
            if (n < DatabaseDescriptor.getQuorum())
                throw new UnavailableException();
            QuorumResponseHandler<Row> quorumResponseHandler = new QuorumResponseHandler<Row>(DatabaseDescriptor.getQuorum(), new ReadResponseResolver());
            MessagingService.instance().sendRR(messages, endPoints, quorumResponseHandler);
            quorumResponseHandlers.add(quorumResponseHandler);
            commandEndPoints.add(endPoints);
        }

        for (QuorumResponseHandler<Row> quorumResponseHandler: quorumResponseHandlers)
        {
            Row row;
            ReadCommand command = commands.get(commandIndex);
            try
            {
                long startTime2 = System.currentTimeMillis();
                row = quorumResponseHandler.get();
                if (row != null)
                    rows.add(row);

                if (logger.isDebugEnabled())
                    logger.debug("quorumResponseHandler: " + (System.currentTimeMillis() - startTime2) + " ms.");
            }
            catch (DigestMismatchException ex)
            {
                if (DatabaseDescriptor.getConsistencyCheck())
                {
                    IResponseResolver<Row> readResponseResolverRepair = new ReadResponseResolver();
                    QuorumResponseHandler<Row> quorumResponseHandlerRepair = new QuorumResponseHandler<Row>(
                            DatabaseDescriptor.getQuorum(),
                            readResponseResolverRepair);
                    logger.info("DigestMismatchException: " + ex.getMessage());
                    Message messageRepair = command.makeReadMessage();
                    MessagingService.instance().sendRR(messageRepair, commandEndPoints.get(commandIndex), quorumResponseHandlerRepair);
                    try
                    {
                        row = quorumResponseHandlerRepair.get();
                        if (row != null)
                            rows.add(row);
                    }
                    catch (DigestMismatchException e)
                    {
                        // TODO should this be a thrift exception?
                        throw new RuntimeException("digest mismatch reading key " + command.key, e);
                    }
                }
            }
            commandIndex++;
        }

        return rows;
    }

    /*
    * This function executes the read protocol locally and should be used only if consistency is not a concern.
    * Read the data from the local disk and return if the row is NOT NULL. If the data is NULL do the read from
    * one of the other replicas (in the same data center if possible) till we get the data. In the event we get
    * the data we perform consistency checks and figure out if any repairs need to be done to the replicas.
    */
    private static List<Row> weakReadLocal(List<ReadCommand> commands)
    {
        List<Row> rows = new ArrayList<Row>();
        List<Future<Object>> futures = new ArrayList<Future<Object>>();

        for (ReadCommand command: commands)
        {
            Callable<Object> callable = new weakReadLocalCallable(command);
            futures.add(StageManager.getStage(StageManager.readStage_).execute(callable));
        }
        for (Future<Object> future : futures)
        {
            Row row;
            try
            {
                row = (Row) future.get();
            }
            catch (Exception e)
            {
                throw new RuntimeException(e);
            }
            rows.add(row);
        }
        return rows;
    }

    static Map<String, Collection<IColumn>> getRangeSlice(RangeSliceCommand rawCommand) throws IOException, UnavailableException
    {
        long startTime = System.currentTimeMillis();
        TokenMetadata tokenMetadata = StorageService.instance().getTokenMetadata();
        RangeSliceCommand command = rawCommand;

        InetAddress endPoint = StorageService.instance().findSuitableEndPoint(command.start_key);
        InetAddress startEndpoint = endPoint;
        InetAddress wrapEndpoint = tokenMetadata.getFirstEndpoint();

        TreeSet<Row> allRows = new TreeSet<Row>(rowComparator);
        do
        {

            Message message = command.getMessage();
            if (logger.isDebugEnabled())
                logger.debug("reading " + command + " from " + message.getMessageId() + "@" + endPoint);
            IAsyncResult iar = MessagingService.instance().sendRR(message, endPoint);
            byte[] responseBody;
            try
            {
                responseBody = iar.get(DatabaseDescriptor.getRpcTimeout(), TimeUnit.MILLISECONDS);
            }
            catch (TimeoutException ex)
            {
                throw new RuntimeException(ex);
            }
            RangeSliceReply reply = RangeSliceReply.read(responseBody);
            List<Row> rangeRows = new ArrayList<Row>(reply.rows);

            // combine these what what has been seen so far.
            if (rangeRows.size() > 0)
            {
                if (allRows.size() > 0)
                {
                    if (keyComparator.compare(rangeRows.get(rangeRows.size() - 1).key(), allRows.first().key()) <= 0)
                    {
                        // unlikely, but possible
                        if (rangeRows.get(rangeRows.size() - 1).equals(allRows.first().key()))
                        {
                            rangeRows.remove(rangeRows.size() - 1);
                        }
                        // put all from rangeRows into allRows.
                        allRows.addAll(rangeRows);
                    }
                    else if (keyComparator.compare(allRows.last().key(), rangeRows.get(0).key()) <= 0)
                    {
                        // common case. deal with simple start/end key overlaps
                        if (allRows.last().key().equals(rangeRows.get(0)))
                        {
                            allRows.remove(allRows.last().key());
                        }
                        allRows.addAll(rangeRows); // todo: check logic.
                    }
                    else
                    {
                        // deal with potential large overlap from scanning the first endpoint, which contains
                        // both the smallest and largest keys
                        allRows.addAll(rangeRows); // todo: check logic.
                    }
                }
                else
                    allRows.addAll(rangeRows); // todo: check logic.
            }

            if (allRows.size() >= rawCommand.max_keys || reply.rangeCompletedLocally)
                break;

            do
            {
                endPoint = tokenMetadata.getSuccessor(endPoint); // TODO move this into the Strategies & modify for RackAwareStrategy
            }
            while (!FailureDetector.instance().isAlive(endPoint));
            int maxResults = endPoint == wrapEndpoint ? rawCommand.max_keys : rawCommand.max_keys - allRows.size();
            command = new RangeSliceCommand(command, maxResults);
        }
        while (!endPoint.equals(startEndpoint));

        Map<String, Collection<IColumn>> results = new TreeMap<String, Collection<IColumn>>();
        for (Row row : allRows)
        {
            // for now, assume only one cf per row, since that is all we can specify in the Command.
            ColumnFamily cf = row.getColumnFamilies().iterator().next();
            results.put(row.key(),cf.getSortedColumns());
        }
        rangeStats.add(System.currentTimeMillis() - startTime);
        return results;
    }

    static List<String> getKeyRange(RangeCommand rawCommand) throws IOException, UnavailableException
    {
        long startTime = System.currentTimeMillis();
        TokenMetadata tokenMetadata = StorageService.instance().getTokenMetadata();
        List<String> allKeys = new ArrayList<String>();
        RangeCommand command = rawCommand;

        InetAddress endPoint = StorageService.instance().findSuitableEndPoint(command.startWith);
        InetAddress startEndpoint = endPoint;
        InetAddress wrapEndpoint = tokenMetadata.getFirstEndpoint();

        do
        {
            Message message = command.getMessage();
            if (logger.isDebugEnabled())
                logger.debug("reading " + command + " from " + message.getMessageId() + "@" + endPoint);
            IAsyncResult iar = MessagingService.instance().sendRR(message, endPoint);

            // read response
            byte[] responseBody;
            try
            {
                responseBody = iar.get(DatabaseDescriptor.getRpcTimeout(), TimeUnit.MILLISECONDS);
            }
            catch (TimeoutException e)
            {
                throw new RuntimeException(e);
            }
            RangeReply rangeReply = RangeReply.read(responseBody);
            List<String> rangeKeys = rangeReply.keys;

            // combine keys from most recent response with the others seen so far
            if (rangeKeys.size() > 0)
            {
                if (allKeys.size() > 0)
                {
                    if (keyComparator.compare(rangeKeys.get(rangeKeys.size() - 1), allKeys.get(0)) <= 0)
                    {
                        // unlikely, but possible
                        if (rangeKeys.get(rangeKeys.size() - 1).equals(allKeys.get(0)))
                        {
                            rangeKeys.remove(rangeKeys.size() - 1);
                        }
                        rangeKeys.addAll(allKeys);
                        allKeys = rangeKeys;
                    }
                    else if (keyComparator.compare(allKeys.get(allKeys.size() - 1), rangeKeys.get(0)) <= 0)
                    {
                        // common case. deal with simple start/end key overlaps
                        if (allKeys.get(allKeys.size() - 1).equals(rangeKeys.get(0)))
                        {
                            allKeys.remove(allKeys.size() - 1);
                        }
                        allKeys.addAll(rangeKeys);
                    }
                    else
                    {
                        // deal with potential large overlap from scanning the first endpoint, which contains
                        // both the smallest and largest keys
                        HashSet<String> keys = new HashSet<String>(allKeys);
                        keys.addAll(rangeKeys);
                        allKeys = new ArrayList<String>(keys);
                        Collections.sort(allKeys);
                    }
                }
                else
                {
                    allKeys = rangeKeys;
                }
            }

            if (allKeys.size() >= rawCommand.maxResults || rangeReply.rangeCompletedLocally)
            {
                break;
            }

            // set up the next query --
            // it's tempting to try to optimize this by starting with the last key seen for the next node,
            // but that won't work when you have a replication factor of more than one--any node, not just
            // the one holding the keys where the range wraps, could include both the smallest keys, and the largest,
            // so starting with the largest in our scan of the next node means we'd never see keys from the middle.
            do
            {
                endPoint = tokenMetadata.getSuccessor(endPoint); // TODO move this into the Strategies & modify for RackAwareStrategy
            } while (!FailureDetector.instance().isAlive(endPoint));
            int maxResults = endPoint.equals(wrapEndpoint) ? rawCommand.maxResults : rawCommand.maxResults - allKeys.size();
            command = new RangeCommand(command.table, command.columnFamily, command.startWith, command.stopAt, maxResults);
        } while (!endPoint.equals(startEndpoint));

        rangeStats.add(System.currentTimeMillis() - startTime);
        return (allKeys.size() > rawCommand.maxResults)
               ? allKeys.subList(0, rawCommand.maxResults)
               : allKeys;
    }

    public double getReadLatency()
    {
        return readStats.mean();
    }

    public double getRangeLatency()
    {
        return rangeStats.mean();
    }

    public double getWriteLatency()
    {
        return writeStats.mean();
    }

    public int getReadOperations()
    {
        return readStats.size();
    }

    public int getRangeOperations()
    {
        return rangeStats.size();
    }

    public int getWriteOperations()
    {
        return writeStats.size();
    }

    static class weakReadLocalCallable implements Callable<Object>
    {
        private ReadCommand command;

        weakReadLocalCallable(ReadCommand command)
        {
            this.command = command;
        }

        public Object call() throws IOException
        {
            List<InetAddress> endpoints = StorageService.instance().getLiveNaturalEndpoints(command.key);
            /* Remove the local storage endpoint from the list. */
            endpoints.remove(FBUtilities.getLocalAddress());
            // TODO: throw a thrift exception if we do not have N nodes

            if (logger.isDebugEnabled())
                logger.debug("weakreadlocal reading " + command);

            Table table = Table.open(command.table);
            Row row = command.getRow(table);

            // Do the consistency checks in the background and return the non NULL row
            if (endpoints.size() > 0 && DatabaseDescriptor.getConsistencyCheck())
                StorageService.instance().doConsistencyCheck(row, endpoints, command);

            return row;
        }
    }
}
