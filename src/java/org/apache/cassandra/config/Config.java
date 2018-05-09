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
package org.apache.cassandra.config;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.TimeUnit;

import com.google.common.base.Joiner;
import com.google.common.collect.Sets;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.apache.cassandra.audit.AuditLogOptions;
import org.apache.cassandra.db.ConsistencyLevel;

/**
 * A class that contains configuration properties for the cassandra node it runs within.
 *
 * Properties declared as volatile can be mutated via JMX.
 */
public class Config
{
    private static final Logger logger = LoggerFactory.getLogger(Config.class);

    /*
     * Prefix for Java properties for internal Cassandra configuration options
     */
    public static final String PROPERTY_PREFIX = "cassandra.";

    public String cluster_name = "Test Cluster";
    public String authenticator;
    public String authorizer;
    public String role_manager;
    public String network_authorizer;
    public volatile int permissions_validity_in_ms = 2000;
    public volatile int permissions_cache_max_entries = 1000;
    public volatile int permissions_update_interval_in_ms = -1;
    public volatile int roles_validity_in_ms = 2000;
    public volatile int roles_cache_max_entries = 1000;
    public volatile int roles_update_interval_in_ms = -1;
    public volatile int credentials_validity_in_ms = 2000;
    public volatile int credentials_cache_max_entries = 1000;
    public volatile int credentials_update_interval_in_ms = -1;

    /* Hashing strategy Random or OPHF */
    public String partitioner;

    public boolean auto_bootstrap = true;
    public volatile boolean hinted_handoff_enabled = true;
    public Set<String> hinted_handoff_disabled_datacenters = Sets.newConcurrentHashSet();
    public volatile int max_hint_window_in_ms = 3 * 3600 * 1000; // three hours
    public String hints_directory;

    public ParameterizedClass seed_provider;
    public DiskAccessMode disk_access_mode = DiskAccessMode.auto;

    public DiskFailurePolicy disk_failure_policy = DiskFailurePolicy.ignore;
    public CommitFailurePolicy commit_failure_policy = CommitFailurePolicy.stop;

    /* initial token in the ring */
    public String initial_token;
    public int num_tokens = 1;
    /** Triggers automatic allocation of tokens if set, using the replication strategy of the referenced keyspace */
    public String allocate_tokens_for_keyspace = null;

    public volatile long request_timeout_in_ms = 10000L;

    public volatile long read_request_timeout_in_ms = 5000L;

    public volatile long range_request_timeout_in_ms = 10000L;

    public volatile long write_request_timeout_in_ms = 2000L;

    public volatile long counter_write_request_timeout_in_ms = 5000L;

    public volatile long cas_contention_timeout_in_ms = 1000L;

    public volatile long truncate_request_timeout_in_ms = 60000L;

    public Integer streaming_connections_per_host = 1;
    public Integer streaming_keep_alive_period_in_secs = 300; //5 minutes

    public boolean cross_node_timeout = false;

    public volatile long slow_query_log_timeout_in_ms = 500L;

    public volatile double phi_convict_threshold = 8.0;

    public int concurrent_reads = 32;
    public int concurrent_writes = 32;
    public int concurrent_counter_writes = 32;
    public int concurrent_materialized_view_writes = 32;

    @Deprecated
    public Integer concurrent_replicates = null;

    public int memtable_flush_writers = 0;
    public Integer memtable_heap_space_in_mb;
    public Integer memtable_offheap_space_in_mb;
    public Float memtable_cleanup_threshold = null;

    public int storage_port = 7000;
    public int ssl_storage_port = 7001;
    public String listen_address;
    public String listen_interface;
    public boolean listen_interface_prefer_ipv6 = false;
    public String broadcast_address;
    public boolean listen_on_broadcast_address = false;
    public String internode_authenticator;

    /*
     * RPC address and interface refer to the address/interface used for the native protocol used to communicate with
     * clients. It's still called RPC in some places even though Thrift RPC is gone. If you see references to native
     * address or native port it's derived from the RPC address configuration.
     *
     * native_transport_port is the port that is paired with RPC address to bind on.
     */
    public String rpc_address;
    public String rpc_interface;
    public boolean rpc_interface_prefer_ipv6 = false;
    public String broadcast_rpc_address;
    public boolean rpc_keepalive = true;
    public int internode_send_buff_size_in_bytes = 0;
    public int internode_recv_buff_size_in_bytes = 0;

    public boolean start_native_transport = true;
    public int native_transport_port = 9042;
    public Integer native_transport_port_ssl = null;
    public int native_transport_max_threads = 128;
    public int native_transport_max_frame_size_in_mb = 256;
    public volatile long native_transport_max_concurrent_connections = -1L;
    public volatile long native_transport_max_concurrent_connections_per_ip = -1L;
    public boolean native_transport_flush_in_batches_legacy = false;
    public volatile boolean native_transport_allow_older_protocols = true;
    public int native_transport_frame_block_size_in_kb = 32;

