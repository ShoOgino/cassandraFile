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
package org.apache.cassandra.db.context;

import static org.junit.Assert.*;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.*;

import org.apache.commons.lang.ArrayUtils;

import org.junit.Test;

import org.apache.cassandra.Util;
import org.apache.cassandra.db.context.IContext.ContextRelationship;
import org.apache.cassandra.utils.FBUtilities;

/**
 * Note: these tests assume IPv4 (4 bytes) is used for id.
 *       if IPv6 (16 bytes) is used, tests will fail (but the code will work).
 *       however, it might be pragmatic to modify the code to just use
 *       the IPv4 portion of the IPv6 address-space.
 */
public class CounterContextTest
{
    private static final CounterContext cc = new CounterContext();

    private static final InetAddress idAddress;
    private static final byte[] id;
    private static final int idLength;
    private static final int clockLength;
    private static final int countLength;

    private static final int stepLength;
    private static final int defaultEntries;

    static
    {
        idAddress      = FBUtilities.getLocalAddress();
        id             = idAddress.getAddress();
        idLength       = 4; // size of int
        clockLength    = 8; // size of long
        countLength    = 8; // size of long
        stepLength     = idLength + clockLength + countLength;

        defaultEntries = 10;
    }

    @Test
    public void testCreate()
    {
        byte[] context = cc.create();
        assert context.length == 0;
    }

    @Test
    public void testUpdatePresent() throws UnknownHostException
    {
        byte[] context;

        context = new byte[stepLength * defaultEntries];

        for (int i = 0; i < defaultEntries; i++)
        {
            cc.writeElementAtStepOffset(
                context,
                i,
                FBUtilities.toByteArray(i),
                1L,
                1L);
        }
        context = cc.update(context, InetAddress.getByAddress(FBUtilities.toByteArray(defaultEntries - 1)), 10L);

        assertEquals(context.length, stepLength * defaultEntries);
        int offset = (defaultEntries - 1) * stepLength;
        assertEquals(  2L, FBUtilities.byteArrayToLong(context, offset + idLength));
        assertEquals( 11L, FBUtilities.byteArrayToLong(context, offset + idLength + clockLength));
        for (int i = 0; i < defaultEntries - 1; i++)
        {
            offset = i * stepLength;
            assertEquals( i, FBUtilities.byteArrayToInt(context,  offset));
            assertEquals(1L, FBUtilities.byteArrayToLong(context, offset + idLength));
            assertEquals(1L, FBUtilities.byteArrayToLong(context, offset + idLength + clockLength));
        }
    }

    @Test
    public void testUpdateNotPresent() throws UnknownHostException
    {
        byte[] context = new byte[stepLength * 3];

        for (int i = 0; i < 3; i++)
        {
            cc.writeElementAtStepOffset(
                context,
                i,
                FBUtilities.toByteArray(i * 2),
                1L,
                1L);
        }

        context = cc.update(context, InetAddress.getByAddress(FBUtilities.toByteArray(3)), 328L);

        assert context.length == stepLength * 4;
        int offset = 2 * stepLength;
        assert   1L == FBUtilities.byteArrayToLong(context, offset + idLength);
        assert 328L == FBUtilities.byteArrayToLong(context, offset + idLength + clockLength);
        for (int i = 1; i < 2; i++)
        {
            offset = i * stepLength;
            assert 2 * i == FBUtilities.byteArrayToInt(context,  offset);
            assert    1L == FBUtilities.byteArrayToLong(context, offset + idLength);
            assert    1L == FBUtilities.byteArrayToLong(context, offset + idLength + clockLength);
        }
        offset = 3 * stepLength;
        assert  4 == FBUtilities.byteArrayToInt(context,  offset);
        assert 1L == FBUtilities.byteArrayToLong(context, offset + idLength);
        assert 1L == FBUtilities.byteArrayToLong(context, offset + idLength + clockLength);
    }

