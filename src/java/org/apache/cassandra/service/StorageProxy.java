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
package org.apache.cassandra.service;

import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.net.InetAddress;
import java.nio.ByteBuffer;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import javax.management.MBeanServer;
import javax.management.ObjectName;

import com.google.common.base.Function;
import com.google.common.collect.*;
import com.google.common.util.concurrent.Uninterruptibles;
import org.apache.commons.lang.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.apache.cassandra.concurrent.Stage;
import org.apache.cassandra.concurrent.StageManager;
import org.apache.cassandra.config.CFMetaData;
import org.apache.cassandra.config.DatabaseDescriptor;
import org.apache.cassandra.config.Schema;
import org.apache.cassandra.db.*;
import org.apache.cassandra.db.Table;
import org.apache.cassandra.db.filter.ColumnSlice;
import org.apache.cassandra.db.filter.IDiskAtomFilter;
import org.apache.cassandra.db.filter.NamesQueryFilter;
import org.apache.cassandra.db.filter.SliceQueryFilter;
import org.apache.cassandra.db.marshal.UUIDType;
import org.apache.cassandra.dht.AbstractBounds;
import org.apache.cassandra.dht.Bounds;
import org.apache.cassandra.dht.RingPosition;
import org.apache.cassandra.dht.Token;
import org.apache.cassandra.exceptions.*;
import org.apache.cassandra.gms.FailureDetector;
import org.apache.cassandra.gms.Gossiper;
import org.apache.cassandra.io.util.DataOutputBuffer;
import org.apache.cassandra.locator.AbstractReplicationStrategy;
import org.apache.cassandra.locator.IEndpointSnitch;
import org.apache.cassandra.locator.TokenMetadata;
import org.apache.cassandra.metrics.ClientRequestMetrics;
import org.apache.cassandra.net.*;
import org.apache.cassandra.service.paxos.*;
import org.apache.cassandra.tracing.Tracing;
import org.apache.cassandra.triggers.TriggerExecutor;
import org.apache.cassandra.utils.*;

public class StorageProxy implements StorageProxyMBean
{
    public static final String MBEAN_NAME = "org.apache.cassandra.db:type=StorageProxy";
    private static final Logger logger = LoggerFactory.getLogger(StorageProxy.class);
    static final boolean OPTIMIZE_LOCAL_REQUESTS = true; // set to false to test messagingservice path on single node

    public static final String UNREACHABLE = "UNREACHABLE";

    private static final WritePerformer standardWritePerformer;
    private static final WritePerformer counterWritePerformer;
    private static final WritePerformer counterWriteOnCoordinatorPerformer;

    public static final StorageProxy instance = new StorageProxy();

    private static volatile boolean hintedHandoffEnabled = DatabaseDescriptor.hintedHandoffEnabled();
    private static volatile int maxHintWindow = DatabaseDescriptor.getMaxHintWindow();
    private static volatile int maxHintsInProgress = 1024 * FBUtilities.getAvailableProcessors();
    private static final AtomicInteger totalHintsInProgress = new AtomicInteger();
    private static final Map<InetAddress, AtomicInteger> hintsInProgress = new MapMaker().concurrencyLevel(1).makeComputingMap(new Function<InetAddress, AtomicInteger>()
    {
        public AtomicInteger apply(InetAddress inetAddress)
        {
            return new AtomicInteger(0);
        }
    });
    private static final AtomicLong totalHints = new AtomicLong();
    private static final ClientRequestMetrics readMetrics = new ClientRequestMetrics("Read");
    private static final ClientRequestMetrics rangeMetrics = new ClientRequestMetrics("RangeSlice");
    private static final ClientRequestMetrics writeMetrics = new ClientRequestMetrics("Write");

    private StorageProxy() {}

    static
    {
        MBeanServer mbs = ManagementFactory.getPlatformMBeanServer();
        try
        {
            mbs.registerMBean(new StorageProxy(), new ObjectName(MBEAN_NAME));
        }
        catch (Exception e)
        {
            throw new RuntimeException(e);
        }

        standardWritePerformer = new WritePerformer()
        {
            public void apply(IMutation mutation,
                              Iterable<InetAddress> targets,
                              AbstractWriteResponseHandler responseHandler,
                              String localDataCenter,
                              ConsistencyLevel consistency_level)
            throws OverloadedException
            {
                assert mutation instanceof RowMutation;
                sendToHintedEndpoints((RowMutation) mutation, targets, responseHandler, localDataCenter, consistency_level);
            }
        };

        /*
         * We execute counter writes in 2 places: either directly in the coordinator node if it is a replica, or
         * in CounterMutationVerbHandler on a replica othewise. The write must be executed on the MUTATION stage
         * but on the latter case, the verb handler already run on the MUTATION stage, so we must not execute the
         * underlying on the stage otherwise we risk a deadlock. Hence two different performer.
         */
        counterWritePerformer = new WritePerformer()
        {
            public void apply(IMutation mutation,
                              Iterable<InetAddress> targets,
                              AbstractWriteResponseHandler responseHandler,
                              String localDataCenter,
                              ConsistencyLevel consistency_level)
            {
                if (logger.isTraceEnabled())
                    logger.trace("insert writing local & replicate " + mutation.toString(true));

                Runnable runnable = counterWriteTask(mutation, targets, responseHandler, localDataCenter, consistency_level);
                runnable.run();
            }
        };

        counterWriteOnCoordinatorPerformer = new WritePerformer()
        {
            public void apply(IMutation mutation,
                              Iterable<InetAddress> targets,
                              AbstractWriteResponseHandler responseHandler,
                              String localDataCenter,
                              ConsistencyLevel consistency_level)
            {
                if (logger.isTraceEnabled())
                    logger.trace("insert writing local & replicate " + mutation.toString(true));

                Runnable runnable = counterWriteTask(mutation, targets, responseHandler, localDataCenter, consistency_level);
                StageManager.getStage(Stage.MUTATION).execute(runnable);
            }
        };
    }

    /**
     * Apply @param updates if and only if the current values in the row for @param key
     * match the ones given by @param old.  The algorithm is "raw" Paxos: that is, Paxos
     * minus leader election -- any node in the cluster may propose changes for any row,
     * which (that is, the row) is the unit of values being proposed, not single columns.
     *
     * The Paxos cohort is only the replicas for the given key, not the entire cluster.
     * So we expect performance to be reasonable, but CAS is still intended to be used
     * "when you really need it," not for all your updates.
     *
     * There are three phases to Paxos:
     *  1. Prepare: the coordinator generates a ballot (timeUUID in our case) and asks replicas to (a) promise
     *     not to accept updates from older ballots and (b) tell us about the most recent update it has already
     *     accepted.
     *  2. Accept: if a majority of replicas reply, the coordinator asks replicas to accept the value of the
     *     highest proposal ballot it heard about, or a new value if no in-progress proposals were reported.
     *  3. Commit (Learn): if a majority of replicas acknowledge the accept request, we can commit the new
     *     value.
     *
     *  Commit procedure is not covered in "Paxos Made Simple," and only briefly mentioned in "Paxos Made Live,"
     *  so here is our approach:
     *   3a. The coordinator sends a commit message to all replicas with the ballot and value.
     *   3b. Because of 1-2, this will be the highest-seen commit ballot.  The replicas will note that,
     *       and send it with subsequent promise replies.  This allows us to discard acceptance records
     *       for successfully committed replicas, without allowing incomplete proposals to commit erroneously
     *       later on.
     *
     *  Note that since we are performing a CAS rather than a simple update, we perform a read (of committed
     *  values) between the prepare and accept phases.  This gives us a slightly longer window for another
     *  coordinator to come along and trump our own promise with a newer one but is otherwise safe.
     *
     * @return true if the operation succeeds in updating the row
     */
    public static boolean cas(String table, String cfName, ByteBuffer key, ColumnFamily expected, ColumnFamily updates, ConsistencyLevel consistencyLevel)
    throws UnavailableException, IsBootstrappingException, ReadTimeoutException, WriteTimeoutException, InvalidRequestException
    {
        consistencyLevel.validateForCas(table);

        CFMetaData metadata = Schema.instance.getCFMetaData(table, cfName);

        long start = System.nanoTime();
        long timeout = TimeUnit.MILLISECONDS.toNanos(DatabaseDescriptor.getCasContentionTimeout());
        while (System.nanoTime() - start < timeout)
        {
            // for simplicity, we'll do a single liveness check at the start of each attempt
            Pair<List<InetAddress>, Integer> p = getPaxosParticipants(table, key);
            List<InetAddress> liveEndpoints = p.left;
            int requiredParticipants = p.right;

            UUID ballot = beginAndRepairPaxos(key, metadata, liveEndpoints, requiredParticipants);
            if (ballot == null)
                continue;

            // read the current value and compare with expected
            Tracing.trace("Reading existing values for CAS precondition");
            ReadCommand readCommand = expected == null
                                    ? new SliceFromReadCommand(table, key, cfName, new SliceQueryFilter(ByteBufferUtil.EMPTY_BYTE_BUFFER, ByteBufferUtil.EMPTY_BYTE_BUFFER, false, 1))
                                    : new SliceByNamesReadCommand(table, key, cfName, new NamesQueryFilter(ImmutableSortedSet.copyOf(expected.getColumnNames())));
            List<Row> rows = read(Arrays.asList(readCommand), ConsistencyLevel.QUORUM);
            ColumnFamily current = rows.get(0).cf;
            if (!casApplies(expected, current))
            {
                Tracing.trace("CAS precondition {} does not match current values {}", expected, current);
                return false;
            }

            // finish the paxos round w/ the desired updates
            // TODO turn null updates into delete?
            Commit proposal = Commit.newProposal(key, ballot, updates);
            Tracing.trace("CAS precondition is met; proposing client-requested updates for {}", ballot);
            if (proposePaxos(proposal, liveEndpoints, requiredParticipants))
            {
                if (consistencyLevel == ConsistencyLevel.SERIAL)
                    sendCommit(proposal, liveEndpoints);
                else
                    commitPaxos(proposal, consistencyLevel);
                Tracing.trace("CAS successful");
                return true;
            }

            Tracing.trace("Paxos proposal not accepted (pre-empted by a higher ballot)");
            Uninterruptibles.sleepUninterruptibly(FBUtilities.threadLocalRandom().nextInt(100), TimeUnit.MILLISECONDS);
            // continue to retry
        }

        throw new WriteTimeoutException(WriteType.CAS, ConsistencyLevel.SERIAL, -1, -1);
    }

