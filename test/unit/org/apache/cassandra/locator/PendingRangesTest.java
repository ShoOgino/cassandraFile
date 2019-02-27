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

package org.apache.cassandra.locator;

import java.net.UnknownHostException;
import java.util.Collections;

import com.google.common.collect.*;
import org.junit.BeforeClass;
import org.junit.Test;


import org.apache.cassandra.config.DatabaseDescriptor;
import org.apache.cassandra.dht.Murmur3Partitioner;
import org.apache.cassandra.dht.Token;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class PendingRangesTest
{
    private static final String RACK1 = "RACK1";
    private static final String DC1 = "DC1";
    private static final String KEYSPACE = "ks";
    private static final InetAddressAndPort PEER1 = peer(1);
    private static final InetAddressAndPort PEER2 = peer(2);
    private static final InetAddressAndPort PEER3 = peer(3);
    private static final InetAddressAndPort PEER4 = peer(4);
    private static final InetAddressAndPort PEER5 = peer(5);
    private static final InetAddressAndPort PEER6 = peer(6);

    private static final InetAddressAndPort PEER1A = peer(11);
    private static final InetAddressAndPort PEER4A = peer(14);

    private static final Token TOKEN1 = token(0);
    private static final Token TOKEN2 = token(10);
    private static final Token TOKEN3 = token(20);
    private static final Token TOKEN4 = token(30);
    private static final Token TOKEN5 = token(40);
    private static final Token TOKEN6 = token(50);

    @BeforeClass
    public static void beforeClass() throws Throwable
    {
        DatabaseDescriptor.daemonInitialization();
        IEndpointSnitch snitch = snitch();
        DatabaseDescriptor.setEndpointSnitch(snitch);
    }

    @Test
    public void calculatePendingRangesForConcurrentReplacements()
    {
        /*
         * As described in CASSANDRA-14802, concurrent range movements can generate pending ranges
         * which are far larger than strictly required, which in turn can impact availability.
         *
         * In the narrow case of straight replacement, the pending ranges should mirror the owned ranges
         * of the nodes being replaced.
         *
         * Note: the following example is purely illustrative as the iteration order for processing
         * bootstrapping endpoints is not guaranteed. Because of this, precisely which endpoints' pending
         * ranges are correct/incorrect depends on the specifics of the ring. Concretely, the bootstrap tokens
         * are ultimately backed by a HashMap, so iteration of bootstrapping nodes is based on the hashcodes
         * of the endpoints.
         *
         * E.g. a 6 node cluster with tokens:
         *
         * nodeA : 0
         * nodeB : 10
         * nodeC : 20
         * nodeD : 30
         * nodeE : 40
         * nodeF : 50
         *
         * with an RF of 3, this gives an initial ring of :
         *
         * nodeA : (50, 0], (40, 50], (30, 40]
         * nodeB : (0, 10], (50, 0], (40, 50]
         * nodeC : (10, 20], (0, 10], (50, 0]
         * nodeD : (20, 30], (10, 20], (0, 10]
         * nodeE : (30, 40], (20, 30], (10, 20]
         * nodeF : (40, 50], (30, 40], (20, 30]
         *
         * If nodeA is replaced by node1A, then the pending ranges map should be:
         * {
         *   (50, 0]  : [node1A],
         *   (40, 50] : [node1A],
         *   (30, 40] : [node1A]
         * }
         *
         * Starting a second concurrent replacement of a node with non-overlapping ranges
         * (i.e. node4 for node4A) should result in a pending range map of:
         * {
         *   (50, 0]  : [node1A],
         *   (40, 50] : [node1A],
         *   (30, 40] : [node1A],
         *   (20, 30] : [node4A],
         *   (10, 20] : [node4A],
         *   (0, 10]  : [node4A]
         * }
         *
         * But, the bug in CASSANDRA-14802 causes it to be:
         * {
         *   (50, 0]  : [node1A],
         *   (40, 50] : [node1A],
         *   (30, 40] : [node1A],
         *   (20, 30] : [node4A],
         *   (10, 20] : [node4A],
         *   (50, 10] : [node4A]
         * }
         *
         * so node4A incorrectly becomes a pending endpoint for an additional sub-range: (50, 0).
         *
         */
        TokenMetadata tm = new TokenMetadata();
        AbstractReplicationStrategy replicationStrategy = simpleStrategy(tm, 3);

        // setup initial ring
        addNode(tm, PEER1, TOKEN1);
        addNode(tm, PEER2, TOKEN2);
        addNode(tm, PEER3, TOKEN3);
        addNode(tm, PEER4, TOKEN4);
        addNode(tm, PEER5, TOKEN5);
        addNode(tm, PEER6, TOKEN6);

        // no pending ranges before any replacements
        tm.calculatePendingRanges(replicationStrategy, KEYSPACE);
        assertEquals(0, Iterators.size(tm.getPendingRanges(KEYSPACE).iterator()));

        // Ranges initially owned by PEER1 and PEER4
        RangesAtEndpoint peer1Ranges = replicationStrategy.getAddressReplicas(tm).get(PEER1);
        RangesAtEndpoint peer4Ranges = replicationStrategy.getAddressReplicas(tm).get(PEER4);
        // Replace PEER1 with PEER1A
        replace(PEER1, PEER1A, TOKEN1, tm, replicationStrategy);
        // The only pending ranges should be the ones previously belonging to PEER1
        // and these should have a single pending endpoint, PEER1A
        RangesByEndpoint.Builder b1 = new RangesByEndpoint.Builder();
        peer1Ranges.iterator().forEachRemaining(replica -> b1.put(PEER1A, new Replica(PEER1A, replica.range(), replica.isFull())));
        RangesByEndpoint expected = b1.build();
        assertPendingRanges(tm.getPendingRanges(KEYSPACE), expected);
        // Also verify the Multimap variant of getPendingRanges
        assertPendingRanges(tm.getPendingRangesMM(KEYSPACE), expected);

        // Replace PEER4 with PEER4A
        replace(PEER4, PEER4A, TOKEN4, tm, replicationStrategy);
        // Pending ranges should now include the ranges originally belonging
        // to PEER1 (now pending for PEER1A) and the ranges originally belonging to PEER4
        // (now pending for PEER4A).
        RangesByEndpoint.Builder b2 = new RangesByEndpoint.Builder();
        peer1Ranges.iterator().forEachRemaining(replica -> b2.put(PEER1A, new Replica(PEER1A, replica.range(), replica.isFull())));
        peer4Ranges.iterator().forEachRemaining(replica -> b2.put(PEER4A, new Replica(PEER4A, replica.range(), replica.isFull())));
        expected = b2.build();
        assertPendingRanges(tm.getPendingRanges(KEYSPACE), expected);
        assertPendingRanges(tm.getPendingRangesMM(KEYSPACE), expected);
    }


    private void assertPendingRanges(PendingRangeMaps pending, RangesByEndpoint expected)
    {
        RangesByEndpoint.Builder actual = new RangesByEndpoint.Builder();
        pending.iterator().forEachRemaining(pendingRange -> {
            Replica replica = Iterators.getOnlyElement(pendingRange.getValue().iterator());
            actual.put(replica.endpoint(), replica);
        });
        assertRangesByEndpoint(expected, actual.build());
    }

    private void assertPendingRanges(EndpointsByRange pending, RangesByEndpoint expected)
    {
        RangesByEndpoint.Builder actual = new RangesByEndpoint.Builder();
        pending.flattenEntries().forEach(entry -> actual.put(entry.getValue().endpoint(), entry.getValue()));
        assertRangesByEndpoint(expected, actual.build());
    }


    private void assertRangesByEndpoint(RangesByEndpoint expected, RangesByEndpoint actual)
    {
        assertEquals(expected.keySet(), actual.keySet());
        for (InetAddressAndPort endpoint : expected.keySet())
        {
            RangesAtEndpoint expectedReplicas = expected.get(endpoint);
            RangesAtEndpoint actualReplicas = actual.get(endpoint);
            assertEquals(expectedReplicas.size(), actualReplicas.size());
            assertTrue(Iterables.all(expectedReplicas, actualReplicas::contains));
        }
    }

    private void addNode(TokenMetadata tm, InetAddressAndPort replica, Token token)
    {
        tm.updateNormalTokens(Collections.singleton(token), replica);
    }

    private void replace(InetAddressAndPort toReplace,
                         InetAddressAndPort replacement,
                         Token token,
                         TokenMetadata tm,
                         AbstractReplicationStrategy replicationStrategy)
    {
        assertEquals(toReplace, tm.getEndpoint(token));
        tm.addReplaceTokens(Collections.singleton(token), replacement, toReplace);
        tm.calculatePendingRanges(replicationStrategy, KEYSPACE);
    }

    private static Token token(long token)
    {
        return Murmur3Partitioner.instance.getTokenFactory().fromString(Long.toString(token));
    }

    private static InetAddressAndPort peer(int addressSuffix)
    {
        try
        {
            return InetAddressAndPort.getByAddress(new byte[]{ 127, 0, 0, (byte) addressSuffix});
        }
        catch (UnknownHostException e)
        {
            throw new RuntimeException(e);
        }
    }

    private static IEndpointSnitch snitch()
    {
        return new AbstractNetworkTopologySnitch()
        {
            public String getRack(InetAddressAndPort endpoint)
            {
                return RACK1;
            }

            public String getDatacenter(InetAddressAndPort endpoint)
            {
                return DC1;
            }
        };
    }

    private static AbstractReplicationStrategy simpleStrategy(TokenMetadata tokenMetadata, int replicationFactor)
    {
        return new SimpleStrategy(KEYSPACE,
                                  tokenMetadata,
                                  DatabaseDescriptor.getEndpointSnitch(),
                                  Collections.singletonMap("replication_factor", Integer.toString(replicationFactor)));
    }
}