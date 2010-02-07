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

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
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
import java.net.InetAddress;

import org.apache.cassandra.dht.*;
import org.apache.cassandra.net.IAsyncResult;
import org.apache.cassandra.net.Message;
import org.apache.cassandra.net.MessagingService;
import org.apache.cassandra.utils.LatencyTracker;
import org.apache.cassandra.thrift.ConsistencyLevel;
import org.apache.cassandra.thrift.UnavailableException;
import org.apache.cassandra.utils.FBUtilities;
import org.apache.cassandra.utils.Pair;
import org.apache.cassandra.utils.WrappedRunnable;
import org.apache.cassandra.locator.TokenMetadata;
import org.apache.cassandra.concurrent.StageManager;

import org.apache.log4j.Logger;

import javax.management.MBeanServer;
import javax.management.ObjectName;


public class StorageProxy implements StorageProxyMBean
{
    private static final Logger logger = Logger.getLogger(StorageProxy.class);

    // mbean stuff
    private static final LatencyTracker readStats = new LatencyTracker();
    private static final LatencyTracker rangeStats = new LatencyTracker();
    private static final LatencyTracker writeStats = new LatencyTracker();

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

    private static final Comparator<String> keyComparator = new Comparator<String>()
    {
        public int compare(String o1, String o2)
        {
            IPartitioner<?> p = StorageService.getPartitioner();
            return p.decorateKey(o1).compareTo(p.decorateKey(o2));
        }
    };

