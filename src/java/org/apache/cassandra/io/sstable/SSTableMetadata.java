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
package org.apache.cassandra.io.sstable;

import java.io.*;
import java.util.*;

import org.apache.cassandra.utils.StreamingHistogram;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.apache.cassandra.db.commitlog.ReplayPosition;
import org.apache.cassandra.io.util.FileUtils;
import org.apache.cassandra.utils.EstimatedHistogram;

/**
 * Metadata for a SSTable.
 * Metadata includes:
 *  - estimated row size histogram
 *  - estimated column count histogram
 *  - replay position
 *  - max column timestamp
 *  - max local deletion time
 *  - bloom filter fp chance
 *  - compression ratio
 *  - partitioner
 *  - generations of sstables from which this sstable was compacted, if any
 *  - tombstone drop time histogram
 *
 * An SSTableMetadata should be instantiated via the Collector, openFromDescriptor()
 * or createDefaultInstance()
 */
public class SSTableMetadata
{
    public static final double NO_BLOOM_FLITER_FP_CHANCE = -1.0;
    public static final double NO_COMPRESSION_RATIO = -1.0;
    public static final SSTableMetadataSerializer serializer = new SSTableMetadataSerializer();

    public final EstimatedHistogram estimatedRowSize;
    public final EstimatedHistogram estimatedColumnCount;
    public final ReplayPosition replayPosition;
    public final long minTimestamp;
    public final long maxTimestamp;
    public final int maxLocalDeletionTime;
    public final double bloomFilterFPChance;
    public final double compressionRatio;
    public final String partitioner;
    public final Set<Integer> ancestors;
    public final StreamingHistogram estimatedTombstoneDropTime;
    public final int sstableLevel;

    private SSTableMetadata()
    {
        this(defaultRowSizeHistogram(),
             defaultColumnCountHistogram(),
             ReplayPosition.NONE,
             Long.MAX_VALUE,
             Long.MIN_VALUE,
             Integer.MAX_VALUE,
             NO_BLOOM_FLITER_FP_CHANCE,
             NO_COMPRESSION_RATIO,
             null,
             Collections.<Integer>emptySet(),
             defaultTombstoneDropTimeHistogram(),
             0);
    }

    private SSTableMetadata(EstimatedHistogram rowSizes,
                            EstimatedHistogram columnCounts,
                            ReplayPosition replayPosition,
                            long minTimestamp,
                            long maxTimestamp,
                            int maxLocalDeletionTime,
                            double bloomFilterFPChance,
                            double compressionRatio,
                            String partitioner,
                            Set<Integer> ancestors,
                            StreamingHistogram estimatedTombstoneDropTime,
                            int sstableLevel)
    {
        this.estimatedRowSize = rowSizes;
        this.estimatedColumnCount = columnCounts;
        this.replayPosition = replayPosition;
        this.minTimestamp = minTimestamp;
        this.maxTimestamp = maxTimestamp;
        this.maxLocalDeletionTime = maxLocalDeletionTime;
        this.bloomFilterFPChance = bloomFilterFPChance;
        this.compressionRatio = compressionRatio;
        this.partitioner = partitioner;
        this.ancestors = ancestors;
        this.estimatedTombstoneDropTime = estimatedTombstoneDropTime;
        this.sstableLevel = sstableLevel;
    }

    public static SSTableMetadata createDefaultInstance()
    {
        return new SSTableMetadata();
    }

    public static Collector createCollector()
    {
        return new Collector();
    }

    /**
     * Used when updating sstablemetadata files with an sstable level
     * @param metadata
     * @param sstableLevel
     * @return
     */
    @Deprecated
    public static SSTableMetadata copyWithNewSSTableLevel(SSTableMetadata metadata, int sstableLevel)
    {
        return new SSTableMetadata(metadata.estimatedRowSize,
                                   metadata.estimatedColumnCount,
                                   metadata.replayPosition,
                                   metadata.minTimestamp,
                                   metadata.maxTimestamp,
                                   metadata.maxLocalDeletionTime,
                                   metadata.bloomFilterFPChance,
                                   metadata.compressionRatio,
                                   metadata.partitioner,
                                   metadata.ancestors,
                                   metadata.estimatedTombstoneDropTime,
                                   sstableLevel);

    }

