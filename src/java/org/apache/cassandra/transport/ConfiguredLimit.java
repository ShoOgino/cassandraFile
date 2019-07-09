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

package org.apache.cassandra.transport;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.apache.cassandra.config.DatabaseDescriptor;
import org.apache.cassandra.db.SystemKeyspace;
import org.apache.cassandra.utils.CassandraVersion;

public abstract class ConfiguredLimit implements ProtocolVersionLimit
{
    private static final Logger logger = LoggerFactory.getLogger(ConfiguredLimit.class);
    static final String DISABLE_MAX_PROTOCOL_AUTO_OVERRIDE = "cassandra.disable_max_protocol_auto_override";
    static final CassandraVersion MIN_VERSION_FOR_V4 = new CassandraVersion("3.0.0");

    public abstract int getMaxVersion();
    public abstract void updateMaxSupportedVersion();

    public static ConfiguredLimit newLimit()
    {
        if (Boolean.getBoolean(DISABLE_MAX_PROTOCOL_AUTO_OVERRIDE))
            return new StaticLimit(Server.CURRENT_VERSION);

        int fromConfig = DatabaseDescriptor.getNativeProtocolMaxVersionOverride();
        return fromConfig != Integer.MIN_VALUE
               ? new StaticLimit(fromConfig)
               : new DynamicLimit(Server.CURRENT_VERSION);
    }

    private static class StaticLimit extends ConfiguredLimit
    {
        private final int maxVersion;
        private StaticLimit(int maxVersion)
        {
            if (maxVersion < Server.MIN_SUPPORTED_VERSION || maxVersion > Server.CURRENT_VERSION)
                throw new IllegalArgumentException(String.format("Invalid max protocol version supplied (%s); " +
                                                                 "Values between %s and %s are supported",
                                                                 maxVersion,
                                                                 Server.MIN_SUPPORTED_VERSION,
                                                                 Server.CURRENT_VERSION));
            this.maxVersion = maxVersion;
            logger.info("Native transport max negotiable version statically limited to {}", maxVersion);
        }

        public int getMaxVersion()
        {
            return maxVersion;
        }

        public void updateMaxSupportedVersion()
        {
            // statically configured, so this is a no-op
        }
    }

    private static class DynamicLimit extends ConfiguredLimit
    {
        private volatile int maxVersion;
        private DynamicLimit(int initialLimit)
        {
            maxVersion = initialLimit;
            maybeUpdateVersion(true);
        }

        public int getMaxVersion()
        {
            return maxVersion;
        }

        public void updateMaxSupportedVersion()
        {
            maybeUpdateVersion(false);
        }

        private void maybeUpdateVersion(boolean allowLowering)
        {
            boolean enforceV3Cap = SystemKeyspace.loadPeerVersions()
                                                 .values()
                                                 .stream()
                                                 .anyMatch(v -> v.compareTo(MIN_VERSION_FOR_V4) < 0);

            if (!enforceV3Cap)
            {
                maxVersion = Server.CURRENT_VERSION;
                return;
            }

            if (maxVersion > Server.VERSION_3 && !allowLowering)
            {
                logger.info("Detected peers which do not fully support protocol V4, but V4 was previously negotiable. " +
                            "Not enforcing cap as this can cause issues for older client versions. After the next " +
                            "restart the server will apply the cap");
                return;
            }
            logger.info("Detected peers which do not fully support protocol V4. Capping max negotiable version to V3");
            maxVersion = Server.VERSION_3;
        }
    }
}