    @Test
    public void testDiff()
    {
        byte[] left = new byte[3 * stepLength];
        byte[] right;

        // equality: equal nodes, all counts same
        cc.writeElementAtStepOffset(left, 0, FBUtilities.toByteArray(3), 3L, 0L);
        cc.writeElementAtStepOffset(left, 1, FBUtilities.toByteArray(6), 2L, 0L);
        cc.writeElementAtStepOffset(left, 2, FBUtilities.toByteArray(9), 1L, 0L);
        right = ArrayUtils.clone(left);

        assert ContextRelationship.EQUAL ==
            cc.diff(left, right);

        // greater than: left has superset of nodes (counts equal)
        left = new byte[4 * stepLength];
        cc.writeElementAtStepOffset(left, 0, FBUtilities.toByteArray(3),  3L, 0L);
        cc.writeElementAtStepOffset(left, 1, FBUtilities.toByteArray(6),  2L, 0L);
        cc.writeElementAtStepOffset(left, 2, FBUtilities.toByteArray(9),  1L, 0L);
        cc.writeElementAtStepOffset(left, 3, FBUtilities.toByteArray(12), 0L, 0L);

        right = new byte[3 * stepLength];
        cc.writeElementAtStepOffset(right, 0, FBUtilities.toByteArray(3), 3L, 0L);
        cc.writeElementAtStepOffset(right, 1, FBUtilities.toByteArray(6), 2L, 0L);
        cc.writeElementAtStepOffset(right, 2, FBUtilities.toByteArray(9), 1L, 0L);

        assert ContextRelationship.GREATER_THAN ==
            cc.diff(left, right);
        
        // less than: left has subset of nodes (counts equal)
        left = new byte[3 * stepLength];
        cc.writeElementAtStepOffset(left, 0, FBUtilities.toByteArray(3), 3L, 0L);
        cc.writeElementAtStepOffset(left, 1, FBUtilities.toByteArray(6), 2L, 0L);
        cc.writeElementAtStepOffset(left, 2, FBUtilities.toByteArray(9), 1L, 0L);

        right = new byte[4 * stepLength];
        cc.writeElementAtStepOffset(right, 0, FBUtilities.toByteArray(3),  3L, 0L);
        cc.writeElementAtStepOffset(right, 1, FBUtilities.toByteArray(6),  2L, 0L);
        cc.writeElementAtStepOffset(right, 2, FBUtilities.toByteArray(9),  1L, 0L);
        cc.writeElementAtStepOffset(right, 3, FBUtilities.toByteArray(12), 0L, 0L);

        assert ContextRelationship.LESS_THAN ==
            cc.diff(left, right);

        // greater than: equal nodes, but left has higher counts
        left = new byte[3 * stepLength];
        cc.writeElementAtStepOffset(left, 0, FBUtilities.toByteArray(3), 3L, 0L);
        cc.writeElementAtStepOffset(left, 1, FBUtilities.toByteArray(6), 2L, 0L);
        cc.writeElementAtStepOffset(left, 2, FBUtilities.toByteArray(9), 3L, 0L);

        right = new byte[3 * stepLength];
        cc.writeElementAtStepOffset(right, 0, FBUtilities.toByteArray(3), 3L, 0L);
        cc.writeElementAtStepOffset(right, 1, FBUtilities.toByteArray(6), 2L, 0L);
        cc.writeElementAtStepOffset(right, 2, FBUtilities.toByteArray(9), 1L, 0L);

        assert ContextRelationship.GREATER_THAN ==
            cc.diff(left, right);

        // less than: equal nodes, but right has higher counts
        left = new byte[3 * stepLength];
        cc.writeElementAtStepOffset(left, 0, FBUtilities.toByteArray(3), 3L, 0L);
        cc.writeElementAtStepOffset(left, 1, FBUtilities.toByteArray(6), 2L, 0L);
        cc.writeElementAtStepOffset(left, 2, FBUtilities.toByteArray(9), 3L, 0L);

        right = new byte[3 * stepLength];
        cc.writeElementAtStepOffset(right, 0, FBUtilities.toByteArray(3), 3L, 0L);
        cc.writeElementAtStepOffset(right, 1, FBUtilities.toByteArray(6), 9L, 0L);
        cc.writeElementAtStepOffset(right, 2, FBUtilities.toByteArray(9), 3L, 0L);

        assert ContextRelationship.LESS_THAN ==
            cc.diff(left, right);

        // disjoint: right and left have disjoint node sets
        left = new byte[3 * stepLength];
        cc.writeElementAtStepOffset(left, 0, FBUtilities.toByteArray(3), 1L, 0L);
        cc.writeElementAtStepOffset(left, 1, FBUtilities.toByteArray(4), 1L, 0L);
        cc.writeElementAtStepOffset(left, 2, FBUtilities.toByteArray(9), 1L, 0L);

        right = new byte[3 * stepLength];
        cc.writeElementAtStepOffset(right, 0, FBUtilities.toByteArray(3), 1L, 0L);
        cc.writeElementAtStepOffset(right, 1, FBUtilities.toByteArray(6), 1L, 0L);
        cc.writeElementAtStepOffset(right, 2, FBUtilities.toByteArray(9), 1L, 0L);

        assert ContextRelationship.DISJOINT ==
            cc.diff(left, right);

        left = new byte[3 * stepLength];
        cc.writeElementAtStepOffset(left, 0, FBUtilities.toByteArray(3), 1L, 0L);
        cc.writeElementAtStepOffset(left, 1, FBUtilities.toByteArray(4), 1L, 0L);
        cc.writeElementAtStepOffset(left, 2, FBUtilities.toByteArray(9), 1L, 0L);

        right = new byte[3 * stepLength];
        cc.writeElementAtStepOffset(right, 0, FBUtilities.toByteArray(2),  1L, 0L);
        cc.writeElementAtStepOffset(right, 1, FBUtilities.toByteArray(6),  1L, 0L);
        cc.writeElementAtStepOffset(right, 2, FBUtilities.toByteArray(12), 1L, 0L);

        assert ContextRelationship.DISJOINT ==
            cc.diff(left, right);

        // disjoint: equal nodes, but right and left have higher counts in differing nodes
        left = new byte[3 * stepLength];
        cc.writeElementAtStepOffset(left, 0, FBUtilities.toByteArray(3), 1L, 0L);
        cc.writeElementAtStepOffset(left, 1, FBUtilities.toByteArray(6), 3L, 0L);
        cc.writeElementAtStepOffset(left, 2, FBUtilities.toByteArray(9), 1L, 0L);

        right = new byte[3 * stepLength];
        cc.writeElementAtStepOffset(right, 0, FBUtilities.toByteArray(3), 1L, 0L);
        cc.writeElementAtStepOffset(right, 1, FBUtilities.toByteArray(6), 1L, 0L);
        cc.writeElementAtStepOffset(right, 2, FBUtilities.toByteArray(9), 5L, 0L);

        assert ContextRelationship.DISJOINT ==
            cc.diff(left, right);

        left = new byte[3 * stepLength];
        cc.writeElementAtStepOffset(left, 0, FBUtilities.toByteArray(3), 2L, 0L);
        cc.writeElementAtStepOffset(left, 1, FBUtilities.toByteArray(6), 3L, 0L);
        cc.writeElementAtStepOffset(left, 2, FBUtilities.toByteArray(9), 1L, 0L);

        right = new byte[3 * stepLength];
        cc.writeElementAtStepOffset(right, 0, FBUtilities.toByteArray(3), 1L, 0L);
        cc.writeElementAtStepOffset(right, 1, FBUtilities.toByteArray(6), 9L, 0L);
        cc.writeElementAtStepOffset(right, 2, FBUtilities.toByteArray(9), 5L, 0L);

        assert ContextRelationship.DISJOINT ==
            cc.diff(left, right);

        // disjoint: left has more nodes, but lower counts
        left = new byte[4 * stepLength];
        cc.writeElementAtStepOffset(left, 0, FBUtilities.toByteArray(3),  2L, 0L);
        cc.writeElementAtStepOffset(left, 1, FBUtilities.toByteArray(6),  3L, 0L);
        cc.writeElementAtStepOffset(left, 2, FBUtilities.toByteArray(9),  1L, 0L);
        cc.writeElementAtStepOffset(left, 3, FBUtilities.toByteArray(12), 1L, 0L);

        right = new byte[3 * stepLength];
        cc.writeElementAtStepOffset(right, 0, FBUtilities.toByteArray(3), 4L, 0L);
        cc.writeElementAtStepOffset(right, 1, FBUtilities.toByteArray(6), 9L, 0L);
        cc.writeElementAtStepOffset(right, 2, FBUtilities.toByteArray(9), 5L, 0L);

        assert ContextRelationship.DISJOINT ==
            cc.diff(left, right);
        
        // disjoint: left has less nodes, but higher counts
        left = new byte[3 * stepLength];
        cc.writeElementAtStepOffset(left, 0, FBUtilities.toByteArray(3), 5L, 0L);
        cc.writeElementAtStepOffset(left, 1, FBUtilities.toByteArray(6), 3L, 0L);
        cc.writeElementAtStepOffset(left, 2, FBUtilities.toByteArray(9), 2L, 0L);

        right = new byte[4 * stepLength];
        cc.writeElementAtStepOffset(right, 0, FBUtilities.toByteArray(3),  4L, 0L);
        cc.writeElementAtStepOffset(right, 1, FBUtilities.toByteArray(6),  3L, 0L);
        cc.writeElementAtStepOffset(right, 2, FBUtilities.toByteArray(9),  2L, 0L);
        cc.writeElementAtStepOffset(right, 3, FBUtilities.toByteArray(12), 1L, 0L);

        assert ContextRelationship.DISJOINT ==
            cc.diff(left, right);

        // disjoint: mixed nodes and counts
        left = new byte[3 * stepLength];
        cc.writeElementAtStepOffset(left, 0, FBUtilities.toByteArray(3), 5L, 0L);
        cc.writeElementAtStepOffset(left, 1, FBUtilities.toByteArray(6), 2L, 0L);
        cc.writeElementAtStepOffset(left, 2, FBUtilities.toByteArray(9), 2L, 0L);

        right = new byte[4 * stepLength];
        cc.writeElementAtStepOffset(right, 0, FBUtilities.toByteArray(3),  4L, 0L);
        cc.writeElementAtStepOffset(right, 1, FBUtilities.toByteArray(6),  3L, 0L);
        cc.writeElementAtStepOffset(right, 2, FBUtilities.toByteArray(9),  2L, 0L);
        cc.writeElementAtStepOffset(right, 3, FBUtilities.toByteArray(12), 1L, 0L);

        assert ContextRelationship.DISJOINT ==
            cc.diff(left, right);

        left = new byte[4 * stepLength];
        cc.writeElementAtStepOffset(left, 0, FBUtilities.toByteArray(3), 5L, 0L);
        cc.writeElementAtStepOffset(left, 1, FBUtilities.toByteArray(6), 2L, 0L);
        cc.writeElementAtStepOffset(left, 2, FBUtilities.toByteArray(7), 2L, 0L);
        cc.writeElementAtStepOffset(left, 3, FBUtilities.toByteArray(9), 2L, 0L);

        right = new byte[3 * stepLength];
        cc.writeElementAtStepOffset(right, 0, FBUtilities.toByteArray(3), 4L, 0L);
        cc.writeElementAtStepOffset(right, 1, FBUtilities.toByteArray(6), 3L, 0L);
        cc.writeElementAtStepOffset(right, 2, FBUtilities.toByteArray(9), 2L, 0L);

        assert ContextRelationship.DISJOINT ==
            cc.diff(left, right);
    }