    static EstimatedHistogram defaultColumnCountHistogram()
    {
        // EH of 114 can track a max value of 2395318855, i.e., > 2B columns
        return new EstimatedHistogram(114);
    }

    static EstimatedHistogram defaultRowSizeHistogram()
    {
        // EH of 150 can track a max value of 1697806495183, i.e., > 1.5PB
        return new EstimatedHistogram(150);
    }

    static StreamingHistogram defaultTombstoneDropTimeHistogram()
    {
        return new StreamingHistogram(SSTable.TOMBSTONE_HISTOGRAM_BIN_SIZE);
    }

    /**
     * @param gcBefore
     * @return estimated droppable tombstone ratio at given gcBefore time.
     */
    public double getEstimatedDroppableTombstoneRatio(int gcBefore)
    {
        long estimatedColumnCount = this.estimatedColumnCount.mean() * this.estimatedColumnCount.count();
        if (estimatedColumnCount > 0)
        {
            double droppable = getDroppableTombstonesBefore(gcBefore);
            return droppable / estimatedColumnCount;
        }
        return 0.0f;
    }

    /**
     * Get the amount of droppable tombstones
     * @param gcBefore the gc time
     * @return amount of droppable tombstones
     */
    public double getDroppableTombstonesBefore(int gcBefore)
    {
        return estimatedTombstoneDropTime.sum(gcBefore);
    }

    public static class Collector
    {
        protected EstimatedHistogram estimatedRowSize = defaultRowSizeHistogram();
        protected EstimatedHistogram estimatedColumnCount = defaultColumnCountHistogram();
        protected ReplayPosition replayPosition = ReplayPosition.NONE;
        protected long minTimestamp = Long.MAX_VALUE;
        protected long maxTimestamp = Long.MIN_VALUE;
        protected int maxLocalDeletionTime = Integer.MIN_VALUE;
        protected double compressionRatio = NO_COMPRESSION_RATIO;
        protected Set<Integer> ancestors = new HashSet<Integer>();
        protected StreamingHistogram estimatedTombstoneDropTime = defaultTombstoneDropTimeHistogram();
        protected int sstableLevel;

        public void addRowSize(long rowSize)
        {
            estimatedRowSize.add(rowSize);
        }

        public void addColumnCount(long columnCount)
        {
            estimatedColumnCount.add(columnCount);
        }

        public void mergeTombstoneHistogram(StreamingHistogram histogram)
        {
            estimatedTombstoneDropTime.merge(histogram);
        }

        /**
         * Ratio is compressed/uncompressed and it is
         * if you have 1.x then compression isn't helping
         */
        public void addCompressionRatio(long compressed, long uncompressed)
        {
            compressionRatio = (double) compressed/uncompressed;
        }

        public void updateMinTimestamp(long potentialMin)
        {
            minTimestamp = Math.min(minTimestamp, potentialMin);
        }

        public void updateMaxTimestamp(long potentialMax)
        {
            maxTimestamp = Math.max(maxTimestamp, potentialMax);
        }

        public void updateMaxLocalDeletionTime(int maxLocalDeletionTime)
        {
            this.maxLocalDeletionTime = Math.max(this.maxLocalDeletionTime, maxLocalDeletionTime);
        }

        public SSTableMetadata finalizeMetadata(String partitioner, double bloomFilterFPChance)
        {
            return new SSTableMetadata(estimatedRowSize,
                                       estimatedColumnCount,
                                       replayPosition,
                                       minTimestamp,
                                       maxTimestamp,
                                       maxLocalDeletionTime,
                                       bloomFilterFPChance,
                                       compressionRatio,
                                       partitioner,
                                       ancestors,
                                       estimatedTombstoneDropTime,
                                       sstableLevel);
        }

        public Collector estimatedRowSize(EstimatedHistogram estimatedRowSize)
        {
            this.estimatedRowSize = estimatedRowSize;
            return this;
        }

        public Collector estimatedColumnCount(EstimatedHistogram estimatedColumnCount)
        {
            this.estimatedColumnCount = estimatedColumnCount;
            return this;
        }

