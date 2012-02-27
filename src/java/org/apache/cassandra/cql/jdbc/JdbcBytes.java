package org.apache.cassandra.cql.jdbc;
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
import java.sql.Types;

import org.apache.cassandra.utils.ByteBufferUtil;


public class JdbcBytes extends AbstractJdbcType<ByteBuffer>
{
    public static final JdbcBytes instance = new JdbcBytes();

    JdbcBytes() {}

    public boolean isCaseSensitive()
    {
        return false;
    }

    public int getScale(ByteBuffer obj)
    {
        return -1;
    }

    public int getPrecision(ByteBuffer obj)
    {
        return -1;
    }

    public boolean isCurrency()
    {
        return false;
    }

    public boolean isSigned()
    {
        return false;
    }

    public String toString(ByteBuffer obj)
    {
        return getString(obj);
    }

    public boolean needsQuotes()
    {
        return true;
    }

    public String getString(ByteBuffer bytes)
    {
        return ByteBufferUtil.bytesToHex(bytes);
    }

    public Class<ByteBuffer> getType()
    {
        return ByteBuffer.class;
    }

    public int getJdbcType()
    {
        return Types.BINARY;
    }

    public ByteBuffer compose(ByteBuffer bytes)
    {
        return bytes.duplicate();
    }

    public ByteBuffer decompose(ByteBuffer value)
    {
        return value;
    }
}