    @Test
    public void testMerge()
    {
        // note: local counts aggregated; remote counts are reconciled (i.e. take max)
        byte[] left = new byte[4 * stepLength];
        cc.writeElementAtStepOffset(left, 0, FBUtilities.toByteArray(1), 1L, 1L);
        cc.writeElementAtStepOffset(left, 1, FBUtilities.toByteArray(2), 2L, 2L);
        cc.writeElementAtStepOffset(left, 2, FBUtilities.toByteArray(4), 6L, 3L);
        cc.writeElementAtStepOffset(
            left,
            3,
            FBUtilities.getLocalAddress().getAddress(),
            7L,
            3L);

        byte[] right = new byte[3 * stepLength];
        cc.writeElementAtStepOffset(right, 0, FBUtilities.toByteArray(4), 4L, 4L);
        cc.writeElementAtStepOffset(right, 1, FBUtilities.toByteArray(5), 5L, 5L);
        cc.writeElementAtStepOffset(
            right,
            2,
            FBUtilities.getLocalAddress().getAddress(),
            2L,
            9L);

        byte[] merged = cc.merge(left, right);

        assertEquals(5 * stepLength, merged.length);
        // local node id's counts are aggregated
        assertEquals(0, FBUtilities.compareByteSubArrays(
            FBUtilities.getLocalAddress().getAddress(),
            0,
            merged,
            4*stepLength,
            4));
        assertEquals(  9L, FBUtilities.byteArrayToLong(merged, 4*stepLength + idLength));
        assertEquals(12L,  FBUtilities.byteArrayToLong(merged, 4*stepLength + idLength + clockLength));

        // remote node id counts are reconciled (i.e. take max)
        assertEquals( 4,   FBUtilities.byteArrayToInt(merged,  2*stepLength));
        assertEquals( 6L,  FBUtilities.byteArrayToLong(merged, 2*stepLength + idLength));
        assertEquals( 3L,  FBUtilities.byteArrayToLong(merged, 2*stepLength + idLength + clockLength));

        assertEquals( 5,   FBUtilities.byteArrayToInt(merged,  3*stepLength));
        assertEquals( 5L,  FBUtilities.byteArrayToLong(merged, 3*stepLength + idLength));
        assertEquals( 5L,  FBUtilities.byteArrayToLong(merged, 3*stepLength + idLength + clockLength));

        assertEquals( 2,   FBUtilities.byteArrayToInt(merged,  1*stepLength));
        assertEquals( 2L,  FBUtilities.byteArrayToLong(merged, 1*stepLength + idLength));
        assertEquals( 2L,  FBUtilities.byteArrayToLong(merged, 1*stepLength + idLength + clockLength));

        assertEquals( 1,   FBUtilities.byteArrayToInt(merged,  0*stepLength));
        assertEquals( 1L,  FBUtilities.byteArrayToLong(merged, 0*stepLength + idLength));
        assertEquals( 1L,  FBUtilities.byteArrayToLong(merged, 0*stepLength + idLength + clockLength));
    }

