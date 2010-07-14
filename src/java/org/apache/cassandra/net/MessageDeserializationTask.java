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

package org.apache.cassandra.net;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.apache.cassandra.config.DatabaseDescriptor;
import org.apache.cassandra.net.sink.SinkManager;
import org.apache.cassandra.utils.WrappedRunnable;

class MessageDeserializationTask extends WrappedRunnable
{
    private static Logger logger = LoggerFactory.getLogger(MessageDeserializationTask.class);
    
    private final ByteArrayInputStream bytes;
    private final long constructionTime = System.currentTimeMillis();
    
    MessageDeserializationTask(ByteArrayInputStream bytes)
    {
        this.bytes = bytes;
    }

    public void runMayThrow() throws IOException
    {
        if (System.currentTimeMillis() >  constructionTime + DatabaseDescriptor.getRpcTimeout())
        {
            logger.warn(String.format("dropping message (%,dms past timeout)",
                                      System.currentTimeMillis() - (constructionTime + DatabaseDescriptor.getRpcTimeout())));
            return;
        }

        Message message = Message.serializer().deserialize(new DataInputStream(bytes));
        message = SinkManager.processServerMessageSink(message);
        MessagingService.receive(message);
    }
}
