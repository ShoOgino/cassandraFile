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

package org.apache.cassandra.db.commitlog;

import java.io.*;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Map;

import org.apache.cassandra.config.CFMetaData;
import org.apache.cassandra.io.ICompactSerializer;
import org.apache.cassandra.io.util.BufferedRandomAccessFile;

class CommitLogHeader
{    
    private static CommitLogHeaderSerializer serializer = new CommitLogHeaderSerializer();

    static int getLowestPosition(CommitLogHeader clheader)
    {
        return clheader.lastFlushedAt.size() == 0 ? 0 : Collections.min(clheader.lastFlushedAt.values(), new Comparator<Integer>(){
            public int compare(Integer o1, Integer o2)
            {
                if (o1 == 0)
                    return 1;
                else if (o2 == 0)
                    return -1;
                else
                    return o1 - o2;
            }
        });
    }

    private Map<Integer, Integer> lastFlushedAt; // position at which each CF was last flushed
    private final int maxSerializedSize;
    
    CommitLogHeader()
    {
        lastFlushedAt = new HashMap<Integer, Integer>();
        maxSerializedSize = 8 * CFMetaData.getCfCount();
    }
    
    /*
     * This ctor is used while deserializing. This ctor
     * also builds an index of position to column family
     * Id.
    */
    private CommitLogHeader(Map<Integer, Integer> lastFlushedAt)
    {
        assert lastFlushedAt.size() <= CFMetaData.getCfCount();
        this.lastFlushedAt = lastFlushedAt;
        maxSerializedSize = 8 * CFMetaData.getCfCount();
    }
        
    boolean isDirty(int cfId)
    {
        return lastFlushedAt.containsKey(cfId);
    } 
    
    int getPosition(int index)
    {
        Integer x = lastFlushedAt.get(index);
        return x == null ? 0 : x;
    }
    
    void turnOn(int cfId, long position)
    {
        lastFlushedAt.put(cfId, (int)position);
    }

    void turnOff(int cfId)
    {
        lastFlushedAt.remove(cfId);
    }

    boolean isSafeToDelete() throws IOException
    {
        return lastFlushedAt.isEmpty();
    }

    byte[] toByteArray() throws IOException
    {
        ByteArrayOutputStream bos = new ByteArrayOutputStream(maxSerializedSize);
        DataOutputStream dos = new DataOutputStream(bos);        
        serializer.serialize(this, dos);
        byte[] src = bos.toByteArray();
        assert src.length < maxSerializedSize;
        byte[] dst = new byte[maxSerializedSize];
        System.arraycopy(src, 0, dst, 0, src.length);
        return dst;
    }
    
    public String toString()
    {
        StringBuilder sb = new StringBuilder("");
        sb.append("CLH(dirty+flushed={");
        for (Map.Entry<Integer, Integer> entry : lastFlushedAt.entrySet())
        {
            sb.append(CFMetaData.getName(entry.getKey())).append(": ").append(entry.getValue()).append(", ");
        }
        sb.append("})");
        return sb.toString();
    }

    public String dirtyString()
    {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<Integer, Integer> entry : lastFlushedAt.entrySet())
            sb.append(entry.getKey()).append(", ");
        return sb.toString();
    }

    static CommitLogHeader readCommitLogHeader(BufferedRandomAccessFile logReader) throws IOException
    {
        int statedSize = logReader.readInt();
        byte[] bytes = new byte[statedSize];
        logReader.readFully(bytes);
        ByteArrayInputStream byteStream = new ByteArrayInputStream(bytes);
        return serializer.deserialize(new DataInputStream(byteStream));
    }

    static class CommitLogHeaderSerializer implements ICompactSerializer<CommitLogHeader>
    {
        public void serialize(CommitLogHeader clHeader, DataOutputStream dos) throws IOException
        {
            dos.writeInt(clHeader.lastFlushedAt.size());
            for (Map.Entry<Integer, Integer> entry : clHeader.lastFlushedAt.entrySet())
            {
                dos.writeInt(entry.getKey());
                dos.writeInt(entry.getValue());
            }
        }

        public CommitLogHeader deserialize(DataInputStream dis) throws IOException
        {
            int lfSz = dis.readInt();
            Map<Integer, Integer> lastFlushedAt = new HashMap<Integer, Integer>();
            for (int i = 0; i < lfSz; i++)
                lastFlushedAt.put(dis.readInt(), dis.readInt());
            return new CommitLogHeader(lastFlushedAt);
        }
    }
}
