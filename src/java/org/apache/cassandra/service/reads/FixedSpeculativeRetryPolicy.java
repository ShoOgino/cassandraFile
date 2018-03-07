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

package org.apache.cassandra.service.reads;

import java.util.concurrent.TimeUnit;

import com.google.common.base.Objects;

import com.codahale.metrics.Timer;

public class FixedSpeculativeRetryPolicy implements SpeculativeRetryPolicy
{
    private final int speculateAtMilliseconds;

    public FixedSpeculativeRetryPolicy(int speculateAtMilliseconds)
    {
        this.speculateAtMilliseconds = speculateAtMilliseconds;
    }

    @Override
    public boolean isDynamic()
    {
        return false;
    }

    @Override
    public long calculateThreshold(Timer readLatency)
    {
        return TimeUnit.MILLISECONDS.toNanos(speculateAtMilliseconds);
    }

    @Override
    public Kind kind()
    {
        return Kind.FIXED;
    }

    @Override
    public boolean equals(Object obj)
    {
        if (!(obj instanceof FixedSpeculativeRetryPolicy))
            return false;
        FixedSpeculativeRetryPolicy rhs = (FixedSpeculativeRetryPolicy) obj;
        return speculateAtMilliseconds == rhs.speculateAtMilliseconds;
    }

    @Override
    public int hashCode()
    {
        return Objects.hashCode(kind(), speculateAtMilliseconds);
    }

    @Override
    public String toString()
    {
        return String.format("%dms", speculateAtMilliseconds);
    }
}