    private static boolean hasLiveColumns(ColumnFamily cf)
    {
        return cf != null && !cf.hasOnlyTombstones();
    }

    private static boolean casApplies(ColumnFamily expected, ColumnFamily current)
    {
        if (!hasLiveColumns(expected))
            return !hasLiveColumns(current);
        else if (!hasLiveColumns(current))
            return false;

        // current has been built from expected, so we know that it can't have columns
        // that excepted don't have. So we just check that for each columns in expected:
        //   - if it is a tombstone, whether current has no column or a tombstone;
        //   - otherwise, that current has a live column with the same value.
        for (Column e : expected)
        {
            Column c = current.getColumn(e.name());
            if (e.isLive())
            {
                if (!(c != null && c.isLive() && c.value().equals(e.value())))
                    return false;
            }
            else
            {
                if (c != null && c.isLive())
                    return false;
            }
        }
        return true;
    }

    private static Pair<List<InetAddress>, Integer> getPaxosParticipants(String table, ByteBuffer key) throws UnavailableException
    {
        Token tk = StorageService.getPartitioner().getToken(key);
        List<InetAddress> naturalEndpoints = StorageService.instance.getNaturalEndpoints(table, tk);
        Collection<InetAddress> pendingEndpoints = StorageService.instance.getTokenMetadata().pendingEndpointsFor(tk, table);
        int requiredParticipants = pendingEndpoints.size() + 1 + naturalEndpoints.size() / 2; // See CASSANDRA-833
        List<InetAddress> liveEndpoints = ImmutableList.copyOf(Iterables.filter(Iterables.concat(naturalEndpoints, pendingEndpoints), IAsyncCallback.isAlive));
        if (liveEndpoints.size() < requiredParticipants)
            throw new UnavailableException(ConsistencyLevel.SERIAL, requiredParticipants, liveEndpoints.size());
        return Pair.create(liveEndpoints, requiredParticipants);
    }

    /**
     * begin a Paxos session by sending a prepare request and completing any in-progress requests seen in the replies
     *
     * @return the Paxos ballot promised by the replicas if no in-progress requests were seen and a quorum of
     * nodes have seen the mostRecentCommit.  Otherwise, return null.
     */
    private static UUID beginAndRepairPaxos(ByteBuffer key, CFMetaData metadata, List<InetAddress> liveEndpoints, int requiredParticipants)
    throws WriteTimeoutException
    {
        UUID ballot = UUIDGen.getTimeUUID();

        // prepare
        Tracing.trace("Preparing {}", ballot);
        Commit toPrepare = Commit.newPrepare(key, metadata, ballot);
        PrepareCallback summary = preparePaxos(toPrepare, liveEndpoints, requiredParticipants);
        if (!summary.promised)
        {
            Tracing.trace("Some replicas have already promised a higher ballot than ours; aborting");
            // sleep a random amount to give the other proposer a chance to finish
            Uninterruptibles.sleepUninterruptibly(FBUtilities.threadLocalRandom().nextInt(100), TimeUnit.MILLISECONDS);
            return null;
        }

        Commit inProgress = summary.inProgressCommit;
        Commit mostRecent = summary.mostRecentCommit;

        // If we have an in-progress ballot greater than the MRC we know, then it's an in-progress round that
        // needs to be completed, so do it.
        if (!inProgress.update.isEmpty() && inProgress.isAfter(mostRecent))
        {
            Tracing.trace("Finishing incomplete paxos round {}", inProgress);
            if (proposePaxos(inProgress, liveEndpoints, requiredParticipants))
            {
                try
                {
                    commitPaxos(inProgress, ConsistencyLevel.QUORUM);
                }
                catch (WriteTimeoutException e)
                {
                    // let caller retry or turn it into a cas timeout, since it's someone elses' write we're applying
                    return null;
                }
            }
            return null;
        }

        // To be able to propose our value on a new round, we need a quorum of replica to have learn the previous one. Why is explained at:
        // https://issues.apache.org/jira/browse/CASSANDRA-5062?focusedCommentId=13619810&page=com.atlassian.jira.plugin.system.issuetabpanels:comment-tabpanel#comment-13619810)
        // Since we waited for quorum nodes, if some of them haven't seen the last commit (which may just be a timing issue, but may also
        // mean we lost messages), we pro-actively "repair" those nodes, and retry.
        Iterable<InetAddress> missingMRC = summary.replicasMissingMostRecentCommit();
        if (Iterables.size(missingMRC) > 0)
        {
            Tracing.trace("Repairing replicas that missed the most recent commit");
            sendCommit(mostRecent, missingMRC);
            // TODO: provided commits don't invalid the prepare we just did above (which they don't), we could just wait
            // for all the missingMRC to acknowledge this commit and then move on with proposing our value. But that means
            // adding the ability to have commitPaxos block, which is exactly CASSANDRA-5442 will do. So once we have that
            // latter ticket, we can pass CL.ALL to the commit above and remove the 'continue'.
            return null;
        }

        return ballot;
    }

    /**
     * Unlike commitPaxos, this does not wait for replies
     */
    private static void sendCommit(Commit commit, Iterable<InetAddress> replicas)
    {
        MessageOut<Commit> message = new MessageOut<Commit>(MessagingService.Verb.PAXOS_COMMIT, commit, Commit.serializer);
        for (InetAddress target : replicas)
            MessagingService.instance().sendOneWay(message, target);
    }

    private static PrepareCallback preparePaxos(Commit toPrepare, List<InetAddress> endpoints, int requiredParticipants)
    throws WriteTimeoutException
    {
        PrepareCallback callback = new PrepareCallback(toPrepare.key, toPrepare.update.metadata(), requiredParticipants);
        MessageOut<Commit> message = new MessageOut<Commit>(MessagingService.Verb.PAXOS_PREPARE, toPrepare, Commit.serializer);
        for (InetAddress target : endpoints)
            MessagingService.instance().sendRR(message, target, callback);
        callback.await();
        return callback;
    }

    private static boolean proposePaxos(Commit proposal, List<InetAddress> endpoints, int requiredParticipants)
    throws WriteTimeoutException
    {
        ProposeCallback callback = new ProposeCallback(requiredParticipants);
        MessageOut<Commit> message = new MessageOut<Commit>(MessagingService.Verb.PAXOS_PROPOSE, proposal, Commit.serializer);
        for (InetAddress target : endpoints)
            MessagingService.instance().sendRR(message, target, callback);
        callback.await();

        return callback.getSuccessful() >= requiredParticipants;
    }

    private static void commitPaxos(Commit proposal, ConsistencyLevel consistencyLevel) throws WriteTimeoutException
    {
        Table table = Table.open(proposal.update.metadata().ksName);

        Token tk = StorageService.getPartitioner().getToken(proposal.key);
        List<InetAddress> naturalEndpoints = StorageService.instance.getNaturalEndpoints(table.getName(), tk);
        Collection<InetAddress> pendingEndpoints = StorageService.instance.getTokenMetadata().pendingEndpointsFor(tk, table.getName());

        AbstractReplicationStrategy rs = table.getReplicationStrategy();
        AbstractWriteResponseHandler responseHandler = rs.getWriteResponseHandler(naturalEndpoints, pendingEndpoints, consistencyLevel, null, WriteType.SIMPLE);

        MessageOut<Commit> message = new MessageOut<Commit>(MessagingService.Verb.PAXOS_COMMIT, proposal, Commit.serializer);
        for (InetAddress destination : Iterables.concat(naturalEndpoints, pendingEndpoints))
        {
            if (FailureDetector.instance.isAlive(destination))
                MessagingService.instance().sendRR(message, destination, responseHandler);
        }

        responseHandler.get();
    }

