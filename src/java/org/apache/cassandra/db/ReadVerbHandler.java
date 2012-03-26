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

import java.io.DataInputStream;
import java.io.IOException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.apache.cassandra.io.util.FastByteArrayInputStream;
import org.apache.cassandra.net.IVerbHandler;
import org.apache.cassandra.net.Message;
import org.apache.cassandra.net.MessageOut;
import org.apache.cassandra.net.MessagingService;
import org.apache.cassandra.service.StorageService;
import org.apache.cassandra.utils.ByteBufferUtil;

public class ReadVerbHandler implements IVerbHandler
{
    private static final Logger logger = LoggerFactory.getLogger( ReadVerbHandler.class );

    public void doVerb(Message message, String id)
    {
        if (StorageService.instance.isBootstrapMode())
        {
            throw new RuntimeException("Cannot service reads while bootstrapping!");
        }

        try
        {
            FastByteArrayInputStream in = new FastByteArrayInputStream(message.getMessageBody());
            ReadCommand command = ReadCommand.serializer().deserialize(new DataInputStream(in), message.getVersion());
            Table table = Table.open(command.table);
            Row row = command.getRow(table);

            MessageOut<ReadResponse> reply = new MessageOut<ReadResponse>(StorageService.Verb.REQUEST_RESPONSE,
                                                                          getResponse(command, row),
                                                                          ReadResponse.serializer());
            if (logger.isDebugEnabled())
              logger.debug(String.format("Read key %s; sending response to %s@%s",
                                          ByteBufferUtil.bytesToHex(command.key), id, message.getFrom()));
            MessagingService.instance().sendReply(reply, id, message.getFrom());
        }
        catch (IOException ex)
        {
            throw new RuntimeException(ex);
        }
    }

    public static ReadResponse getResponse(ReadCommand command, Row row)
    {
        if (command.isDigestQuery())
        {
            if (logger.isDebugEnabled())
                logger.debug("digest is " + ByteBufferUtil.bytesToHex(ColumnFamily.digest(row.cf)));
            return new ReadResponse(ColumnFamily.digest(row.cf));
        }
        else
        {
            return new ReadResponse(row);
        }
    }
}
