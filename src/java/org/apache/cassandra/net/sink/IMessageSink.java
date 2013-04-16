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
package org.apache.cassandra.net.sink;

import java.net.InetAddress;

import org.apache.cassandra.net.MessageIn;
import org.apache.cassandra.net.MessageOut;

public interface IMessageSink
{
    /**
     * Transform or drop an outgoing message
     *
     * @return null if the message is dropped, or the transformed message to send, which may be just
     * the original message
     */
    public MessageOut handleMessage(MessageOut message, int id, InetAddress to);

    /**
     * Transform or drop an incoming message
     *
     * @return null if the message is dropped, or the transformed message to receive, which may be just
     * the original message
     */
    public MessageIn handleMessage(MessageIn message, int id, InetAddress to);
}