    /**
     * Use this method to have these Mutations applied
     * across all replicas. This method will take care
     * of the possibility of a replica being down and hint
     * the data across to some other replica.
     *
     * @param mutations the mutations to be applied across the replicas
     * @param consistency_level the consistency level for the operation
     */
    public static void mutate(Collection<? extends IMutation> mutations, ConsistencyLevel consistency_level)
    throws UnavailableException, OverloadedException, WriteTimeoutException
    {
        Tracing.trace("Determining replicas for mutation");
        logger.trace("Mutations/ConsistencyLevel are {}/{}", mutations, consistency_level);
        final String localDataCenter = DatabaseDescriptor.getEndpointSnitch().getDatacenter(FBUtilities.getBroadcastAddress());

        long startTime = System.nanoTime();
        List<AbstractWriteResponseHandler> responseHandlers = new ArrayList<AbstractWriteResponseHandler>(mutations.size());

        try
        {
            for (IMutation mutation : mutations)
            {
                if (mutation instanceof CounterMutation)
                {
                    responseHandlers.add(mutateCounter((CounterMutation)mutation, localDataCenter));
                }
                else
                {
                    WriteType wt = mutations.size() <= 1 ? WriteType.SIMPLE : WriteType.UNLOGGED_BATCH;
                    responseHandlers.add(performWrite(mutation, consistency_level, localDataCenter, standardWritePerformer, null, wt));
                }
            }

            // wait for writes.  throws TimeoutException if necessary
            for (AbstractWriteResponseHandler responseHandler : responseHandlers)
            {
                responseHandler.get();
            }

        }
        catch (WriteTimeoutException ex)
        {
            writeMetrics.timeouts.mark();
            ClientRequestMetrics.writeTimeouts.inc();
            if (logger.isDebugEnabled())
            {
                List<String> mstrings = new ArrayList<String>(mutations.size());
                for (IMutation mutation : mutations)
                    mstrings.add(mutation.toString(true));
                logger.debug("Write timeout {} for one (or more) of: {}", ex.toString(), mstrings);
            }
            Tracing.trace("Write timeout");
            throw ex;
        }
        catch (UnavailableException e)
        {
            writeMetrics.unavailables.mark();
            ClientRequestMetrics.writeUnavailables.inc();
            Tracing.trace("Unavailable");
            throw e;
        }
        catch (OverloadedException e)
        {
            ClientRequestMetrics.writeUnavailables.inc();
            Tracing.trace("Overloaded");
            throw e;
        }
        finally
        {
            writeMetrics.addNano(System.nanoTime() - startTime);
        }
    }

    public static void mutateWithTriggers(Collection<? extends IMutation> mutations, ConsistencyLevel consistencyLevel, boolean mutateAtomically) throws WriteTimeoutException, UnavailableException,
            OverloadedException, InvalidRequestException
    {
        Collection<RowMutation> tmutations = TriggerExecutor.instance.execute(mutations);
        if (mutateAtomically || tmutations != null)
        {
            Collection<RowMutation> allMutations = (Collection<RowMutation>) mutations;
            if (tmutations != null)
                allMutations.addAll(tmutations);
            StorageProxy.mutateAtomically(allMutations, consistencyLevel);
        }
        else
        {
            StorageProxy.mutate(mutations, consistencyLevel);
        }
    }

    /**
     * See mutate. Adds additional steps before and after writing a batch.
     * Before writing the batch (but after doing availability check against the FD for the row replicas):
     *      write the entire batch to a batchlog elsewhere in the cluster.
     * After: remove the batchlog entry (after writing hints for the batch rows, if necessary).
     *
     * @param mutations the RowMutations to be applied across the replicas
     * @param consistency_level the consistency level for the operation
     */
    public static void mutateAtomically(Collection<RowMutation> mutations, ConsistencyLevel consistency_level)
    throws UnavailableException, OverloadedException, WriteTimeoutException
    {
        Tracing.trace("Determining replicas for atomic batch");
        long startTime = System.nanoTime();
        logger.trace("Mutations/ConsistencyLevel are {}/{}", mutations, consistency_level);

        List<WriteResponseHandlerWrapper> wrappers = new ArrayList<WriteResponseHandlerWrapper>(mutations.size());
        String localDataCenter = DatabaseDescriptor.getEndpointSnitch().getDatacenter(FBUtilities.getBroadcastAddress());

        try
        {
            // add a handler for each mutation - includes checking availability, but doesn't initiate any writes, yet
            for (RowMutation mutation : mutations)
            {
                WriteResponseHandlerWrapper wrapper = wrapResponseHandler(mutation, consistency_level, WriteType.BATCH);
                // exit early if we can't fulfill the CL at this time.
                wrapper.handler.assureSufficientLiveNodes();
                wrappers.add(wrapper);
            }

            // write to the batchlog
            Collection<InetAddress> batchlogEndpoints = getBatchlogEndpoints(localDataCenter);
            UUID batchUUID = UUID.randomUUID();
            syncWriteToBatchlog(mutations, batchlogEndpoints, batchUUID);

            // now actually perform the writes and wait for them to complete
            syncWriteBatchedMutations(wrappers, localDataCenter, consistency_level);

            // remove the batchlog entries asynchronously
            asyncRemoveFromBatchlog(batchlogEndpoints, batchUUID);
        }
        catch (UnavailableException e)
        {
            writeMetrics.unavailables.mark();
            ClientRequestMetrics.writeUnavailables.inc();
            Tracing.trace("Unavailable");
            throw e;
        }
        catch (WriteTimeoutException e)
        {
            writeMetrics.timeouts.mark();
            ClientRequestMetrics.writeTimeouts.inc();
            Tracing.trace("Write timeout");
            throw e;
        }
        finally
        {
            writeMetrics.addNano(System.nanoTime() - startTime);
        }
    }

    private static void syncWriteToBatchlog(Collection<RowMutation> mutations, Collection<InetAddress> endpoints, UUID uuid)
    throws WriteTimeoutException
    {
        RowMutation rm = BatchlogManager.getBatchlogMutationFor(mutations, uuid);
        AbstractWriteResponseHandler handler = new WriteResponseHandler(endpoints,
                                                                        Collections.<InetAddress>emptyList(),
                                                                        ConsistencyLevel.ONE,
                                                                        Table.open(Table.SYSTEM_KS),
                                                                        null,
                                                                        WriteType.BATCH_LOG);
        updateBatchlog(rm, endpoints, handler);
        handler.get();
    }

    private static void asyncRemoveFromBatchlog(Collection<InetAddress> endpoints, UUID uuid)
    {
        ColumnFamily cf = EmptyColumns.factory.create(Schema.instance.getCFMetaData(Table.SYSTEM_KS, SystemTable.BATCHLOG_CF));
        cf.delete(new DeletionInfo(FBUtilities.timestampMicros(), (int) (System.currentTimeMillis() / 1000)));
        AbstractWriteResponseHandler handler = new WriteResponseHandler(endpoints,
                                                                        Collections.<InetAddress>emptyList(),
                                                                        ConsistencyLevel.ANY,
                                                                        Table.open(Table.SYSTEM_KS),
                                                                        null,
                                                                        WriteType.SIMPLE);
        RowMutation rm = new RowMutation(Table.SYSTEM_KS, UUIDType.instance.decompose(uuid), cf);
        updateBatchlog(rm, endpoints, handler);
    }

    private static void updateBatchlog(RowMutation rm, Collection<InetAddress> endpoints, AbstractWriteResponseHandler handler)
    {
        if (endpoints.contains(FBUtilities.getBroadcastAddress()))
        {
            assert endpoints.size() == 1;
            insertLocal(rm, handler);
        }
        else
        {
            sendMessagesToOneDC(rm.createMessage(), endpoints, true, handler);
        }
    }

    private static void syncWriteBatchedMutations(List<WriteResponseHandlerWrapper> wrappers,
                                                  String localDataCenter,
                                                  ConsistencyLevel consistencyLevel)
    throws WriteTimeoutException, OverloadedException
    {
        for (WriteResponseHandlerWrapper wrapper : wrappers)
        {
            Iterable<InetAddress> endpoints = Iterables.concat(wrapper.handler.naturalEndpoints, wrapper.handler.pendingEndpoints);
            sendToHintedEndpoints(wrapper.mutation, endpoints, wrapper.handler, localDataCenter, consistencyLevel);
        }

        for (WriteResponseHandlerWrapper wrapper : wrappers)
        {
            wrapper.handler.get();
        }
    }

    /**
     * Perform the write of a mutation given a WritePerformer.
     * Gather the list of write endpoints, apply locally and/or forward the mutation to
     * said write endpoint (deletaged to the actual WritePerformer) and wait for the
     * responses based on consistency level.
     *
     * @param mutation the mutation to be applied
     * @param consistency_level the consistency level for the write operation
     * @param performer the WritePerformer in charge of appliying the mutation
     * given the list of write endpoints (either standardWritePerformer for
     * standard writes or counterWritePerformer for counter writes).
     * @param callback an optional callback to be run if and when the write is
     * successful.
     */
    public static AbstractWriteResponseHandler performWrite(IMutation mutation,
                                                            ConsistencyLevel consistency_level,
                                                            String localDataCenter,
                                                            WritePerformer performer,
                                                            Runnable callback,
                                                            WriteType writeType)
    throws UnavailableException, OverloadedException
    {
        String table = mutation.getTable();
        AbstractReplicationStrategy rs = Table.open(table).getReplicationStrategy();

        Token tk = StorageService.getPartitioner().getToken(mutation.key());
        List<InetAddress> naturalEndpoints = StorageService.instance.getNaturalEndpoints(table, tk);
        Collection<InetAddress> pendingEndpoints = StorageService.instance.getTokenMetadata().pendingEndpointsFor(tk, table);

        AbstractWriteResponseHandler responseHandler = rs.getWriteResponseHandler(naturalEndpoints, pendingEndpoints, consistency_level, callback, writeType);

        // exit early if we can't fulfill the CL at this time
        responseHandler.assureSufficientLiveNodes();

        performer.apply(mutation, Iterables.concat(naturalEndpoints, pendingEndpoints), responseHandler, localDataCenter, consistency_level);
        return responseHandler;
    }

