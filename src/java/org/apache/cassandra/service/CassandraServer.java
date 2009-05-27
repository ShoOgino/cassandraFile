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

import java.io.BufferedInputStream;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.TimeoutException;

import org.apache.log4j.Logger;

import org.apache.cassandra.config.CFMetaData;
import org.apache.cassandra.config.DatabaseDescriptor;
import org.apache.cassandra.cql.common.CqlResult;
import org.apache.cassandra.cql.driver.CqlDriver;
import org.apache.cassandra.db.*;
import org.apache.cassandra.utils.LogUtil;
import org.apache.cassandra.dht.OrderPreservingPartitioner;
import org.apache.thrift.TException;

/**
 * Author : Avinash Lakshman ( alakshman@facebook.com) & Prashant Malik ( pmalik@facebook.com )
 */

public class CassandraServer implements Cassandra.Iface
{
	private static Logger logger = Logger.getLogger(CassandraServer.class);

    private final static List<column_t> EMPTY_COLUMNS = Arrays.asList();
    private final static List<superColumn_t> EMPTY_SUPERCOLUMNS = Arrays.asList();

    /*
      * Handle to the storage service to interact with the other machines in the
      * cluster.
      */
	protected StorageService storageService;

    public CassandraServer()
	{
		storageService = StorageService.instance();
	}

	/*
	 * The start function initializes the server and start's listening on the
	 * specified port.
	 */
	public void start() throws IOException
    {
		LogUtil.init();
		//LogUtil.setLogLevel("com.facebook", "DEBUG");
		// Start the storage service
		storageService.start();
	}
	
	private void validateKeyCommand(String key, String tablename, String... columnFamilyNames) throws InvalidRequestException
	{
        if (key.isEmpty())
        {
            throw new InvalidRequestException("Key may not be empty");
        }
        validateCommand(tablename, columnFamilyNames);
	}

    private void validateCommand(String tablename, String... columnFamilyNames) throws TableNotDefinedException, ColumnFamilyNotDefinedException
    {
        if (!DatabaseDescriptor.getTables().contains(tablename))
        {
            throw new TableNotDefinedException("Table " + tablename + " does not exist in this schema.");
        }
        Table table = Table.open(tablename);
        for (String cfName : columnFamilyNames)
        {
            if (!table.getColumnFamilies().contains(cfName))
            {
                throw new ColumnFamilyNotDefinedException("Column Family " + cfName + " is invalid.");
            }
        }
    }

    protected ColumnFamily readColumnFamily(ReadCommand command) throws InvalidRequestException
    {
        String cfName = command.getColumnFamilyName();
        validateKeyCommand(command.key, command.table, cfName);

        Row row;
        try
        {
            row = StorageProxy.readProtocol(command, StorageService.ConsistencyLevel.WEAK);
        }
        catch (IOException e)
        {
            throw new RuntimeException(e);
        }
        catch (TimeoutException e)
        {
            throw new RuntimeException(e);
        }

        if (row == null)
        {
            return null;
        }
        return row.getColumnFamily(cfName);
	}

    public List<column_t> thriftifyColumns(Collection<IColumn> columns)
    {
        if (columns == null || columns.isEmpty())
        {
            return EMPTY_COLUMNS;
        }

        ArrayList<column_t> thriftColumns = new ArrayList<column_t>(columns.size());
        for (IColumn column : columns)
        {
            if (column.isMarkedForDelete())
            {
                continue;
            }
            column_t thrift_column = new column_t(column.name(), column.value(), column.timestamp());
            thriftColumns.add(thrift_column);
        }

        return thriftColumns;
    }

    public List<column_t> get_columns_since(String tablename, String key, String columnFamily_column, long timeStamp) throws InvalidRequestException
    {
        logger.debug("get_columns_since");
        ColumnFamily cfamily = readColumnFamily(new ColumnsSinceReadCommand(tablename, key, columnFamily_column, timeStamp));
        String[] values = RowMutation.getColumnAndColumnFamily(columnFamily_column);
        if (cfamily == null)
        {
            return EMPTY_COLUMNS;
        }
        Collection<IColumn> columns = null;
        if( values.length > 1 )
        {
            // this is the super column case
            IColumn column = cfamily.getColumn(values[1]);
            if(column != null)
                columns = column.getSubColumns();
        }
        else
        {
            columns = cfamily.getAllColumns();
        }
        return thriftifyColumns(columns);
	}
	

