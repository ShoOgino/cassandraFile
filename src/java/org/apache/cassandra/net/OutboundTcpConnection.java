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
package org.apache.cassandra.net;

import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.InetAddress;
import java.net.Socket;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicLong;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.apache.cassandra.io.util.FileUtils;
import org.apache.cassandra.utils.FBUtilities;
import org.xerial.snappy.SnappyOutputStream;

import org.apache.cassandra.config.Config;
import org.apache.cassandra.config.DatabaseDescriptor;
import org.apache.cassandra.gms.Gossiper;

public class OutboundTcpConnection extends Thread
{
    private static final Logger logger = LoggerFactory.getLogger(OutboundTcpConnection.class);

    private static final MessageOut CLOSE_SENTINEL = new MessageOut(MessagingService.Verb.INTERNAL_RESPONSE);

    private static final int OPEN_RETRY_DELAY = 100; // ms between retries

    // sending thread reads from "active" (one of queue1, queue2) until it is empty.
    // then it swaps it with "backlog."
    private volatile BlockingQueue<Entry> backlog = new LinkedBlockingQueue<Entry>();
    private volatile BlockingQueue<Entry> active = new LinkedBlockingQueue<Entry>();

    private final OutboundTcpConnectionPool poolReference;

    private DataOutputStream out;
    private Socket socket;
    private volatile long completed;
    private final AtomicLong dropped = new AtomicLong();
    private int targetVersion;

    public OutboundTcpConnection(OutboundTcpConnectionPool pool)
    {
        super("WRITE-" + pool.endPoint());
        this.poolReference = pool;
    }

    private static boolean isLocalDC(InetAddress targetHost)
    {
        String remoteDC = DatabaseDescriptor.getEndpointSnitch().getDatacenter(targetHost);
        String localDC = DatabaseDescriptor.getEndpointSnitch().getDatacenter(DatabaseDescriptor.getRpcAddress());
        return remoteDC.equals(localDC);
    }

    public void enqueue(MessageOut<?> message, String id)
    {
        expireMessages();
        try
        {
            backlog.put(new Entry(message, id, System.currentTimeMillis()));
        }
        catch (InterruptedException e)
        {
            throw new AssertionError(e);
        }
    }

    void closeSocket()
    {
        active.clear();
        backlog.clear();
        enqueue(CLOSE_SENTINEL, null);
    }

    void softCloseSocket()
    {
        enqueue(CLOSE_SENTINEL, null);
    }

    public int getTargetVersion()
    {
        return targetVersion;
    }

    public void run()
    {
        while (true)
        {
            Entry entry = active.poll();
            if (entry == null)
            {
                // exhausted the active queue.  switch to backlog, once there's something to process there
                try
                {
                    entry = backlog.take();
                }
                catch (InterruptedException e)
                {
                    throw new AssertionError(e);
                }

                BlockingQueue<Entry> tmp = backlog;
                backlog = active;
                active = tmp;
            }

            MessageOut<?> m = entry.message;
            String id = entry.id;
            if (m == CLOSE_SENTINEL)
            {
                disconnect();
                continue;
            }
            if (entry.timestamp < System.currentTimeMillis() - DatabaseDescriptor.getRpcTimeout())
                dropped.incrementAndGet();
            else if (socket != null || connect())
                writeConnected(m, id);
            else
                // clear out the queue, else gossip messages back up.
                active.clear();
        }
    }

    public int getPendingMessages()
    {
        return active.size() + backlog.size();
    }

    public long getCompletedMesssages()
    {
        return completed;
    }

    public long getDroppedMessages()
    {
        return dropped.get();
    }

    private boolean shouldCompressConnection()
    {
        // assumes version >= 1.2
        return DatabaseDescriptor.internodeCompression() == Config.InternodeCompression.all
               || (DatabaseDescriptor.internodeCompression() == Config.InternodeCompression.dc && !isLocalDC(poolReference.endPoint()));
    }

    private void writeConnected(MessageOut<?> message, String id)
    {
        try
        {
            write(message, id, out);
            completed++;
            if (active.peek() == null)
            {
                out.flush();
            }
        }
        catch (Exception e)
        {
            // Non IO exceptions is likely a programming error so let's not silence it
            if (!(e instanceof IOException))
                logger.error("error writing to " + poolReference.endPoint(), e);
            else if (logger.isDebugEnabled())
                logger.debug("error writing to " + poolReference.endPoint(), e);
            disconnect();
        }
    }

    public void write(MessageOut<?> message, String id, DataOutputStream out) throws IOException
    {
        write(message, id, out, targetVersion);
    }

