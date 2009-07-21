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

package org.apache.cassandra.config;

import java.util.*;
import java.io.*;

import org.apache.log4j.Logger;

import org.apache.cassandra.db.*;
import org.apache.cassandra.db.marshal.AbstractType;
import org.apache.cassandra.db.marshal.AsciiType;
import org.apache.cassandra.db.marshal.UTF8Type;
import org.apache.cassandra.utils.FileUtils;
import org.apache.cassandra.utils.XMLUtils;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;


/**
 * Author : Avinash Lakshman ( alakshman@facebook.com) & Prashant Malik ( pmalik@facebook.com )
 */

public class DatabaseDescriptor
{
    private static Logger logger_ = Logger.getLogger(DatabaseDescriptor.class);

    public static final String random_ = "RANDOM";
    public static final String ophf_ = "OPHF";
    private static int storagePort_ = 7000;
    private static int controlPort_ = 7001;
    private static int thriftPort_ = 9160;
    private static String listenAddress_; // leave null so we can fall through to getLocalHost
    private static String thriftAddress_;
    private static String clusterName_ = "Test";
    private static int replicationFactor_ = 3;
    private static long rpcTimeoutInMillis_ = 2000;
    private static Set<String> seeds_ = new HashSet<String>();
    private static String snapshotDirectory_;
    /* Keeps the list of data file directories */
    private static String[] dataFileDirectories_;
    /* Current index into the above list of directories */
    private static int currentIndex_ = 0;
    private static String logFileDirectory_;
    private static String bootstrapFileDirectory_;
    private static boolean rackAware_ = false;
    private static int threadsPerPool_ = 4;
    private static List<String> tables_ = new ArrayList<String>();
    private static Set<String> applicationColumnFamilies_ = new HashSet<String>();

    // Default descriptive names for use in CQL. The user can override
    // these choices in the config file. These are not case sensitive.
    // Hence, these are stored in UPPER case for easy comparison.
    private static String d_rowKey_           = "ROW_KEY";
    private static String d_superColumnMap_   = "SUPER_COLUMN_MAP";
    private static String d_superColumnKey_   = "SUPER_COLUMN_KEY";
    private static String d_columnMap_        = "COLUMN_MAP";
    private static String d_columnKey_        = "COLUMN_KEY";
    private static String d_columnValue_      = "COLUMN_VALUE";
    private static String d_columnTimestamp_  = "ColumnIMESTAMP";

    private static Map<String, Double> tableKeysCachedFractions_;
    /*
     * A map from table names to the set of column families for the table and the
     * corresponding meta data for that column family.
    */
    private static Map<String, Map<String, CFMetaData>> tableToCFMetaDataMap_;
    /* Hashing strategy Random or OPHF */
    private static String partitionerClass_;
    /* if the size of columns or super-columns are more than this, indexing will kick in */
    private static int columnIndexSizeInKB_;
    /* Number of hours to keep a memtable in memory */
    private static int memtableLifetime_ = 6;
    /* Size of the memtable in memory before it is dumped */
    private static int memtableSize_ = 128;
    /* Number of objects in millions in the memtable before it is dumped */
    private static double memtableObjectCount_ = 1;
    /* 
     * This parameter enables or disables consistency checks. 
     * If set to false the read repairs are disable for very
     * high throughput on reads but at the cost of consistency.
    */
    private static boolean doConsistencyCheck_ = true;
    /* Callout directories */
    private static String calloutLocation_;
    /* Job Jar Location */
    private static String jobJarFileLocation_;
    /* Address where to run the job tracker */
    private static String jobTrackerHost_;    
    /* time to wait before garbage collecting tombstones (deletion markers) */
    private static int gcGraceInSeconds_ = 10 * 24 * 3600; // 10 days

    // the path qualified config file (storage-conf.xml) name
    private static String configFileName_;
    /* initial token in the ring */
    private static String initialToken_ = null;

