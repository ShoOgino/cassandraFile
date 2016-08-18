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

package org.apache.cassandra.tools;

import org.junit.Test;
import org.junit.runner.RunWith;

import com.datastax.driver.core.exceptions.NoHostAvailableException;
import org.apache.cassandra.OrderedJUnit4ClassRunner;

import static org.junit.Assert.fail;

@RunWith(OrderedJUnit4ClassRunner.class)
public class BulkLoaderTest extends ToolsTester
{
    @Test
    public void testBulkLoader_NoArgs()
    {
        runTool(1, "org.apache.cassandra.tools.BulkLoader");
        assertNoUnexpectedThreadsStarted(null, null);
        assertSchemaNotLoaded();
        assertCLSMNotLoaded();
        assertSystemKSNotLoaded();
        assertKeyspaceNotLoaded();
        assertServerNotLoaded();
    }

    @Test
    public void testBulkLoader_WithArgs() throws Exception
    {
        try
        {
            runTool(0, "org.apache.cassandra.tools.BulkLoader", "-d", "127.9.9.1", sstableDirName("legacy_sstables", "legacy_ma_simple"));
            fail();
        }
        catch (RuntimeException e)
        {
            if (!(e.getCause() instanceof BulkLoadException))
                throw e;
            if (!(e.getCause().getCause() instanceof NoHostAvailableException))
                throw e;
        }
        assertNoUnexpectedThreadsStarted(null, new String[]{"globalEventExecutor-1-1", "globalEventExecutor-1-2"});
        assertSchemaNotLoaded();
        assertCLSMNotLoaded();
        assertSystemKSNotLoaded();
        assertKeyspaceNotLoaded();
        assertServerNotLoaded();
    }
}
