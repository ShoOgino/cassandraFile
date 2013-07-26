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
package org.apache.cassandra.cql3;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;

import org.jboss.netty.buffer.ChannelBuffer;

import org.apache.cassandra.db.ConsistencyLevel;
import org.apache.cassandra.service.pager.PagingState;
import org.apache.cassandra.transport.CBCodec;
import org.apache.cassandra.transport.CBUtil;

/**
 * Options for a query.
 */
public class QueryOptions
{
    public static final QueryOptions DEFAULT = new QueryOptions(ConsistencyLevel.ONE, Collections.<ByteBuffer>emptyList());

    public static final CBCodec<QueryOptions> codec = new Codec();

    private final ConsistencyLevel consistency;
    private final List<ByteBuffer> values;
    private final boolean skipMetadata;

    private final SpecificOptions options;

    public QueryOptions(ConsistencyLevel consistency, List<ByteBuffer> values)
    {
        this(consistency, values, false, SpecificOptions.DEFAULT);
    }

    public QueryOptions(ConsistencyLevel consistency,
                        List<ByteBuffer> values,
                        boolean skipMetadata,
                        int pageSize,
                        PagingState pagingState,
                        ConsistencyLevel serialConsistency)
    {
        this(consistency, values, skipMetadata, new SpecificOptions(pageSize, pagingState, serialConsistency));
    }

    private QueryOptions(ConsistencyLevel consistency, List<ByteBuffer> values, boolean skipMetadata, SpecificOptions options)
    {
        this.consistency = consistency;
        this.values = values;
        this.skipMetadata = skipMetadata;
        this.options = options;
    }

    public ConsistencyLevel getConsistency()
    {
        return consistency;
    }

    public List<ByteBuffer> getValues()
    {
        return values;
    }

    public boolean skipMetadata()
    {
        return skipMetadata;
    }

    /**
     * The pageSize for this query. Will be <= 0 if not relevant for the query.
     */
    public int getPageSize()
    {
        return options.pageSize;
    }

    /**
     * The paging state for this query, or null if not relevant.
     */
    public PagingState getPagingState()
    {
        return options.state;
    }

    /**
     * Serial consistency for conditional updates.
     */
    public ConsistencyLevel getSerialConsistency()
    {
        return options.serialConsistency;
    }

    // Options that are likely to not be present in most queries
    private static class SpecificOptions
    {
        private static final SpecificOptions DEFAULT = new SpecificOptions(-1, null, null);

        private final int pageSize;
        private final PagingState state;
        private final ConsistencyLevel serialConsistency;

        private SpecificOptions(int pageSize, PagingState state, ConsistencyLevel serialConsistency)
        {
            this.pageSize = pageSize;
            this.state = state;
            this.serialConsistency = serialConsistency == null ? ConsistencyLevel.SERIAL : serialConsistency;
        }
    }

    private static class Codec implements CBCodec<QueryOptions>
    {
        private static enum Flag
        {
            // The order of that enum matters!!
            VALUES,
            SKIP_METADATA,
            PAGE_SIZE,
            PAGING_STATE,
            SERIAL_CONSISTENCY;

            public static EnumSet<Flag> deserialize(int flags)
            {
                EnumSet<Flag> set = EnumSet.noneOf(Flag.class);
                Flag[] values = Flag.values();
                for (int n = 0; n < values.length; n++)
                {
                    if ((flags & (1 << n)) != 0)
                        set.add(values[n]);
                }
                return set;
            }

            public static int serialize(EnumSet<Flag> flags)
            {
                int i = 0;
                for (Flag flag : flags)
                    i |= 1 << flag.ordinal();
                return i;
            }
        }

        public QueryOptions decode(ChannelBuffer body, int version)
        {
            assert version >= 2;

            ConsistencyLevel consistency = CBUtil.readConsistencyLevel(body);
            EnumSet<Flag> flags = Flag.deserialize((int)body.readByte());

            List<ByteBuffer> values = Collections.emptyList();
            if (flags.contains(Flag.VALUES))
            {
                int paramCount = body.readUnsignedShort();
                if (paramCount > 0)
                {
                    values = new ArrayList<ByteBuffer>(paramCount);
                    for (int i = 0; i < paramCount; i++)
                        values.add(CBUtil.readValue(body));
                }
            }

            boolean skipMetadata = flags.contains(Flag.SKIP_METADATA);
            flags.remove(Flag.VALUES);
            flags.remove(Flag.SKIP_METADATA);

            SpecificOptions options = SpecificOptions.DEFAULT;
            if (!flags.isEmpty())
            {
                int pageSize = flags.contains(Flag.PAGE_SIZE) ? body.readInt() : -1;
                PagingState pagingState = flags.contains(Flag.PAGING_STATE) ? PagingState.deserialize(CBUtil.readValue(body)) : null;
                ConsistencyLevel serialConsistency = flags.contains(Flag.SERIAL_CONSISTENCY) ? CBUtil.readConsistencyLevel(body) : ConsistencyLevel.SERIAL;
                options = new SpecificOptions(pageSize, pagingState, serialConsistency);
            }
            return new QueryOptions(consistency, values, skipMetadata, options);
        }

        public ChannelBuffer encode(QueryOptions options, int version)
        {
            assert version >= 2;

            int nbBuff = 2;

            EnumSet<Flag> flags = EnumSet.noneOf(Flag.class);
            if (options.getValues().size() > 0)
            {
                flags.add(Flag.VALUES);
                nbBuff++;
            }
            if (options.skipMetadata)
                flags.add(Flag.SKIP_METADATA);
            if (options.getPageSize() >= 0)
            {
                flags.add(Flag.PAGE_SIZE);
                nbBuff++;
            }
            if (options.getSerialConsistency() != ConsistencyLevel.SERIAL)
            {
                flags.add(Flag.SERIAL_CONSISTENCY);
                nbBuff++;
            }

            CBUtil.BufferBuilder builder = new CBUtil.BufferBuilder(nbBuff, 0, options.values.size() + (flags.contains(Flag.PAGING_STATE) ? 1 : 0));
            builder.add(CBUtil.consistencyLevelToCB(options.getConsistency()));
            builder.add(CBUtil.byteToCB((byte)Flag.serialize(flags)));

            if (flags.contains(Flag.VALUES))
            {
                builder.add(CBUtil.shortToCB(options.getValues().size()));
                for (ByteBuffer value : options.getValues())
                    builder.addValue(value);
            }
            if (flags.contains(Flag.PAGE_SIZE))
                builder.add(CBUtil.intToCB(options.getPageSize()));
            if (flags.contains(Flag.PAGING_STATE))
                builder.addValue(options.getPagingState().serialize());
            if (flags.contains(Flag.SERIAL_CONSISTENCY))
                builder.add(CBUtil.consistencyLevelToCB(options.getSerialConsistency()));
            return builder.build();
        }
    }
}
