/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.cassandra.schema;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import com.google.common.collect.ImmutableMap;

import org.junit.BeforeClass;
import org.junit.Test;

import org.apache.cassandra.SchemaLoader;
import org.apache.cassandra.config.CFMetaData;
import org.apache.cassandra.config.ColumnDefinition;
import org.apache.cassandra.config.Schema;
import org.apache.cassandra.config.SchemaConstants;
import org.apache.cassandra.cql3.QueryProcessor;
import org.apache.cassandra.cql3.UntypedResultSet;
import org.apache.cassandra.db.ColumnFamilyStore;
import org.apache.cassandra.db.Keyspace;
import org.apache.cassandra.db.Mutation;
import org.apache.cassandra.db.marshal.AsciiType;
import org.apache.cassandra.db.marshal.UTF8Type;
import org.apache.cassandra.db.partitions.PartitionUpdate;
import org.apache.cassandra.db.rows.UnfilteredRowIterators;
import org.apache.cassandra.exceptions.ConfigurationException;
import org.apache.cassandra.utils.ByteBufferUtil;
import org.apache.cassandra.utils.FBUtilities;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class SchemaKeyspaceTest
{
    private static final String KEYSPACE1 = "CFMetaDataTest1";
    private static final String CF_STANDARD1 = "Standard1";

    @BeforeClass
    public static void defineSchema() throws ConfigurationException
    {
        SchemaLoader.prepareServer();
        SchemaLoader.createKeyspace(KEYSPACE1,
                                    KeyspaceParams.simple(1),
                                    SchemaLoader.standardCFMD(KEYSPACE1, CF_STANDARD1));
    }

    @Test
    public void testConversionsInverses() throws Exception
    {
        for (String keyspaceName : Schema.instance.getNonSystemKeyspaces())
        {
            for (ColumnFamilyStore cfs : Keyspace.open(keyspaceName).getColumnFamilyStores())
            {
                CFMetaData cfm = cfs.metadata;
                checkInverses(cfm);

                // Testing with compression to catch #3558
                CFMetaData withCompression = cfm.copy();
                withCompression.compression(CompressionParams.snappy(32768));
                checkInverses(withCompression);
            }
        }
    }

    @Test
    public void testExtensions() throws IOException
    {
        String keyspace = "SandBox";

        createTable(keyspace, "CREATE TABLE test (a text primary key, b int, c int)");

        CFMetaData metadata = Schema.instance.getCFMetaData(keyspace, "test");
        assertTrue("extensions should be empty", metadata.params.extensions.isEmpty());

        ImmutableMap<String, ByteBuffer> extensions = ImmutableMap.of("From ... with Love",
                                                                      ByteBuffer.wrap(new byte[]{0, 0, 7}));

        CFMetaData copy = metadata.copy().extensions(extensions);

        updateTable(keyspace, metadata, copy);

        metadata = Schema.instance.getCFMetaData(keyspace, "test");
        assertEquals(extensions, metadata.params.extensions);
    }

    private static void updateTable(String keyspace, CFMetaData oldTable, CFMetaData newTable)
    {
        KeyspaceMetadata ksm = Schema.instance.getKeyspaceInstance(keyspace).getMetadata();
        Mutation mutation = SchemaKeyspace.makeUpdateTableMutation(ksm, oldTable, newTable, FBUtilities.timestampMicros()).build();
        SchemaKeyspace.mergeSchema(Collections.singleton(mutation));
    }

    private static void createTable(String keyspace, String cql)
    {
        CFMetaData table = CFMetaData.compile(cql, keyspace);

        KeyspaceMetadata ksm = KeyspaceMetadata.create(keyspace, KeyspaceParams.simple(1), Tables.of(table));
        Mutation mutation = SchemaKeyspace.makeCreateTableMutation(ksm, table, FBUtilities.timestampMicros()).build();
        SchemaKeyspace.mergeSchema(Collections.singleton(mutation));
    }

    private static void checkInverses(CFMetaData cfm) throws Exception
    {
        KeyspaceMetadata keyspace = Schema.instance.getKSMetaData(cfm.ksName);

        // Test schema conversion
        Mutation rm = SchemaKeyspace.makeCreateTableMutation(keyspace, cfm, FBUtilities.timestampMicros()).build();
        PartitionUpdate serializedCf = rm.getPartitionUpdate(Schema.instance.getId(SchemaConstants.SCHEMA_KEYSPACE_NAME, SchemaKeyspace.TABLES));
        PartitionUpdate serializedCD = rm.getPartitionUpdate(Schema.instance.getId(SchemaConstants.SCHEMA_KEYSPACE_NAME, SchemaKeyspace.COLUMNS));

        UntypedResultSet.Row tableRow = QueryProcessor.resultify(String.format("SELECT * FROM %s.%s", SchemaConstants.SCHEMA_KEYSPACE_NAME, SchemaKeyspace.TABLES),
                                                                 UnfilteredRowIterators.filter(serializedCf.unfilteredIterator(), FBUtilities.nowInSeconds()))
                                                      .one();
        TableParams params = SchemaKeyspace.createTableParamsFromRow(tableRow);

        UntypedResultSet columnsRows = QueryProcessor.resultify(String.format("SELECT * FROM %s.%s", SchemaConstants.SCHEMA_KEYSPACE_NAME, SchemaKeyspace.COLUMNS),
                                                                UnfilteredRowIterators.filter(serializedCD.unfilteredIterator(), FBUtilities.nowInSeconds()));
        Set<ColumnDefinition> columns = new HashSet<>();
        for (UntypedResultSet.Row row : columnsRows)
            columns.add(SchemaKeyspace.createColumnFromRow(row, Types.none()));

        assertEquals(cfm.params, params);
        assertEquals(new HashSet<>(cfm.allColumns()), columns);
    }
}
