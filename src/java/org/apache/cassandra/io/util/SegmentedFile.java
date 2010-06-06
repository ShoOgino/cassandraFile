/**
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
 */

package org.apache.cassandra.io.util;

import java.io.*;
import java.util.*;
import java.nio.MappedByteBuffer;

import org.apache.cassandra.utils.Pair;
import org.apache.cassandra.config.DatabaseDescriptor;
import org.apache.cassandra.config.Config;

/**
 * Abstracts a read-only file that has been split into segments, each of which can be represented by an independent
 * FileDataInput. Allows for iteration over the FileDataInputs, or random access to the FileDataInput for a given
 * position.
 *
 * The JVM can only map up to 2GB at a time, so each segment is at most that size when using mmap i/o. If a segment
 * would need to be longer than 2GB, that segment will not be mmap'd, and a new RandomAccessFile will be created for
 * each access to that segment.
 */
public abstract class SegmentedFile
{
    public final String path;
    public final long length;

    /**
     * Use getBuilder to get a Builder to construct a SegmentedFile.
     */
    SegmentedFile(String path, long length)
    {
        this.path = path;
        this.length = length;
    }

    /**
     * @return A SegmentedFile.Builder.
     */
    public static Builder getBuilder()
    {
        if (DatabaseDescriptor.getDiskAccessMode() == Config.DiskAccessMode.mmap)
            return new MmappedSegmentedFile.Builder();
        assert DatabaseDescriptor.getDiskAccessMode() == Config.DiskAccessMode.standard;
        return new BufferedSegmentedFile.Builder();
    }

    public abstract FileDataInput getSegment(long position, int bufferSize);

    /**
     * @return An Iterator over segments, beginning with the segment containing the given position: each segment must be closed after use.
     */
    public Iterator<FileDataInput> iterator(long position, int bufferSize)
    {
        return new SegmentIterator(position, bufferSize);
    }

    /**
     * Collects potential segmentation points in an underlying file, and builds a SegmentedFile to represent it.
     */
    public static abstract class Builder
    {
        /**
         * Adds a position that would be a safe place for a segment boundary in the file. For a block/row based file
         * format, safe boundaries are block/row edges.
         * @param boundary The absolute position of the potential boundary in the file.
         */
        public abstract void addPotentialBoundary(long boundary);

        /**
         * Called after all potential boundaries have been added to apply this Builder to a concrete file on disk.
         * @param path The file on disk.
         */
        public abstract SegmentedFile complete(String path);
    }

    static final class Segment extends Pair<Long, MappedByteBuffer> implements Comparable<Segment>
    {
        public Segment(long offset, MappedByteBuffer segment)
        {
            super(offset, segment);
        }

        public final int compareTo(Segment that)
        {
            return (int)Math.signum(this.left - that.left);
        }
    }

    /**
     * A lazy Iterator over segments in forward order from the given position.
     */
    final class SegmentIterator implements Iterator<FileDataInput>
    {
        private long nextpos;
        private final int bufferSize;
        public SegmentIterator(long position, int bufferSize)
        {
            this.nextpos = position;
            this.bufferSize = bufferSize;
        }

        public boolean hasNext()
        {
            return nextpos < length;
        }

        public FileDataInput next()
        {
            long position = nextpos;
            if (position >= length)
                throw new NoSuchElementException();

            FileDataInput segment = getSegment(nextpos, bufferSize);
            try
            {
                nextpos = nextpos + segment.bytesRemaining();
            }
            catch (IOException e)
            {
                throw new IOError(e);
            }
            return segment;
        }

        public void remove() { throw new UnsupportedOperationException(); }
    }
}
