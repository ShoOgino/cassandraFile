package org.apache.cassandra.stress.operations;
/*
 *
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 *
 */


import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import org.apache.cassandra.utils.ByteBufferUtil;

public class CqlReader extends CqlOperation<ByteBuffer[][]>
{

    public CqlReader(State state, long idx)
    {
        super(state, idx);
    }

    @Override
    protected String buildQuery()
    {
        StringBuilder query = new StringBuilder("SELECT ");

        if (state.settings.columns.slice)
        {
            if (state.isCql2())
                query.append("FIRST ").append(state.settings.columns.maxColumnsPerKey).append(" ''..''");
            else
                query.append("*");
        }
        else
        {
            try
            {
                for (int i = 0; i < state.settings.columns.names.size() ; i++)
                {
                    if (i > 0)
                        query.append(",");
                    query.append(wrapInQuotesIfRequired(ByteBufferUtil.string(state.settings.columns.names.get(i))));
                }
            }
            catch (CharacterCodingException e)
            {
                throw new IllegalStateException(e);
            }
        }

        query.append(" FROM ").append(wrapInQuotesIfRequired(state.type.table));

        if (state.isCql2())
            query.append(" USING CONSISTENCY ").append(state.settings.command.consistencyLevel);
        query.append(" WHERE KEY=?");
        return query.toString();
    }

    @Override
    protected List<Object> getQueryParameters(byte[] key)
    {
        return Collections.<Object>singletonList(ByteBuffer.wrap(key));
    }

    @Override
    protected CqlRunOp<ByteBuffer[][]> buildRunOp(ClientWrapper client, String query, Object queryId, List<Object> params, String keyid, ByteBuffer key)
    {
        List<ByteBuffer> expectRow = state.rowGen.isDeterministic() ? generateColumnValues(key) : null;
        return new CqlRunOpMatchResults(client, query, queryId, params, keyid, key, Arrays.asList(expectRow));
    }

}
