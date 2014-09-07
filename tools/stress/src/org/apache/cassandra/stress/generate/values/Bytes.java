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
package org.apache.cassandra.stress.generate.values;

import org.apache.cassandra.db.marshal.BytesType;
import org.apache.cassandra.stress.generate.FasterRandom;

import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.Random;

public class Bytes extends Generator<ByteBuffer>
{
    private final byte[] bytes;
    private final FasterRandom rand = new FasterRandom();

    public Bytes(String name, GeneratorConfig config)
    {
        super(BytesType.instance, config, name, ByteBuffer.class);
        bytes = new byte[(int) sizeDistribution.maxValue()];
    }

    @Override
    public ByteBuffer generate()
    {
        long seed = identityDistribution.next();
        sizeDistribution.setSeed(seed);
        rand.setSeed(~seed);
        int size = (int) sizeDistribution.next();
        for (int i = 0; i < size; )
            for (long v = rand.nextLong(),
                 n = Math.min(size - i, Long.SIZE/Byte.SIZE);
                 n-- > 0; v >>= Byte.SIZE)
                bytes[i++] = (byte)v;
        return ByteBuffer.wrap(Arrays.copyOf(bytes, size));
    }
}