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
package org.apache.cassandra.db.lifecycle;

import java.io.File;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import com.google.common.annotations.VisibleForTesting;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.apache.cassandra.io.FSError;
import org.apache.cassandra.io.util.FileUtils;
import org.apache.cassandra.utils.Throwables;

/**
 * A set of log replicas. This class mostly iterates over replicas when writing or reading,
 * ensuring consistency among them and hiding replication details from LogFile.
 *
 * @see LogReplica, LogFile
 */
public class LogReplicaSet implements AutoCloseable
{
    private static final Logger logger = LoggerFactory.getLogger(LogReplicaSet.class);

    private final Map<File, LogReplica> replicasByFile = new LinkedHashMap<>();

    private Collection<LogReplica> replicas()
    {
        return replicasByFile.values();
    }

    void addReplicas(List<File> replicas)
    {
        replicas.forEach(this::addReplica);
    }

    void addReplica(File file)
    {
        File folder = file.getParentFile();
        assert !replicasByFile.containsKey(folder);
        try
        {
            replicasByFile.put(folder, LogReplica.open(file));
        }
        catch(FSError e)
        {
            logger.error("Failed to open log replica {}", file, e);
            FileUtils.handleFSErrorAndPropagate(e);
        }

        if (logger.isTraceEnabled())
            logger.trace("Added log file replica {} ", file);
    }

    void maybeCreateReplica(File folder, String fileName, Set<LogRecord> records)
    {
        if (replicasByFile.containsKey(folder))
            return;

        try
        {
            @SuppressWarnings("resource")  // LogReplicas are closed in LogReplicaSet::close
            final LogReplica replica = LogReplica.create(folder, fileName);
            records.forEach(replica::append);
            replicasByFile.put(folder, replica);

            if (logger.isTraceEnabled())
                logger.trace("Created new file replica {}", replica);
        }
        catch(FSError e)
        {
            logger.error("Failed to create log replica {}/{}", folder,  fileName, e);
            FileUtils.handleFSErrorAndPropagate(e);
        }
    }

    Throwable syncFolder(Throwable accumulate)
    {
        return Throwables.perform(accumulate, replicas().stream().map(s -> s::syncFolder));
    }

    Throwable delete(Throwable accumulate)
    {
        return Throwables.perform(accumulate, replicas().stream().map(s -> s::delete));
    }

    private static boolean isPrefixMatch(String first, String second)
    {
        return first.length() >= second.length() ?
               first.startsWith(second) :
               second.startsWith(first);
    }

    boolean readRecords(Set<LogRecord> records)
    {
        Map<File, List<String>> linesByReplica = replicas().stream()
                                                           .map(LogReplica::file)
                                                           .collect(Collectors.toMap(Function.<File>identity(), FileUtils::readLines));
        int maxNumLines = linesByReplica.values().stream().map(List::size).reduce(0, Integer::max);
        for (int i = 0; i < maxNumLines; i++)
        {
            String firstLine = null;
            boolean partial = false;
            for (Map.Entry<File, List<String>> entry : linesByReplica.entrySet())
            {
                List<String> currentLines = entry.getValue();
                if (i >= currentLines.size())
                    continue;

                String currentLine = currentLines.get(i);
                if (firstLine == null)
                {
                    firstLine = currentLine;
                    continue;
                }

                if (!isPrefixMatch(firstLine, currentLine))
                { // not a prefix match
                    logger.error("Mismatched line in file {}: got '{}' expected '{}', giving up",
                                 entry.getKey().getName(),
                                 currentLine,
                                 firstLine);
                    return false;
                }

                if (!firstLine.equals(currentLine))
                {
                    if (i == currentLines.size() - 1)
                    { // last record, just set record as invalid and move on
                        logger.warn("Mismatched last line in file {}: '{}' not the same as '{}'",
                                    entry.getKey().getName(),
                                    currentLine,
                                    firstLine);

                        if (currentLine.length() > firstLine.length())
                            firstLine = currentLine;

                        partial = true;
                    }
                    else
                    {   // mismatched entry file has more lines, giving up
                        logger.error("Mismatched line in file {}: got '{}' expected '{}', giving up",
                                     entry.getKey().getName(),
                                     currentLine,
                                     firstLine);
                        return false;
                    }
                }
            }

            LogRecord record = LogRecord.make(firstLine);
            if (records.contains(record))
            { // duplicate records
                logger.error("Found duplicate record {} for {}, giving up", record, record.fileName());
                return false;
            }

            if (partial)
                record.setPartial();

            records.add(record);

            if (record.isFinal() && i != (maxNumLines - 1))
            { // too many final records
                logger.error("Found too many lines for {}, giving up", record.fileName());
                return false;
            }
        }

        return true;
    }

    /**
     *  Add the record to all the replicas: if it is a final record then we throw only if we fail to write it
     *  to all, otherwise we throw if we fail to write it to any file, see CASSANDRA-10421 for details
     */
    void append(LogRecord record)
    {
        Throwable err = Throwables.perform(null, replicas().stream().map(r -> () -> r.append(record)));
        if (err != null)
        {
            if (!record.isFinal() || err.getSuppressed().length == replicas().size() -1)
                Throwables.maybeFail(err);

            logger.error("Failed to add record '{}' to some replicas '{}'", record, this);
        }
    }

    boolean exists()
    {
        Optional<Boolean> ret = replicas().stream().map(LogReplica::exists).reduce(Boolean::logicalAnd);
        return ret.isPresent() ?
               ret.get()
               : false;
    }

    public void close()
    {
        Throwables.maybeFail(Throwables.perform(null, replicas().stream().map(r -> r::close)));
    }

    @Override
    public String toString()
    {
        Optional<String> ret = replicas().stream().map(LogReplica::toString).reduce(String::concat);
        return ret.isPresent() ?
               ret.get()
               : "[-]";
    }

    @VisibleForTesting
    List<File> getFiles()
    {
        return replicas().stream().map(LogReplica::file).collect(Collectors.toList());
    }

    @VisibleForTesting
    List<String> getFilePaths()
    {
        return replicas().stream().map(LogReplica::file).map(File::getPath).collect(Collectors.toList());
    }
}