    /**
     * Max size of values in SSTables, in MegaBytes.
     * Default is the same as the native protocol frame limit: 256Mb.
     * See AbstractType for how it is used.
     */
    public int max_value_size_in_mb = 256;

    public boolean snapshot_before_compaction = false;
    public boolean auto_snapshot = true;

    /* if the size of columns or super-columns are more than this, indexing will kick in */
    public int column_index_size_in_kb = 64;
    public int column_index_cache_size_in_kb = 2;
    public volatile int batch_size_warn_threshold_in_kb = 5;
    public volatile int batch_size_fail_threshold_in_kb = 50;
    public Integer unlogged_batch_across_partitions_warn_threshold = 10;
    public volatile Integer concurrent_compactors;
    public volatile int compaction_throughput_mb_per_sec = 16;
    public volatile int compaction_large_partition_warning_threshold_mb = 100;
    public int min_free_space_per_drive_in_mb = 50;

    public volatile int concurrent_validations = Integer.MAX_VALUE;
    public volatile int concurrent_materialized_view_builders = 1;

    /**
     * @deprecated retry support removed on CASSANDRA-10992
     */
    @Deprecated
    public int max_streaming_retries = 3;

    public volatile int stream_throughput_outbound_megabits_per_sec = 200;
    public volatile int inter_dc_stream_throughput_outbound_megabits_per_sec = 200;

    public String[] data_file_directories = new String[0];

    public String saved_caches_directory;

    // Commit Log
    public String commitlog_directory;
    public Integer commitlog_total_space_in_mb;
    public CommitLogSync commitlog_sync;

    /**
     * @deprecated since 4.0 This value was near useless, and we're not using it anymore
     */
    public double commitlog_sync_batch_window_in_ms = Double.NaN;
    public double commitlog_sync_group_window_in_ms = Double.NaN;
    public int commitlog_sync_period_in_ms;
    public int commitlog_segment_size_in_mb = 32;
    public ParameterizedClass commitlog_compression;
    public int commitlog_max_compression_buffers_in_pool = 3;
    public Integer periodic_commitlog_sync_lag_block_in_ms;
    public TransparentDataEncryptionOptions transparent_data_encryption_options = new TransparentDataEncryptionOptions();

    public Integer max_mutation_size_in_kb;

    // Change-data-capture logs
    public boolean cdc_enabled = false;
    public String cdc_raw_directory;
    public int cdc_total_space_in_mb = 0;
    public int cdc_free_space_check_interval_ms = 250;

    @Deprecated
    public int commitlog_periodic_queue_size = -1;

    public String endpoint_snitch;
    public boolean dynamic_snitch = true;
    public int dynamic_snitch_update_interval_in_ms = 100;
    public int dynamic_snitch_reset_interval_in_ms = 600000;
    public double dynamic_snitch_badness_threshold = 0.1;

    public EncryptionOptions.ServerEncryptionOptions server_encryption_options = new EncryptionOptions.ServerEncryptionOptions();
    public EncryptionOptions client_encryption_options = new EncryptionOptions();

    public InternodeCompression internode_compression = InternodeCompression.none;

    public int hinted_handoff_throttle_in_kb = 1024;
    public int batchlog_replay_throttle_in_kb = 1024;
    public int max_hints_delivery_threads = 2;
    public int hints_flush_period_in_ms = 10000;
    public int max_hints_file_size_in_mb = 128;
    public ParameterizedClass hints_compression;
    public int sstable_preemptive_open_interval_in_mb = 50;

    public volatile boolean incremental_backups = false;
    public boolean trickle_fsync = false;
    public int trickle_fsync_interval_in_kb = 10240;

    public Long key_cache_size_in_mb = null;
    public volatile int key_cache_save_period = 14400;
    public volatile int key_cache_keys_to_save = Integer.MAX_VALUE;

    public String row_cache_class_name = "org.apache.cassandra.cache.OHCProvider";
    public long row_cache_size_in_mb = 0;
    public volatile int row_cache_save_period = 0;
    public volatile int row_cache_keys_to_save = Integer.MAX_VALUE;

    public Long counter_cache_size_in_mb = null;
    public volatile int counter_cache_save_period = 7200;
    public volatile int counter_cache_keys_to_save = Integer.MAX_VALUE;

    private static boolean isClientMode = false;

    public Integer file_cache_size_in_mb;

