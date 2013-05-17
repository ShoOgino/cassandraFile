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
package org.apache.cassandra.cql3;

import java.util.*;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.apache.cassandra.config.CFMetaData;
import org.apache.cassandra.db.compaction.AbstractCompactionStrategy;
import org.apache.cassandra.exceptions.ConfigurationException;
import org.apache.cassandra.exceptions.SyntaxException;
import org.apache.cassandra.io.compress.CompressionParameters;

public class CFPropDefs extends PropertyDefinitions
{
    private static final Logger logger = LoggerFactory.getLogger(CFPropDefs.class);

    public static final String KW_COMMENT = "comment";
    public static final String KW_READREPAIRCHANCE = "read_repair_chance";
    public static final String KW_DCLOCALREADREPAIRCHANCE = "dclocal_read_repair_chance";
    public static final String KW_GCGRACESECONDS = "gc_grace_seconds";
    public static final String KW_MINCOMPACTIONTHRESHOLD = "min_threshold";
    public static final String KW_MAXCOMPACTIONTHRESHOLD = "max_threshold";
    public static final String KW_REPLICATEONWRITE = "replicate_on_write";
    public static final String KW_CACHING = "caching";
    public static final String KW_DEFAULT_TIME_TO_LIVE = "default_time_to_live";
    public static final String KW_SPECULATIVE_RETRY = "speculative_retry";
    public static final String KW_POPULATE_IO_CACHE_ON_FLUSH = "populate_io_cache_on_flush";
    public static final String KW_BF_FP_CHANCE = "bloom_filter_fp_chance";
    public static final String KW_MEMTABLE_FLUSH_PERIOD = "memtable_flush_period_in_ms";
    public static final String KW_TRIGGER_CLASS = "trigger_class";

    public static final String KW_COMPACTION = "compaction";
    public static final String KW_COMPRESSION = "compression";

    public static final String COMPACTION_STRATEGY_CLASS_KEY = "class";

    public static final Set<String> keywords = new HashSet<String>();
    public static final Set<String> obsoleteKeywords = new HashSet<String>();

    static
    {
        keywords.add(KW_COMMENT);
        keywords.add(KW_READREPAIRCHANCE);
        keywords.add(KW_DCLOCALREADREPAIRCHANCE);
        keywords.add(KW_GCGRACESECONDS);
        keywords.add(KW_REPLICATEONWRITE);
        keywords.add(KW_CACHING);
        keywords.add(KW_DEFAULT_TIME_TO_LIVE);
        keywords.add(KW_SPECULATIVE_RETRY);
        keywords.add(KW_POPULATE_IO_CACHE_ON_FLUSH);
        keywords.add(KW_BF_FP_CHANCE);
        keywords.add(KW_COMPACTION);
        keywords.add(KW_COMPRESSION);
        keywords.add(KW_MEMTABLE_FLUSH_PERIOD);
        keywords.add(KW_TRIGGER_CLASS);

        obsoleteKeywords.add("compaction_strategy_class");
        obsoleteKeywords.add("compaction_strategy_options");
        obsoleteKeywords.add("min_compaction_threshold");
        obsoleteKeywords.add("max_compaction_threshold");
        obsoleteKeywords.add("compaction_parameters");
        obsoleteKeywords.add("compression_parameters");
    }

    private Class<? extends AbstractCompactionStrategy> compactionStrategyClass = null;

    public void validate() throws ConfigurationException, SyntaxException
    {
        validate(keywords, obsoleteKeywords);

        Map<String, String> compactionOptions = getCompactionOptions();
        if (!compactionOptions.isEmpty())
        {
            String strategy = compactionOptions.get(COMPACTION_STRATEGY_CLASS_KEY);
            if (strategy == null)
                throw new ConfigurationException("Missing sub-option '" + COMPACTION_STRATEGY_CLASS_KEY + "' for the '" + KW_COMPACTION + "' option.");

            compactionStrategyClass = CFMetaData.createCompactionStrategy(strategy);
            compactionOptions.remove(COMPACTION_STRATEGY_CLASS_KEY);

            CFMetaData.validateCompactionOptions(compactionStrategyClass, compactionOptions);
        }

        Integer defaultTimeToLive = getInt(KW_DEFAULT_TIME_TO_LIVE, null);

        if (defaultTimeToLive != null)
        {
            if (defaultTimeToLive < 0)
                throw new ConfigurationException(String.format("%s cannot be smaller than %s, (default %s)",
                        KW_DEFAULT_TIME_TO_LIVE,
                        0,
                        CFMetaData.DEFAULT_DEFAULT_TIME_TO_LIVE));
        }
    }

