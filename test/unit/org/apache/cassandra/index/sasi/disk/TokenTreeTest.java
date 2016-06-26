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
package org.apache.cassandra.index.sasi.disk;

import java.io.File;
import java.io.IOException;
import java.util.*;

import com.google.common.collect.Iterators;
import com.google.common.collect.PeekingIterator;

import com.carrotsearch.hppc.cursors.LongObjectCursor;
import org.apache.cassandra.config.DatabaseDescriptor;
import org.apache.cassandra.db.BufferDecoratedKey;
import org.apache.cassandra.db.ClusteringComparator;
import org.apache.cassandra.db.DecoratedKey;
import org.apache.cassandra.db.marshal.LongType;
import org.apache.cassandra.dht.Murmur3Partitioner;
import org.apache.cassandra.index.sasi.disk.TokenTreeBuilder.EntryType;
import org.apache.cassandra.index.sasi.utils.*;
import org.apache.cassandra.io.util.*;

import junit.framework.Assert;

import org.junit.BeforeClass;
import org.junit.Test;
import org.apache.commons.lang3.builder.HashCodeBuilder;

public class TokenTreeTest
{
    private static final ClusteringComparator CLUSTERING_COMPARATOR = new ClusteringComparator(LongType.instance);

    @BeforeClass
    public static void setupDD()
    {
        DatabaseDescriptor.daemonInitialization();
    }

    static KeyOffsets singleOffset = new KeyOffsets() {{ put(1L, KeyOffsets.asArray(10L)); }};
    static KeyOffsets bigSingleOffset = new KeyOffsets() {{ put(2147521562L, KeyOffsets.asArray(10)); }};
    static KeyOffsets shortPackableCollision = new KeyOffsets() {{
        put(2L, KeyOffsets.asArray(10));
        put(3L, KeyOffsets.asArray(10));
    }}; // can pack two shorts
    static KeyOffsets intPackableCollision = new KeyOffsets()
    {{
        put(6L, KeyOffsets.asArray(10));
        put(((long) Short.MAX_VALUE) + 1, KeyOffsets.asArray(10));
    }}; // can pack int & short
    static KeyOffsets multiCollision = new KeyOffsets()
    {{
        put(3L, KeyOffsets.asArray(10));
        put(4L, KeyOffsets.asArray(10));
        put(5L, KeyOffsets.asArray(10));
    }}; // can't pack
    static KeyOffsets unpackableCollision = new KeyOffsets()
    {{
        put(((long) Short.MAX_VALUE) + 1, KeyOffsets.asArray(10));
        put(((long) Short.MAX_VALUE) + 2, KeyOffsets.asArray(10));
    }}; // can't pack

    final static SortedMap<Long, KeyOffsets> simpleTokenMap = new TreeMap<Long, KeyOffsets>()
    {{
            put(1L, bigSingleOffset); put(3L, shortPackableCollision); put(4L, intPackableCollision); put(6L, singleOffset);
            put(9L, multiCollision); put(10L, unpackableCollision); put(12L, singleOffset); put(13L, singleOffset);
            put(15L, singleOffset); put(16L, singleOffset); put(20L, singleOffset); put(22L, singleOffset);
            put(25L, singleOffset); put(26L, singleOffset); put(27L, singleOffset); put(28L, singleOffset);
            put(40L, singleOffset); put(50L, singleOffset); put(100L, singleOffset); put(101L, singleOffset);
            put(102L, singleOffset); put(103L, singleOffset); put(108L, singleOffset); put(110L, singleOffset);
            put(112L, singleOffset); put(115L, singleOffset); put(116L, singleOffset); put(120L, singleOffset);
            put(121L, singleOffset); put(122L, singleOffset); put(123L, singleOffset); put(125L, singleOffset);
    }};

    final static SortedMap<Long, KeyOffsets> bigTokensMap = new TreeMap<Long, KeyOffsets>()
    {{
            for (long i = 0; i < 1000000; i++)
                put(i, singleOffset);
    }};

    final static SortedMap<Long, KeyOffsets> collidingTokensMap = new TreeMap<Long, KeyOffsets>()
    {{
        put(1L, singleOffset);
        put(7L, singleOffset);
        put(8L, singleOffset);
    }};

    final static SortedMap<Long, KeyOffsets> tokens = bigTokensMap;

    final static SequentialWriterOption DEFAULT_OPT = SequentialWriterOption.newBuilder().bufferSize(4096).build();