    // same as above except does not initiate writes (but does perfrom availability checks).
    private static WriteResponseHandlerWrapper wrapResponseHandler(RowMutation mutation, ConsistencyLevel consistency_level, WriteType writeType)
    {
        AbstractReplicationStrategy rs = Table.open(mutation.getTable()).getReplicationStrategy();
        String table = mutation.getTable();
        Token tk = StorageService.getPartitioner().getToken(mutation.key());
        List<InetAddress> naturalEndpoints = StorageService.instance.getNaturalEndpoints(table, tk);
        Collection<InetAddress> pendingEndpoints = StorageService.instance.getTokenMetadata().pendingEndpointsFor(tk, table);
        AbstractWriteResponseHandler responseHandler = rs.getWriteResponseHandler(naturalEndpoints, pendingEndpoints, consistency_level, null, writeType);
        return new WriteResponseHandlerWrapper(responseHandler, mutation);
    }

    // used by atomic_batch_mutate to decouple availability check from the write itself, caches consistency level and endpoints.
    private static class WriteResponseHandlerWrapper
    {
        final AbstractWriteResponseHandler handler;
        final RowMutation mutation;

        WriteResponseHandlerWrapper(AbstractWriteResponseHandler handler, RowMutation mutation)
        {
            this.handler = handler;
            this.mutation = mutation;
        }
    }

    /*
     * Replicas are picked manually:
     * - replicas should be alive according to the failure detector
     * - replicas should be in the local datacenter
     * - choose min(2, number of qualifying candiates above)
     * - allow the local node to be the only replica only if it's a single-node cluster
     */
    private static Collection<InetAddress> getBatchlogEndpoints(String localDataCenter) throws UnavailableException
    {
        // will include every known node in the DC, including localhost.
        TokenMetadata.Topology topology = StorageService.instance.getTokenMetadata().cloneOnlyTokenMap().getTopology();
        Collection<InetAddress> localMembers = topology.getDatacenterEndpoints().get(localDataCenter);

        // special case for single-node datacenters
        if (localMembers.size() == 1)
            return localMembers;

        // not a single-node cluster - don't count the local node.
        localMembers.remove(FBUtilities.getBroadcastAddress());

        // include only alive nodes
        List<InetAddress> candidates = new ArrayList<InetAddress>(localMembers.size());
        for (InetAddress member : localMembers)
        {
            if (FailureDetector.instance.isAlive(member))
                candidates.add(member);
        }

        if (candidates.isEmpty())
            throw new UnavailableException(ConsistencyLevel.ONE, 1, 0);

        if (candidates.size() > 2)
        {
            IEndpointSnitch snitch = DatabaseDescriptor.getEndpointSnitch();
            snitch.sortByProximity(FBUtilities.getBroadcastAddress(), candidates);
            candidates = candidates.subList(0, 2);
        }

        return candidates;
    }

    /**
     * Send the mutations to the right targets, write it locally if it corresponds or writes a hint when the node
     * is not available.
     *
     * Note about hints:
     *
     * | Hinted Handoff | Consist. Level |
     * | on             |       >=1      | --> wait for hints. We DO NOT notify the handler with handler.response() for hints;
     * | on             |       ANY      | --> wait for hints. Responses count towards consistency.
     * | off            |       >=1      | --> DO NOT fire hints. And DO NOT wait for them to complete.
     * | off            |       ANY      | --> DO NOT fire hints. And DO NOT wait for them to complete.
     *
     * @throws TimeoutException if the hints cannot be written/enqueued
     */
    public static void sendToHintedEndpoints(final RowMutation rm,
                                             Iterable<InetAddress> targets,
                                             AbstractWriteResponseHandler responseHandler,
                                             String localDataCenter,
                                             ConsistencyLevel consistency_level)
    throws OverloadedException
    {
        // replicas grouped by datacenter
        Map<String, Collection<InetAddress>> dcGroups = null;

        for (InetAddress destination : targets)
        {
            // avoid OOMing due to excess hints.  we need to do this check even for "live" nodes, since we can
            // still generate hints for those if it's overloaded or simply dead but not yet known-to-be-dead.
            // The idea is that if we have over maxHintsInProgress hints in flight, this is probably due to
            // a small number of nodes causing problems, so we should avoid shutting down writes completely to
            // healthy nodes.  Any node with no hintsInProgress is considered healthy.
            if (totalHintsInProgress.get() > maxHintsInProgress
                && (hintsInProgress.get(destination).get() > 0 && shouldHint(destination)))
            {
                throw new OverloadedException("Too many in flight hints: " + totalHintsInProgress.get());
            }

            if (FailureDetector.instance.isAlive(destination))
            {
                if (destination.equals(FBUtilities.getBroadcastAddress()) && OPTIMIZE_LOCAL_REQUESTS)
                {
                    insertLocal(rm, responseHandler);
                }
                else
                {
                    // belongs on a different server
                    String dc = DatabaseDescriptor.getEndpointSnitch().getDatacenter(destination);
                    Collection<InetAddress> messages = (dcGroups != null) ? dcGroups.get(dc) : null;
                    if (messages == null)
                    {
                        messages = new ArrayList<InetAddress>(3); // most DCs will have <= 3 replicas
                        if (dcGroups == null)
                            dcGroups = new HashMap<String, Collection<InetAddress>>();
                        dcGroups.put(dc, messages);
                    }

                    messages.add(destination);
                }
            }
            else
            {
                if (!shouldHint(destination))
                    continue;

                // Schedule a local hint
                submitHint(rm, destination, responseHandler, consistency_level);
            }
        }

        if (dcGroups != null)
        {
            MessageOut<RowMutation> message = rm.createMessage();
            // for each datacenter, send the message to one node to relay the write to other replicas
            for (Map.Entry<String, Collection<InetAddress>> entry: dcGroups.entrySet())
            {
                boolean isLocalDC = entry.getKey().equals(localDataCenter);
                Collection<InetAddress> dcTargets = entry.getValue();
                // a single message object is used for unhinted writes, so clean out any forwards
                // from previous loop iterations
                message = message.withHeaderRemoved(RowMutation.FORWARD_TO);
                sendMessagesToOneDC(message, dcTargets, isLocalDC, responseHandler);
            }
        }
    }

    public static Future<Void> submitHint(final RowMutation mutation,
                                          final InetAddress target,
                                          final AbstractWriteResponseHandler responseHandler,
                                          final ConsistencyLevel consistencyLevel)
    {
        // local write that time out should be handled by LocalMutationRunnable
        assert !target.equals(FBUtilities.getBroadcastAddress()) : target;

        HintRunnable runnable = new HintRunnable(target)
        {
            public void runMayThrow()
            {
                int ttl = HintedHandOffManager.calculateHintTTL(mutation);
                if (ttl > 0)
                {
                    logger.debug("Adding hint for {}", target);
                    writeHintForMutation(mutation, ttl, target);
                    // Notify the handler only for CL == ANY
                    if (responseHandler != null && consistencyLevel == ConsistencyLevel.ANY)
                        responseHandler.response(null);
                }
                else
                {
                    logger.debug("Skipped writing hint for {} (ttl {})", target, ttl);
                }
            }
        };

        return submitHint(runnable);
    }

    private static Future<Void> submitHint(HintRunnable runnable)
    {
        totalHintsInProgress.incrementAndGet();
        hintsInProgress.get(runnable.target).incrementAndGet();
        return (Future<Void>) StageManager.getStage(Stage.MUTATION).submit(runnable);
    }

    public static void writeHintForMutation(RowMutation mutation, int ttl, InetAddress target)
    {
        assert ttl > 0;
        UUID hostId = StorageService.instance.getTokenMetadata().getHostId(target);
        assert hostId != null : "Missing host ID for " + target.getHostAddress();
        HintedHandOffManager.hintFor(mutation, ttl, hostId).apply();
        totalHints.incrementAndGet();
    }

    private static void sendMessagesToOneDC(MessageOut message, Collection<InetAddress> targets, boolean localDC, AbstractWriteResponseHandler handler)
    {
        Iterator<InetAddress> iter = targets.iterator();
        InetAddress target = iter.next();

        // direct writes to local DC or old Cassandra versions
        // (1.1 knows how to forward old-style String message IDs; updated to int in 2.0)
        if (localDC || MessagingService.instance().getVersion(target) < MessagingService.VERSION_20)
        {
            // yes, the loop and non-loop code here are the same; this is clunky but we want to avoid
            // creating a second iterator since we already have a perfectly good one
            MessagingService.instance().sendRR(message, target, handler);
            while (iter.hasNext())
            {
                target = iter.next();
                MessagingService.instance().sendRR(message, target, handler);
            }
            return;
        }

        // Add all the other destinations of the same message as a FORWARD_HEADER entry
        DataOutputBuffer out = new DataOutputBuffer();
        try
        {
            out.writeInt(targets.size() - 1);
            while (iter.hasNext())
            {
                InetAddress destination = iter.next();
                CompactEndpointSerializationHelper.serialize(destination, out);
                int id = MessagingService.instance().addCallback(handler, message, destination, message.getTimeout());
                out.writeInt(id);
                logger.trace("Adding FWD message to {}@{}", id, destination);
            }
            message = message.withParameter(RowMutation.FORWARD_TO, out.getData());
            // send the combined message + forward headers
            int id = MessagingService.instance().sendRR(message, target, handler);
            logger.trace("Sending message to {}@{}", id, target);
        }
        catch (IOException e)
        {
            // DataOutputBuffer is in-memory, doesn't throw IOException
            throw new AssertionError(e);
        }
    }

    private static void insertLocal(final RowMutation rm, final AbstractWriteResponseHandler responseHandler)
    {
        if (logger.isTraceEnabled())
            logger.trace("insert writing local " + rm.toString(true));

        Runnable runnable = new DroppableRunnable(MessagingService.Verb.MUTATION)
        {
            public void runMayThrow()
            {
                rm.apply();
                responseHandler.response(null);
            }
        };
        StageManager.getStage(Stage.MUTATION).execute(runnable);
    }

