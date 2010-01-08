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

import java.io.IOException;
import java.nio.channels.SelectableChannel;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;

import org.apache.log4j.Logger;

public class SelectorManager extends Thread
{
    private static final Logger logger = Logger.getLogger(SelectorManager.class); 

    // the underlying selector used
    protected Selector selector;

    // workaround JDK select/register bug
    Object gate = new Object();

    // flag to indicate that shutdown has been requested.
    private boolean shutdownRequested = false;

    // The static selector manager which is used by all applications
    private static SelectorManager manager;
    
    // The static UDP selector manager which is used by all applications
    private static SelectorManager udpManager;

    private SelectorManager(String name)
    {
        super(name);

        try
        {
            selector = Selector.open();
        }
        catch (IOException e)
        {
            throw new RuntimeException(e);
        }

        setDaemon(false);
    }

    /**
     * Registers a new channel with the selector, and attaches the given
     * SelectionKeyHandler as the handler for the newly created key. Operations
     * which the handler is interested in will be called as available.
     * 
     * @param channel
     *            The channel to register with the selector
     * @param handler
     *            The handler to use for the callbacks
     * @param ops
     *            The initial interest operations
     * @return The SelectionKey which uniquely identifies this channel
     * @exception IOException if the channel is closed
     */
    public SelectionKey register(SelectableChannel channel,
            SelectionKeyHandler handler, int ops) throws IOException
    {
        assert channel != null;
        assert handler != null;

        synchronized(gate)
        {
            selector.wakeup();
            return channel.register(selector, ops, handler);
        }
    }

    // requests the thread to shutdown. However, it brings no guarantees. Added for testing.
    private void requestShutdown()
    {
        shutdownRequested = true;
    }

    /**
     * This method starts the socket manager listening for events. It is
     * designed to be started when this thread's start() method is invoked.
     */
    public void run()
    {
        while (true)
        {
            try
            {
                selector.select();
                doProcess();
                synchronized(gate) {}
            }
            catch (IOException e)
            {
                throw new RuntimeException(e);
            }
            if (shutdownRequested)
            {
                shutdownRequested = false;
                break;
            }
        }
    }

    protected void doProcess() throws IOException
    {
        SelectionKey[] keys = selector.selectedKeys().toArray(new SelectionKey[0]);

        for (SelectionKey key : keys)
        {
            selector.selectedKeys().remove(key);

            SelectionKeyHandler skh = (SelectionKeyHandler) key.attachment();

            if (skh != null)
            {
                // accept
                if (key.isValid() && key.isAcceptable())
                {
                    skh.accept(key);
                }

                // connect
                if (key.isValid() && key.isConnectable())
                {
                    skh.connect(key);
                }

                // read
                if (key.isValid() && key.isReadable())
                {
                    skh.read(key);
                }

                // write
                if (key.isValid() && key.isWritable())
                {
                    skh.write(key);
                }
            }
        }
    }

    /**
     * Intended to reset the singleton as part of testing.
     */
    static void reset()
    {
        synchronized(SelectorManager.class)
        {
            if (manager != null)
                manager.requestShutdown();
            manager = null;
            if (udpManager != null)
                udpManager.requestShutdown();
            udpManager = null;
        }
    }

    /**
     * Returns the SelectorManager applications should use.
     * 
     * @return The SelectorManager which applications should use
     */
    public static SelectorManager getSelectorManager()
    {
        synchronized (SelectorManager.class)
        {
            if (manager == null)
            {
                manager = new SelectorManager("TCP Selector Manager");
            }            
        }
        return manager;
    }
    
    public static SelectorManager getUdpSelectorManager()
    {
        synchronized (SelectorManager.class)
        {
            if (udpManager == null)
            {
                udpManager = new SelectorManager("UDP Selector Manager");
            }            
        }
        return udpManager;
    }

    protected void turnOnInterestOps(SelectionKey key, int ops)
    {
        synchronized(gate)
        {
            selector.wakeup();
            key.interestOps(key.interestOps() | ops);
        }
    }
    
    protected void turnOffInterestOps(SelectionKey key, int ops)
    {
        synchronized(gate)
        {
            selector.wakeup();
            key.interestOps(key.interestOps() & (~ops) );
        }
    }
}
