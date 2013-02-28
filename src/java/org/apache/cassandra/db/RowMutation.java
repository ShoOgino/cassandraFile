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
package org.apache.cassandra.db;

import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.*;

import org.apache.commons.lang.StringUtils;

import org.apache.cassandra.config.CFMetaData;
import org.apache.cassandra.config.KSMetaData;
import org.apache.cassandra.config.Schema;
import org.apache.cassandra.db.marshal.UUIDType;
import org.apache.cassandra.io.IVersionedSerializer;
import org.apache.cassandra.net.MessageOut;
import org.apache.cassandra.net.MessagingService;
import org.apache.cassandra.thrift.ColumnOrSuperColumn;
import org.apache.cassandra.thrift.Deletion;
import org.apache.cassandra.utils.ByteBufferUtil;
import org.apache.cassandra.utils.FBUtilities;
import org.apache.cassandra.utils.UUIDGen;

// TODO convert this to a Builder pattern instead of encouraging RM.add directly,
// which is less-efficient since we have to keep a mutable HashMap around
public class RowMutation implements IMutation
{
    public static final RowMutationSerializer serializer = new RowMutationSerializer();
    public static final String FORWARD_TO = "FWD_TO";
    public static final String FORWARD_FROM = "FWD_FRM";

    private final String table;
    private final ByteBuffer key;
    // map of column family id to mutations for that column family.
    private final Map<UUID, ColumnFamily> modifications;

    public RowMutation(String table, ByteBuffer key)
    {
        this(table, key, new HashMap<UUID, ColumnFamily>());
    }

    public RowMutation(String table, ByteBuffer key, ColumnFamily cf)
    {
        this(table, key, Collections.singletonMap(cf.id(), cf));
    }

    public RowMutation(String table, Row row)
    {
        this(table, row.key.key, row.cf);
    }

    protected RowMutation(String table, ByteBuffer key, Map<UUID, ColumnFamily> modifications)
    {
        this.table = table;
        this.key = key;
        this.modifications = modifications;
    }

    public String getTable()
    {
        return table;
    }

    public Collection<UUID> getColumnFamilyIds()
    {
        return modifications.keySet();
    }

    public ByteBuffer key()
    {
        return key;
    }

    public Collection<ColumnFamily> getColumnFamilies()
    {
        return modifications.values();
    }

    public ColumnFamily getColumnFamily(UUID cfId)
    {
        return modifications.get(cfId);
    }

    /**
     * Returns mutation representing a Hints to be sent to <code>address</code>
     * as soon as it becomes available.  See HintedHandoffManager for more details.
     */
    public static RowMutation hintFor(RowMutation mutation, UUID targetId) throws IOException
    {
        UUID hintId = UUIDGen.getTimeUUID();

        // determine the TTL for the RowMutation
        // this is set at the smallest GCGraceSeconds for any of the CFs in the RM
        // this ensures that deletes aren't "undone" by delivery of an old hint
        int ttl = Integer.MAX_VALUE;
        for (ColumnFamily cf : mutation.getColumnFamilies())
            ttl = Math.min(ttl, cf.metadata().getGcGraceSeconds());

        // serialize the hint with id and version as a composite column name
        ByteBuffer name = HintedHandOffManager.comparator.decompose(hintId, MessagingService.current_version);
        ByteBuffer value = ByteBuffer.wrap(FBUtilities.serialize(mutation, serializer, MessagingService.current_version));
        RowMutation rm = new RowMutation(Table.SYSTEM_KS, UUIDType.instance.decompose(targetId));
        rm.add(SystemTable.HINTS_CF, name, value, System.currentTimeMillis(), ttl);

        return rm;
    }

    /*
     * Specify a column family name and the corresponding column
     * family object.
     * param @ cf - column family name
     * param @ columnFamily - the column family.
     */
    public void add(ColumnFamily columnFamily)
    {
        assert columnFamily != null;
        ColumnFamily prev = modifications.put(columnFamily.id(), columnFamily);
        if (prev != null)
            // developer error
            throw new IllegalArgumentException("ColumnFamily " + columnFamily + " already has modifications in this mutation: " + prev);
    }

    /**
     * @return the ColumnFamily in this RowMutation corresponding to @param cfName, creating an empty one if necessary.
     */
    public ColumnFamily addOrGet(String cfName)
    {
        CFMetaData cfm = Schema.instance.getCFMetaData(table, cfName);
        ColumnFamily cf = modifications.get(cfm.cfId);
        if (cf == null)
        {
            cf = ColumnFamily.create(cfm);
            modifications.put(cfm.cfId, cf);
        }
        return cf;
    }

    public boolean isEmpty()
    {
        return modifications.isEmpty();
    }

    public void add(String cfName, ByteBuffer name, ByteBuffer value, long timestamp, int timeToLive)
    {
        addOrGet(cfName).addColumn(name, value, timestamp, timeToLive);
    }

    public void addCounter(String cfName, ByteBuffer name, long value)
    {
        addOrGet(cfName).addCounter(name, value);
    }

    public void add(String cfName, ByteBuffer name, ByteBuffer value, long timestamp)
    {
        add(cfName, name, value, timestamp, 0);
    }

    public void delete(String cfName, long timestamp)
    {
        int localDeleteTime = (int) (System.currentTimeMillis() / 1000);
        addOrGet(cfName).delete(new DeletionInfo(timestamp, localDeleteTime));
    }

    public void delete(String cfName, ByteBuffer name, long timestamp)
    {
        int localDeleteTime = (int) (System.currentTimeMillis() / 1000);
        addOrGet(cfName).addTombstone(name, localDeleteTime, timestamp);
    }

