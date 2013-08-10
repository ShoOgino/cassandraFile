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
package org.apache.cassandra.metrics;

import java.util.concurrent.ThreadPoolExecutor;

import com.codahale.metrics.MetricRegistry;
import com.codahale.metrics.*;

/**
 * Metrics for {@link ThreadPoolExecutor}.
 */
public class ThreadPoolMetrics
{
    /** Number of active tasks. */
    public final Gauge<Integer> activeTasks;
    /** Number of tasks that had blocked before being accepted (or rejected). */
    public final Counter totalBlocked;
    /**
     * Number of tasks currently blocked, waiting to be accepted by
     * the executor (because all threads are busy and the backing queue is full).
     */
    public final Counter currentBlocked;
    /** Number of completed tasks. */
    public final Gauge<Long> completedTasks;
    /** Number of tasks waiting to be executed. */
    public final Gauge<Long> pendingTasks;

    private MetricNameFactory factory;

    /**
     * Create metrics for given ThreadPoolExecutor.
     *
     * @param executor Thread pool
     * @param path Type of thread pool
     * @param poolName Name of thread pool to identify metrics
     */
    public ThreadPoolMetrics(final ThreadPoolExecutor executor, String path, String poolName)
    {
        this.factory = new ThreadPoolMetricNameFactory(path, poolName);

        activeTasks = CassandraMetricRegistry.register(factory.createMetricName("ActiveTasks"), new Gauge<Integer>()
        {
            public Integer getValue()
            {
                return executor.getActiveCount();
            }
        });
        totalBlocked = CassandraMetricRegistry.get().counter(factory.createMetricName("TotalBlockedTasks"));
        currentBlocked = CassandraMetricRegistry.get().counter(factory.createMetricName("CurrentlyBlockedTasks"));
        completedTasks = CassandraMetricRegistry.register(factory.createMetricName("CompletedTasks"), new Gauge<Long>()
        {
            public Long getValue()
            {
                return executor.getCompletedTaskCount();
            }
        });
        pendingTasks = CassandraMetricRegistry.register(factory.createMetricName("PendingTasks"), new Gauge<Long>()
        {
            public Long getValue()
            {
                return executor.getTaskCount() - executor.getCompletedTaskCount();
            }
        });
    }

    public void release()
    {
        CassandraMetricRegistry.unregister(factory.createMetricName("ActiveTasks"));
        CassandraMetricRegistry.unregister(factory.createMetricName("PendingTasks"));
        CassandraMetricRegistry.unregister(factory.createMetricName("CompletedTasks"));
        CassandraMetricRegistry.unregister(factory.createMetricName("TotalBlockedTasks"));
        CassandraMetricRegistry.unregister(factory.createMetricName("CurrentlyBlockedTasks"));
    }

    class ThreadPoolMetricNameFactory implements MetricNameFactory
    {
        private final String path;
        private final String poolName;

        ThreadPoolMetricNameFactory(String path, String poolName)
        {
            this.path = path;
            this.poolName = poolName;
        }

        public String createMetricName(String metricName)
        {
            String groupName = ThreadPoolMetrics.class.getPackage().getName();
            String type = "ThreadPools";
            StringBuilder mbeanName = new StringBuilder();
            mbeanName.append(groupName).append(":");
            mbeanName.append("type=").append(type);
            mbeanName.append(",path=").append(path);
            mbeanName.append(",scope=").append(poolName);
            mbeanName.append(",name=").append(metricName);

            return MetricRegistry.name(groupName, type, metricName, path + "." + poolName, mbeanName.toString());
        }
    }
}