    @Test
    public void testTotal()
    {
        byte[] left = new byte[4 * stepLength];
        cc.writeElementAtStepOffset(left, 0, FBUtilities.toByteArray(1), 1L, 1L);
        cc.writeElementAtStepOffset(left, 1, FBUtilities.toByteArray(2), 2L, 2L);
        cc.writeElementAtStepOffset(left, 2, FBUtilities.toByteArray(4), 3L, 3L);
        cc.writeElementAtStepOffset(
            left,
            3,
            FBUtilities.getLocalAddress().getAddress(),
            3L,
            3L);

        byte[] right = new byte[3 * stepLength];
        cc.writeElementAtStepOffset(right, 0, FBUtilities.toByteArray(4), 4L, 4L);
        cc.writeElementAtStepOffset(right, 1, FBUtilities.toByteArray(5), 5L, 5L);
        cc.writeElementAtStepOffset(
            right,
            2,
            FBUtilities.getLocalAddress().getAddress(),
            9L,
            9L);

        byte[] merged = cc.merge(left, right);

        // 127.0.0.1: 12 (3+9)
        // 0.0.0.1:    1
        // 0.0.0.2:    2
        // 0.0.0.4:    4
        // 0.0.0.5:    5

        assertEquals(24L, FBUtilities.byteArrayToLong(cc.total(merged)));
    }

