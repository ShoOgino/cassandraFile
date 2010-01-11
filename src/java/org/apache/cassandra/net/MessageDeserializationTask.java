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

import org.apache.cassandra.net.sink.SinkManager;

import org.apache.log4j.Logger;

class MessageDeserializationTask implements Runnable
{
    private static Logger logger_ = Logger.getLogger(MessageDeserializationTask.class); 
    private byte[] bytes_ = new byte[0];
    
    MessageDeserializationTask(byte[] bytes)
    {
        bytes_ = bytes;        
    }
    
    public void run()
    {
        Message message = null;
        try
        {
            message = Message.serializer().deserialize(new DataInputStream(new ByteArrayInputStream(bytes_)));
        }
        catch (IOException e)
        {
            throw new RuntimeException(e);
        }

        if ( message != null )
        {
            message = SinkManager.processServerMessageSink(message);
            MessagingService.receive(message);
        }
    }

}
