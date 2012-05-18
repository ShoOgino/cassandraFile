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
package org.apache.cassandra.cql3.statements;

import java.util.Collections;
import java.util.List;

import org.apache.cassandra.cql3.*;
import org.apache.cassandra.db.marshal.AbstractType;
import org.apache.cassandra.thrift.InvalidRequestException;

public abstract class ParsedStatement
{
    private int boundTerms;

    public int getBoundsTerms()
    {
        return boundTerms;
    }

    // Used by the parser and preparable statement
    public void setBoundTerms(int boundTerms)
    {
        this.boundTerms = boundTerms;
    }

    public abstract Prepared prepare() throws InvalidRequestException;

    public static class Prepared
    {
        public final CQLStatement statement;
        public final List<CFDefinition.Name> boundNames;

        public Prepared(CQLStatement statement, List<CFDefinition.Name> boundNames)
        {
            this.statement = statement;
            this.boundNames = boundNames;
        }

        public Prepared(CQLStatement statement)
        {
            this(statement, Collections.<CFDefinition.Name>emptyList());
        }
    }
}
