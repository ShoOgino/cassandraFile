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
package org.apache.cassandra.client;

import java.net.InetAddress;
import org.apache.cassandra.service.Cassandra;
import org.apache.cassandra.service.Column;
import org.apache.cassandra.service.ColumnPath;
import org.apache.cassandra.config.DatabaseDescriptor;
import org.apache.thrift.protocol.TBinaryProtocol;
import org.apache.thrift.transport.TSocket;
import org.apache.thrift.transport.TTransport;

/**
 *  Sample code that uses RingCache in the client.
 */
public class TestRingCache
{
    private static RingCache ringCache;
    private static Cassandra.Client thriftClient;

    static
    {
        ringCache = new RingCache();
    }

    private static void setup(String server, int port) throws Exception
    {
        /* Establish a thrift connection to the cassandra instance */
        TSocket socket = new TSocket(server, port);
        TTransport transport;
        System.out.println(" connected to " + server + ":" + port + ".");
        transport = socket;
        TBinaryProtocol binaryProtocol = new TBinaryProtocol(transport, false, false);
        Cassandra.Client cassandraClient = new Cassandra.Client(binaryProtocol);
        transport.open();
        thriftClient = cassandraClient;
    }

    /**
     * usage: java -Dstorage-config="confpath" org.apache.cassandra.client.TestRingCache
     * @param args
     * @throws Exception
     */
    public static void main(String[] args) throws Throwable
    {
        String table = "Keyspace1";
        for (int nRows=1; nRows<10; nRows++)
        {
            String row = "row" + nRows;
            ColumnPath col = new ColumnPath("Standard1", null, "col1".getBytes());

            InetAddress endPoints[] = ringCache.getEndPoint(row);
            String hosts="";
            for (int i=0; i<endPoints.length; i++)
                hosts = hosts + ((i>0) ? "," : "") + endPoints[i];
            System.out.println("hosts with key " + row + " : " + hosts + "; choose " + endPoints[0]);
        
            // now, read the row back directly from the host owning the row locally
            setup(endPoints[0].getHostAddress(), DatabaseDescriptor.getThriftPort());
            thriftClient.insert(table, row, col, "val1".getBytes(), 1, 1);
            Column column=thriftClient.get(table, row, col, 1).column;
            System.out.println("read row " + row + " " + new String(column.name) + ":" + new String(column.value) + ":" + column.timestamp);
        }
        System.exit(1);
    }
}