    public void deleteRange(String cfName, ByteBuffer start, ByteBuffer end, long timestamp)
    {
        int localDeleteTime = (int) (System.currentTimeMillis() / 1000);
        addOrGet(cfName).addAtom(new RangeTombstone(start, end, timestamp, localDeleteTime));
    }

    public void addAll(IMutation m)
    {
        if (!(m instanceof RowMutation))
            throw new IllegalArgumentException();

        RowMutation rm = (RowMutation)m;
        if (!table.equals(rm.table) || !key.equals(rm.key))
            throw new IllegalArgumentException();

        for (Map.Entry<UUID, ColumnFamily> entry : rm.modifications.entrySet())
        {
            // It's slighty faster to assume the key wasn't present and fix if
            // not in the case where it wasn't there indeed.
            ColumnFamily cf = modifications.put(entry.getKey(), entry.getValue());
            if (cf != null)
                entry.getValue().resolve(cf);
        }
    }

    /*
     * This is equivalent to calling commit. Applies the changes to
     * to the table that is obtained by calling Table.open().
     */
    public void apply()
    {
        Table ks = Table.open(table);
        ks.apply(this, ks.metadata.durableWrites);
    }

    public void applyUnsafe()
    {
        Table.open(table).apply(this, false);
    }

    public MessageOut<RowMutation> createMessage()
    {
        return createMessage(MessagingService.Verb.MUTATION);
    }

    public MessageOut<RowMutation> createMessage(MessagingService.Verb verb)
    {
        return new MessageOut<RowMutation>(verb, this, serializer);
    }

    public String toString()
    {
        return toString(false);
    }

    public String toString(boolean shallow)
    {
        StringBuilder buff = new StringBuilder("RowMutation(");
        buff.append("keyspace='").append(table).append('\'');
        buff.append(", key='").append(ByteBufferUtil.bytesToHex(key)).append('\'');
        buff.append(", modifications=[");
        if (shallow)
        {
            List<String> cfnames = new ArrayList<String>(modifications.size());
            for (UUID cfid : modifications.keySet())
            {
                CFMetaData cfm = Schema.instance.getCFMetaData(cfid);
                cfnames.add(cfm == null ? "-dropped-" : cfm.cfName);
            }
            buff.append(StringUtils.join(cfnames, ", "));
        }
        else
            buff.append(StringUtils.join(modifications.values(), ", "));
        return buff.append("])").toString();
    }


    public static class RowMutationSerializer implements IVersionedSerializer<RowMutation>
    {
        public void serialize(RowMutation rm, DataOutput dos, int version) throws IOException
        {
            dos.writeUTF(rm.getTable());
            ByteBufferUtil.writeWithShortLength(rm.key(), dos);

            /* serialize the modifications in the mutation */
            int size = rm.modifications.size();
            dos.writeInt(size);
            assert size >= 0;
            for (Map.Entry<UUID, ColumnFamily> entry : rm.modifications.entrySet())
            {
                if (version < MessagingService.VERSION_12)
                    ColumnFamily.serializer.serializeCfId(entry.getKey(), dos, version);
                ColumnFamily.serializer.serialize(entry.getValue(), dos, version);
            }
        }

        public RowMutation deserialize(DataInput dis, int version, ColumnSerializer.Flag flag) throws IOException
        {
            String table = dis.readUTF();
            ByteBuffer key = ByteBufferUtil.readWithShortLength(dis);
            int size = dis.readInt();

            Map<UUID, ColumnFamily> modifications;
            if (size == 1)
            {
                ColumnFamily cf = deserializeOneCf(dis, version, flag);
                modifications = Collections.singletonMap(cf.id(), cf);
            }
            else
            {
                modifications = new HashMap<UUID, ColumnFamily>();
                for (int i = 0; i < size; ++i)
                {
                    ColumnFamily cf = deserializeOneCf(dis, version, flag);
                    modifications.put(cf.id(), cf);
                }
            }

            return new RowMutation(table, key, modifications);
        }

        private ColumnFamily deserializeOneCf(DataInput dis, int version, ColumnSerializer.Flag flag) throws IOException
        {
            // We used to uselessly write the cf id here
            if (version < MessagingService.VERSION_12)
                ColumnFamily.serializer.deserializeCfId(dis, version);
            ColumnFamily cf = ColumnFamily.serializer.deserialize(dis, flag, TreeMapBackedSortedColumns.factory(), version);
            // We don't allow RowMutation with null column family, so we should never get null back.
            assert cf != null;
            return cf;
        }

        public RowMutation deserialize(DataInput dis, int version) throws IOException
        {
            return deserialize(dis, version, ColumnSerializer.Flag.FROM_REMOTE);
        }

        public long serializedSize(RowMutation rm, int version)
        {
            TypeSizes sizes = TypeSizes.NATIVE;
            int size = sizes.sizeof(rm.getTable());
            int keySize = rm.key().remaining();
            size += sizes.sizeof((short) keySize) + keySize;

            size += sizes.sizeof(rm.modifications.size());
            for (Map.Entry<UUID,ColumnFamily> entry : rm.modifications.entrySet())
            {
                if (version < MessagingService.VERSION_12)
                    size += ColumnFamily.serializer.cfIdSerializedSize(entry.getValue().id(), sizes, version);
                size += ColumnFamily.serializer.serializedSize(entry.getValue(), TypeSizes.NATIVE, version);
            }

            return size;
        }
    }
}