    @Test
    public void testSerializedSizeDynamic() throws Exception
    {
        testSerializedSize(new DynamicTokenTreeBuilder(tokens));
    }

    @Test
    public void testSerializedSizeStatic() throws Exception
    {
        testSerializedSize(new StaticTokenTreeBuilder(new FakeCombinedTerm(tokens)));
    }


    public void testSerializedSize(final TokenTreeBuilder builder) throws Exception
    {
        builder.finish();
        final File treeFile = File.createTempFile("token-tree-size-test", "tt");
        treeFile.deleteOnExit();

        try (SequentialWriter writer = new SequentialWriter(treeFile, DEFAULT_OPT))
        {
            builder.write(writer);
            writer.sync();
        }

        final RandomAccessReader reader = RandomAccessReader.open(treeFile);
        Assert.assertEquals((int) reader.bytesRemaining(), builder.serializedSize());
        reader.close();
    }

    @Test
    public void buildSerializeAndIterateDynamic() throws Exception
    {
        buildSerializeAndIterate(new DynamicTokenTreeBuilder(simpleTokenMap), simpleTokenMap);
    }

    @Test
    public void buildSerializeAndIterateStatic() throws Exception
    {
        buildSerializeAndIterate(new StaticTokenTreeBuilder(new FakeCombinedTerm(tokens)), tokens);
    }


    public void buildSerializeAndIterate(TokenTreeBuilder builder, SortedMap<Long, KeyOffsets> tokenMap) throws Exception
    {

        builder.finish();
        final File treeFile = File.createTempFile("token-tree-iterate-test1", "tt");
        treeFile.deleteOnExit();

        try (SequentialWriter writer = new SequentialWriter(treeFile, DEFAULT_OPT))
        {
            builder.write(writer);
            writer.sync();
        }

        final RandomAccessReader reader = RandomAccessReader.open(treeFile);
        final TokenTree tokenTree = new TokenTree(new MappedBuffer(reader));

        final Iterator<Token> tokenIterator = tokenTree.iterator(KeyConverter.instance);
        final Iterator<Map.Entry<Long, KeyOffsets>> listIterator = tokenMap.entrySet().iterator();
        while (tokenIterator.hasNext() && listIterator.hasNext())
        {
            Token treeNext = tokenIterator.next();
            Map.Entry<Long, KeyOffsets> listNext = listIterator.next();

            Assert.assertEquals(listNext.getKey(), treeNext.get());
            Assert.assertEquals(convert(listNext.getValue()), convert(treeNext));
        }

        Assert.assertFalse("token iterator not finished", tokenIterator.hasNext());
        Assert.assertFalse("list iterator not finished", listIterator.hasNext());

        reader.close();
    }

    @Test
    public void buildSerializeAndGetDynamic() throws Exception
    {
        buildSerializeAndGet(false);
    }

    @Test
    public void buildSerializeAndGetStatic() throws Exception
    {
        buildSerializeAndGet(true);
    }

    public void buildSerializeAndGet(boolean isStatic) throws Exception
    {
        final long tokMin = 0;
        final long tokMax = 1000;

        final TokenTree tokenTree = generateTree(tokMin, tokMax, isStatic);

        for (long i = 0; i <= tokMax; i++)
        {
            TokenTree.OnDiskToken result = tokenTree.get(i, KeyConverter.instance);
            Assert.assertNotNull("failed to find object for token " + i, result);

            KeyOffsets found = result.getOffsets();
            Assert.assertEquals(1, found.size());
            Assert.assertEquals(i, found.iterator().next().key);
        }

        Assert.assertNull("found missing object", tokenTree.get(tokMax + 10, KeyConverter.instance));
    }

    @Test
    public void buildSerializeIterateAndSkipDynamic() throws Exception
    {
        buildSerializeIterateAndSkip(new DynamicTokenTreeBuilder(tokens), tokens);
    }

    @Test
    public void buildSerializeIterateAndSkipStatic() throws Exception
    {
        buildSerializeIterateAndSkip(new StaticTokenTreeBuilder(new FakeCombinedTerm(tokens)), tokens);
    }

