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
package org.apache.cassandra.cql3.operations;

import java.nio.ByteBuffer;
import java.util.List;

import org.apache.cassandra.cql3.ColumnNameBuilder;
import org.apache.cassandra.cql3.ColumnSpecification;
import org.apache.cassandra.cql3.Term;
import org.apache.cassandra.cql3.UpdateParameters;
import org.apache.cassandra.db.ColumnFamily;
import org.apache.cassandra.db.Column;
import org.apache.cassandra.db.marshal.AbstractType;
import org.apache.cassandra.db.marshal.CollectionType;
import org.apache.cassandra.exceptions.InvalidRequestException;
import org.apache.cassandra.utils.Pair;

public interface Operation
{
    public static enum Type { COLUMN, COUNTER, LIST, SET, MAP, PREPARED }

    public void execute(ColumnFamily cf,
                        ColumnNameBuilder builder,
                        AbstractType<?> validator,
                        UpdateParameters params,
                        List<Pair<ByteBuffer, Column>> list) throws InvalidRequestException;

    public Operation validateAndAddBoundNames(ColumnSpecification column, ColumnSpecification[] boundNames) throws InvalidRequestException;

    public List<Term> getValues();

    public boolean requiresRead(AbstractType<?> validator);

    public Type getType();
}