    @Test
    public void testCleanNodeCounts() throws UnknownHostException
    {
        byte[] bytes = new byte[4 * stepLength];
        cc.writeElementAtStepOffset(bytes, 0, FBUtilities.toByteArray(1), 1L, 1L);
        cc.writeElementAtStepOffset(bytes, 1, FBUtilities.toByteArray(2), 2L, 2L);
        cc.writeElementAtStepOffset(bytes, 2, FBUtilities.toByteArray(4), 3L, 3L);
        cc.writeElementAtStepOffset(bytes, 3, FBUtilities.toByteArray(8), 4L, 4L);

        assertEquals(4, FBUtilities.byteArrayToInt(bytes,  2*stepLength));
        assertEquals(3L, FBUtilities.byteArrayToLong(bytes, 2*stepLength + idLength));

        bytes = cc.cleanNodeCounts(bytes, InetAddress.getByAddress(FBUtilities.toByteArray(4)));

        // node: 0.0.0.4 should be removed
        assertEquals(3 * stepLength, bytes.length);

        // other nodes should be unaffected
        assertEquals(1, FBUtilities.byteArrayToInt(bytes,  0*stepLength));
        assertEquals(1L, FBUtilities.byteArrayToLong(bytes, 0*stepLength + idLength));

        assertEquals(2, FBUtilities.byteArrayToInt(bytes,  1*stepLength));
        assertEquals(2L, FBUtilities.byteArrayToLong(bytes, 1*stepLength + idLength));

        assertEquals(8, FBUtilities.byteArrayToInt(bytes,  2*stepLength));
        assertEquals(4L, FBUtilities.byteArrayToLong(bytes, 2*stepLength + idLength));
    }
}