    public List<column_t> get_slice_by_names(String tablename, String key, String columnFamily, List<String> columnNames) throws InvalidRequestException
    {
        logger.debug("get_slice_by_names");
        ColumnFamily cfamily = readColumnFamily(new SliceByNamesReadCommand(tablename, key, columnFamily, columnNames));
        if (cfamily == null)
        {
            return EMPTY_COLUMNS;
        }
        return thriftifyColumns(cfamily.getAllColumns());
    }
    
    public List<column_t> get_slice(String tablename, String key, String columnFamily_column, int start, int count) throws InvalidRequestException
    {
        logger.debug("get_slice");
        String[] values = RowMutation.getColumnAndColumnFamily(columnFamily_column);
        ColumnFamily cfamily = readColumnFamily(new SliceReadCommand(tablename, key, columnFamily_column, start, count));
        if (cfamily == null)
        {
            return EMPTY_COLUMNS;
        }
        Collection<IColumn> columns = null;
        if( values.length > 1 )
        {
            // this is the super column case
            IColumn column = cfamily.getColumn(values[1]);
            if(column != null)
                columns = column.getSubColumns();
        }
        else
        {
            columns = cfamily.getAllColumns();
        }
        return thriftifyColumns(columns);
	}

    public List<column_t> get_slice_from(String tablename, String key, String columnFamily_column, boolean isAscending, int count) throws InvalidRequestException
    {
        logger.debug("get_slice_from");
        String[] values = RowMutation.getColumnAndColumnFamily(columnFamily_column);
        if (values.length != 2 || DatabaseDescriptor.getColumnFamilyType(values[0]) != "Standard")
            throw new InvalidRequestException("get_slice_from requires a standard CF name and a starting column name");
        if (count <= 0)
            throw new InvalidRequestException("get_slice_from requires positive count");
        if ("Name".compareTo(DatabaseDescriptor.getCFMetaData(tablename, values[0]).indexProperty_) != 0)
            throw new InvalidRequestException("get_slice_from requires CF indexed by name");
        ColumnFamily cfamily = readColumnFamily(new SliceFromReadCommand(tablename, key, columnFamily_column, isAscending, count));
        if (cfamily == null)
        {
            return EMPTY_COLUMNS;
        }
        Collection<IColumn> columns = cfamily.getAllColumns();
        return thriftifyColumns(columns);
    }

    public column_t get_column(String tablename, String key, String columnFamily_column) throws NotFoundException, InvalidRequestException
    {
        logger.debug("get_column");
        String[] values = RowMutation.getColumnAndColumnFamily(columnFamily_column);
        if (values.length < 1)
        {
            throw new InvalidRequestException("get_column requires non-empty columnfamily");
        }
        if (DatabaseDescriptor.getColumnFamilyType(values[0]).equals("Standard"))
        {
            if (values.length != 2)
            {
                throw new InvalidRequestException("get_column requires both parts of columnfamily:column for standard CF " + values[0]);
            }
        }
        else
        {
            if (values.length != 3)
            {
                throw new InvalidRequestException("get_column requires all parts of columnfamily:supercolumn:subcolumn for super CF " + values[0]);
            }
        }

        ColumnReadCommand readCommand = new ColumnReadCommand(tablename, key, columnFamily_column);
        ColumnFamily cfamily = readColumnFamily(readCommand);
        if (cfamily == null)
        {
            throw new NotFoundException();
        }
        Collection<IColumn> columns = null;
        if( values.length > 2 )
        {
            // this is the super column case
            IColumn column = cfamily.getColumn(values[1]);
            if(column != null)
                columns = column.getSubColumns();
        }
        else
        {
            columns = cfamily.getAllColumns();
        }
        if (columns == null || columns.size() == 0)
        {
            throw new NotFoundException();
        }

        assert columns.size() == 1;
        IColumn column = columns.iterator().next();
        if (column.isMarkedForDelete())
        {
            throw new NotFoundException();
        }

        return new column_t(column.name(), column.value(), column.timestamp());
    }
    

    public int get_column_count(String tablename, String key, String columnFamily_column) throws InvalidRequestException
    {
        logger.debug("get_column_count");
        String[] values = RowMutation.getColumnAndColumnFamily(columnFamily_column);
        ColumnFamily cfamily = readColumnFamily(new SliceReadCommand(tablename, key, columnFamily_column, -1, Integer.MAX_VALUE));
        if (cfamily == null)
        {
            return 0;
        }
        Collection<IColumn> columns = null;
        if( values.length > 1 )
        {
            // this is the super column case
            IColumn column = cfamily.getColumn(values[1]);
            if(column != null)
                columns = column.getSubColumns();
        }
        else
        {
            columns = cfamily.getAllColumns();
        }
        if (columns == null || columns.size() == 0)
        {
            return 0;
        }
        return columns.size();
	}

