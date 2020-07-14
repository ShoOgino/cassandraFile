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

import org.apache.cassandra.OrderedJUnit4ClassRunner;

import static org.junit.Assert.assertEquals;

@RunWith(OrderedJUnit4ClassRunner.class)
public class SSTableExportTest extends OfflineToolUtils
{
    private ToolRunner.Runners runner = new ToolRunner.Runners();
    
    @Test
    public void testSSTableExport_NoArgs()
    {
        assertEquals(1, runner.invokeClassAsTool("org.apache.cassandra.tools.SSTableExport").getExitCode());
        assertNoUnexpectedThreadsStarted(null, OPTIONAL_THREADS_WITH_SCHEMA);
        assertSchemaNotLoaded();
        assertCLSMNotLoaded();
        assertSystemKSNotLoaded();
        assertKeyspaceNotLoaded();
        assertServerNotLoaded();
    }

    @Test
    public void testSSTableExport_WithArgs() throws Exception
    {
        runner.invokeClassAsTool("org.apache.cassandra.tools.SSTableExport",findOneSSTable("legacy_sstables", "legacy_ma_simple"))
              .waitAndAssertOnCleanExit();
        assertNoUnexpectedThreadsStarted(null, OPTIONAL_THREADS_WITH_SCHEMA);
        assertSchemaNotLoaded();
        assertCLSMNotLoaded();
        assertSystemKSNotLoaded();
        assertServerNotLoaded();
    }
}
