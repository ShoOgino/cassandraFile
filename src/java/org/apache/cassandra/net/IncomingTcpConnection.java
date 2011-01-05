package org.apache.cassandra.net;
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


import java.io.*;
import java.net.Socket;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.apache.cassandra.streaming.IncomingStreamReader;
import org.apache.cassandra.streaming.StreamHeader;

public class IncomingTcpConnection extends Thread
{
    private static Logger logger = LoggerFactory.getLogger(IncomingTcpConnection.class);

    private final DataInputStream input;
    private Socket socket;

    public IncomingTcpConnection(Socket socket)
    {
        assert socket != null;
        this.socket = socket;
        try
        {
            input = new DataInputStream(new BufferedInputStream(socket.getInputStream(), 4096));
        }
        catch (IOException e)
        {
            close();
            throw new IOError(e);
        }
    }

    @Override
    public void run()
    {
        while (true)
        {
            try
            {
                MessagingService.validateMagic(input.readInt());
                int header = input.readInt();
                int type = MessagingService.getBits(header, 1, 2);
                boolean isStream = MessagingService.getBits(header, 3, 1) == 1;
                int version = MessagingService.getBits(header, 15, 8);

                if (isStream)
                {
                    int size = input.readInt();
                    byte[] headerBytes = new byte[size];
                    input.readFully(headerBytes);
                    StreamHeader streamHeader = StreamHeader.serializer().deserialize(new DataInputStream(new ByteArrayInputStream(headerBytes)));
                    new IncomingStreamReader(streamHeader, socket.getChannel()).read();
                }
                else
                {
                    int size = input.readInt();
                    byte[] contentBytes = new byte[size];
                    input.readFully(contentBytes);
                    
                    Message message = Message.serializer().deserialize(new DataInputStream(new ByteArrayInputStream(contentBytes)));
                    MessagingService.receive(message);
                }
            }
            catch (EOFException e)
            {
                if (logger.isTraceEnabled())
                    logger.trace("eof reading from socket; closing", e);
                break;
            }
            catch (IOException e) 
            {
                if (logger.isDebugEnabled())
                    logger.debug("error reading from socket; closing", e);
                break;
            }
        }

        close();
    }

    private void close()
    {
        try
        {
            socket.close();
        }
        catch (IOException e)
        {
            if (logger.isDebugEnabled())
                logger.debug("error closing socket", e);
        }
    }
}