    static
    {
        try
        {
            configFileName_ = System.getProperty("storage-config") + File.separator + "storage-conf.xml";
            if (logger_.isDebugEnabled())
              logger_.debug("Loading settings from " + configFileName_);
            XMLUtils xmlUtils = new XMLUtils(configFileName_);

            /* Cluster Name */
            clusterName_ = xmlUtils.getNodeValue("/Storage/ClusterName");

            /* Hashing strategy */
            partitionerClass_ = xmlUtils.getNodeValue("/Storage/Partitioner");
            try
            {
                Class.forName(DatabaseDescriptor.getPartitionerClass());
            }
            catch (NullPointerException e)
            {
                throw new ConfigurationException("Missing partitioner directive /Storage/Partitioner");
            }
            catch (ClassNotFoundException e)
            {
                throw new ConfigurationException("Invalid partitioner class " + partitionerClass_);
            }

            /* Callout location */
            calloutLocation_ = xmlUtils.getNodeValue("/Storage/CalloutLocation");

            /* JobTracker address */
            jobTrackerHost_ = xmlUtils.getNodeValue("/Storage/JobTrackerHost");

            /* Job Jar file location */
            jobJarFileLocation_ = xmlUtils.getNodeValue("/Storage/JobJarFileLocation");

            String gcGrace = xmlUtils.getNodeValue("/Storage/GCGraceSeconds");
            if ( gcGrace != null )
                gcGraceInSeconds_ = Integer.parseInt(gcGrace);

            initialToken_ = xmlUtils.getNodeValue("/Storage/InitialToken");

            /* Data replication factor */
            String replicationFactor = xmlUtils.getNodeValue("/Storage/ReplicationFactor");
            if ( replicationFactor != null )
                replicationFactor_ = Integer.parseInt(replicationFactor);

            /* RPC Timeout */
            String rpcTimeoutInMillis = xmlUtils.getNodeValue("/Storage/RpcTimeoutInMillis");
            if ( rpcTimeoutInMillis != null )
                rpcTimeoutInMillis_ = Integer.parseInt(rpcTimeoutInMillis);

            /* Thread per pool */
            String threadsPerPool = xmlUtils.getNodeValue("/Storage/ThreadsPerPool");
            if ( threadsPerPool != null )
                threadsPerPool_ = Integer.parseInt(threadsPerPool);

            /* TCP port on which the storage system listens */
            String port = xmlUtils.getNodeValue("/Storage/StoragePort");
            if ( port != null )
                storagePort_ = Integer.parseInt(port);

            /* Local IP or hostname to bind services to */
            String listenAddress = xmlUtils.getNodeValue("/Storage/ListenAddress");
            if ( listenAddress != null)
                listenAddress_ = listenAddress;
            
            /* Local IP or hostname to bind thrift server to */
            String thriftAddress = xmlUtils.getNodeValue("/Storage/ThriftAddress");
            if ( thriftAddress != null )
                thriftAddress_ = thriftAddress;
            
            /* UDP port for control messages */
            port = xmlUtils.getNodeValue("/Storage/ControlPort");
            if ( port != null )
                controlPort_ = Integer.parseInt(port);

            /* get the thrift port from conf file */
            port = xmlUtils.getNodeValue("/Storage/ThriftPort");
            if (port != null)
                thriftPort_ = Integer.parseInt(port);

            /* Number of days to keep the memtable around w/o flushing */
            String lifetime = xmlUtils.getNodeValue("/Storage/MemtableLifetimeInDays");
            if ( lifetime != null )
                memtableLifetime_ = Integer.parseInt(lifetime);

            /* Size of the memtable in memory in MB before it is dumped */
            String memtableSize = xmlUtils.getNodeValue("/Storage/MemtableSizeInMB");
            if ( memtableSize != null )
                memtableSize_ = Integer.parseInt(memtableSize);
            /* Number of objects in millions in the memtable before it is dumped */
            String memtableObjectCount = xmlUtils.getNodeValue("/Storage/MemtableObjectCountInMillions");
            if ( memtableObjectCount != null )
                memtableObjectCount_ = Double.parseDouble(memtableObjectCount);
            if (memtableObjectCount_ <= 0)
            {
                throw new ConfigurationException("Memtable object count must be a positive double");
            }

            /* This parameter enables or disables consistency checks.
             * If set to false the read repairs are disable for very
             * high throughput on reads but at the cost of consistency.*/
            String doConsistencyCheck = xmlUtils.getNodeValue("/Storage/DoConsistencyChecksBoolean");
            if ( doConsistencyCheck != null )
                doConsistencyCheck_ = Boolean.parseBoolean(doConsistencyCheck);

            /* read the size at which we should do column indexes */
            String columnIndexSizeInKB = xmlUtils.getNodeValue("/Storage/ColumnIndexSizeInKB");
            if(columnIndexSizeInKB == null)
            {
                columnIndexSizeInKB_ = 64;
            }
            else
            {
                columnIndexSizeInKB_ = Integer.parseInt(columnIndexSizeInKB);
            }

            /* snapshot directory */
            snapshotDirectory_ = xmlUtils.getNodeValue("/Storage/SnapshotDirectory");
            if ( snapshotDirectory_ != null )
                FileUtils.createDirectory(snapshotDirectory_);

            /* data file directory */
            dataFileDirectories_ = xmlUtils.getNodeValues("/Storage/DataFileDirectories/DataFileDirectory");
            if (dataFileDirectories_.length == 0)
            {
                throw new ConfigurationException("At least one DataFileDirectory must be specified");
            }
            for ( String dataFileDirectory : dataFileDirectories_ )
                FileUtils.createDirectory(dataFileDirectory);

            /* bootstrap file directory */
            bootstrapFileDirectory_ = xmlUtils.getNodeValue("/Storage/BootstrapFileDirectory");
            if (bootstrapFileDirectory_ == null)
            {
                throw new ConfigurationException("BootstrapFileDirectory must be specified");
            }
            FileUtils.createDirectory(bootstrapFileDirectory_);

            /* commit log directory */
            logFileDirectory_ = xmlUtils.getNodeValue("/Storage/CommitLogDirectory");
            if (logFileDirectory_ == null)
            {
                throw new ConfigurationException("CommitLogDirectory must be specified");
            }
            FileUtils.createDirectory(logFileDirectory_);

            /* threshold after which commit log should be rotated. */
            String value = xmlUtils.getNodeValue("/Storage/CommitLogRotationThresholdInMB");
            if ( value != null)
                CommitLog.setSegmentSize(Integer.parseInt(value) * 1024 * 1024);

            tableToCFMetaDataMap_ = new HashMap<String, Map<String, CFMetaData>>();
            tableKeysCachedFractions_ = new HashMap<String, Double>();

            /* Rack Aware option */
            value = xmlUtils.getNodeValue("/Storage/RackAware");
            if ( value != null )
                rackAware_ = Boolean.parseBoolean(value);

            /* Read the table related stuff from config */
            NodeList tables = xmlUtils.getRequestedNodeList("/Storage/Tables/Table");
            int size = tables.getLength();
            for ( int i = 0; i < size; ++i )
            {
                Node table = tables.item(i);

                /* parsing out the table name */
                String tName = XMLUtils.getAttributeValue(table, "Name");
                if (tName == null)
                {
                    throw new ConfigurationException("Table name attribute is required");
                }
                if (tName.equalsIgnoreCase(Table.SYSTEM_TABLE))
                {
                    throw new ConfigurationException("'system' is a reserved table name for Cassandra internals");
                }
                tables_.add(tName);
                tableToCFMetaDataMap_.put(tName, new HashMap<String, CFMetaData>());

                String xqlCacheSize = "/Storage/Tables/Table[@Name='" + tName + "']/KeysCachedFraction";
                value = xmlUtils.getNodeValue(xqlCacheSize);
                if (value == null)
                {
                    tableKeysCachedFractions_.put(tName, 0.01);
                }
                else
                {
                    tableKeysCachedFractions_.put(tName, Double.valueOf(value));
                }

                String xqlTable = "/Storage/Tables/Table[@Name='" + tName + "']/";
                NodeList columnFamilies = xmlUtils.getRequestedNodeList(xqlTable + "ColumnFamily");

                // get name of the rowKey for this table
                String n_rowKey = xmlUtils.getNodeValue(xqlTable + "RowKey");
                if (n_rowKey == null)
                    n_rowKey = d_rowKey_;

                //NodeList columnFamilies = xmlUtils.getRequestedNodeList(table, "ColumnFamily");
                int size2 = columnFamilies.getLength();

                for ( int j = 0; j < size2; ++j )
                {
                    Node columnFamily = columnFamilies.item(j);
                    String cName = XMLUtils.getAttributeValue(columnFamily, "Name");
                    if (cName == null)
                    {
                        throw new ConfigurationException("ColumnFamily name attribute is required");
                    }
                    String xqlCF = xqlTable + "ColumnFamily[@Name='" + cName + "']/";

                    /* squirrel away the application column families */
                    applicationColumnFamilies_.add(cName);

                    // Parse out the column type
                    String rawColumnType = XMLUtils.getAttributeValue(columnFamily, "ColumnType");
                    String columnType = ColumnFamily.getColumnType(rawColumnType);
                    if (columnType == null)
                    {
                        throw new ConfigurationException("Column " + cName + " has invalid type " + rawColumnType);
                    }

                    if (XMLUtils.getAttributeValue(columnFamily, "ColumnSort") != null)
                    {
                        throw new ConfigurationException("ColumnSort is no longer an accepted attribute.  Use CompareWith instead.");
                    }

                    // Parse out the column comparator
                    Class<? extends AbstractType> typeClass;
                    String compareWith = XMLUtils.getAttributeValue(columnFamily, "CompareWith");
                    if (compareWith == null)
                    {
                        typeClass = org.apache.cassandra.db.marshal.AsciiType.class;
                    }
                    else
                    {
                        String className = compareWith.contains(".") ? compareWith : "org.apache.cassandra.db.marshal." + compareWith;
                        try
                        {
                            typeClass = (Class<? extends AbstractType>)Class.forName(className);
                        }
                        catch (ClassNotFoundException e)
                        {
                            throw new ConfigurationException("Unable to load class " + className + " for CompareWith attribute");
                        }
                    }
                    AbstractType columnComparator = typeClass.getConstructor().newInstance();

                    // see if flush period is set
                    String flushPeriodInMinutes = XMLUtils.getAttributeValue(columnFamily, "FlushPeriodInMinutes");
                    int flushPeriod=0;
                    if ( flushPeriodInMinutes != null )
                        flushPeriod = Integer.parseInt(flushPeriodInMinutes);

                    
                    // Parse out user-specified logical names for the various dimensions
                    // of a the column family from the config.
                    String n_superColumnMap = xmlUtils.getNodeValue(xqlCF + "SuperColumnMap");
                    if (n_superColumnMap == null)
                        n_superColumnMap = d_superColumnMap_;

                    String n_superColumnKey = xmlUtils.getNodeValue(xqlCF + "SuperColumnKey");
                    if (n_superColumnKey == null)
                        n_superColumnKey = d_superColumnKey_;

                    String n_columnMap = xmlUtils.getNodeValue(xqlCF + "ColumnMap");
                    if (n_columnMap == null)
                        n_columnMap = d_columnMap_;

                    String n_columnKey = xmlUtils.getNodeValue(xqlCF + "ColumnKey");
                    if (n_columnKey == null)
                        n_columnKey = d_columnKey_;

                    String n_columnValue = xmlUtils.getNodeValue(xqlCF + "ColumnValue");
                    if (n_columnValue == null)
                        n_columnValue = d_columnValue_;

                    String n_columnTimestamp = xmlUtils.getNodeValue(xqlCF + "ColumnTimestamp");
                    if (n_columnTimestamp == null)
                        n_columnTimestamp = d_columnTimestamp_;

                    // now populate the column family meta data and
                    // insert it into the table dictionary.
                    CFMetaData cfMetaData = new CFMetaData();

                    cfMetaData.tableName = tName;
                    cfMetaData.cfName = cName;

                    cfMetaData.columnType = columnType;
                    cfMetaData.comparator = columnComparator;

                    cfMetaData.n_rowKey = n_rowKey;
                    cfMetaData.n_columnMap = n_columnMap;
                    cfMetaData.n_columnKey = n_columnKey;
                    cfMetaData.n_columnValue = n_columnValue;
                    cfMetaData.n_columnTimestamp = n_columnTimestamp;
                    if ("Super".equals(columnType))
                    {
                        cfMetaData.n_superColumnKey = n_superColumnKey;
                        cfMetaData.n_superColumnMap = n_superColumnMap;
                    }
                    cfMetaData.flushPeriodInMinutes = flushPeriod;
                    
                    tableToCFMetaDataMap_.get(tName).put(cName, cfMetaData);
                }
            }

            // Hardcoded system tables
            Map<String, CFMetaData> systemMetadata = new HashMap<String, CFMetaData>();

            CFMetaData data = new CFMetaData();
            data.comparator = new AsciiType();
            systemMetadata.put(SystemTable.LOCATION_CF, data);

            data = new CFMetaData();
            data.columnType = "Super";
            data.comparator = new UTF8Type();
            systemMetadata.put(HintedHandOffManager.HINTS_CF, data);

            tableToCFMetaDataMap_.put("system", systemMetadata);

            /* make sure we have a directory for each table */
            createTableDirectories();

            /* Load the seeds for node contact points */
            String[] seeds = xmlUtils.getNodeValues("/Storage/Seeds/Seed");
            for( int i = 0; i < seeds.length; ++i )
            {
                seeds_.add( seeds[i] );
            }
        }
        catch (ConfigurationException e)
        {
            logger_.error("Fatal error: " + e.getMessage());
            System.err.println("Bad configuration; unable to start server");
            System.exit(1);
        }
        catch (Exception e)
        {
            throw new RuntimeException(e);
        }
    }
    