    public void buildSerializeIterateAndSkip(TokenTreeBuilder builder, SortedMap<Long, KeyOffsets> tokens) throws Exception
    {
        builder.finish();
        final File treeFile = File.createTempFile("token-tree-iterate-test2", "tt");
        treeFile.deleteOnExit();

        try (SequentialWriter writer = new SequentialWriter(treeFile, DEFAULT_OPT))
        {
            builder.write(writer);
            writer.sync();
        }

        final RandomAccessReader reader = RandomAccessReader.open(treeFile);
        final TokenTree tokenTree = new TokenTree(new MappedBuffer(reader));

        final RangeIterator<Long, Token> treeIterator = tokenTree.iterator(KeyConverter.instance);
        final RangeIterator<Long, TokenWithOffsets> listIterator = new EntrySetSkippableIterator(tokens);

        long lastToken = 0L;
        while (treeIterator.hasNext() && lastToken < 12)
        {
            Token treeNext = treeIterator.next();
            TokenWithOffsets listNext = listIterator.next();

            Assert.assertEquals(listNext.token, (lastToken = treeNext.get()));
            Assert.assertEquals(convert(listNext.offsets), convert(treeNext));
        }

        treeIterator.skipTo(100548L);
        listIterator.skipTo(100548L);

        while (treeIterator.hasNext() && listIterator.hasNext())
        {
            Token treeNext = treeIterator.next();
            TokenWithOffsets listNext = listIterator.next();

            Assert.assertEquals(listNext.token, (long) treeNext.get());
            Assert.assertEquals(convert(listNext.offsets), convert(treeNext));

        }

        Assert.assertFalse("Tree iterator not completed", treeIterator.hasNext());
        Assert.assertFalse("List iterator not completed", listIterator.hasNext());

        reader.close();
    }

    @Test
    public void skipPastEndDynamic() throws Exception
    {
        skipPastEnd(new DynamicTokenTreeBuilder(simpleTokenMap), simpleTokenMap);
    }

    @Test
    public void skipPastEndStatic() throws Exception
    {
        skipPastEnd(new StaticTokenTreeBuilder(new FakeCombinedTerm(simpleTokenMap)), simpleTokenMap);
    }

    public void skipPastEnd(TokenTreeBuilder builder, SortedMap<Long, KeyOffsets> tokens) throws Exception
    {
        builder.finish();
        final File treeFile = File.createTempFile("token-tree-skip-past-test", "tt");
        treeFile.deleteOnExit();

        try (SequentialWriter writer = new SequentialWriter(treeFile, DEFAULT_OPT))
        {
            builder.write(writer);
            writer.sync();
        }

        final RandomAccessReader reader = RandomAccessReader.open(treeFile);
        final RangeIterator<Long, Token> tokenTree = new TokenTree(new MappedBuffer(reader)).iterator(KeyConverter.instance);

        tokenTree.skipTo(tokens.lastKey() + 10);
    }

    @Test
    public void testTokenMergeDyanmic() throws Exception
    {
        testTokenMerge(false);
    }

    @Test
    public void testTokenMergeStatic() throws Exception
    {
        testTokenMerge(true);
    }

    public void testTokenMerge(boolean isStatic) throws Exception
    {
        final long min = 0, max = 1000;

        // two different trees with the same offsets
        TokenTree treeA = generateTree(min, max, isStatic);
        TokenTree treeB = generateTree(min, max, isStatic);

        RangeIterator<Long, Token> a = treeA.iterator(KeyConverter.instance);
        RangeIterator<Long, Token> b = treeB.iterator(KeyConverter.instance);

        long count = min;
        while (a.hasNext() && b.hasNext())
        {
            final Token tokenA = a.next();
            final Token tokenB = b.next();

            // merging of two OnDiskToken
            tokenA.merge(tokenB);
            // merging with RAM Token with different offset
            tokenA.merge(new TokenWithOffsets(tokenA.get(), convert(count + 1)));
            // and RAM token with the same offset
            tokenA.merge(new TokenWithOffsets(tokenA.get(), convert(count)));

            // should fail when trying to merge different tokens
            try
            {
                long l = tokenA.get();
                tokenA.merge(new TokenWithOffsets(l + 1, convert(count)));
                Assert.fail();
            }
            catch (IllegalArgumentException e)
            {
                // expected
            }

            final Set<Long> offsets = new TreeSet<>();
            for (RowKey key : tokenA)
                offsets.add(LongType.instance.compose(key.decoratedKey.getKey()));

            Set<Long> expected = new TreeSet<>();
            {
                expected.add(count);
                expected.add(count + 1);
            }

            Assert.assertEquals(expected, offsets);
            count++;
        }

        Assert.assertEquals(max, count - 1);
    }

