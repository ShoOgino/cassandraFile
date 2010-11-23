package org.apache.cassandra.db.marshal;
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

import com.google.common.base.Charsets;

import org.apache.cassandra.utils.ByteBufferUtil;

public class AsciiType extends BytesType
{
    public static final AsciiType instance = new AsciiType();

    AsciiType() {} // singleton

    @Override
    public String getString(ByteBuffer bytes)
    {
        return ByteBufferUtil.string(bytes, Charsets.US_ASCII);
    }

    public ByteBuffer fromString(String source)
    {
        return ByteBuffer.wrap(source.getBytes(Charsets.US_ASCII));
    }
}
