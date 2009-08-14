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


import java.util.Arrays;

public class BytesType extends AbstractType
{
    public int compare(byte[] o1, byte[] o2)
    {
        int length = Math.max(o1.length, o2.length);
        for (int i = 0; i < length; i++)
        {
            int index = i + 1;
            if (index > o1.length && index <= o2.length)
            {
                return -1;
            }
            if (index > o2.length && index <= o1.length)
            {
                return 1;
            }

            int delta = o1[i] - o2[i];
            if (delta != 0)
            {
                return delta;
            }
        }
        return 0;
    }

    public String getString(byte[] bytes)
    {
        return Arrays.toString(bytes);
    }
}