    public Class<? extends AbstractCompactionStrategy> getCompactionStrategy()
    {
        return compactionStrategyClass;
    }

    public Map<String, String> getCompactionOptions() throws SyntaxException
    {
        Map<String, String> compactionOptions = getMap(KW_COMPACTION);
        if (compactionOptions == null)
            return Collections.<String, String>emptyMap();
        return compactionOptions;
    }

    public Map<String, String> getCompressionOptions() throws SyntaxException
    {
        Map<String, String> compressionOptions = getMap(KW_COMPRESSION);
        if (compressionOptions == null)
            return new HashMap<String, String>();
        return compressionOptions;
    }

    public void applyToCFMetadata(CFMetaData cfm) throws ConfigurationException, SyntaxException
    {
        if (hasProperty(KW_COMMENT))
            cfm.comment(getString(KW_COMMENT, ""));

        cfm.readRepairChance(getDouble(KW_READREPAIRCHANCE, cfm.getReadRepairChance()));
        cfm.dcLocalReadRepairChance(getDouble(KW_DCLOCALREADREPAIRCHANCE, cfm.getDcLocalReadRepair()));
        cfm.gcGraceSeconds(getInt(KW_GCGRACESECONDS, cfm.getGcGraceSeconds()));
        cfm.replicateOnWrite(getBoolean(KW_REPLICATEONWRITE, cfm.getReplicateOnWrite()));
        int minCompactionThreshold = toInt(KW_MINCOMPACTIONTHRESHOLD, getCompactionOptions().get(KW_MINCOMPACTIONTHRESHOLD), cfm.getMinCompactionThreshold());
        int maxCompactionThreshold = toInt(KW_MAXCOMPACTIONTHRESHOLD, getCompactionOptions().get(KW_MAXCOMPACTIONTHRESHOLD), cfm.getMaxCompactionThreshold());
        if (minCompactionThreshold <= 0 || maxCompactionThreshold <= 0)
            throw new ConfigurationException("Disabling compaction by setting compaction thresholds to 0 has been deprecated, set the compaction option 'enabled' to false instead.");
        cfm.minCompactionThreshold(minCompactionThreshold);
        cfm.maxCompactionThreshold(maxCompactionThreshold);
        cfm.caching(CFMetaData.Caching.fromString(getString(KW_CACHING, cfm.getCaching().toString())));
        cfm.defaultTimeToLive(getInt(KW_DEFAULT_TIME_TO_LIVE, cfm.getDefaultTimeToLive()));
        cfm.speculativeRetry(CFMetaData.SpeculativeRetry.fromString(getString(KW_SPECULATIVE_RETRY, cfm.getSpeculativeRetry().toString())));
        cfm.memtableFlushPeriod(getInt(KW_MEMTABLE_FLUSH_PERIOD, cfm.getMemtableFlushPeriod()));
        cfm.populateIoCacheOnFlush(getBoolean(KW_POPULATE_IO_CACHE_ON_FLUSH, cfm.populateIoCacheOnFlush()));
        if (hasProperty(KW_TRIGGER_CLASS))
            cfm.triggerClass(getSet(KW_TRIGGER_CLASS, cfm.getTriggerClass()));

        if (compactionStrategyClass != null)
        {
            cfm.compactionStrategyClass(compactionStrategyClass);
            cfm.compactionStrategyOptions(new HashMap<String, String>(getCompactionOptions()));
        }

        cfm.bloomFilterFpChance(getDouble(KW_BF_FP_CHANCE, cfm.getBloomFilterFpChance()));

        if (!getCompressionOptions().isEmpty())
            cfm.compressionParameters(CompressionParameters.create(getCompressionOptions()));
    }

    @Override
    public String toString()
    {
        return String.format("CFPropDefs(%s)", properties.toString());
    }
}
