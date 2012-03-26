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
package org.apache.cassandra.gms;

import java.io.DataInputStream;
import java.io.IOException;
import java.net.InetAddress;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.apache.cassandra.io.util.FastByteArrayInputStream;
import org.apache.cassandra.net.IVerbHandler;
import org.apache.cassandra.net.MessageIn;
import org.apache.cassandra.net.MessageOut;
import org.apache.cassandra.net.MessagingService;
import org.apache.cassandra.service.StorageService;

public class GossipDigestAckVerbHandler implements IVerbHandler
{
    private static final Logger logger = LoggerFactory.getLogger(GossipDigestAckVerbHandler.class);

    public void doVerb(MessageIn message, String id)
    {
        InetAddress from = message.getFrom();
        if (logger.isTraceEnabled())
            logger.trace("Received a GossipDigestAckMessage from {}", from);
        if (!Gossiper.instance.isEnabled())
        {
            if (logger.isTraceEnabled())
                logger.trace("Ignoring GossipDigestAckMessage because gossip is disabled");
            return;
        }

        byte[] bytes = message.getMessageBody();
        DataInputStream dis = new DataInputStream( new FastByteArrayInputStream(bytes) );

        try
        {
            GossipDigestAckMessage gDigestAckMessage = GossipDigestAckMessage.serializer().deserialize(dis, message.getVersion());
            List<GossipDigest> gDigestList = gDigestAckMessage.getGossipDigestList();
            Map<InetAddress, EndpointState> epStateMap = gDigestAckMessage.getEndpointStateMap();

            if ( epStateMap.size() > 0 )
            {
                /* Notify the Failure Detector */
                Gossiper.instance.notifyFailureDetector(epStateMap);
                Gossiper.instance.applyStateLocally(epStateMap);
            }

            /* Get the state required to send to this gossipee - construct GossipDigestAck2Message */
            Map<InetAddress, EndpointState> deltaEpStateMap = new HashMap<InetAddress, EndpointState>();
            for( GossipDigest gDigest : gDigestList )
            {
                InetAddress addr = gDigest.getEndpoint();
                EndpointState localEpStatePtr = Gossiper.instance.getStateForVersionBiggerThan(addr, gDigest.getMaxVersion());
                if ( localEpStatePtr != null )
                    deltaEpStateMap.put(addr, localEpStatePtr);
            }

            MessageOut<GossipDigestAck2Message> gDigestAck2Message = new MessageOut<GossipDigestAck2Message>(StorageService.Verb.GOSSIP_DIGEST_ACK2,
                                                                                                             new GossipDigestAck2Message(deltaEpStateMap), 
                                                                                                             GossipDigestAck2Message.serializer());
            if (logger.isTraceEnabled())
                logger.trace("Sending a GossipDigestAck2Message to {}", from);
            MessagingService.instance().sendOneWay(gDigestAck2Message, from);
        }
        catch ( IOException e )
        {
            throw new RuntimeException(e);
        }
    }
}