    /**
     * Because of the current {@link org.apache.cassandra.utils.memory.BufferPool} slab sizes of 64 kb, we
     * store in the file cache buffers that divide 64 kb, so we need to round the buffer sizes to powers of two.
     * This boolean controls weather they are rounded up or down. Set it to true to round up to the
     * next power of two, set it to false to round down to the previous power of two. Note that buffer sizes are
     * already rounded to 4 kb and capped between 4 kb minimum and 64 kb maximum by the {@link DiskOptimizationStrategy}.
     * By default, this boolean is set to round down when {@link #disk_optimization_strategy} is {@code ssd},
     * and to round up when it is {@code spinning}.
     */
    public Boolean file_cache_round_up;

    public boolean buffer_pool_use_heap_if_exhausted = true;

    public DiskOptimizationStrategy disk_optimization_strategy = DiskOptimizationStrategy.ssd;

    public double disk_optimization_estimate_percentile = 0.95;

    public double disk_optimization_page_cross_chance = 0.1;

    public boolean inter_dc_tcp_nodelay = true;

    public MemtableAllocationType memtable_allocation_type = MemtableAllocationType.heap_buffers;

    public volatile int tombstone_warn_threshold = 1000;
    public volatile int tombstone_failure_threshold = 100000;

    public volatile Long index_summary_capacity_in_mb;
    public volatile int index_summary_resize_interval_in_minutes = 60;

    public int gc_log_threshold_in_ms = 200;
    public int gc_warn_threshold_in_ms = 1000;

    // TTL for different types of trace events.
    public int tracetype_query_ttl = (int) TimeUnit.DAYS.toSeconds(1);
    public int tracetype_repair_ttl = (int) TimeUnit.DAYS.toSeconds(7);

    /**
     * Maintain statistics on whether writes achieve the ideal consistency level
     * before expiring and becoming hints
     */
    public volatile ConsistencyLevel ideal_consistency_level = null;

    /*
     * Strategy to use for coalescing messages in {@link OutboundMessagingPool}.
     * Can be fixed, movingaverage, timehorizon, disabled. Setting is case and leading/trailing
     * whitespace insensitive. You can also specify a subclass of
     * {@link org.apache.cassandra.utils.CoalescingStrategies.CoalescingStrategy} by name.
     */
    public String otc_coalescing_strategy = "DISABLED";

    /*
     * How many microseconds to wait for coalescing. For fixed strategy this is the amount of time after the first
     * message is received before it will be sent with any accompanying messages. For moving average this is the
     * maximum amount of time that will be waited as well as the interval at which messages must arrive on average
     * for coalescing to be enabled.
     */
    public static final int otc_coalescing_window_us_default = 200;
    public int otc_coalescing_window_us = otc_coalescing_window_us_default;
    public int otc_coalescing_enough_coalesced_messages = 8;

    /**
     * Backlog expiration interval in milliseconds for the OutboundTcpConnection.
     */
    public static final int otc_backlog_expiration_interval_ms_default = 200;
    public volatile int otc_backlog_expiration_interval_ms = otc_backlog_expiration_interval_ms_default;

    public int windows_timer_interval = 0;

    /**
     * Size of the CQL prepared statements cache in MB.
     * Defaults to 1/256th of the heap size or 10MB, whichever is greater.
     */
    public Long prepared_statements_cache_size_mb = null;

    public boolean enable_user_defined_functions = false;
    public boolean enable_scripted_user_defined_functions = false;

    public boolean enable_materialized_views = true;

    public boolean enable_transient_replication = false;

    /**
     * Optionally disable asynchronous UDF execution.
     * Disabling asynchronous UDF execution also implicitly disables the security-manager!
     * By default, async UDF execution is enabled to be able to detect UDFs that run too long / forever and be
     * able to fail fast - i.e. stop the Cassandra daemon, which is currently the only appropriate approach to
     * "tell" a user that there's something really wrong with the UDF.
     * When you disable async UDF execution, users MUST pay attention to read-timeouts since these may indicate
     * UDFs that run too long or forever - and this can destabilize the cluster.
     */
    public boolean enable_user_defined_functions_threads = true;
    /**
     * Time in milliseconds after a warning will be emitted to the log and to the client that a UDF runs too long.
     * (Only valid, if enable_user_defined_functions_threads==true)
     */
    public long user_defined_function_warn_timeout = 500;
    /**
     * Time in milliseconds after a fatal UDF run-time situation is detected and action according to
     * user_function_timeout_policy will take place.
     * (Only valid, if enable_user_defined_functions_threads==true)
     */
    public long user_defined_function_fail_timeout = 1500;
    /**
     * Defines what to do when a UDF ran longer than user_defined_function_fail_timeout.
     * Possible options are:
     * - 'die' - i.e. it is able to emit a warning to the client before the Cassandra Daemon will shut down.
     * - 'die_immediate' - shut down C* daemon immediately (effectively prevent the chance that the client will receive a warning).
     * - 'ignore' - just log - the most dangerous option.
     * (Only valid, if enable_user_defined_functions_threads==true)
     */
    public UserFunctionTimeoutPolicy user_function_timeout_policy = UserFunctionTimeoutPolicy.die;

