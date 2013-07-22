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
package org.apache.cassandra.triggers;

import java.io.InputStream;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Properties;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.apache.cassandra.db.ColumnFamily;
import org.apache.cassandra.db.RowMutation;
import org.apache.cassandra.io.util.FileUtils;

public class InvertedIndex implements ITrigger {
    private static final Logger logger = LoggerFactory.getLogger(InvertedIndex.class);
    private Properties properties = loadProperties();

    public Collection<RowMutation> augment(ByteBuffer key, ColumnFamily update) {
        List<RowMutation> mutations = new ArrayList<RowMutation>();
        for (ByteBuffer name : update.getColumnNames()) {
            RowMutation mutation = new RowMutation(properties.getProperty("keyspace"), update.getColumn(name).value());
            mutation.add(properties.getProperty("columnfamily"), name, key, System.currentTimeMillis());
            mutations.add(mutation);
        }
        return mutations;
    }

    private static Properties loadProperties() {
        Properties properties = new Properties();
        InputStream stream = InvertedIndex.class.getClassLoader().getResourceAsStream("InvertedIndex.properties");
        try {
            properties.load(stream);
        } catch (Exception e) {
            throw new RuntimeException(e);
        } finally {
            FileUtils.closeQuietly(stream);
        }
        logger.info("loaded property file, InvertedIndex.properties");
        return properties;
    }
}