    /**
     * Create the table directory in each data directory
     */
    public static void createTableDirectories() throws IOException
    {
        for (String dataFile : dataFileDirectories_) 
        {
            FileUtils.createDirectory(dataFile + File.separator + Table.SYSTEM_TABLE);
            for (String table : tables_)
            {
                FileUtils.createDirectory(dataFile + File.separator + table);
            }
        }
    }

    /**
     * Create the metadata tables. This table has information about
     * the table name and the column families that make up the table.
     * Each column family also has an associated ID which is an int.
    */
    // TODO duplicating data b/t tablemetadata and CFMetaData is confusing and error-prone
    public static void storeMetadata() throws IOException
    {
        int cfId = 0;
        Set<String> tables = tableToCFMetaDataMap_.keySet();

        for (String table : tables)
        {
            Table.TableMetadata tmetadata = Table.TableMetadata.instance(table);
            if (tmetadata.isEmpty())
            {
                tmetadata = Table.TableMetadata.instance(table);
                /* Column families associated with this table */
                Map<String, CFMetaData> columnFamilies = tableToCFMetaDataMap_.get(table);

                for (String columnFamily : columnFamilies.keySet())
                {
                    tmetadata.add(columnFamily, cfId++, DatabaseDescriptor.getColumnType(table, columnFamily));
                }
            }
        }
    }

