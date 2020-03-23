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

package org.apache.cassandra.db.streaming;

import java.io.File;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;

import org.apache.cassandra.config.DatabaseDescriptor;
import org.apache.cassandra.dht.Range;
import org.apache.cassandra.dht.Token;
import org.apache.cassandra.io.sstable.Component;
import org.apache.cassandra.io.sstable.format.SSTableReader;
import org.apache.cassandra.io.util.DataOutputStreamPlus;
import org.apache.cassandra.net.AsyncStreamingOutputPlus;
import org.apache.cassandra.schema.TableId;
import org.apache.cassandra.streaming.OutgoingStream;
import org.apache.cassandra.streaming.StreamOperation;
import org.apache.cassandra.streaming.StreamSession;
import org.apache.cassandra.utils.concurrent.Ref;

/**
 * used to transfer the part(or whole) of a SSTable data file
 */
public class CassandraOutgoingFile implements OutgoingStream
{
    public static final List<Component> STREAM_COMPONENTS = ImmutableList.of(Component.DATA, Component.PRIMARY_INDEX, Component.STATS,
                                                                             Component.COMPRESSION_INFO, Component.FILTER, Component.SUMMARY,
                                                                             Component.DIGEST, Component.CRC);

    private final Ref<SSTableReader> ref;
    private final long estimatedKeys;
    private final List<SSTableReader.PartitionPositionBounds> sections;
    private final String filename;
    private final CassandraStreamHeader header;
    private final boolean keepSSTableLevel;
    private final ComponentManifest manifest;

    private final boolean shouldStreamEntireSStable;

    public CassandraOutgoingFile(StreamOperation operation, Ref<SSTableReader> ref,
                                 List<SSTableReader.PartitionPositionBounds> sections, List<Range<Token>> normalizedRanges,
                                 long estimatedKeys)
    {
        Preconditions.checkNotNull(ref.get());
        Range.assertNormalized(normalizedRanges);
        this.ref = ref;
        this.estimatedKeys = estimatedKeys;
        this.sections = sections;
        this.filename = ref.get().getFilename();
        this.manifest = getComponentManifest(ref.get());
        this.shouldStreamEntireSStable = shouldStreamEntireSSTable();

        SSTableReader sstable = ref.get();
        keepSSTableLevel = operation == StreamOperation.BOOTSTRAP || operation == StreamOperation.REBUILD;
        this.header =
            CassandraStreamHeader.builder()
                                 .withSSTableFormat(sstable.descriptor.formatType)
                                 .withSSTableVersion(sstable.descriptor.version)
                                 .withSSTableLevel(keepSSTableLevel ? sstable.getSSTableLevel() : 0)
                                 .withEstimatedKeys(estimatedKeys)
                                 .withSections(sections)
                                 .withCompressionMetadata(sstable.compression ? sstable.getCompressionMetadata() : null)
                                 .withSerializationHeader(sstable.header.toComponent())
                                 .isEntireSSTable(shouldStreamEntireSStable)
                                 .withComponentManifest(manifest)
                                 .withFirstKey(sstable.first)
                                 .withTableId(sstable.metadata().id)
                                 .build();
    }

    @VisibleForTesting
    public static ComponentManifest getComponentManifest(SSTableReader sstable)
    {
        LinkedHashMap<Component, Long> components = new LinkedHashMap<>(STREAM_COMPONENTS.size());
        for (Component component : STREAM_COMPONENTS)
        {
            File file = new File(sstable.descriptor.filenameFor(component));
            if (file.exists())
                components.put(component, file.length());
        }

        return new ComponentManifest(components);
    }

    public static CassandraOutgoingFile fromStream(OutgoingStream stream)
    {
        Preconditions.checkArgument(stream instanceof CassandraOutgoingFile);
        return (CassandraOutgoingFile) stream;
    }

    @VisibleForTesting
    public Ref<SSTableReader> getRef()
    {
        return ref;
    }

    @Override
    public String getName()
    {
        return filename;
    }

    @Override
    public long getSize()
    {
        return header.size();
    }

    @Override
    public TableId getTableId()
    {
        return ref.get().metadata().id;
    }

    @Override
    public long getRepairedAt()
    {
        return ref.get().getRepairedAt();
    }

    @Override
    public UUID getPendingRepair()
    {
        return ref.get().getPendingRepair();
    }

    @Override
    public void write(StreamSession session, DataOutputStreamPlus out, int version) throws IOException
    {
        SSTableReader sstable = ref.get();
        CassandraStreamHeader.serializer.serialize(header, out, version);
        out.flush();

        if (shouldStreamEntireSStable && out instanceof AsyncStreamingOutputPlus)
        {
            CassandraEntireSSTableStreamWriter writer = new CassandraEntireSSTableStreamWriter(sstable, session, manifest);
            writer.write((AsyncStreamingOutputPlus) out);
        }
        else
        {
            CassandraStreamWriter writer = (header.compressionInfo == null) ?
                     new CassandraStreamWriter(sstable, header.sections, session) :
                     new CassandraCompressedStreamWriter(sstable, header.sections,
                                                         header.compressionInfo, session);
            writer.write(out);
        }
    }

    @VisibleForTesting
    public boolean shouldStreamEntireSSTable()
    {
        // don't stream if full sstable transfers are disabled or legacy counter shards are present
        if (!DatabaseDescriptor.streamEntireSSTables() || ref.get().getSSTableMetadata().hasLegacyCounterShards)
            return false;

        return contained(sections, ref.get());
    }

    @VisibleForTesting
    public boolean contained(List<SSTableReader.PartitionPositionBounds> sections, SSTableReader sstable)
    {
        if (sections == null || sections.isEmpty())
            return false;

        // if transfer sections contain entire sstable
        long transferLength = sections.stream().mapToLong(p -> p.upperPosition - p.lowerPosition).sum();
        return transferLength == sstable.uncompressedLength();
    }

    @Override
    public void finish()
    {
        ref.release();
    }

    public boolean equals(Object o)
    {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        CassandraOutgoingFile that = (CassandraOutgoingFile) o;
        return estimatedKeys == that.estimatedKeys &&
               Objects.equals(ref, that.ref) &&
               Objects.equals(sections, that.sections);
    }

    public int hashCode()
    {
        return Objects.hash(ref, estimatedKeys, sections);
    }

    @Override
    public String toString()
    {
        return "CassandraOutgoingFile{" + filename + '}';
    }
}
