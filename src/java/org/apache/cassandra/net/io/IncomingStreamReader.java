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

package org.apache.cassandra.net.io;

import java.net.InetSocketAddress;
import java.net.InetAddress;
import java.nio.channels.FileChannel;
import java.nio.channels.SocketChannel;
import java.io.*;

import org.apache.log4j.Logger;

import org.apache.cassandra.net.FileStreamTask;
import org.apache.cassandra.streaming.IStreamComplete;
import org.apache.cassandra.streaming.InitiatedFile;
import org.apache.cassandra.streaming.StreamInManager;

public class IncomingStreamReader
{
    private static Logger logger = Logger.getLogger(IncomingStreamReader.class);
    private InitiatedFile initiatedFile;
    private StreamInManager.StreamStatus streamStatus;
    private SocketChannel socketChannel;

    public IncomingStreamReader(SocketChannel socketChannel)
    {
        this.socketChannel = socketChannel;
        InetSocketAddress remoteAddress = (InetSocketAddress)socketChannel.socket().getRemoteSocketAddress();
        initiatedFile = StreamInManager.getStreamContext(remoteAddress.getAddress());
        assert initiatedFile != null;
        streamStatus = StreamInManager.getStreamStatus(remoteAddress.getAddress());
        assert streamStatus != null;
    }

    public void read() throws IOException
    {
        InetSocketAddress remoteAddress = (InetSocketAddress)socketChannel.socket().getRemoteSocketAddress();
        if (logger.isDebugEnabled())
          logger.debug("Creating file for " + initiatedFile.getTargetFile());
        FileOutputStream fos = new FileOutputStream(initiatedFile.getTargetFile(), true);
        FileChannel fc = fos.getChannel();

        long bytesRead = 0;
        try
        {
            while (bytesRead < initiatedFile.getExpectedBytes())
                bytesRead += fc.transferFrom(socketChannel, bytesRead, FileStreamTask.CHUNK_SIZE);
        }
        catch (IOException ex)
        {
            /* Ask the source node to re-stream this file. */
            streamStatus.setAction(StreamInManager.StreamCompletionAction.STREAM);
            handleStreamCompletion(remoteAddress.getAddress());
            /* Delete the orphaned file. */
            File file = new File(initiatedFile.getTargetFile());
            file.delete();
            throw ex;
        }

        if (bytesRead == initiatedFile.getExpectedBytes())
        {
            if (logger.isDebugEnabled())
            {
                logger.debug("Removing stream context " + initiatedFile);
            }
            fc.close();
            handleStreamCompletion(remoteAddress.getAddress());
        }
    }

    private void handleStreamCompletion(InetAddress remoteHost) throws IOException
    {
        /*
         * Streaming is complete. If all the data that has to be received inform the sender via
         * the stream completion callback so that the source may perform the requisite cleanup.
        */
        IStreamComplete streamComplete = StreamInManager.getStreamCompletionHandler(remoteHost);
        if (streamComplete != null)
            streamComplete.onStreamCompletion(remoteHost, initiatedFile, streamStatus);
    }
}