    /**
     * Handle counter mutation on the coordinator host.
     *
     * A counter mutation needs to first be applied to a replica (that we'll call the leader for the mutation) before being
     * replicated to the other endpoint. To achieve so, there is two case:
     *   1) the coordinator host is a replica: we proceed to applying the update locally and replicate throug
     *   applyCounterMutationOnCoordinator
     *   2) the coordinator is not a replica: we forward the (counter)mutation to a chosen replica (that will proceed through
     *   applyCounterMutationOnLeader upon receive) and wait for its acknowledgment.
     *
     * Implementation note: We check if we can fulfill the CL on the coordinator host even if he is not a replica to allow
     * quicker response and because the WriteResponseHandlers don't make it easy to send back an error. We also always gather
     * the write latencies at the coordinator node to make gathering point similar to the case of standard writes.
     */
    public static AbstractWriteResponseHandler mutateCounter(CounterMutation cm, String localDataCenter) throws UnavailableException, OverloadedException
    {
        InetAddress endpoint = findSuitableEndpoint(cm.getTable(), cm.key(), localDataCenter, cm.consistency());

        if (endpoint.equals(FBUtilities.getBroadcastAddress()))
        {
            return applyCounterMutationOnCoordinator(cm, localDataCenter);
        }
        else
        {
            // Exit now if we can't fulfill the CL here instead of forwarding to the leader replica
            String table = cm.getTable();
            AbstractReplicationStrategy rs = Table.open(table).getReplicationStrategy();
            Token tk = StorageService.getPartitioner().getToken(cm.key());
            List<InetAddress> naturalEndpoints = StorageService.instance.getNaturalEndpoints(table, tk);
            Collection<InetAddress> pendingEndpoints = StorageService.instance.getTokenMetadata().pendingEndpointsFor(tk, table);

            rs.getWriteResponseHandler(naturalEndpoints, pendingEndpoints, cm.consistency(), null, WriteType.COUNTER).assureSufficientLiveNodes();

            // Forward the actual update to the chosen leader replica
            AbstractWriteResponseHandler responseHandler = new WriteResponseHandler(endpoint, WriteType.COUNTER);

            if (logger.isTraceEnabled())
                logger.trace("forwarding counter update of key " + ByteBufferUtil.bytesToHex(cm.key()) + " to " + endpoint);
            MessagingService.instance().sendRR(cm.makeMutationMessage(), endpoint, responseHandler);
            return responseHandler;
        }
    }

    /**
     * Find a suitable replica as leader for counter update.
     * For now, we pick a random replica in the local DC (or ask the snitch if
     * there is no replica alive in the local DC).
     * TODO: if we track the latency of the counter writes (which makes sense
     * contrarily to standard writes since there is a read involved), we could
     * trust the dynamic snitch entirely, which may be a better solution. It
     * is unclear we want to mix those latencies with read latencies, so this
     * may be a bit involved.
     */
    private static InetAddress findSuitableEndpoint(String tableName, ByteBuffer key, String localDataCenter, ConsistencyLevel cl) throws UnavailableException
    {
        Table table = Table.open(tableName);
        IEndpointSnitch snitch = DatabaseDescriptor.getEndpointSnitch();
        List<InetAddress> endpoints = StorageService.instance.getLiveNaturalEndpoints(table, key);
        if (endpoints.isEmpty())
            // TODO have a way to compute the consistency level
            throw new UnavailableException(cl, cl.blockFor(table), 0);

        List<InetAddress> localEndpoints = new ArrayList<InetAddress>();
        for (InetAddress endpoint : endpoints)
        {
            if (snitch.getDatacenter(endpoint).equals(localDataCenter))
                localEndpoints.add(endpoint);
        }
        if (localEndpoints.isEmpty())
        {
            // No endpoint in local DC, pick the closest endpoint according to the snitch
            snitch.sortByProximity(FBUtilities.getBroadcastAddress(), endpoints);
            return endpoints.get(0);
        }
        else
        {
            return localEndpoints.get(FBUtilities.threadLocalRandom().nextInt(localEndpoints.size()));
        }
    }

    // Must be called on a replica of the mutation. This replica becomes the
    // leader of this mutation.
    public static AbstractWriteResponseHandler applyCounterMutationOnLeader(CounterMutation cm, String localDataCenter, Runnable callback)
    throws UnavailableException, OverloadedException
    {
        return performWrite(cm, cm.consistency(), localDataCenter, counterWritePerformer, callback, WriteType.COUNTER);
    }

    // Same as applyCounterMutationOnLeader but must with the difference that it use the MUTATION stage to execute the write (while
    // applyCounterMutationOnLeader assumes it is on the MUTATION stage already)
    public static AbstractWriteResponseHandler applyCounterMutationOnCoordinator(CounterMutation cm, String localDataCenter)
    throws UnavailableException, OverloadedException
    {
        return performWrite(cm, cm.consistency(), localDataCenter, counterWriteOnCoordinatorPerformer, null, WriteType.COUNTER);
    }

    private static Runnable counterWriteTask(final IMutation mutation,
                                             final Iterable<InetAddress> targets,
                                             final AbstractWriteResponseHandler responseHandler,
                                             final String localDataCenter,
                                             final ConsistencyLevel consistency_level)
    {
        return new LocalMutationRunnable()
        {
            public void runMayThrow()
            {
                assert mutation instanceof CounterMutation;
                final CounterMutation cm = (CounterMutation) mutation;

                // apply mutation
                cm.apply();
                responseHandler.response(null);

                // then send to replicas, if any
                final Set<InetAddress> remotes = Sets.difference(ImmutableSet.copyOf(targets), ImmutableSet.of(FBUtilities.getBroadcastAddress()));
                if (cm.shouldReplicateOnWrite() && !remotes.isEmpty())
                {
                    // We do the replication on another stage because it involves a read (see CM.makeReplicationMutation)
                    // and we want to avoid blocking too much the MUTATION stage
                    StageManager.getStage(Stage.REPLICATE_ON_WRITE).execute(new DroppableRunnable(MessagingService.Verb.READ)
                    {
                        public void runMayThrow() throws OverloadedException
                        {
                            // send mutation to other replica
                            sendToHintedEndpoints(cm.makeReplicationMutation(), remotes, responseHandler, localDataCenter, consistency_level);
                        }
                    });
                }
            }
        };
    }

    private static boolean systemTableQuery(List<ReadCommand> cmds)
    {
        for (ReadCommand cmd : cmds)
            if (!cmd.table.equals(Table.SYSTEM_KS))
                return false;
        return true;
    }

    /**
     * Performs the actual reading of a row out of the StorageService, fetching
     * a specific set of column names from a given column family.
     */
    public static List<Row> read(List<ReadCommand> commands, ConsistencyLevel consistency_level)
    throws UnavailableException, IsBootstrappingException, ReadTimeoutException, InvalidRequestException, WriteTimeoutException
    {
        if (StorageService.instance.isBootstrapMode() && !systemTableQuery(commands))
        {
            readMetrics.unavailables.mark();
            ClientRequestMetrics.readUnavailables.inc();
            throw new IsBootstrappingException();
        }

        long startTime = System.nanoTime();
        List<Row> rows = null;
        try
        {
            if (consistency_level == ConsistencyLevel.SERIAL)
            {
                // make sure any in-progress paxos writes are done (i.e., committed to a majority of replicas), before performing a quorum read
                if (commands.size() > 1)
                    throw new InvalidRequestException("SERIAL consistency may only be requested for one row at a time");

                ReadCommand command = commands.get(0);
                CFMetaData metadata = Schema.instance.getCFMetaData(command.table, command.cfName);

                long start = System.nanoTime();
                long timeout = TimeUnit.MILLISECONDS.toNanos(DatabaseDescriptor.getCasContentionTimeout());
                while (true)
                {
                    Pair<List<InetAddress>, Integer> p = getPaxosParticipants(command.table, command.key);
                    List<InetAddress> liveEndpoints = p.left;
                    int requiredParticipants = p.right;

                    if (beginAndRepairPaxos(command.key, metadata, liveEndpoints, requiredParticipants) != null)
                        break;

                    if (System.nanoTime() - start >= timeout)
                        throw new WriteTimeoutException(WriteType.CAS, ConsistencyLevel.SERIAL, -1, -1);
                }

                rows = fetchRows(commands, ConsistencyLevel.QUORUM);
            }
            else
            {
                rows = fetchRows(commands, consistency_level);
            }
        }
        catch (UnavailableException e)
        {
            readMetrics.unavailables.mark();
            ClientRequestMetrics.readUnavailables.inc();
            throw e;
        }
        catch (ReadTimeoutException e)
        {
            readMetrics.timeouts.mark();
            ClientRequestMetrics.readTimeouts.inc();
            throw e;
        }
        finally
        {
            readMetrics.addNano(System.nanoTime() - startTime);
        }
        return rows;
    }

