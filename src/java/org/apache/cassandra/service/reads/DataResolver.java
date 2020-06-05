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
package org.apache.cassandra.service.reads;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.function.UnaryOperator;

import com.google.common.base.Joiner;

import org.apache.cassandra.cql3.statements.schema.IndexTarget;
import org.apache.cassandra.db.DecoratedKey;
import org.apache.cassandra.db.DeletionTime;
import org.apache.cassandra.db.ReadCommand;
import org.apache.cassandra.db.ReadResponse;
import org.apache.cassandra.db.filter.DataLimits;
import org.apache.cassandra.db.partitions.PartitionIterator;
import org.apache.cassandra.db.partitions.PartitionIterators;
import org.apache.cassandra.db.partitions.UnfilteredPartitionIterator;
import org.apache.cassandra.db.partitions.UnfilteredPartitionIterators;
import org.apache.cassandra.db.rows.RangeTombstoneMarker;
import org.apache.cassandra.db.rows.Row;
import org.apache.cassandra.db.rows.UnfilteredRowIterator;
import org.apache.cassandra.db.rows.UnfilteredRowIterators;
import org.apache.cassandra.db.transform.EmptyPartitionsDiscarder;
import org.apache.cassandra.db.transform.Filter;
import org.apache.cassandra.db.transform.FilteredPartitions;
import org.apache.cassandra.db.transform.Transformation;
import org.apache.cassandra.index.sasi.SASIIndex;
import org.apache.cassandra.locator.Endpoints;
import org.apache.cassandra.locator.ReplicaPlan;
import org.apache.cassandra.net.Message;
import org.apache.cassandra.schema.IndexMetadata;
import org.apache.cassandra.schema.TableMetadata;
import org.apache.cassandra.service.reads.repair.ReadRepair;
import org.apache.cassandra.service.reads.repair.RepairedDataTracker;
import org.apache.cassandra.service.reads.repair.RepairedDataVerifier;

import static com.google.common.collect.Iterables.*;

public class DataResolver<E extends Endpoints<E>, P extends ReplicaPlan.ForRead<E>> extends ResponseResolver<E, P>
{
    private final boolean enforceStrictLiveness;
    private final ReadRepair<E, P> readRepair;

    public DataResolver(ReadCommand command, ReplicaPlan.Shared<E, P> replicaPlan, ReadRepair<E, P> readRepair, long queryStartNanoTime)
    {
        super(command, replicaPlan, queryStartNanoTime);
        this.enforceStrictLiveness = command.metadata().enforceStrictLiveness();
        this.readRepair = readRepair;
    }

    public PartitionIterator getData()
    {
        ReadResponse response = responses.get(0).payload;
        return UnfilteredPartitionIterators.filter(response.makeIterator(command), command.nowInSec());
    }

    public boolean isDataPresent()
    {
        return !responses.isEmpty();
    }

    public PartitionIterator resolve()
    {
        // We could get more responses while this method runs, which is ok (we're happy to ignore any response not here
        // at the beginning of this method), so grab the response count once and use that through the method.
        Collection<Message<ReadResponse>> messages = responses.snapshot();
        assert !any(messages, msg -> msg.payload.isDigestResponse());

        E replicas = replicaPlan().candidates().select(transform(messages, Message::from), false);

        // If requested, inspect each response for a digest of the replica's repaired data set
        RepairedDataTracker repairedDataTracker = command.isTrackingRepairedStatus()
                                                  ? new RepairedDataTracker(getRepairedDataVerifier(command))
                                                  : null;
        if (repairedDataTracker != null)
        {
            messages.forEach(msg -> {
                if (msg.payload.mayIncludeRepairedDigest() && replicas.byEndpoint().get(msg.from()).isFull())
                {
                    repairedDataTracker.recordDigest(msg.from(),
                                                     msg.payload.repairedDataDigest(),
                                                     msg.payload.isRepairedDigestConclusive());
                }
            });
        }

        if (!needsReplicaFilteringProtection())
        {
            ResolveContext context = new ResolveContext(replicas);
            return resolveWithReadRepair(context,
                                         i -> shortReadProtectedResponse(i, context),
                                         UnaryOperator.identity(),
                                         repairedDataTracker);
        }

        return resolveWithReplicaFilteringProtection(replicas, repairedDataTracker);
    }

