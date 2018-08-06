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

package org.apache.cassandra.tools.fqltool;


import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

import com.datastax.driver.core.BatchStatement;
import io.netty.buffer.Unpooled;
import net.openhft.chronicle.core.io.IORuntimeException;
import net.openhft.chronicle.wire.ReadMarshallable;
import net.openhft.chronicle.wire.ValueIn;
import net.openhft.chronicle.wire.WireIn;
import org.apache.cassandra.cql3.QueryOptions;
import org.apache.cassandra.transport.ProtocolVersion;

import static org.apache.cassandra.audit.FullQueryLogger.GENERATED_NOW_IN_SECONDS;
import static org.apache.cassandra.audit.FullQueryLogger.GENERATED_TIMESTAMP;
import static org.apache.cassandra.audit.FullQueryLogger.KEYSPACE;
import static org.apache.cassandra.audit.FullQueryLogger.PROTOCOL_VERSION;
import static org.apache.cassandra.audit.FullQueryLogger.QUERY_OPTIONS;
import static org.apache.cassandra.audit.FullQueryLogger.QUERY_START_TIME;
import static org.apache.cassandra.audit.FullQueryLogger.TYPE;
import static org.apache.cassandra.audit.FullQueryLogger.VERSION;
import static org.apache.cassandra.audit.FullQueryLogger.BATCH;
import static org.apache.cassandra.audit.FullQueryLogger.BATCH_TYPE;
import static org.apache.cassandra.audit.FullQueryLogger.QUERIES;
import static org.apache.cassandra.audit.FullQueryLogger.QUERY;
import static org.apache.cassandra.audit.FullQueryLogger.SINGLE_QUERY;
import static org.apache.cassandra.audit.FullQueryLogger.VALUES;

public class FQLQueryReader implements ReadMarshallable
{
    private FQLQuery query;

    public void readMarshallable(WireIn wireIn) throws IORuntimeException
    {
        int currentVersion = wireIn.read(VERSION).int16();
        String type = wireIn.read(TYPE).text();
        long queryStartTime = wireIn.read(QUERY_START_TIME).int64();
        int protocolVersion = wireIn.read(PROTOCOL_VERSION).int32();
        QueryOptions queryOptions = QueryOptions.codec.decode(Unpooled.wrappedBuffer(wireIn.read(QUERY_OPTIONS).bytes()), ProtocolVersion.decode(protocolVersion));
        long generatedTimestamp = wireIn.read(GENERATED_TIMESTAMP).int64();
        int generatedNowInSeconds = wireIn.read(GENERATED_NOW_IN_SECONDS).int32();
        String keyspace = wireIn.read(KEYSPACE).text();

        switch (type)
        {
            case SINGLE_QUERY:
                String queryString = wireIn.read(QUERY).text();
                query = new FQLQuery.Single(keyspace,
                                            protocolVersion,
                                            queryOptions,
                                            queryStartTime,
                                            generatedTimestamp,
                                            generatedNowInSeconds,
                                            queryString,
                                            queryOptions.getValues());
                break;
            case BATCH:
                BatchStatement.Type batchType = BatchStatement.Type.valueOf(wireIn.read(BATCH_TYPE).text());
                ValueIn in = wireIn.read(QUERIES);
                int queryCount = in.int32();

                List<String> queries = new ArrayList<>(queryCount);
                for (int i = 0; i < queryCount; i++)
                    queries.add(in.text());
                in = wireIn.read(VALUES);
                int valueCount = in.int32();
                List<List<ByteBuffer>> values = new ArrayList<>(valueCount);
                for (int ii = 0; ii < valueCount; ii++)
                {
                    List<ByteBuffer> subValues = new ArrayList<>();
                    values.add(subValues);
                    int numSubValues = in.int32();
                    for (int zz = 0; zz < numSubValues; zz++)
                        subValues.add(ByteBuffer.wrap(in.bytes()));
                }
                query = new FQLQuery.Batch(keyspace,
                                           protocolVersion,
                                           queryOptions,
                                           queryStartTime,
                                           generatedTimestamp,
                                           generatedNowInSeconds,
                                           batchType,
                                           queries,
                                           values);
                break;
            default:
                throw new RuntimeException("Unknown type: " + type);
        }
    }

    public FQLQuery getQuery()
    {
        return query;
    }
}