    /**
     * Use this method to have these RowMutations applied
     * across all replicas. This method will take care
     * of the possibility of a replica being down and hint
     * the data across to some other replica.
     *
     * This is the ZERO consistency level. We do not wait for replies.
     *
     * @param mutations the mutations to be applied across the replicas
    */
    public static void mutate(List<RowMutation> mutations)
    {
        long startTime = System.nanoTime();
        try
        {
            for (final RowMutation rm: mutations)
            {
                try
                {
                    List<InetAddress> naturalEndpoints = StorageService.instance.getNaturalEndpoints(rm.getTable(), rm.key());
                    Map<InetAddress, InetAddress> endpointMap = StorageService.instance.getHintedEndpointMap(rm.getTable(), rm.key(), naturalEndpoints);
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
                                Runnable runnable = new WrappedRunnable()
                                {
                                    public void runMayThrow() throws IOException
                                    {
                                        rm.apply();
                                    }
                                };
                                StageManager.getStage(StageManager.MUTATION_STAGE).execute(runnable);
                            }
                            else
                            {
                                if (unhintedMessage == null)
                                    unhintedMessage = rm.makeRowMutationMessage();
                                if (logger.isDebugEnabled())
                                    logger.debug("insert writing key " + rm.key() + " to " + unhintedMessage.getMessageId() + "@" + target);
                                MessagingService.instance.sendOneWay(unhintedMessage, target);
                            }
                        }
                        else
                        {
                            Message hintedMessage = rm.makeRowMutationMessage();
                            hintedMessage.addHeader(RowMutation.HINT, target.getAddress());
                            if (logger.isDebugEnabled())
                                logger.debug("insert writing key " + rm.key() + " to " + hintedMessage.getMessageId() + "@" + hintedTarget + " for " + target);
                            MessagingService.instance.sendOneWay(hintedMessage, hintedTarget);
                        }
                    }
                }
                catch (IOException e)
                {
                    throw new RuntimeException("error inserting key " + rm.key(), e);
                }
            }
        }
        finally
        {
            writeStats.addNano(System.nanoTime() - startTime);
        }
    }
    
    public static void mutateBlocking(List<RowMutation> mutations, ConsistencyLevel consistency_level) throws UnavailableException, TimeoutException
    {
        long startTime = System.nanoTime();
        ArrayList<WriteResponseHandler> responseHandlers = new ArrayList<WriteResponseHandler>();

        RowMutation mostRecentRowMutation = null;
        try
        {
            for (RowMutation rm: mutations)
            {
                mostRecentRowMutation = rm;
                List<InetAddress> naturalEndpoints = StorageService.instance.getNaturalEndpoints(rm.getTable(), rm.key());
                Map<InetAddress, InetAddress> endpointMap = StorageService.instance.getHintedEndpointMap(rm.getTable(), rm.key(), naturalEndpoints);
                int blockFor = determineBlockFor(naturalEndpoints.size(), endpointMap.size(), consistency_level);
                
                // avoid starting a write we know can't achieve the required consistency
                assureSufficientLiveNodes(endpointMap, blockFor, consistency_level);
                
                // send out the writes, as in insert() above, but this time with a callback that tracks responses
                final WriteResponseHandler responseHandler = StorageService.instance.getWriteResponseHandler(blockFor, consistency_level, rm.getTable());
                responseHandlers.add(responseHandler);
                Message unhintedMessage = null;
                for (Map.Entry<InetAddress, InetAddress> entry : endpointMap.entrySet())
                {
                    InetAddress naturalTarget = entry.getKey();
                    InetAddress maybeHintedTarget = entry.getValue();
    
                    if (naturalTarget.equals(maybeHintedTarget))
                    {
                        // not hinted
                        if (naturalTarget.equals(FBUtilities.getLocalAddress()))
                        {
                            insertLocalMessage(rm, responseHandler);
                        }
                        else
                        {
                            // belongs on a different server.  send it there.
                            if (unhintedMessage == null)
                            {
                                unhintedMessage = rm.makeRowMutationMessage();
                                MessagingService.instance.addCallback(responseHandler, unhintedMessage.getMessageId());
                            }
                            if (logger.isDebugEnabled())
                                logger.debug("insert writing key " + rm.key() + " to " + unhintedMessage.getMessageId() + "@" + naturalTarget);
                            MessagingService.instance.sendOneWay(unhintedMessage, naturalTarget);
                        }
                    }
                    else
                    {
                        Message hintedMessage = rm.makeRowMutationMessage();
                        hintedMessage.addHeader(RowMutation.HINT, naturalTarget.getAddress());
                        // (hints are part of the callback and count towards consistency only under CL.ANY
                        if (consistency_level == ConsistencyLevel.ANY)
                            MessagingService.instance.addCallback(responseHandler, hintedMessage.getMessageId());
                        if (logger.isDebugEnabled())
                            logger.debug("insert writing key " + rm.key() + " to " + hintedMessage.getMessageId() + "@" + maybeHintedTarget + " for " + naturalTarget);
                        MessagingService.instance.sendOneWay(hintedMessage, maybeHintedTarget);
                    }
                }
            }
            // wait for writes.  throws timeoutexception if necessary
            for( WriteResponseHandler responseHandler : responseHandlers )
            {
                responseHandler.get();
            }
        }
        catch (IOException e)
        {
            if (mostRecentRowMutation == null)
                throw new RuntimeException("no mutations were seen but found an error during write anyway", e);
            else
                throw new RuntimeException("error writing key " + mostRecentRowMutation.key(), e);
        }
        finally
        {
            writeStats.addNano(System.nanoTime() - startTime);
        }

    }

    private static void assureSufficientLiveNodes(Map<InetAddress, InetAddress> endpointMap, int blockFor, ConsistencyLevel consistencyLevel)
            throws UnavailableException
    {
        if (consistencyLevel == ConsistencyLevel.ANY)
        {
            // ensure there are blockFor distinct living nodes (hints are ok).
            if (new HashSet(endpointMap.values()).size() < blockFor)
                throw new UnavailableException();
        }
        else
        {
            // only count live + unhinted nodes.
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
        }
    }

    private static void insertLocalMessage(final RowMutation rm, final WriteResponseHandler responseHandler)
    {
        if (logger.isDebugEnabled())
            logger.debug("insert writing local key " + rm.key());
        Runnable runnable = new WrappedRunnable()
        {
            public void runMayThrow() throws IOException
            {
                rm.apply();
                responseHandler.localResponse();
            }
        };
        StageManager.getStage(StageManager.MUTATION_STAGE).execute(runnable);
    }

    private static int determineBlockFor(int naturalTargets, int hintedTargets, ConsistencyLevel consistency_level)
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
        else if (consistency_level == ConsistencyLevel.ANY)
        {
            blockFor = 1;
        }
        else
        {
            throw new UnsupportedOperationException("invalid consistency level " + consistency_level);
        }
        return blockFor;
    }    

    /**
     * Read the data from one replica.  When we get
     * the data we perform consistency checks and figure out if any repairs need to be done to the replicas.
     * @param commands a set of commands to perform reads
     * @return the row associated with command.key
     * @throws Exception
     */
    private static List<Row> weakReadRemote(List<ReadCommand> commands) throws IOException, UnavailableException, TimeoutException
    {
        if (logger.isDebugEnabled())
            logger.debug("weakreadremote reading " + StringUtils.join(commands, ", "));

        List<Row> rows = new ArrayList<Row>();
        List<IAsyncResult> iars = new ArrayList<IAsyncResult>();

        for (ReadCommand command: commands)
        {
            InetAddress endPoint = StorageService.instance.findSuitableEndPoint(command.table, command.key);
            Message message = command.makeReadMessage();

            if (logger.isDebugEnabled())
                logger.debug("weakreadremote reading " + command + " from " + message.getMessageId() + "@" + endPoint);
            message.addHeader(ReadCommand.DO_REPAIR, ReadCommand.DO_REPAIR.getBytes());
            iars.add(MessagingService.instance.sendRR(message, endPoint));
        }

        for (IAsyncResult iar: iars)
        {
            byte[] body;
            body = iar.get(DatabaseDescriptor.getRpcTimeout(), TimeUnit.MILLISECONDS);
            ByteArrayInputStream bufIn = new ByteArrayInputStream(body);
            ReadResponse response = ReadResponse.serializer().deserialize(new DataInputStream(bufIn));
            if (response.row() != null)
                rows.add(response.row());
        }
        return rows;
    }

    /**
     * Performs the actual reading of a row out of the StorageService, fetching
     * a specific set of column names from a given column family.
     */
    public static List<Row> readProtocol(List<ReadCommand> commands, ConsistencyLevel consistency_level)
            throws IOException, UnavailableException, TimeoutException
    {
        long startTime = System.nanoTime();

        List<Row> rows = new ArrayList<Row>();

        if (consistency_level == ConsistencyLevel.ONE)
        {
            List<ReadCommand> localCommands = new ArrayList<ReadCommand>();
            List<ReadCommand> remoteCommands = new ArrayList<ReadCommand>();

            for (ReadCommand command: commands)
            {
                List<InetAddress> endpoints = StorageService.instance.getNaturalEndpoints(command.table, command.key);
                boolean foundLocal = endpoints.contains(FBUtilities.getLocalAddress());
                //TODO: Throw InvalidRequest if we're in bootstrap mode?
                if (foundLocal && !StorageService.instance.isBootstrapMode())
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
            assert consistency_level.getValue() >= ConsistencyLevel.QUORUM.getValue();
            rows = strongRead(commands, consistency_level);
        }

        readStats.addNano(System.nanoTime() - startTime);

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
    private static List<Row> strongRead(List<ReadCommand> commands, ConsistencyLevel consistency_level) throws IOException, UnavailableException, TimeoutException
    {
        List<QuorumResponseHandler<Row>> quorumResponseHandlers = new ArrayList<QuorumResponseHandler<Row>>();
        List<InetAddress[]> commandEndPoints = new ArrayList<InetAddress[]>();
        List<Row> rows = new ArrayList<Row>();

        int commandIndex = 0;

        for (ReadCommand command: commands)
        {
            assert !command.isDigestQuery();
            ReadCommand readMessageDigestOnly = command.copy();
            readMessageDigestOnly.setDigestQuery(true);
            Message message = command.makeReadMessage();
            Message messageDigestOnly = readMessageDigestOnly.makeReadMessage();

            InetAddress dataPoint = StorageService.instance.findSuitableEndPoint(command.table, command.key);
            List<InetAddress> endpointList = StorageService.instance.getLiveNaturalEndpoints(command.table, command.key);
            final String table = command.table;
            int responseCount = determineBlockFor(DatabaseDescriptor.getReplicationFactor(table), DatabaseDescriptor.getReplicationFactor(table), consistency_level);
            if (endpointList.size() < responseCount)
                throw new UnavailableException();

            InetAddress[] endPoints = new InetAddress[endpointList.size()];
            Message messages[] = new Message[endpointList.size()];
            // data-request message is sent to dataPoint, the node that will actually get
            // the data for us. The other replicas are only sent a digest query.
            int n = 0;
            for (InetAddress endpoint : endpointList)
            {
                Message m = endpoint.equals(dataPoint) ? message : messageDigestOnly;
                endPoints[n] = endpoint;
                messages[n++] = m;
                if (logger.isDebugEnabled())
                    logger.debug("strongread reading " + (m == message ? "data" : "digest") + " for " + command + " from " + m.getMessageId() + "@" + endpoint);
            }
            QuorumResponseHandler<Row> quorumResponseHandler = new QuorumResponseHandler<Row>(DatabaseDescriptor.getQuorum(command.table), new ReadResponseResolver(command.table, responseCount));
            MessagingService.instance.sendRR(messages, endPoints, quorumResponseHandler);
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
                    IResponseResolver<Row> readResponseResolverRepair = new ReadResponseResolver(command.table, DatabaseDescriptor.getQuorum(command.table));
                    QuorumResponseHandler<Row> quorumResponseHandlerRepair = new QuorumResponseHandler<Row>(
                            DatabaseDescriptor.getQuorum(command.table),
                            readResponseResolverRepair);
                    logger.info("DigestMismatchException: " + ex.getMessage());
                    Message messageRepair = command.makeReadMessage();
                    MessagingService.instance.sendRR(messageRepair, commandEndPoints.get(commandIndex), quorumResponseHandlerRepair);
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
    * This function executes the read protocol locally.  Consistency checks are performed in the background.
    */
    private static List<Row> weakReadLocal(List<ReadCommand> commands)
    {
        List<Row> rows = new ArrayList<Row>();
        List<Future<Object>> futures = new ArrayList<Future<Object>>();

        for (ReadCommand command: commands)
        {
            Callable<Object> callable = new weakReadLocalCallable(command);
            futures.add(StageManager.getStage(StageManager.READ_STAGE).submit(callable));
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

    public static List<Pair<String, ColumnFamily>> getRangeSlice(RangeSliceCommand command, ConsistencyLevel consistency_level)
    throws IOException, UnavailableException, TimeoutException
    {
        long startTime = System.nanoTime();
        TokenMetadata tokenMetadata = StorageService.instance.getTokenMetadata();
        Iterator<Token> iter = TokenMetadata.ringIterator(tokenMetadata.sortedTokens(), command.range.left);

        final String table = command.keyspace;
        int responseCount = determineBlockFor(DatabaseDescriptor.getReplicationFactor(table), DatabaseDescriptor.getReplicationFactor(table), consistency_level);

        // starting with the range containing the start key, scan until either we have enough results,
        // or the node scan reports that it was done (i.e., encountered a key outside the desired range).
        Map<String, ColumnFamily> rows = new HashMap<String, ColumnFamily>(command.max_keys);
        outer:
        while (iter.hasNext())
        {
            Token currentToken = iter.next();
            Range currentRange = new Range(tokenMetadata.getPredecessor(currentToken), currentToken);
            List<InetAddress> endpoints = StorageService.instance.getLiveNaturalEndpoints(command.keyspace, currentToken);
            if (endpoints.size() < responseCount)
                throw new UnavailableException();
            DatabaseDescriptor.getEndPointSnitch(command.keyspace).sortByProximity(FBUtilities.getLocalAddress(), endpoints);

            // make sure we only get keys from the current range (and not other replicas that might be on the nodes).
            // usually this will be only one range, but sometimes the intersection of a wrapping Range with a non-wrapping
            // is two disjoint, non-wrapping Ranges separated by a gap.
            List<AbstractBounds> restricted = command.range.restrictTo(currentRange);

            for (AbstractBounds range : restricted)
            {
                RangeSliceCommand c2 = new RangeSliceCommand(command.keyspace, command.column_family, command.super_column, command.predicate, range, command.max_keys);
                Message message = c2.getMessage();

                // collect replies and resolve according to consistency level
                RangeSliceResponseResolver resolver = new RangeSliceResponseResolver(command.keyspace, currentRange, endpoints);
                QuorumResponseHandler<Map<String, ColumnFamily>> handler = new QuorumResponseHandler<Map<String, ColumnFamily>>(responseCount, resolver);

                Iterator<InetAddress> endpointIter = endpoints.iterator();
                for (int i = 0; i < responseCount; i++)
                {
                    InetAddress endpoint = endpointIter.next();
                    MessagingService.instance.sendRR(message, endpoint, handler);
                    if (logger.isDebugEnabled())
                        logger.debug("reading " + c2 + " for " + range + " from " + message.getMessageId() + "@" + endpoint);
                }
                // TODO read repair on remaining replicas?

                // if we're done, great, otherwise, move to the next range
                try
                {
                    rows.putAll(handler.get());
                }
                catch (DigestMismatchException e)
                {
                    throw new AssertionError(e); // no digests in range slices yet
                }
                if (rows.size() >= command.max_keys || resolver.completed())
                    break outer;
            }
        }

        List<Pair<String, ColumnFamily>> results = new ArrayList<Pair<String, ColumnFamily>>(rows.size());
        for (Map.Entry<String, ColumnFamily> entry : rows.entrySet())
        {
            ColumnFamily cf = entry.getValue();
            results.add(new Pair<String, ColumnFamily>(entry.getKey(), cf));
        }
        Collections.sort(results, new Comparator<Pair<String, ColumnFamily>>()
        {
            public int compare(Pair<String, ColumnFamily> o1, Pair<String, ColumnFamily> o2)
            {
                return keyComparator.compare(o1.left, o2.left);                
            }
        });
        rangeStats.addNano(System.nanoTime() - startTime);
        return results;
    }

    public long getReadOperations()
    {
        return readStats.getOpCount();
    }

    public long getTotalReadLatencyMicros()
    {
        return readStats.getTotalLatencyMicros();
    }

    public double getRecentReadLatencyMicros()
    {
        return readStats.getRecentLatencyMicros();
    }

    public long getRangeOperations()
    {
        return rangeStats.getOpCount();
    }

    public long getTotalRangeLatencyMicros()
    {
        return rangeStats.getTotalLatencyMicros();
    }

    public double getRecentRangeLatencyMicros()
    {
        return rangeStats.getRecentLatencyMicros();
    }

    public long getWriteOperations()
    {
        return writeStats.getOpCount();
    }

    public long getTotalWriteLatencyMicros()
    {
        return writeStats.getTotalLatencyMicros();
    }

    public double getRecentWriteLatencyMicros()
    {
        return writeStats.getRecentLatencyMicros();
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
            List<InetAddress> endpoints = StorageService.instance.getLiveNaturalEndpoints(command.table, command.key);
            /* Remove the local storage endpoint from the list. */
            endpoints.remove(FBUtilities.getLocalAddress());

            if (logger.isDebugEnabled())
                logger.debug("weakreadlocal reading " + command);

            Table table = Table.open(command.table);
            Row row = command.getRow(table);

            // Do the consistency checks in the background and return the non NULL row
            if (endpoints.size() > 0 && DatabaseDescriptor.getConsistencyCheck())
                StorageService.instance.doConsistencyCheck(row, endpoints, command);

            return row;
        }
    }
}