    private boolean needsReplicaFilteringProtection()
    {
        if (command.rowFilter().isEmpty())
            return false;

        IndexMetadata indexDef = command.indexMetadata();
        if (indexDef != null && indexDef.isCustom())
        {
            String className = indexDef.options.get(IndexTarget.CUSTOM_INDEX_OPTION_NAME);
            return !SASIIndex.class.getName().equals(className);
        }

        return true;
    }

    private class ResolveContext
    {
        private final E replicas;
        private final DataLimits.Counter mergedResultCounter;

        private ResolveContext(E replicas)
        {
            this.replicas = replicas;
            this.mergedResultCounter = command.limits().newCounter(command.nowInSec(),
                                                                   true,
                                                                   command.selectsFullPartition(),
                                                                   enforceStrictLiveness);
        }

        private boolean needsReadRepair()
        {
            return replicas.size() > 1;
        }

        private boolean needShortReadProtection()
        {
            // If we have only one result, there is no read repair to do and we can't get short reads
            // Also, so-called "short reads" stems from nodes returning only a subset of the results they have for a
            // partition due to the limit, but that subset not being enough post-reconciliation. So if we don't have limit,
            // don't bother protecting against short reads.
            return replicas.size() > 1 && !command.limits().isUnlimited();
        }
    }

    @FunctionalInterface
    private interface ResponseProvider
    {
        UnfilteredPartitionIterator getResponse(int i);
    }

    private UnfilteredPartitionIterator shortReadProtectedResponse(int i, ResolveContext context)
    {
        UnfilteredPartitionIterator originalResponse = responses.get(i).payload.makeIterator(command);

        return context.needShortReadProtection()
               ? ShortReadProtection.extend(context.replicas.get(i),
                                            originalResponse,
                                            command,
                                            context.mergedResultCounter,
                                            queryStartNanoTime,
                                            enforceStrictLiveness)
               : originalResponse;
    }

    private PartitionIterator resolveWithReadRepair(ResolveContext context,
                                                    ResponseProvider responseProvider,
                                                    UnaryOperator<PartitionIterator> preCountFilter,
                                                    RepairedDataTracker repairedDataTracker)
    {
        UnfilteredPartitionIterators.MergeListener listener = null;
        if (context.needsReadRepair())
        {
            P sources = replicaPlan.getWithContacts(context.replicas);
            listener = wrapMergeListener(readRepair.getMergeListener(sources), sources, repairedDataTracker);
        }

        return resolveInternal(context, listener, responseProvider, preCountFilter);
    }