    public static int getGcGraceInSeconds()
    {
        return gcGraceInSeconds_;
    }

    public static String getPartitionerClass()
    {
        return partitionerClass_;
    }
    
    public static String getCalloutLocation()
    {
        return calloutLocation_;
    }
    
    public static String getJobTrackerAddress()
    {
        return jobTrackerHost_;
    }
    
    public static int getColumnIndexSize()
    {
    	return columnIndexSizeInKB_ * 1024;
    }

    public static int getMemtableLifetime()
    {
      return memtableLifetime_;
    }

    public static String getInitialToken()
    {
      return initialToken_;
    }

    public static int getMemtableSize()
    {
      return memtableSize_;
    }

    public static double getMemtableObjectCount()
    {
      return memtableObjectCount_;
    }

    public static boolean getConsistencyCheck()
    {
      return doConsistencyCheck_;
    }

    public static String getClusterName()
    {
        return clusterName_;
    }

    public static String getConfigFileName() {
        return configFileName_;
    }
    
    public static boolean isApplicationColumnFamily(String columnFamily)
    {
        return applicationColumnFamilies_.contains(columnFamily);
    }

    public static String getJobJarLocation()
    {
        return jobJarFileLocation_;
    }
    
    public static Map<String, CFMetaData> getTableMetaData(String tableName)
    {
        assert tableName != null;
        return tableToCFMetaDataMap_.get(tableName);
    }

