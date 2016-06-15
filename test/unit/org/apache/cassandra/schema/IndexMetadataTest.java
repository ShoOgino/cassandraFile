/*
 *
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 *
 */
package org.apache.cassandra.schema;

import org.junit.Assert;
import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class IndexMetadataTest {
    
    @Test
    public void testIsNameValidPositive()
    {
        assertTrue(IndexMetadata.isNameValid("abcdefghijklmnopqrstuvwxyz"));
        assertTrue(IndexMetadata.isNameValid("ABCDEFGHIJKLMNOPQRSTUVWXYZ"));
        assertTrue(IndexMetadata.isNameValid("_01234567890"));
    }
    
    @Test
    public void testIsNameValidNegative()
    {
        assertFalse(IndexMetadata.isNameValid(null));
        assertFalse(IndexMetadata.isNameValid(""));
        assertFalse(IndexMetadata.isNameValid(" "));
        assertFalse(IndexMetadata.isNameValid("@"));
        assertFalse(IndexMetadata.isNameValid("!"));
    }
    
    @Test
    public void testGetDefaultIndexName()
    {
        Assert.assertEquals("aB4__idx", IndexMetadata.getDefaultIndexName("a B-4@!_+", null));
        Assert.assertEquals("34_Ddd_F6_idx", IndexMetadata.getDefaultIndexName("34_()Ddd", "#F%6*"));
        
    }
}