    @SuppressWarnings("resource")
    private PartitionIterator resolveWithReplicaFilteringProtection(E replicas, RepairedDataTracker repairedDataTracker)
    {
        // Protecting against inconsistent replica filtering (some replica returning a row that is outdated but that
        // wouldn't be removed by normal reconciliation because up-to-date replica have filtered the up-to-date version
        // of that row) works in 3 steps:
        //   1) we read the full response just to collect rows that may be outdated (the ones we got from some
        //      replica but didn't got any response for other; it could be those other replica have filtered a more
        //      up-to-date result). In doing so, we do not count any of such "potentially outdated" row towards the
        //      query limit. This simulate the worst case scenario where all those "potentially outdated" rows are
        //      indeed outdated, and thus make sure we are guaranteed to read enough results (thanks to short read
        //      protection).
        //   2) we query all the replica/rows we need to rule out whether those "potentially outdated" rows are outdated
        //      or not.
        //   3) we re-read cached copies of each replica response using the "normal" read path merge with read-repair,
        //      but where for each replica we use their original response _plus_ the additional rows queried in the
        //      previous step (and apply the command#rowFilter() on the full result). Since the first phase has
        //      pessimistically collected enough results for the case where all potentially outdated results are indeed
        //      outdated, we shouldn't need further short-read protection requests during this phase.

        // We need separate contexts, as each context has his own counter
        ResolveContext firstPhaseContext = new ResolveContext(replicas);
        ResolveContext secondPhaseContext = new ResolveContext(replicas);
        ReplicaFilteringProtection<E> rfp = new ReplicaFilteringProtection<>(replicaPlan().keyspace(),
                                                                             command,
                                                                             replicaPlan().consistencyLevel(),
                                                                             queryStartNanoTime,
                                                                             firstPhaseContext.replicas);
        PartitionIterator firstPhasePartitions = resolveInternal(firstPhaseContext,
                                                                 rfp.mergeController(),
                                                                 i -> shortReadProtectedResponse(i, firstPhaseContext),
                                                                 UnaryOperator.identity());

        // Consume the first phase partitions to populate the replica filtering protection with both those materialized
        // partitions and the primary keys to be fetched.
        PartitionIterators.consume(firstPhasePartitions);
        firstPhasePartitions.close();

        // After reading the entire query results the protection helper should have cached all the partitions so we can
        // clear the responses accumulator for the sake of memory usage, given that the second phase might take long if
        // it needs to query replicas.
        responses.clearUnsafe();

        return resolveWithReadRepair(secondPhaseContext,
                                     rfp::queryProtectedPartitions,
                                     results -> command.rowFilter().filter(results, command.metadata(), command.nowInSec()),
                                     repairedDataTracker);
    }

    @SuppressWarnings("resource")
    private PartitionIterator resolveInternal(ResolveContext context,
                                              UnfilteredPartitionIterators.MergeListener mergeListener,
                                              ResponseProvider responseProvider,
                                              UnaryOperator<PartitionIterator> preCountFilter)
    {
        int count = context.replicas.size();
        List<UnfilteredPartitionIterator> results = new ArrayList<>(count);
        for (int i = 0; i < count; i++)
            results.add(responseProvider.getResponse(i));

        /*
         * Even though every response, individually, will honor the limit, it is possible that we will, after the merge,
         * have more rows than the client requested. To make sure that we still conform to the original limit,
         * we apply a top-level post-reconciliation counter to the merged partition iterator.
         *
         * Short read protection logic (ShortReadRowsProtection.moreContents()) relies on this counter to be applied
         * to the current partition to work. For this reason we have to apply the counter transformation before
         * empty partition discard logic kicks in - for it will eagerly consume the iterator.
         *
         * That's why the order here is: 1) merge; 2) filter rows; 3) count; 4) discard empty partitions
         *
         * See CASSANDRA-13747 for more details.
         */

        UnfilteredPartitionIterator merged = UnfilteredPartitionIterators.merge(results, mergeListener);
        FilteredPartitions filtered = FilteredPartitions.filter(merged, new Filter(command.nowInSec(), command.metadata().enforceStrictLiveness()));
        PartitionIterator counted = Transformation.apply(preCountFilter.apply(filtered), context.mergedResultCounter);
        return Transformation.apply(counted, new EmptyPartitionsDiscarder());
    }

    protected RepairedDataVerifier getRepairedDataVerifier(ReadCommand command)
    {
        return RepairedDataVerifier.verifier(command);
    }

    private String makeResponsesDebugString(DecoratedKey partitionKey)
    {
        return Joiner.on(",\n").join(transform(getMessages().snapshot(), m -> m.from() + " => " + m.payload.toDebugString(command, partitionKey)));
    }