    @Test
    public void testEntryTypeOrdinalLookup()
    {
        Assert.assertEquals(EntryType.SIMPLE, EntryType.of(EntryType.SIMPLE.ordinal()));
        Assert.assertEquals(EntryType.PACKED, EntryType.of(EntryType.PACKED.ordinal()));
        Assert.assertEquals(EntryType.FACTORED, EntryType.of(EntryType.FACTORED.ordinal()));
        Assert.assertEquals(EntryType.OVERFLOW, EntryType.of(EntryType.OVERFLOW.ordinal()));
    }

    @Test
    public void testMergingOfEqualTokenTrees() throws Exception
    {
        testMergingOfEqualTokenTrees(simpleTokenMap);
        testMergingOfEqualTokenTrees(bigTokensMap);
    }

    public void testMergingOfEqualTokenTrees(SortedMap<Long, KeyOffsets> tokensMap) throws Exception
    {
        TokenTreeBuilder tokensA = new DynamicTokenTreeBuilder(tokensMap);
        TokenTreeBuilder tokensB = new DynamicTokenTreeBuilder(tokensMap);

        TokenTree a = buildTree(tokensA);
        TokenTree b = buildTree(tokensB);

        TokenTreeBuilder tokensC = new StaticTokenTreeBuilder(new CombinedTerm(null, null)
        {
            public RangeIterator<Long, Token> getTokenIterator()
            {
                RangeIterator.Builder<Long, Token> union = RangeUnionIterator.builder();
                union.add(a.iterator(KeyConverter.instance));
                union.add(b.iterator(KeyConverter.instance));

                return union.build();
            }
        });

        TokenTree c = buildTree(tokensC);
        Assert.assertEquals(tokensMap.size(), c.getCount());
        Iterator<Token> tokenIterator = c.iterator(KeyConverter.instance);
        Iterator<Map.Entry<Long, KeyOffsets>> listIterator = tokensMap.entrySet().iterator();

        while (tokenIterator.hasNext() && listIterator.hasNext())
        {
            Token treeNext = tokenIterator.next();
            Map.Entry<Long, KeyOffsets> listNext = listIterator.next();

            Assert.assertEquals(listNext.getKey(), treeNext.get());
            Assert.assertEquals(convert(listNext.getValue()), convert(treeNext));
        }

        for (Map.Entry<Long, KeyOffsets> entry : tokensMap.entrySet())
        {
            TokenTree.OnDiskToken result = c.get(entry.getKey(), KeyConverter.instance);
            Assert.assertNotNull("failed to find object for token " + entry.getKey(), result);
            KeyOffsets found = result.getOffsets();
            Assert.assertEquals(entry.getValue(), found);

        }
    }


    private TokenTree buildTree(TokenTreeBuilder builder) throws Exception
    {
        builder.finish();
        final File treeFile = File.createTempFile("token-tree-", "db");
        treeFile.deleteOnExit();

        try (SequentialWriter writer = new SequentialWriter(treeFile, DEFAULT_OPT))
        {
            builder.write(writer);
            writer.sync();
        }

        final RandomAccessReader reader = RandomAccessReader.open(treeFile);
        return new TokenTree(new MappedBuffer(reader));
    }

    private static class EntrySetSkippableIterator extends RangeIterator<Long, TokenWithOffsets>
    {
        private final PeekingIterator<Map.Entry<Long, KeyOffsets>> elements;

        EntrySetSkippableIterator(SortedMap<Long, KeyOffsets> elms)
        {
            super(elms.firstKey(), elms.lastKey(), elms.size());
            elements = Iterators.peekingIterator(elms.entrySet().iterator());
        }

        @Override
        public TokenWithOffsets computeNext()
        {
            if (!elements.hasNext())
                return endOfData();

            Map.Entry<Long, KeyOffsets> next = elements.next();
            return new TokenWithOffsets(next.getKey(), next.getValue());
        }

        @Override
        protected void performSkipTo(Long nextToken)
        {
            while (elements.hasNext())
            {
                if (Long.compare(elements.peek().getKey(), nextToken) >= 0)
                {
                    break;
                }

                elements.next();
            }
        }

        @Override
        public void close() throws IOException
        {
            // nothing to do here
        }
    }

    public static class FakeCombinedTerm extends CombinedTerm
    {
        private final SortedMap<Long, KeyOffsets> tokens;

