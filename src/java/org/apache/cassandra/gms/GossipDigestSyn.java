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

import java.io.*;
import java.net.InetAddress;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;

import org.apache.cassandra.db.DBTypeSizes;
import org.apache.cassandra.io.IVersionedSerializer;
import org.apache.cassandra.net.CompactEndpointSerializationHelper;
import org.apache.cassandra.utils.FBUtilities;


/**
 * This is the first message that gets sent out as a start of the Gossip protocol in a
 * round.
 */

public class GossipDigestSyn
{
    private static final IVersionedSerializer<GossipDigestSyn> serializer;
    static
    {
        serializer = new GossipDigestSynSerializer();
    }

    final String clusterId;
    final List<GossipDigest> gDigests;

    public static IVersionedSerializer<GossipDigestSyn> serializer()
    {
        return serializer;
    }

    public GossipDigestSyn(String clusterId, List<GossipDigest> gDigests)
    {
        this.clusterId = clusterId;
        this.gDigests = gDigests;
    }

    List<GossipDigest> getGossipDigests()
    {
        return gDigests;
    }
}

class GossipDigestSerializationHelper
{
    static void serialize(List<GossipDigest> gDigestList, DataOutput dos, int version) throws IOException
    {
        dos.writeInt(gDigestList.size());
        for ( GossipDigest gDigest : gDigestList )
        {
            GossipDigest.serializer().serialize( gDigest, dos, version);
        }
    }

    static List<GossipDigest> deserialize(DataInput dis, int version) throws IOException
    {
        int size = dis.readInt();
        List<GossipDigest> gDigests = new ArrayList<GossipDigest>(size);

        for ( int i = 0; i < size; ++i )
        {
            gDigests.add(GossipDigest.serializer().deserialize(dis, version));
        }
        return gDigests;
    }
    
    static int serializedSize(List<GossipDigest> digests, int version)
    {
        int size = DBTypeSizes.NATIVE.sizeof(digests.size());
        for (GossipDigest digest : digests)
            size += GossipDigest.serializer().serializedSize(digest, version);
        return size;
    }
}

class GossipDigestSynSerializer implements IVersionedSerializer<GossipDigestSyn>
{
    public void serialize(GossipDigestSyn gDigestSynMessage, DataOutput dos, int version) throws IOException
    {
        dos.writeUTF(gDigestSynMessage.clusterId);
        GossipDigestSerializationHelper.serialize(gDigestSynMessage.gDigests, dos, version);
    }

    public GossipDigestSyn deserialize(DataInput dis, int version) throws IOException
    {
        String clusterId = dis.readUTF();
        List<GossipDigest> gDigests = GossipDigestSerializationHelper.deserialize(dis, version);
        return new GossipDigestSyn(clusterId, gDigests);
    }

    public long serializedSize(GossipDigestSyn syn, int version)
    {
        return FBUtilities.serializedUTF8Size(syn.clusterId) + GossipDigestSerializationHelper.serializedSize(syn.gDigests, version);
    }
}

