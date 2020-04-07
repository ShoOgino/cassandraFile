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

package org.apache.cassandra.transport;

import java.util.List;

import org.junit.Before;
import org.junit.Test;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import org.apache.cassandra.cql3.CQLTester;
import org.apache.cassandra.metrics.ClientRequestSizeMetrics;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Ensures we properly account for metrics tracked in the native protocol
 */
public class ServerMetricsTest extends CQLTester
{
    private long totalBytesReadStart;
    private long totalBytesWrittenStart;

    private long totalBytesReadHistoCount;
    private long totalBytesWrittenHistoCount;

    @Before
    public void setUp()
    {
        totalBytesReadStart = ClientRequestSizeMetrics.totalBytesRead.getCount();
        totalBytesWrittenStart = ClientRequestSizeMetrics.totalBytesWritten.getCount();

        totalBytesReadHistoCount = ClientRequestSizeMetrics.bytesRecievedPerFrame.getCount();
        totalBytesWrittenHistoCount = ClientRequestSizeMetrics.bytesTransmittedPerFrame.getCount();
    }

    @Test
    public void testReadAndWriteMetricsAreRecordedDuringNativeRequests() throws Throwable
    {
        executeNet("SELECT * from system.peers");

        assertThat(ClientRequestSizeMetrics.totalBytesRead.getCount()).isGreaterThan(totalBytesReadStart);
        assertThat(ClientRequestSizeMetrics.totalBytesWritten.getCount()).isGreaterThan(totalBytesWrittenStart);
        assertThat(ClientRequestSizeMetrics.bytesRecievedPerFrame.getCount()).isGreaterThan(totalBytesReadStart);
        assertThat(ClientRequestSizeMetrics.bytesTransmittedPerFrame.getCount()).isGreaterThan(totalBytesWrittenStart);
    }

}
