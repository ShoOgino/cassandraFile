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

package org.apache.cassandra.service;

import java.math.BigInteger;
import java.util.Comparator;

public interface IPartitioner
{
    public BigInteger hash(String key);

    /** transform key to on-disk format s.t. keys are stored in node comparison order.
     *  this lets bootstrap rip out parts of the sstable sequentially instead of doing random seeks. */
    public String decorateKey(String key);

    public String undecorateKey(String decoratedKey);

    public Comparator<String> getReverseDecoratedKeyComparator();
}
