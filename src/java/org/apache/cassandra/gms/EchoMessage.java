package org.apache.cassandra.gms;
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


import java.io.DataInput;
import java.io.IOException;

import org.apache.cassandra.io.IVersionedSerializer;
import org.apache.cassandra.io.util.DataOutputPlus;

public final class EchoMessage
{
	public static final EchoMessage instance = new EchoMessage();
	
    public static final IVersionedSerializer<EchoMessage> serializer = new EchoMessageSerializer();

	private EchoMessage()
	{
	}
	
    public static class EchoMessageSerializer implements IVersionedSerializer<EchoMessage>
    {
        public void serialize(EchoMessage t, DataOutputPlus out, int version) throws IOException
        {
        }

        public EchoMessage deserialize(DataInput in, int version) throws IOException
        {
            return EchoMessage.instance;
        }

        public long serializedSize(EchoMessage t, int version)
        {
            return 0;
        }
    }
}