        public FakeCombinedTerm(SortedMap<Long, KeyOffsets> tokens)
        {
            super(null, null);
            this.tokens = tokens;
        }

        public RangeIterator<Long, Token> getTokenIterator()
        {
            return new TokenMapIterator(tokens);
        }
    }

    public static class TokenMapIterator extends RangeIterator<Long, Token>
    {
        public final Iterator<Map.Entry<Long, KeyOffsets>> iterator;

        public TokenMapIterator(SortedMap<Long, KeyOffsets> tokens)
        {
            super(tokens.firstKey(), tokens.lastKey(), tokens.size());
            iterator = tokens.entrySet().iterator();
        }

        public Token computeNext()
        {
            if (!iterator.hasNext())
                return endOfData();

            Map.Entry<Long, KeyOffsets> entry = iterator.next();
            return new TokenWithOffsets(entry.getKey(), entry.getValue());
        }

        public void close() throws IOException
        {

        }

        public void performSkipTo(Long next)
        {
            throw new UnsupportedOperationException();
        }
    }

    public static class TokenWithOffsets extends Token
    {
        private final KeyOffsets offsets;

        public TokenWithOffsets(Long token, final KeyOffsets offsets)
        {
            super(token);
            this.offsets = offsets;
        }

        @Override
        public KeyOffsets getOffsets()
        {
            return offsets;
        }

        @Override
        public void merge(CombinedValue<Long> other)
        {}

        @Override
        public int compareTo(CombinedValue<Long> o)
        {
            return Long.compare(token, o.get());
        }

        @Override
        public boolean equals(Object other)
        {
            if (!(other instanceof TokenWithOffsets))
                return false;

            TokenWithOffsets o = (TokenWithOffsets) other;
            return token == o.token && offsets.equals(o.offsets);
        }

        @Override
        public int hashCode()
        {
            return new HashCodeBuilder().append(token).build();
        }

        @Override
        public String toString()
        {
            return String.format("TokenValue(token: %d, offsets: %s)", token, offsets);
        }

        @Override
        public Iterator<RowKey> iterator()
        {
            List<RowKey> keys = new ArrayList<>(offsets.size());
            for (LongObjectCursor<long[]> offset : offsets)
                for (long l : offset.value)
                    keys.add(KeyConverter.instance.getRowKey(offset.key, l));
            return keys.iterator();
        }
    }

    private static Set<RowKey> convert(KeyOffsets offsets)
    {
        Set<RowKey> keys = new HashSet<>();
        for (LongObjectCursor<long[]> offset : offsets)
            for (long l : offset.value)
                keys.add(new RowKey(KeyConverter.dk(offset.key),
                                    KeyConverter.ck(l),
                                    CLUSTERING_COMPARATOR));

        return keys;
    }

    private static Set<RowKey> convert(Token results)
    {
        Set<RowKey> keys = new HashSet<>();
        for (RowKey key : results)
            keys.add(key);

        return keys;
    }

    private static KeyOffsets convert(long... values)
    {
        KeyOffsets result = new KeyOffsets(values.length);
        for (long v : values)
            result.put(v, KeyOffsets.asArray(v + 5));

        return result;
    }

    private TokenTree generateTree(final long minToken, final long maxToken, boolean isStatic) throws IOException
    {
        final SortedMap<Long, KeyOffsets> toks = new TreeMap<Long, KeyOffsets>()
        {{
            for (long i = minToken; i <= maxToken; i++)
            {
                KeyOffsets offsetSet = new KeyOffsets();
                offsetSet.put(i, KeyOffsets.asArray(i + 5));
                put(i, offsetSet);
            }
        }};

        final TokenTreeBuilder builder = isStatic ? new StaticTokenTreeBuilder(new FakeCombinedTerm(toks)) : new DynamicTokenTreeBuilder(toks);
        builder.finish();
        final File treeFile = File.createTempFile("token-tree-get-test", "tt");
        treeFile.deleteOnExit();

        try (SequentialWriter writer = new SequentialWriter(treeFile, DEFAULT_OPT))
        {
            builder.write(writer);
            writer.sync();
        }

        RandomAccessReader reader = null;

        try
        {
            reader = RandomAccessReader.open(treeFile);
            return new TokenTree(new MappedBuffer(reader));
        }
        finally
        {
            FileUtils.closeQuietly(reader);
        }
    }
}