    public void insert(String tablename, String key, String columnFamily_column, byte[] cellData, long timestamp, int block)
    throws InvalidRequestException, UnavailableException
    {
        logger.debug("insert");
        RowMutation rm = new RowMutation(tablename, key.trim());
        rm.add(columnFamily_column, cellData, timestamp);
        Set<String> cfNames = rm.columnFamilyNames();
        validateKeyCommand(rm.key(), rm.table(), cfNames.toArray(new String[cfNames.size()]));

        doInsert(block, rm);
    }

    public void batch_insert(batch_mutation_t batchMutation, int block) throws InvalidRequestException, UnavailableException
    {
        logger.debug("batch_insert");
        RowMutation rm = RowMutation.getRowMutation(batchMutation);
        Set<String> cfNames = rm.columnFamilyNames();
        validateKeyCommand(rm.key(), rm.table(), cfNames.toArray(new String[cfNames.size()]));

        doInsert(block, rm);
    }

    public void remove(String tablename, String key, String columnFamily_column, long timestamp, int block)
    throws InvalidRequestException, UnavailableException
    {
        logger.debug("remove");
        RowMutation rm = new RowMutation(tablename, key.trim());
        rm.delete(columnFamily_column, timestamp);
        Set<String> cfNames = rm.columnFamilyNames();
        validateKeyCommand(rm.key(), rm.table(), cfNames.toArray(new String[cfNames.size()]));
        doInsert(block, rm);
	}

    private void doInsert(int block, RowMutation rm)
            throws UnavailableException
    {
        if (block>0)
        {
            StorageProxy.insertBlocking(rm,block);
        }
        else
        {
            StorageProxy.insert(rm);
        }
    }

    public List<superColumn_t> get_slice_super_by_names(String tablename, String key, String columnFamily, List<String> superColumnNames) throws InvalidRequestException
    {
        logger.debug("get_slice_super_by_names");
        ColumnFamily cfamily = readColumnFamily(new SliceByNamesReadCommand(tablename, key, columnFamily, superColumnNames));
        if (cfamily == null)
        {
            return EMPTY_SUPERCOLUMNS;
        }
        return thriftifySuperColumns(cfamily.getAllColumns());
    }

    private List<superColumn_t> thriftifySuperColumns(Collection<IColumn> columns)
    {
        if (columns == null || columns.isEmpty())
        {
            return EMPTY_SUPERCOLUMNS;
        }

        ArrayList<superColumn_t> thriftSuperColumns = new ArrayList<superColumn_t>(columns.size());
        for (IColumn column : columns)
        {
            List<column_t> subcolumns = thriftifyColumns(column.getSubColumns());
            if (subcolumns.isEmpty())
            {
                continue;
            }
            thriftSuperColumns.add(new superColumn_t(column.name(), subcolumns));
        }

        return thriftSuperColumns;
    }

    public List<superColumn_t> get_slice_super(String tablename, String key, String columnFamily_superColumnName, int start, int count) throws InvalidRequestException
    {
        logger.debug("get_slice_super");
        ColumnFamily cfamily = readColumnFamily(new SliceReadCommand(tablename, key, columnFamily_superColumnName, start, count));
        if (cfamily == null)
        {
            return EMPTY_SUPERCOLUMNS;
        }
        Collection<IColumn> columns = cfamily.getAllColumns();
        return thriftifySuperColumns(columns);
    }
    
    public superColumn_t get_superColumn(String tablename, String key, String columnFamily_column) throws InvalidRequestException, NotFoundException
    {
        logger.debug("get_superColumn");
        ColumnFamily cfamily = readColumnFamily(new ColumnReadCommand(tablename, key, columnFamily_column));
        if (cfamily == null)
        {
            throw new NotFoundException();
        }
        Collection<IColumn> columns = cfamily.getAllColumns();
        if (columns == null || columns.size() == 0)
        {
            throw new NotFoundException();
        }

        assert columns.size() == 1;
        IColumn column = columns.iterator().next();
        if (column.getSubColumns().size() == 0)
        {
            throw new NotFoundException();
        }

        return new superColumn_t(column.name(), thriftifyColumns(column.getSubColumns()));
    }