    /*
     * Given a table name & column family name, get the column family
     * meta data. If the table name or column family name is not valid
     * this function returns null.
     */
    public static CFMetaData getCFMetaData(String tableName, String cfName)
    {
        assert tableName != null;
        Map<String, CFMetaData> cfInfo = tableToCFMetaDataMap_.get(tableName);
        if (cfInfo == null)
            return null;
        
        return cfInfo.get(cfName);
    }
    
    public static String getColumnType(String tableName, String cfName)
    {
        assert tableName != null;
        CFMetaData cfMetaData = getCFMetaData(tableName, cfName);
        
        if (cfMetaData == null)
            return null;
        return cfMetaData.columnType;
    }

    public static int getFlushPeriod(String tableName, String columnFamilyName)
    {
        assert tableName != null;
        CFMetaData cfMetaData = getCFMetaData(tableName, columnFamilyName);
        
        if (cfMetaData == null)
            return 0;
        return cfMetaData.flushPeriodInMinutes;
    }

    public static List<String> getTables()
    {
        return tables_;
    }

    public static String getTable(String tableName)
    {
        assert tableName != null;
        int index = getTables().indexOf(tableName);
        return index >= 0 ? getTables().get(index) : null;
    }

    public static void  setTables(String table)
    {
        tables_.add(table);
    }