    public static void write(MessageOut message, String id, DataOutputStream out, int version) throws IOException
    {
        out.writeInt(MessagingService.PROTOCOL_MAGIC);
        if (version < MessagingService.VERSION_12)
            writeHeader(out, version, false);
        // 0.8 included a total message size int.  1.0 doesn't need it but expects it to be there.
        if (version <  MessagingService.VERSION_12)
            out.writeInt(-1);

        out.writeUTF(id);
        message.serialize(out, version);
    }

    private static void writeHeader(DataOutputStream out, int version, boolean compressionEnabled) throws IOException
    {
        // 2 bits: unused.  used to be "serializer type," which was always Binary
        // 1 bit: compression
        // 1 bit: streaming mode
        // 3 bits: unused
        // 8 bits: version
        // 15 bits: unused
        int header = 0;
        if (compressionEnabled)
            header |= 4;
        header |= (version << 8);
        out.writeInt(header);
    }

    private void disconnect()
    {
        if (socket != null)
        {
            try
            {
                socket.close();
            }
            catch (IOException e)
            {
                if (logger.isDebugEnabled())
                    logger.debug("exception closing connection to " + poolReference.endPoint(), e);
            }
            out = null;
            socket = null;
        }
    }

    private boolean connect()
    {
        if (logger.isDebugEnabled())
            logger.debug("attempting to connect to " + poolReference.endPoint());

        targetVersion = MessagingService.instance().getVersion(poolReference.endPoint());

        long start = System.currentTimeMillis();
        while (System.currentTimeMillis() < start + DatabaseDescriptor.getRpcTimeout())
        {
            try
            {
                socket = poolReference.newSocket();
                socket.setKeepAlive(true);
                socket.setTcpNoDelay(true);
                out = new DataOutputStream(new BufferedOutputStream(socket.getOutputStream(), 4096));

                if (targetVersion >= MessagingService.VERSION_12)
                {
                    out.writeInt(MessagingService.PROTOCOL_MAGIC);
                    writeHeader(out, targetVersion, shouldCompressConnection());
                    out.flush();

                    DataInputStream in = new DataInputStream(socket.getInputStream());
                    int maxTargetVersion = in.readInt();
                    if (targetVersion > maxTargetVersion)
                    {
                        logger.debug("Target max version is {}; will reconnect with that version", maxTargetVersion);
                        MessagingService.instance().setVersion(poolReference.endPoint(), maxTargetVersion);
                        disconnect();
                        return false;
                    }

                    if (targetVersion < maxTargetVersion && targetVersion < MessagingService.current_version)
                    {
                        logger.debug("Detected higher max version {} (using {}); will reconnect when queued messages are done",
                                     maxTargetVersion, targetVersion);
                        MessagingService.instance().setVersion(poolReference.endPoint(), Math.min(MessagingService.current_version, maxTargetVersion));
                        softCloseSocket();
                    }

                    out.writeInt(MessagingService.current_version);
                    CompactEndpointSerializationHelper.serialize(FBUtilities.getBroadcastAddress(), out);
                    if (shouldCompressConnection())
                    {
                        out.flush();
                        logger.debug("Upgrading OutputStream to be compressed");
                        out = new DataOutputStream(new SnappyOutputStream(new BufferedOutputStream(socket.getOutputStream())));
                    }
                }

                return true;
            }
            catch (IOException e)
            {
                socket = null;
                if (logger.isTraceEnabled())
                    logger.trace("unable to connect to " + poolReference.endPoint(), e);
                try
                {
                    Thread.sleep(OPEN_RETRY_DELAY);
                }
                catch (InterruptedException e1)
                {
                    throw new AssertionError(e1);
                }
            }
        }
        return false;
    }

    private void expireMessages()
    {
        while (true)
        {
            Entry entry = backlog.peek();
            if (entry == null || entry.timestamp >= System.currentTimeMillis() - DatabaseDescriptor.getRpcTimeout())
                break;

            Entry entry2 = backlog.poll();
            if (entry2 != entry)
            {
                // sending thread switched queues.  add this entry (from the "new" backlog)
                // at the end of the active queue, which keeps it in the same position relative to the other entries
                // without having to contend with other clients for the head-of-backlog lock.
                if (entry2 != null)
                    active.add(entry2);
                break;
            }

            dropped.incrementAndGet();
        }
    }

    private static class Entry
    {
        final MessageOut<?> message;
        final String id;
        final long timestamp;

        Entry(MessageOut<?> message, String id, long timestamp)
        {
            this.message = message;
            this.id = id;
            this.timestamp = timestamp;
        }
    }
}
