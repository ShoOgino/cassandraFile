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
package org.apache.cassandra.db;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.apache.cassandra.io.FSError;
import org.apache.cassandra.net.IVerbHandler;
import org.apache.cassandra.net.Message;
import org.apache.cassandra.net.MessagingService;
import org.apache.cassandra.tracing.Tracing;

public class TruncateVerbHandler implements IVerbHandler<TruncateRequest>
{
    public static final TruncateVerbHandler instance = new TruncateVerbHandler();

    private static final Logger logger = LoggerFactory.getLogger(TruncateVerbHandler.class);

    public void doVerb(Message<TruncateRequest> message)
    {
        TruncateRequest t = message.payload;
        Tracing.trace("Applying truncation of {}.{}", t.keyspace, t.table);
        try
        {
            ColumnFamilyStore cfs = Keyspace.open(t.keyspace).getColumnFamilyStore(t.table);
            cfs.truncateBlocking();
        }
        catch (Exception e)
        {
            logger.error("Error in truncation", e);
            respondError(t, message);

            if (FSError.findNested(e) != null)
                throw FSError.findNested(e);
        }
        Tracing.trace("Enqueuing response to truncate operation to {}", message.from());

        TruncateResponse response = new TruncateResponse(t.keyspace, t.table, true);
        logger.trace("{} applied.  Enqueuing response to {}@{} ", t, message.id(), message.from());
        MessagingService.instance().send(message.responseWith(response), message.from());
    }

    private static void respondError(TruncateRequest t, Message truncateRequestMessage)
    {
        TruncateResponse response = new TruncateResponse(t.keyspace, t.table, false);
        MessagingService.instance().send(truncateRequestMessage.responseWith(response), truncateRequestMessage.from());
    }
}
