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
package org.apache.cassandra.db;

import java.io.DataInputStream;
import java.io.IOException;

import org.apache.cassandra.io.DataInputBuffer;
import org.apache.cassandra.io.SSTableReader;


public class IdentityFilter implements IFilter
{
	public ColumnFamily filter(String cfString, ColumnFamily columnFamily)
	{
		return columnFamily;
	}

	public IColumn filter(IColumn column, DataInputStream dis) throws IOException
	{
		return column;
	}

	public DataInputBuffer next(String key, String cf, SSTableReader ssTable) throws IOException
	{
		return ssTable.next(key, cf);
	}
}