        public Collector replayPosition(ReplayPosition replayPosition)
        {
            this.replayPosition = replayPosition;
            return this;
        }

        public Collector addAncestor(int generation)
        {
            this.ancestors.add(generation);
            return this;
        }

        void update(long size, ColumnStats stats)
        {
            updateMinTimestamp(stats.minTimestamp);
            /*
             * The max timestamp is not always collected here (more precisely, row.maxTimestamp() may return Long.MIN_VALUE),
             * to avoid deserializing an EchoedRow.
             * This is the reason why it is collected first when calling ColumnFamilyStore.createCompactionWriter
             * However, for old sstables without timestamp, we still want to update the timestamp (and we know
             * that in this case we will not use EchoedRow, since CompactionControler.needsDeserialize() will be true).
            */
            updateMaxTimestamp(stats.maxTimestamp);
            updateMaxLocalDeletionTime(stats.maxLocalDeletionTime);
            addRowSize(size);
            addColumnCount(stats.columnCount);
            mergeTombstoneHistogram(stats.tombstoneHistogram);
        }

        public Collector sstableLevel(int sstableLevel)
        {
            this.sstableLevel = sstableLevel;
            return this;
        }

    }

    public static class SSTableMetadataSerializer
    {
        private static final Logger logger = LoggerFactory.getLogger(SSTableMetadataSerializer.class);

        public void serialize(SSTableMetadata sstableStats, DataOutput dos) throws IOException
        {
            assert sstableStats.partitioner != null;

            EstimatedHistogram.serializer.serialize(sstableStats.estimatedRowSize, dos);
            EstimatedHistogram.serializer.serialize(sstableStats.estimatedColumnCount, dos);
            ReplayPosition.serializer.serialize(sstableStats.replayPosition, dos);
            dos.writeLong(sstableStats.minTimestamp);
            dos.writeLong(sstableStats.maxTimestamp);
            dos.writeInt(sstableStats.maxLocalDeletionTime);
            dos.writeDouble(sstableStats.bloomFilterFPChance);
            dos.writeDouble(sstableStats.compressionRatio);
            dos.writeUTF(sstableStats.partitioner);
            dos.writeInt(sstableStats.ancestors.size());
            for (Integer g : sstableStats.ancestors)
                dos.writeInt(g);
            StreamingHistogram.serializer.serialize(sstableStats.estimatedTombstoneDropTime, dos);
            dos.writeInt(sstableStats.sstableLevel);
        }

        /**
         * Used to serialize to an old version - needed to be able to update sstable level without a full compaction.
         *
         * @deprecated will be removed when it is assumed that the minimum upgrade-from-version is the version that this
         * patch made it into
         *
         * @param sstableStats
         * @param legacyDesc
         * @param dos
         * @throws IOException
         */
        @Deprecated
        public void legacySerialize(SSTableMetadata sstableStats, Descriptor legacyDesc, DataOutput dos) throws IOException
        {
            EstimatedHistogram.serializer.serialize(sstableStats.estimatedRowSize, dos);
            EstimatedHistogram.serializer.serialize(sstableStats.estimatedColumnCount, dos);
            if (legacyDesc.version.metadataIncludesReplayPosition)
                ReplayPosition.serializer.serialize(sstableStats.replayPosition, dos);
            if (legacyDesc.version.tracksMinTimestamp)
                dos.writeLong(sstableStats.minTimestamp);
            if (legacyDesc.version.tracksMaxTimestamp)
                dos.writeLong(sstableStats.maxTimestamp);
            if (legacyDesc.version.tracksMaxLocalDeletionTime)
                dos.writeInt(sstableStats.maxLocalDeletionTime);
            if (legacyDesc.version.hasBloomFilterFPChance)
                dos.writeDouble(sstableStats.bloomFilterFPChance);
            if (legacyDesc.version.hasCompressionRatio)
                dos.writeDouble(sstableStats.compressionRatio);
            if (legacyDesc.version.hasPartitioner)
                dos.writeUTF(sstableStats.partitioner);
            if (legacyDesc.version.hasAncestors)
            {
                dos.writeInt(sstableStats.ancestors.size());
                for (Integer g : sstableStats.ancestors)
                    dos.writeInt(g);
            }
            if (legacyDesc.version.tracksTombstones)
                StreamingHistogram.serializer.serialize(sstableStats.estimatedTombstoneDropTime, dos);

            dos.writeInt(sstableStats.sstableLevel);
        }

