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

package org.apache.cassandra.db;

import org.apache.cassandra.CleanupHelper;
import org.apache.cassandra.config.CFMetaData;
import static org.apache.cassandra.config.DatabaseDescriptor.ConfigurationException;
import org.apache.cassandra.config.DatabaseDescriptor;
import org.apache.cassandra.config.KSMetaData;
import org.apache.cassandra.db.commitlog.CommitLog;
import org.apache.cassandra.db.filter.NamesQueryFilter;
import org.apache.cassandra.db.filter.QueryPath;
import org.apache.cassandra.db.marshal.TimeUUIDType;
import org.apache.cassandra.utils.FBUtilities;
import org.apache.cassandra.db.marshal.UTF8Type;
import org.apache.cassandra.utils.UUIDGen;
import org.junit.Before;
import org.junit.Test;

import java.io.File;
import java.io.FileFilter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutionException;

public class DefsTest extends CleanupHelper
{
    @Before
    public void setup()
    {
        // just something to ensure that DD has been initialized.
        DatabaseDescriptor.getNonSystemTables();
    }

    @Test
    public void saveAndRestore() throws IOException
    {
        // verify dump and reload.
        UUID first = UUIDGen.makeType1UUIDFromHost(FBUtilities.getLocalAddress());
        DefsTable.dumpToStorage(first);
        List<KSMetaData> defs = new ArrayList<KSMetaData>(DefsTable.loadFromStorage(first));

        assert defs.size() > 0;
        assert defs.size() == DatabaseDescriptor.getNonSystemTables().size();
        for (KSMetaData loaded : defs)
        {
            KSMetaData defined = DatabaseDescriptor.getTableDefinition(loaded.name);
            assert defined.equals(loaded);
        }
    }
     

    @Test
    public void addNewCF() throws IOException, ConfigurationException, ExecutionException, InterruptedException
    {
        final String ks = "Keyspace1";
        final String cf = "BrandNewCf";
        KSMetaData original = DatabaseDescriptor.getTableDefinition(ks);

        CFMetaData newCf = new CFMetaData(original.name, cf, "Standard", new UTF8Type(), null, "A New Column Family", 0, 0);
        int clSegments = CommitLog.instance().getSegmentCount();
        DefsTable.add(newCf);
        assert CommitLog.instance().getSegmentCount() == clSegments + 1;

        assert DatabaseDescriptor.getTableDefinition(ks).cfMetaData().containsKey(newCf.cfName);
        assert DatabaseDescriptor.getTableDefinition(ks).cfMetaData().get(newCf.cfName).equals(newCf);

        // now read and write to it.
        RowMutation rm = new RowMutation(ks, "key0");
        rm.add(new QueryPath(cf, null, "col0".getBytes()), "value0".getBytes(), 1L);
        rm.apply();
        ColumnFamilyStore store = Table.open(ks).getColumnFamilyStore(cf);
        assert store != null;
        store.forceBlockingFlush();
        ColumnFamily cfam = store.getColumnFamily(new NamesQueryFilter("key0", new QueryPath(cf), "col0".getBytes()));
        assert cfam.getColumn("col0".getBytes()) != null;
        IColumn col = cfam.getColumn("col0".getBytes());
        assert Arrays.equals("value0".getBytes(), col.value());
    }

    @Test
    public void removeCf() throws IOException, ConfigurationException, ExecutionException, InterruptedException
    {
        // sanity
        final KSMetaData ks = DatabaseDescriptor.getTableDefinition("Keyspace1");
        assert ks != null;
        final CFMetaData cfm = ks.cfMetaData().get("Standard1");
        assert cfm != null;
        
        // write some data, force a flush, then verify that files exist on disk.
        RowMutation rm = new RowMutation(ks.name, "key0");
        for (int i = 0; i < 100; i++)
            rm.add(new QueryPath(cfm.cfName, null, ("col" + i).getBytes()), "anyvalue".getBytes(), 1L);
        rm.apply();
        ColumnFamilyStore store = Table.open(cfm.tableName).getColumnFamilyStore(cfm.cfName);
        assert store != null;
        store.forceBlockingFlush();
        store.getFlushPath();
        assert getFiles(cfm.tableName, cfm.cfName).size() > 0;
        
        DefsTable.drop(cfm, true);
        
        assert !DatabaseDescriptor.getTableDefinition(ks.name).cfMetaData().containsKey(cfm.cfName);
        
        // any write should fail.
        rm = new RowMutation(ks.name, "key0");
        try
        {
            rm.add(new QueryPath("Standard1", null, "col0".getBytes()), "value0".getBytes(), 1L);
            rm.apply();
            assert false : "This mutation should have failed since the CF no longer exists.";
        }
        catch (Throwable th)
        {
            assert th instanceof IllegalArgumentException;
        }
        
        // verify that the files are gone.
        assert getFiles(cfm.tableName, cfm.cfName).size() == 0;
    }
    
    private static Collection<File> getFiles(String table, final String cf)
    {
        List<File> found = new ArrayList<File>();
        for (String path : DatabaseDescriptor.getAllDataFileLocationsForTable(table))
        {
            File[] dbFiles = new File(path).listFiles(new FileFilter()
            {
                public boolean accept(File pathname)
                {
                    return pathname.getName().startsWith(cf + "-") && pathname.getName().endsWith(".db") && pathname.exists();
                            
                }
            });
            for (File f : dbFiles)
                found.add(f);
        }
        return found;
    }
}