    /**
     * This function executes local and remote reads, and blocks for the results:
     *
     * 1. Get the replica locations, sorted by response time according to the snitch
     * 2. Send a data request to the closest replica, and digest requests to either
     *    a) all the replicas, if read repair is enabled
     *    b) the closest R-1 replicas, where R is the number required to satisfy the ConsistencyLevel
     * 3. Wait for a response from R replicas
     * 4. If the digests (if any) match the data return the data
     * 5. else carry out read repair by getting data from all the nodes.
     */
    private static List<Row> fetchRows(List<ReadCommand> initialCommands, ConsistencyLevel consistency_level)
    throws UnavailableException, ReadTimeoutException
    {
        List<Row> rows = new ArrayList<Row>(initialCommands.size());
        List<ReadCommand> commandsToRetry = Collections.emptyList();

        do
        {
            List<ReadCommand> commands = commandsToRetry.isEmpty() ? initialCommands : commandsToRetry;
            AbstractReadExecutor[] readExecutors = new AbstractReadExecutor[commands.size()];

            if (!commandsToRetry.isEmpty())
                logger.debug("Retrying {} commands", commandsToRetry.size());

            // send out read requests
            for (int i = 0; i < commands.size(); i++)
            {
                ReadCommand command = commands.get(i);
                assert !command.isDigestQuery();
                logger.trace("Command/ConsistencyLevel is {}/{}", command, consistency_level);

                AbstractReadExecutor exec = AbstractReadExecutor.getReadExecutor(command, consistency_level);
                exec.executeAsync();
                readExecutors[i] = exec;
            }

            for (AbstractReadExecutor exec: readExecutors)
                exec.speculate();

            // read results and make a second pass for any digest mismatches
            List<ReadCommand> repairCommands = null;
            List<ReadCallback<ReadResponse, Row>> repairResponseHandlers = null;
            for (AbstractReadExecutor exec: readExecutors)
            {
                try
                {
                    Row row = exec.get();
                    if (row != null)
                    {
                        exec.command.maybeTrim(row);
                        rows.add(row);
                    }
                    if (logger.isDebugEnabled())
                        logger.debug("Read: {} ms.", TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - exec.handler.start));
                }
                catch (DigestMismatchException ex)
                {
                    logger.trace("Digest mismatch: {}", ex);
                    // Do a full data read to resolve the correct response (and repair node that need be)
                    RowDataResolver resolver = new RowDataResolver(exec.command.table, exec.command.key, exec.command.filter());
                    ReadCallback<ReadResponse, Row> repairHandler = exec.handler.withNewResolver(resolver);

                    if (repairCommands == null)
                    {
                        repairCommands = new ArrayList<ReadCommand>();
                        repairResponseHandlers = new ArrayList<ReadCallback<ReadResponse, Row>>();
                    }
                    repairCommands.add(exec.command);
                    repairResponseHandlers.add(repairHandler);

                    MessageOut<ReadCommand> message = exec.command.createMessage();
                    for (InetAddress endpoint : exec.handler.endpoints)
                        MessagingService.instance().sendRR(message, endpoint, repairHandler);
                }
            }

            if (commandsToRetry != Collections.EMPTY_LIST)
                commandsToRetry.clear();

            // read the results for the digest mismatch retries
            if (repairResponseHandlers != null)
            {
                for (int i = 0; i < repairCommands.size(); i++)
                {
                    ReadCommand command = repairCommands.get(i);
                    ReadCallback<ReadResponse, Row> handler = repairResponseHandlers.get(i);

                    Row row;
                    try
                    {
                        row = handler.get();
                    }
                    catch (DigestMismatchException e)
                    {
                        throw new AssertionError(e); // full data requested from each node here, no digests should be sent
                    }

                    RowDataResolver resolver = (RowDataResolver)handler.resolver;
                    try
                    {
                        // wait for the repair writes to be acknowledged, to minimize impact on any replica that's
                        // behind on writes in case the out-of-sync row is read multiple times in quick succession
                        FBUtilities.waitOnFutures(resolver.repairResults, DatabaseDescriptor.getWriteRpcTimeout());
                    }
                    catch (TimeoutException e)
                    {
                        int blockFor = consistency_level.blockFor(Table.open(command.getKeyspace()));
                        throw new ReadTimeoutException(consistency_level, blockFor, blockFor, true);
                    }

                    // retry any potential short reads
                    ReadCommand retryCommand = command.maybeGenerateRetryCommand(resolver, row);
                    if (retryCommand != null)
                    {
                        logger.debug("Issuing retry for read command");
                        if (commandsToRetry == Collections.EMPTY_LIST)
                            commandsToRetry = new ArrayList<ReadCommand>();
                        commandsToRetry.add(retryCommand);
                        continue;
                    }

                    if (row != null)
                    {
                        command.maybeTrim(row);
                        rows.add(row);
                    }
                }
            }
        } while (!commandsToRetry.isEmpty());