    public volatile boolean back_pressure_enabled = false;
    public volatile ParameterizedClass back_pressure_strategy;

    public RepairCommandPoolFullStrategy repair_command_pool_full_strategy = RepairCommandPoolFullStrategy.queue;
    public int repair_command_pool_size = concurrent_validations;

    public String full_query_log_dir = null;

    // parameters to adjust how much to delay startup until a certain amount of the cluster is connect to and marked alive
    public int block_for_peers_percentage = 70;
    public int block_for_peers_timeout_in_secs = 10;
    public volatile boolean automatic_sstable_upgrade = false;
    public volatile int max_concurrent_automatic_sstable_upgrades = 1;
    public boolean stream_entire_sstables = true;

    public volatile AuditLogOptions audit_logging_options = new AuditLogOptions();

    public CorruptedTombstoneStrategy corrupted_tombstone_strategy = CorruptedTombstoneStrategy.disabled;

    public volatile boolean diagnostic_events_enabled = false;

    /**
     * flags for enabling tracking repaired state of data during reads
     * separate flags for range & single partition reads as single partition reads are only tracked
     * when CL > 1 and a digest mismatch occurs. Currently, range queries don't use digests so if
     * enabled for range reads, all such reads will include repaired data tracking. As this adds
     * some overhead, operators may wish to disable it whilst still enabling it for partition reads
     */
    public volatile boolean repaired_data_tracking_for_range_reads_enabled = false;
    public volatile boolean repaired_data_tracking_for_partition_reads_enabled = false;
    /* If true, unconfirmed mismatches (those which cannot be considered conclusive proof of out of
     * sync repaired data due to the presence of pending repair sessions, or unrepaired partition
     * deletes) will increment a metric, distinct from confirmed mismatches. If false, unconfirmed
     * mismatches are simply ignored by the coordinator.
     * This is purely to allow operators to avoid potential signal:noise issues as these types of
     * mismatches are considerably less actionable than their confirmed counterparts. Setting this
     * to true only disables the incrementing of the counters when an unconfirmed mismatch is found
     * and has no other effect on the collection or processing of the repaired data.
     */
    public volatile boolean report_unconfirmed_repaired_data_mismatches = false;

    /**
     * @deprecated migrate to {@link DatabaseDescriptor#isClientInitialized()}
     */
    @Deprecated
    public static boolean isClientMode()
    {
        return isClientMode;
    }

    /**
     * Client mode means that the process is a pure client, that uses C* code base but does
     * not read or write local C* database files.
     *
     * @deprecated migrate to {@link DatabaseDescriptor#clientInitialization(boolean)}
     */
    @Deprecated
    public static void setClientMode(boolean clientMode)
    {
        isClientMode = clientMode;
    }

    public enum CommitLogSync
    {
        periodic,
        batch,
        group
    }
    public enum InternodeCompression
    {
        all, none, dc
    }

    public enum DiskAccessMode
    {
        auto,
        mmap,
        mmap_index_only,
        standard,
    }

    public enum MemtableAllocationType
    {
        unslabbed_heap_buffers,
        heap_buffers,
        offheap_buffers,
        offheap_objects
    }

    public enum DiskFailurePolicy
    {
        best_effort,
        stop,
        ignore,
        stop_paranoid,
        die
    }

    public enum CommitFailurePolicy
    {
        stop,
        stop_commit,
        ignore,
        die,
    }

    public enum UserFunctionTimeoutPolicy
    {
        ignore,
        die,
        die_immediate
    }

    public enum DiskOptimizationStrategy
    {
        ssd,
        spinning
    }

    public enum RepairCommandPoolFullStrategy
    {
        queue,
        reject
    }

    public enum CorruptedTombstoneStrategy
    {
        disabled,
        warn,
        exception
    }

    private static final List<String> SENSITIVE_KEYS = new ArrayList<String>() {{
        add("client_encryption_options");
        add("server_encryption_options");
    }};

    public static void log(Config config)
    {
        Map<String, String> configMap = new TreeMap<>();
        for (Field field : Config.class.getFields())
        {
            // ignore the constants
            if (Modifier.isFinal(field.getModifiers()))
                continue;

            String name = field.getName();
            if (SENSITIVE_KEYS.contains(name))
            {
                configMap.put(name, "<REDACTED>");
                continue;
            }

            String value;
            try
            {
                // Field.get() can throw NPE if the value of the field is null
                value = field.get(config).toString();
            }
            catch (NullPointerException | IllegalAccessException npe)
            {
                value = "null";
            }
            configMap.put(name, value);
        }

        logger.info("Node configuration:[{}]", Joiner.on("; ").join(configMap.entrySet()));
    }
}