    public void batch_insert_superColumn(batch_mutation_super_t batchMutationSuper, int block) throws InvalidRequestException, UnavailableException
    {
        logger.debug("batch_insert_SuperColumn");
        RowMutation rm = RowMutation.getRowMutation(batchMutationSuper);
        Set<String> cfNames = rm.columnFamilyNames();
        validateKeyCommand(rm.key(), rm.table(), cfNames.toArray(new String[cfNames.size()]));
        doInsert(block, rm);
    }

    public String getStringProperty(String propertyName)
    {
        if (propertyName.equals("cluster name"))
        {
            return DatabaseDescriptor.getClusterName();
        }
        else if (propertyName.equals("config file"))
        {
            String filename = DatabaseDescriptor.getConfigFileName();
            try
            {
                StringBuffer fileData = new StringBuffer(8192);
                BufferedInputStream stream = new BufferedInputStream(new FileInputStream(filename));
                byte[] buf = new byte[1024];
                int numRead;
                while( (numRead = stream.read(buf)) != -1)
                {
                    String str = new String(buf, 0, numRead);
                    fileData.append(str);
                }
                stream.close();
                return fileData.toString();
            }
            catch (IOException e)
            {
                return "file not found!";
            }
        }
        else if (propertyName.equals("version"))
        {
            return "1";
        }
        else
        {
            return "?";
        }
    }

    public List<String> getStringListProperty(String propertyName)
    {
        if (propertyName.equals("tables"))
        {
            return DatabaseDescriptor.getTables();        
        }
        else
        {
            return new ArrayList<String>();
        }
    }

    public String describeTable(String tableName)
    {
        String desc = "";
        Map<String, CFMetaData> tableMetaData = DatabaseDescriptor.getTableMetaData(tableName);

        if (tableMetaData == null)
        {
            return "Table " + tableName +  " not found.";
        }

        Iterator iter = tableMetaData.entrySet().iterator();
        while (iter.hasNext())
        {
            Map.Entry<String, CFMetaData> pairs = (Map.Entry<String, CFMetaData>)iter.next();
            desc = desc + pairs.getValue().pretty() + "-----\n";
        }
        return desc;
    }

    public CqlResult_t executeQuery(String query) throws TException
    {
        CqlResult_t result = new CqlResult_t();

        CqlResult cqlResult = CqlDriver.executeQuery(query);
        
        // convert CQL result type to Thrift specific return type
        if (cqlResult != null)
        {
            result.errorTxt = cqlResult.errorTxt;
            result.resultSet = cqlResult.resultSet;
            result.errorCode = cqlResult.errorCode;
        }
        return result;
    }

    public List<String> get_key_range(String tablename, List<String> columnFamilies, String startWith, String stopAt, int maxResults) throws InvalidRequestException
    {
        logger.debug("get_key_range");
        validateCommand(tablename, columnFamilies.toArray(new String[columnFamilies.size()]));
        if (!(StorageService.getPartitioner() instanceof OrderPreservingPartitioner))
        {
            throw new InvalidRequestException("range queries may only be performed against an order-preserving partitioner");
        }
        if (maxResults <= 0)
        {
            throw new InvalidRequestException("maxResults must be positive");
        }

        return StorageProxy.getKeyRange(new RangeCommand(tablename, columnFamilies, startWith, stopAt, maxResults));
    }

    /*
     * This method is used to ensure that all keys
     * prior to the specified key, as dtermined by
     * the SSTable index bucket it falls in, are in
     * buffer cache.  
    */
    public void touch (String key, boolean fData)
    {
        logger.debug("touch");
  		StorageProxy.touchProtocol(DatabaseDescriptor.getTables().get(0), key, fData, StorageService.ConsistencyLevel.WEAK);
	}

	public List<column_t> get_slice_by_name_range(String tablename, String key, String columnFamily, String start, String end, int count)
    throws InvalidRequestException, NotFoundException, TException
    {
		logger.debug("get_slice_by_range");
        ColumnFamily cfamily = readColumnFamily(new SliceByRangeReadCommand(tablename, key, columnFamily, start, end, count));
        if (cfamily == null)
        {
            return EMPTY_COLUMNS;
        }
        return thriftifyColumns(cfamily.getAllColumns());
	}

    // main method moved to CassandraDaemon
}