    public static int getStoragePort()
    {
        return storagePort_;
    }

    public static int getControlPort()
    {
        return controlPort_;
    }

    public static int getThriftPort()
    {
        return thriftPort_;
    }

    public static int getReplicationFactor()
    {
        return replicationFactor_;
    }

    public static int getQuorum()
    {
        return (replicationFactor_ / 2) + 1;
    }

    public static long getRpcTimeout()
    {
        return rpcTimeoutInMillis_;
    }

    public static int getThreadsPerPool()
    {
        return threadsPerPool_;
    }

    public static String getSnapshotDirectory()
    {
        return snapshotDirectory_;
    }

    public static void setSnapshotDirectory(String snapshotDirectory)
    {
    	snapshotDirectory_ = snapshotDirectory;
    }

    public static String[] getAllDataFileLocations()
    {
        return dataFileDirectories_;
    }

    public static String[] getAllDataFileLocationsForTable(String table)
    {
        String[] tableLocations = new String[dataFileDirectories_.length];

        for (int i = 0; i < dataFileDirectories_.length; i++)
        {
            tableLocations[i] = dataFileDirectories_[i] + File.separator + table;
        }

        return tableLocations;
    }

    public static String getDataFileLocationForTable(String table)
    {
        String dataFileDirectory = dataFileDirectories_[currentIndex_] + File.separator + table;
        currentIndex_ = (currentIndex_ + 1) % dataFileDirectories_.length;
        return dataFileDirectory;
    }

    public static String getBootstrapFileLocation()
    {
        return bootstrapFileDirectory_;
    }

    public static void setBootstrapFileLocation(String bfLocation)
    {
        bootstrapFileDirectory_ = bfLocation;
    }

    public static String getLogFileLocation()
    {
        return logFileDirectory_;
    }

    public static void setLogFileLocation(String logLocation)
    {
        logFileDirectory_ = logLocation;
    }

    public static boolean isRackAware()
    {
        return rackAware_;
    }

    public static Set<String> getSeeds()
    {
        return seeds_;
    }

    public static String getColumnFamilyType(String tableName, String cfName)
    {
        assert tableName != null;
        String cfType = getColumnType(tableName, cfName);
        if ( cfType == null )
            cfType = "Standard";
    	return cfType;
    }

    /*
     * Loop through all the disks to see which disk has the max free space
     * return the disk with max free space for compactions. If the size of the expected
     * compacted file is greater than the max disk space available return null, we cannot
     * do compaction in this case.
     */
    public static String getDataFileLocationForTable(String table, long expectedCompactedFileSize)
    {
      long maxFreeDisk = 0;
      int maxDiskIndex = 0;
      String dataFileDirectory = null;
      String[] dataDirectoryForTable = getAllDataFileLocationsForTable(table);

      for ( int i = 0 ; i < dataDirectoryForTable.length ; i++ )
      {
        File f = new File(dataDirectoryForTable[i]);
        if( maxFreeDisk < f.getUsableSpace())
        {
          maxFreeDisk = f.getUsableSpace();
          maxDiskIndex = i;
        }
      }
      // Load factor of 0.9 we do not want to use the entire disk that is too risky.
      maxFreeDisk = (long)(0.9 * maxFreeDisk);
      if( expectedCompactedFileSize < maxFreeDisk )
      {
        dataFileDirectory = dataDirectoryForTable[maxDiskIndex];
        currentIndex_ = (maxDiskIndex + 1 )%dataDirectoryForTable.length ;
      }
      else
      {
        currentIndex_ = maxDiskIndex;
      }
        return dataFileDirectory;
    }
    
    public static AbstractType getType(String tableName, String cfName)
    {
        assert tableName != null;
        return getCFMetaData(tableName, cfName).comparator;
    }

    public static Map<String, Map<String, CFMetaData>> getTableToColumnFamilyMap()
    {
        return tableToCFMetaDataMap_;
    }

    public static double getKeysCachedFraction(String tableName)
    {
        return tableKeysCachedFractions_.get(tableName);
    }

    private static class ConfigurationException extends Exception
    {
        public ConfigurationException(String message)
        {
            super(message);
        }
    }

    public static String getListenAddress()
    {
        return listenAddress_;
    }
    
    public static String getThriftAddress()
    {
        return thriftAddress_;
    }
}