        public SSTableMetadata deserialize(Descriptor descriptor) throws IOException
        {
            return deserialize(descriptor, true);
        }

        public SSTableMetadata deserialize(Descriptor descriptor, boolean loadSSTableLevel) throws IOException
        {
            logger.debug("Load metadata for {}", descriptor);
            File statsFile = new File(descriptor.filenameFor(SSTable.COMPONENT_STATS));
            if (!statsFile.exists())
            {
                logger.debug("No sstable stats for {}", descriptor);
                return new SSTableMetadata();
            }

            DataInputStream dis = new DataInputStream(new BufferedInputStream(new FileInputStream(statsFile)));
            try
            {
                return deserialize(dis, descriptor, loadSSTableLevel);
            }
            finally
            {
                FileUtils.closeQuietly(dis);
            }
        }
        public SSTableMetadata deserialize(DataInputStream dis, Descriptor desc) throws IOException
        {
            return deserialize(dis, desc, true);
        }

        public SSTableMetadata deserialize(DataInputStream dis, Descriptor desc, boolean loadSSTableLevel) throws IOException
        {
            EstimatedHistogram rowSizes = EstimatedHistogram.serializer.deserialize(dis);
            EstimatedHistogram columnCounts = EstimatedHistogram.serializer.deserialize(dis);
            ReplayPosition replayPosition = desc.version.metadataIncludesReplayPosition
                                          ? ReplayPosition.serializer.deserialize(dis)
                                          : ReplayPosition.NONE;
            if (!desc.version.metadataIncludesModernReplayPosition)
            {
                // replay position may be "from the future" thanks to older versions generating them with nanotime.
                // make sure we don't omit replaying something that we should.  see CASSANDRA-4782
                replayPosition = ReplayPosition.NONE;
            }
            long minTimestamp = desc.version.tracksMinTimestamp ? dis.readLong() : Long.MIN_VALUE;
            if (!desc.version.tracksMinTimestamp)
                minTimestamp = Long.MAX_VALUE;
            long maxTimestamp = desc.version.containsTimestamp() ? dis.readLong() : Long.MIN_VALUE;
            if (!desc.version.tracksMaxTimestamp) // see javadoc to Descriptor.containsTimestamp
                maxTimestamp = Long.MAX_VALUE;
            int maxLocalDeletionTime = desc.version.tracksMaxLocalDeletionTime ? dis.readInt() : Integer.MAX_VALUE;
            double bloomFilterFPChance = desc.version.hasBloomFilterFPChance ? dis.readDouble() : NO_BLOOM_FLITER_FP_CHANCE;
            double compressionRatio = desc.version.hasCompressionRatio ? dis.readDouble() : NO_COMPRESSION_RATIO;
            String partitioner = desc.version.hasPartitioner ? dis.readUTF() : null;
            int nbAncestors = desc.version.hasAncestors ? dis.readInt() : 0;
            Set<Integer> ancestors = new HashSet<Integer>(nbAncestors);
            for (int i = 0; i < nbAncestors; i++)
                ancestors.add(dis.readInt());
            StreamingHistogram tombstoneHistogram = desc.version.tracksTombstones
                                                   ? StreamingHistogram.serializer.deserialize(dis)
                                                   : defaultTombstoneDropTimeHistogram();
            int sstableLevel = 0;

            if (loadSSTableLevel && dis.available() > 0)
                sstableLevel = dis.readInt();

            return new SSTableMetadata(rowSizes,
                                       columnCounts,
                                       replayPosition,
                                       minTimestamp,
                                       maxTimestamp,
                                       maxLocalDeletionTime,
                                       bloomFilterFPChance,
                                       compressionRatio,
                                       partitioner,
                                       ancestors,
                                       tombstoneHistogram,
                                       sstableLevel);
        }
    }
}
