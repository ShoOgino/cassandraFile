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
package org.apache.cassandra.service;

import java.net.SocketAddress;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class SocketSessionManagementService
{
    public final static SocketSessionManagementService instance = new SocketSessionManagementService();
    public final static ThreadLocal<SocketAddress> remoteSocket = new ThreadLocal<SocketAddress>();
    private Map<SocketAddress, ClientState> activeSocketSessions = new ConcurrentHashMap<SocketAddress, ClientState>();

    public ClientState get(SocketAddress key)
    {
        ClientState retval = null;
        if (null != key)
        {
            retval = activeSocketSessions.get(key);
        }
        return retval;
    }

    public void put(SocketAddress key, ClientState value)
    {
        if (null != key && null != value)
        {
            activeSocketSessions.put(key, value);
        }
    }

    public boolean remove(SocketAddress key)
    {
        assert null != key;
        boolean retval = false;
        if (null != activeSocketSessions.remove(key))
        {
            retval = true;
        }
        return retval;
    }

    public void clear()
    {
        activeSocketSessions.clear();
    }

}