        return rows;
    }

    static class LocalReadRunnable extends DroppableRunnable
    {
        private final ReadCommand command;
        private final ReadCallback<ReadResponse, Row> handler;
        private final long start = System.nanoTime();

        LocalReadRunnable(ReadCommand command, ReadCallback<ReadResponse, Row> handler)
        {
            super(MessagingService.Verb.READ);
            this.command = command;
            this.handler = handler;
        }

        protected void runMayThrow()
        {
            logger.trace("LocalReadRunnable reading {}", command);

            Table table = Table.open(command.table);
            Row r = command.getRow(table);
            ReadResponse result = ReadVerbHandler.getResponse(command, r);
            MessagingService.instance().addLatency(FBUtilities.getBroadcastAddress(), TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start));
            handler.response(result);
        }
    }

    static class LocalRangeSliceRunnable extends DroppableRunnable
    {
        private final RangeSliceCommand command;
        private final ReadCallback<RangeSliceReply, Iterable<Row>> handler;
        private final long start = System.nanoTime();

        LocalRangeSliceRunnable(RangeSliceCommand command, ReadCallback<RangeSliceReply, Iterable<Row>> handler)
        {
            super(MessagingService.Verb.READ);
            this.command = command;
            this.handler = handler;
        }

        protected void runMayThrow()
        {
            logger.trace("LocalReadRunnable reading {}", command);

            RangeSliceReply result = new RangeSliceReply(RangeSliceVerbHandler.executeLocally(command));
            MessagingService.instance().addLatency(FBUtilities.getBroadcastAddress(), TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start));
            handler.response(result);
        }
    }

    public static List<InetAddress> getLiveSortedEndpoints(Table table, ByteBuffer key)
    {
        return getLiveSortedEndpoints(table, StorageService.instance.getPartitioner().decorateKey(key));
    }

    private static List<InetAddress> getLiveSortedEndpoints(Table table, RingPosition pos)
    {
        List<InetAddress> liveEndpoints = StorageService.instance.getLiveNaturalEndpoints(table, pos);
        DatabaseDescriptor.getEndpointSnitch().sortByProximity(FBUtilities.getBroadcastAddress(), liveEndpoints);
        return liveEndpoints;
    }

    private static List<InetAddress> intersection(List<InetAddress> l1, List<InetAddress> l2)
    {
        // Note: we don't use Guava Sets.intersection() for 3 reasons:
        //   1) retainAll would be inefficient if l1 and l2 are large but in practice both are the replicas for a range and
        //   so will be very small (< RF). In that case, retainAll is in fact more efficient.
        //   2) we do ultimately need a list so converting everything to sets don't make sense
        //   3) l1 and l2 are sorted by proximity. The use of retainAll  maintain that sorting in the result, while using sets wouldn't.
        List<InetAddress> inter = new ArrayList<InetAddress>(l1);
        inter.retainAll(l2);
        return inter;
    }

    public static List<Row> getRangeSlice(RangeSliceCommand command, ConsistencyLevel consistency_level)
    throws UnavailableException, ReadTimeoutException
    {
        Tracing.trace("Determining replicas to query");
        logger.trace("Command/ConsistencyLevel is {}/{}", command.toString(), consistency_level);
        long startTime = System.nanoTime();

        Table table = Table.open(command.keyspace);
        List<Row> rows;
        // now scan until we have enough results
        try
        {
            IDiskAtomFilter commandPredicate = command.predicate;

            int cql3RowCount = 0;
            rows = new ArrayList<Row>();
            List<AbstractBounds<RowPosition>> ranges = getRestrictedRanges(command.range);
            int i = 0;
            AbstractBounds<RowPosition> nextRange = null;
            List<InetAddress> nextEndpoints = null;
            List<InetAddress> nextFilteredEndpoints = null;
            while (i < ranges.size())
            {
                AbstractBounds<RowPosition> range = nextRange == null
                                                  ? ranges.get(i)
                                                  : nextRange;
                List<InetAddress> liveEndpoints = nextEndpoints == null
                                                ? getLiveSortedEndpoints(table, range.right)
                                                : nextEndpoints;
                List<InetAddress> filteredEndpoints = nextFilteredEndpoints == null
                                                    ? consistency_level.filterForQuery(table, liveEndpoints)
                                                    : nextFilteredEndpoints;
                ++i;

                // getRestrictedRange has broken the queried range into per-[vnode] token ranges, but this doesn't take
                // the replication factor into account. If the intersection of live endpoints for 2 consecutive ranges
                // still meets the CL requirements, then we can merge both ranges into the same RangeSliceCommand.
                while (i < ranges.size())
                {
                    nextRange = ranges.get(i);
                    nextEndpoints = getLiveSortedEndpoints(table, nextRange.right);
                    nextFilteredEndpoints = consistency_level.filterForQuery(table, nextEndpoints);

                    /*
                     * If the current range right is the min token, we should stop merging because CFS.getRangeSlice
                     * don't know how to deal with a wrapping range.
                     * Note: it would be slightly more efficient to have CFS.getRangeSlice on the destination nodes unwraps
                     * the range if necessary and deal with it. However, we can't start sending wrapped range without breaking
                     * wire compatibility, so It's likely easier not to bother;
                     */
                    if (range.right.isMinimum())
                        break;

                    List<InetAddress> merged = intersection(liveEndpoints, nextEndpoints);

                    // Check if there is enough endpoint for the merge to be possible.
                    if (!consistency_level.isSufficientLiveNodes(table, merged))
                        break;

                    List<InetAddress> filteredMerged = consistency_level.filterForQuery(table, merged);

                    // Estimate whether merging will be a win or not
                    if (!DatabaseDescriptor.getEndpointSnitch().isWorthMergingForRangeQuery(filteredMerged, filteredEndpoints, nextFilteredEndpoints))
                        break;

                    // If we get there, merge this range and the next one
                    range = range.withNewRight(nextRange.right);
                    liveEndpoints = merged;
                    filteredEndpoints = filteredMerged;
                    ++i;
                }

                RangeSliceCommand nodeCmd = new RangeSliceCommand(command.keyspace,
                                                                  command.column_family,
                                                                  commandPredicate,
                                                                  range,
                                                                  command.row_filter,
                                                                  command.maxResults,
                                                                  command.countCQL3Rows,
                                                                  command.isPaging);

                // collect replies and resolve according to consistency level
                RangeSliceResponseResolver resolver = new RangeSliceResponseResolver(nodeCmd.keyspace);
                ReadCallback<RangeSliceReply, Iterable<Row>> handler = new ReadCallback(resolver, consistency_level, nodeCmd, filteredEndpoints);
                handler.assureSufficientLiveNodes();
                resolver.setSources(filteredEndpoints);
                if (filteredEndpoints.size() == 1
                    && filteredEndpoints.get(0).equals(FBUtilities.getBroadcastAddress())
                    && OPTIMIZE_LOCAL_REQUESTS)
                {
                    logger.trace("reading data locally");
                    StageManager.getStage(Stage.READ).execute(new LocalRangeSliceRunnable(nodeCmd, handler));
                }
                else
                {
                    MessageOut<RangeSliceCommand> message = nodeCmd.createMessage();
                    for (InetAddress endpoint : filteredEndpoints)
                    {
                        MessagingService.instance().sendRR(message, endpoint, handler);
                        logger.trace("reading {} from {}", nodeCmd, endpoint);
                    }
                }

                try
                {
                    for (Row row : handler.get())
                    {
                        rows.add(row);
                        if (nodeCmd.countCQL3Rows)
                            cql3RowCount += row.getLiveCount(commandPredicate);
                        logger.trace("range slices read {}", row.key);
                    }
                    FBUtilities.waitOnFutures(resolver.repairResults, DatabaseDescriptor.getWriteRpcTimeout());
                }
                catch (TimeoutException ex)
                {
                    logger.debug("Range slice timeout: {}", ex.toString());
                    // We actually got all response at that point
                    int blockFor = consistency_level.blockFor(table);
                    throw new ReadTimeoutException(consistency_level, blockFor, blockFor, true);
                }
                catch (DigestMismatchException e)
                {
                    throw new AssertionError(e); // no digests in range slices yet
                }

                // if we're done, great, otherwise, move to the next range
                int count = nodeCmd.countCQL3Rows ? cql3RowCount : rows.size();
                if (count >= nodeCmd.maxResults)
                    break;

                // if we are paging and already got some rows, reset the column filter predicate,
                // so we start iterating the next row from the first column
                if (!rows.isEmpty() && command.isPaging)
                {
                    // We only allow paging with a slice filter (doesn't make sense otherwise anyway)
                    assert commandPredicate instanceof SliceQueryFilter;
                    commandPredicate = ((SliceQueryFilter)commandPredicate).withUpdatedSlices(ColumnSlice.ALL_COLUMNS_ARRAY);
                }
            }
        }
        finally
        {
            rangeMetrics.addNano(System.nanoTime() - startTime);
        }
        return trim(command, rows);
    }

    private static List<Row> trim(RangeSliceCommand command, List<Row> rows)
    {
        // When countCQL3Rows, we let the caller trim the result.
        if (command.countCQL3Rows)
            return rows;
        else
            return rows.size() > command.maxResults ? rows.subList(0, command.maxResults) : rows;
    }

    /**
     * initiate a request/response session with each live node to check whether or not everybody is using the same
     * migration id. This is useful for determining if a schema change has propagated through the cluster. Disagreement
     * is assumed if any node fails to respond.
     */
    public static Map<String, List<String>> describeSchemaVersions()
    {
        final String myVersion = Schema.instance.getVersion().toString();
        final Map<InetAddress, UUID> versions = new ConcurrentHashMap<InetAddress, UUID>();
        final Set<InetAddress> liveHosts = Gossiper.instance.getLiveMembers();
        final CountDownLatch latch = new CountDownLatch(liveHosts.size());

        IAsyncCallback<UUID> cb = new IAsyncCallback<UUID>()
        {
            public void response(MessageIn<UUID> message)
            {
                // record the response from the remote node.
                logger.trace("Received schema check response from {}", message.from.getHostAddress());
                versions.put(message.from, message.payload);
                latch.countDown();
            }

            public boolean isLatencyForSnitch()
            {
                return false;
            }
        };
        // an empty message acts as a request to the SchemaCheckVerbHandler.
        MessageOut message = new MessageOut(MessagingService.Verb.SCHEMA_CHECK);
        for (InetAddress endpoint : liveHosts)
            MessagingService.instance().sendRR(message, endpoint, cb);

        try
        {
            // wait for as long as possible. timeout-1s if possible.
            latch.await(DatabaseDescriptor.getRpcTimeout(), TimeUnit.MILLISECONDS);
        }
        catch (InterruptedException ex)
        {
            throw new AssertionError("This latch shouldn't have been interrupted.");
        }

        logger.trace("My version is {}", myVersion);

        // maps versions to hosts that are on that version.
        Map<String, List<String>> results = new HashMap<String, List<String>>();
        Iterable<InetAddress> allHosts = Iterables.concat(Gossiper.instance.getLiveMembers(), Gossiper.instance.getUnreachableMembers());
        for (InetAddress host : allHosts)
        {
            UUID version = versions.get(host);
            String stringVersion = version == null ? UNREACHABLE : version.toString();
            List<String> hosts = results.get(stringVersion);
            if (hosts == null)
            {
                hosts = new ArrayList<String>();
                results.put(stringVersion, hosts);
            }
            hosts.add(host.getHostAddress());
        }

        // we're done: the results map is ready to return to the client.  the rest is just debug logging:
        if (results.get(UNREACHABLE) != null)
            logger.debug("Hosts not in agreement. Didn't get a response from everybody: {}", StringUtils.join(results.get(UNREACHABLE), ","));
        for (Map.Entry<String, List<String>> entry : results.entrySet())
        {
            // check for version disagreement. log the hosts that don't agree.
            if (entry.getKey().equals(UNREACHABLE) || entry.getKey().equals(myVersion))
                continue;
            for (String host : entry.getValue())
                logger.debug("{} disagrees ({})", host, entry.getKey());
        }
        if (results.size() == 1)
            logger.debug("Schemas are in agreement.");

        return results;
    }

    /**
     * Compute all ranges we're going to query, in sorted order. Nodes can be replica destinations for many ranges,
     * so we need to restrict each scan to the specific range we want, or else we'd get duplicate results.
     */
    static <T extends RingPosition> List<AbstractBounds<T>> getRestrictedRanges(final AbstractBounds<T> queryRange)
    {
        // special case for bounds containing exactly 1 (non-minimum) token
        if (queryRange instanceof Bounds && queryRange.left.equals(queryRange.right) && !queryRange.left.isMinimum(StorageService.getPartitioner()))
        {
            logger.trace("restricted single token match for query {}", queryRange);
            return Collections.singletonList(queryRange);
        }

        TokenMetadata tokenMetadata = StorageService.instance.getTokenMetadata();

        List<AbstractBounds<T>> ranges = new ArrayList<AbstractBounds<T>>();
        // divide the queryRange into pieces delimited by the ring and minimum tokens
        Iterator<Token> ringIter = TokenMetadata.ringIterator(tokenMetadata.sortedTokens(), queryRange.left.getToken(), true);
        AbstractBounds<T> remainder = queryRange;
        while (ringIter.hasNext())
        {
            /*
             * remainder can be a range/bounds of token _or_ keys and we want to split it with a token:
             *   - if remainder is tokens, then we'll just split using the provided token.
             *   - if remainder is keys, we want to split using token.upperBoundKey. For instance, if remainder
             *     is [DK(10, 'foo'), DK(20, 'bar')], and we have 3 nodes with tokens 0, 15, 30. We want to
             *     split remainder to A=[DK(10, 'foo'), 15] and B=(15, DK(20, 'bar')]. But since we can't mix
             *     tokens and keys at the same time in a range, we uses 15.upperBoundKey() to have A include all
             *     keys having 15 as token and B include none of those (since that is what our node owns).
             * asSplitValue() abstracts that choice.
             */
            Token upperBoundToken = ringIter.next();
            T upperBound = (T)upperBoundToken.upperBound(queryRange.left.getClass());
            if (!remainder.left.equals(upperBound) && !remainder.contains(upperBound))
                // no more splits
                break;
            Pair<AbstractBounds<T>,AbstractBounds<T>> splits = remainder.split(upperBound);
            if (splits == null)
                continue;

            ranges.add(splits.left);
            remainder = splits.right;
        }
        ranges.add(remainder);
        if (logger.isDebugEnabled())
            logger.trace("restricted ranges for query {} are {}", queryRange, ranges);

        return ranges;
    }

    public long getReadOperations()
    {
        return readMetrics.latency.count();
    }

    public long getTotalReadLatencyMicros()
    {
        return readMetrics.totalLatency.count();
    }

    public double getRecentReadLatencyMicros()
    {
        return readMetrics.getRecentLatency();
    }

    public long[] getTotalReadLatencyHistogramMicros()
    {
        return readMetrics.totalLatencyHistogram.getBuckets(false);
    }

    public long[] getRecentReadLatencyHistogramMicros()
    {
        return readMetrics.recentLatencyHistogram.getBuckets(true);
    }

    public long getRangeOperations()
    {
        return rangeMetrics.latency.count();
    }

    public long getTotalRangeLatencyMicros()
    {
        return rangeMetrics.totalLatency.count();
    }

    public double getRecentRangeLatencyMicros()
    {
        return rangeMetrics.getRecentLatency();
    }

    public long[] getTotalRangeLatencyHistogramMicros()
    {
        return rangeMetrics.totalLatencyHistogram.getBuckets(false);
    }

    public long[] getRecentRangeLatencyHistogramMicros()
    {
        return rangeMetrics.recentLatencyHistogram.getBuckets(true);
    }

    public long getWriteOperations()
    {
        return writeMetrics.latency.count();
    }

    public long getTotalWriteLatencyMicros()
    {
        return writeMetrics.totalLatency.count();
    }

    public double getRecentWriteLatencyMicros()
    {
        return writeMetrics.getRecentLatency();
    }

    public long[] getTotalWriteLatencyHistogramMicros()
    {
        return writeMetrics.totalLatencyHistogram.getBuckets(false);
    }

    public long[] getRecentWriteLatencyHistogramMicros()
    {
        return writeMetrics.recentLatencyHistogram.getBuckets(true);
    }

    public boolean getHintedHandoffEnabled()
    {
        return DatabaseDescriptor.hintedHandoffEnabled();
    }

    public void setHintedHandoffEnabled(boolean b)
    {
        DatabaseDescriptor.setHintedHandoffEnabled(b);
    }

    public int getMaxHintWindow()
    {
        return DatabaseDescriptor.getMaxHintWindow();
    }

    public void setMaxHintWindow(int ms)
    {
        DatabaseDescriptor.setMaxHintWindow(ms);
    }

    public static boolean shouldHint(InetAddress ep)
    {
        if (!DatabaseDescriptor.hintedHandoffEnabled())
        {
            HintedHandOffManager.instance.metrics.incrPastWindow(ep);
            return false;
        }

        boolean hintWindowExpired = Gossiper.instance.getEndpointDowntime(ep) > DatabaseDescriptor.getMaxHintWindow();
        if (hintWindowExpired)
        {
            HintedHandOffManager.instance.metrics.incrPastWindow(ep);
            logger.trace("not hinting {} which has been down {}ms", ep, Gossiper.instance.getEndpointDowntime(ep));
        }
        return !hintWindowExpired;
    }

    /**
     * Performs the truncate operatoin, which effectively deletes all data from
     * the column family cfname
     * @param keyspace
     * @param cfname
     * @throws UnavailableException If some of the hosts in the ring are down.
     * @throws TimeoutException
     * @throws IOException
     */
    public static void truncateBlocking(String keyspace, String cfname) throws UnavailableException, TimeoutException, IOException
    {
        logger.debug("Starting a blocking truncate operation on keyspace {}, CF ", keyspace, cfname);
        if (isAnyHostDown())
        {
            logger.info("Cannot perform truncate, some hosts are down");
            // Since the truncate operation is so aggressive and is typically only
            // invoked by an admin, for simplicity we require that all nodes are up
            // to perform the operation.
            int liveMembers = Gossiper.instance.getLiveMembers().size();
            throw new UnavailableException(ConsistencyLevel.ALL, liveMembers + Gossiper.instance.getUnreachableMembers().size(), liveMembers);
        }

        Set<InetAddress> allEndpoints = Gossiper.instance.getLiveMembers();
        int blockFor = allEndpoints.size();
        final TruncateResponseHandler responseHandler = new TruncateResponseHandler(blockFor);

        // Send out the truncate calls and track the responses with the callbacks.
        logger.trace("Starting to send truncate messages to hosts {}", allEndpoints);
        final Truncation truncation = new Truncation(keyspace, cfname);
        MessageOut<Truncation> message = truncation.createMessage();
        for (InetAddress endpoint : allEndpoints)
            MessagingService.instance().sendRR(message, endpoint, responseHandler);

        // Wait for all
        logger.trace("Sent all truncate messages, now waiting for {} responses", blockFor);
        responseHandler.get();
    }

    /**
     * Asks the gossiper if there are any nodes that are currently down.
     * @return true if the gossiper thinks all nodes are up.
     */
    private static boolean isAnyHostDown()
    {
        return !Gossiper.instance.getUnreachableMembers().isEmpty();
    }

    public interface WritePerformer
    {
        public void apply(IMutation mutation, Iterable<InetAddress> targets, AbstractWriteResponseHandler responseHandler, String localDataCenter, ConsistencyLevel consistency_level) throws OverloadedException;
    }

    /**
     * A Runnable that aborts if it doesn't start running before it times out
     */
    private static abstract class DroppableRunnable implements Runnable
    {
        private final long constructionTime = System.nanoTime();
        private final MessagingService.Verb verb;

        public DroppableRunnable(MessagingService.Verb verb)
        {
            this.verb = verb;
        }

        public final void run()
        {
            if (TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - constructionTime) > DatabaseDescriptor.getTimeout(verb))
            {
                MessagingService.instance().incrementDroppedMessages(verb);
                return;
            }

            try
            {
                runMayThrow();
            }
            catch (Exception e)
            {
                throw new RuntimeException(e);
            }
        }

        abstract protected void runMayThrow() throws Exception;
    }

    /**
     * Like DroppableRunnable, but if it aborts, it will rerun (on the mutation stage) after
     * marking itself as a hint in progress so that the hint backpressure mechanism can function.
     */
    private static abstract class LocalMutationRunnable implements Runnable
    {
        private final long constructionTime = System.nanoTime();

        public final void run()
        {
            if (TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - constructionTime) > DatabaseDescriptor.getTimeout(MessagingService.Verb.MUTATION))
            {
                MessagingService.instance().incrementDroppedMessages(MessagingService.Verb.MUTATION);
                HintRunnable runnable = new HintRunnable(FBUtilities.getBroadcastAddress())
                {
                    protected void runMayThrow() throws Exception
                    {
                        LocalMutationRunnable.this.runMayThrow();
                    }
                };
                submitHint(runnable);
                return;
            }

            try
            {
                runMayThrow();
            }
            catch (Exception e)
            {
                throw new RuntimeException(e);
            }
        }

        abstract protected void runMayThrow() throws Exception;
    }

    /**
     * HintRunnable will decrease totalHintsInProgress and targetHints when finished.
     * It is the caller's responsibility to increment them initially.
     */
    private abstract static class HintRunnable implements Runnable
    {
        public final InetAddress target;

        protected HintRunnable(InetAddress target)
        {
            this.target = target;
        }

        public void run()
        {
            try
            {
                runMayThrow();
            }
            catch (Exception e)
            {
                throw new RuntimeException(e);
            }
            finally
            {
                totalHintsInProgress.decrementAndGet();
                hintsInProgress.get(target).decrementAndGet();
            }
        }

        abstract protected void runMayThrow() throws Exception;
    }

    public long getTotalHints()
    {
        return totalHints.get();
    }

    public int getMaxHintsInProgress()
    {
        return maxHintsInProgress;
    }

    public void setMaxHintsInProgress(int qs)
    {
        maxHintsInProgress = qs;
    }

    public int getHintsInProgress()
    {
        return totalHintsInProgress.get();
    }

    public void verifyNoHintsInProgress()
    {
        if (getHintsInProgress() > 0)
            logger.warn("Some hints were not written before shutdown.  This is not supposed to happen.  You should (a) run repair, and (b) file a bug report");
    }

    public Long getRpcTimeout() { return DatabaseDescriptor.getRpcTimeout(); }
    public void setRpcTimeout(Long timeoutInMillis) { DatabaseDescriptor.setRpcTimeout(timeoutInMillis); }

    public Long getReadRpcTimeout() { return DatabaseDescriptor.getReadRpcTimeout(); }
    public void setReadRpcTimeout(Long timeoutInMillis) { DatabaseDescriptor.setReadRpcTimeout(timeoutInMillis); }

    public Long getWriteRpcTimeout() { return DatabaseDescriptor.getWriteRpcTimeout(); }
    public void setWriteRpcTimeout(Long timeoutInMillis) { DatabaseDescriptor.setWriteRpcTimeout(timeoutInMillis); }

    public Long getCasContentionTimeout() { return DatabaseDescriptor.getCasContentionTimeout(); }
    public void setCasContentionTimeout(Long timeoutInMillis) { DatabaseDescriptor.setCasContentionTimeout(timeoutInMillis); }

    public Long getRangeRpcTimeout() { return DatabaseDescriptor.getRangeRpcTimeout(); }
    public void setRangeRpcTimeout(Long timeoutInMillis) { DatabaseDescriptor.setRangeRpcTimeout(timeoutInMillis); }

    public Long getTruncateRpcTimeout() { return DatabaseDescriptor.getTruncateRpcTimeout(); }
    public void setTruncateRpcTimeout(Long timeoutInMillis) { DatabaseDescriptor.setTruncateRpcTimeout(timeoutInMillis); }
    public void reloadTriggerClass() { TriggerExecutor.instance.reloadClasses(); }
}