    private UnfilteredPartitionIterators.MergeListener wrapMergeListener(UnfilteredPartitionIterators.MergeListener partitionListener,
                                                                         P sources,
                                                                         RepairedDataTracker repairedDataTracker)
    {
        // Avoid wrapping no-op listener as it doesn't throw, unless we're tracking repaired status
        // in which case we need to inject the tracker & verify on close
        if (partitionListener == UnfilteredPartitionIterators.MergeListener.NOOP)
        {
            if (repairedDataTracker == null)
                return partitionListener;

            return new UnfilteredPartitionIterators.MergeListener()
            {

                public UnfilteredRowIterators.MergeListener getRowMergeListener(DecoratedKey partitionKey, List<UnfilteredRowIterator> versions)
                {
                    return UnfilteredRowIterators.MergeListener.NOOP;
                }

                public void close()
                {
                    repairedDataTracker.verify();
                }
            };
        }

        return new UnfilteredPartitionIterators.MergeListener()
        {
            public UnfilteredRowIterators.MergeListener getRowMergeListener(DecoratedKey partitionKey, List<UnfilteredRowIterator> versions)
            {
                UnfilteredRowIterators.MergeListener rowListener = partitionListener.getRowMergeListener(partitionKey, versions);

                return new UnfilteredRowIterators.MergeListener()
                {
                    public void onMergedPartitionLevelDeletion(DeletionTime mergedDeletion, DeletionTime[] versions)
                    {
                        try
                        {
                            rowListener.onMergedPartitionLevelDeletion(mergedDeletion, versions);
                        }
                        catch (AssertionError e)
                        {
                            // The following can be pretty verbose, but it's really only triggered if a bug happen, so we'd
                            // rather get more info to debug than not.
                            TableMetadata table = command.metadata();
                            String details = String.format("Error merging partition level deletion on %s: merged=%s, versions=%s, sources={%s}, debug info:%n %s",
                                                           table,
                                                           mergedDeletion == null ? "null" : mergedDeletion.toString(),
                                                           '[' + Joiner.on(", ").join(transform(Arrays.asList(versions), rt -> rt == null ? "null" : rt.toString())) + ']',
                                                           sources.contacts(),
                                                           makeResponsesDebugString(partitionKey));
                            throw new AssertionError(details, e);
                        }
                    }

                    public Row onMergedRows(Row merged, Row[] versions)
                    {
                        try
                        {
                            return rowListener.onMergedRows(merged, versions);
                        }
                        catch (AssertionError e)
                        {
                            // The following can be pretty verbose, but it's really only triggered if a bug happen, so we'd
                            // rather get more info to debug than not.
                            TableMetadata table = command.metadata();
                            String details = String.format("Error merging rows on %s: merged=%s, versions=%s, sources={%s}, debug info:%n %s",
                                                           table,
                                                           merged == null ? "null" : merged.toString(table),
                                                           '[' + Joiner.on(", ").join(transform(Arrays.asList(versions), rt -> rt == null ? "null" : rt.toString(table))) + ']',
                                                           sources.contacts(),
                                                           makeResponsesDebugString(partitionKey));
                            throw new AssertionError(details, e);
                        }
                    }

                    public void onMergedRangeTombstoneMarkers(RangeTombstoneMarker merged, RangeTombstoneMarker[] versions)
                    {
                        try
                        {
                            // The code for merging range tombstones is a tad complex and we had the assertions there triggered
                            // unexpectedly in a few occasions (CASSANDRA-13237, CASSANDRA-13719). It's hard to get insights
                            // when that happen without more context that what the assertion errors give us however, hence the
                            // catch here that basically gather as much as context as reasonable.
                            rowListener.onMergedRangeTombstoneMarkers(merged, versions);
                        }
                        catch (AssertionError e)
                        {

                            // The following can be pretty verbose, but it's really only triggered if a bug happen, so we'd
                            // rather get more info to debug than not.
                            TableMetadata table = command.metadata();
                            String details = String.format("Error merging RTs on %s: merged=%s, versions=%s, sources={%s}, debug info:%n %s",
                                                           table,
                                                           merged == null ? "null" : merged.toString(table),
                                                           '[' + Joiner.on(", ").join(transform(Arrays.asList(versions), rt -> rt == null ? "null" : rt.toString(table))) + ']',
                                                           sources.contacts(),
                                                           makeResponsesDebugString(partitionKey));
                            throw new AssertionError(details, e);
                        }

                    }

                    public void close()
                    {
                        rowListener.close();
                    }
                };
            }

            public void close()
            {
                partitionListener.close();
                if (repairedDataTracker != null)
                    repairedDataTracker.verify();
            }
        };
    }
}
