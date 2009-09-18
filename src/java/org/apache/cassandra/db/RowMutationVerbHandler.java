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

package org.apache.cassandra.db;

import java.io.*;

import org.apache.cassandra.io.DataInputBuffer;
import org.apache.cassandra.net.EndPoint;
import org.apache.cassandra.net.IVerbHandler;
import org.apache.cassandra.net.Message;

import org.apache.log4j.Logger;

import org.apache.cassandra.net.*;

public class RowMutationVerbHandler implements IVerbHandler
{
    private static Logger logger_ = Logger.getLogger(RowMutationVerbHandler.class);

    public void doVerb(Message message)
    {
        byte[] bytes = message.getMessageBody();
        DataInputBuffer buffer = new DataInputBuffer();
        buffer.reset(bytes, bytes.length);

        try
        {
            RowMutation rm = RowMutation.serializer().deserialize(buffer);
            if (logger_.isDebugEnabled())
              logger_.debug("Applying " + rm);

            /* Check if there were any hints in this message */
            byte[] hintedBytes = message.getHeader(RowMutation.HINT);
            if ( hintedBytes != null && hintedBytes.length > 0 )
            {
            	EndPoint hint = EndPoint.fromBytes(hintedBytes);
                if (logger_.isDebugEnabled())
                  logger_.debug("Adding hint for " + hint);
                /* add necessary hints to this mutation */
                RowMutation hintedMutation = new RowMutation(Table.SYSTEM_TABLE, rm.table());
                hintedMutation.addHints(rm.key(), hint.getHost());
                hintedMutation.apply();
            }

            rm.apply();

            WriteResponse response = new WriteResponse(rm.table(), rm.key(), true);
            Message responseMessage = WriteResponse.makeWriteResponseMessage(message, response);
            if (logger_.isDebugEnabled())
              logger_.debug(rm + " applied.  Sending response to " + message.getMessageId() + "@" + message.getFrom());
            MessagingService.getMessagingInstance().sendOneWay(responseMessage, message.getFrom());
        }
        catch (IOException e)
        {
            logger_.error("Error in row mutation", e);
        }
    }
}
