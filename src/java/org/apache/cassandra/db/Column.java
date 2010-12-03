/**
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

package org.apache.cassandra.db;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.util.Collection;

import org.apache.cassandra.db.marshal.AbstractType;
import org.apache.cassandra.io.util.DataOutputBuffer;
import org.apache.cassandra.utils.ByteBufferUtil;
import org.apache.cassandra.utils.FBUtilities;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


/**
 * Column is immutable, which prevents all kinds of confusion in a multithreaded environment.
 * (TODO: look at making SuperColumn immutable too.  This is trickier but is probably doable
 *  with something like PCollections -- http://code.google.com
 */

public class Column implements IColumn
{
    private static Logger logger = LoggerFactory.getLogger(Column.class);

    public static ColumnSerializer serializer()
    {
        return new ColumnSerializer();
    }

    protected final ByteBuffer name;
    protected final ByteBuffer value;
    protected final long timestamp;

    Column(ByteBuffer name)
    {
        this(name, FBUtilities.EMPTY_BYTE_BUFFER);
    }

    public Column(ByteBuffer name, ByteBuffer value)
    {
        this(name, value, 0);
    }

    public Column(ByteBuffer name, ByteBuffer value, long timestamp)
    {
        assert name != null;
        assert value != null;
        assert name.remaining() <= IColumn.MAX_NAME_LENGTH;
        this.name = name;
        this.value = value;
        this.timestamp = timestamp;
    }

    public ByteBuffer name()
    {
        return name;
    }

    public Column getSubColumn(ByteBuffer columnName)
    {
        throw new UnsupportedOperationException("This operation is unsupported on simple columns.");
    }

    public ByteBuffer value()
    {
        return value;
    }

    public Collection<IColumn> getSubColumns()
    {
        throw new UnsupportedOperationException("This operation is unsupported on simple columns.");
    }

    public long timestamp()
    {
        return timestamp;
    }

    public boolean isMarkedForDelete()
    {
        return false;
    }

    public long getMarkedForDeleteAt()
    {
        throw new IllegalStateException("column is not marked for delete");
    }

    public long mostRecentLiveChangeAt()
    {
        return timestamp;
    }

    public int size()
    {
        /*
         * Size of a column is =
         *   size of a name (short + length of the string)
         * + 1 byte to indicate if the column has been deleted
         * + 8 bytes for timestamp
         * + 4 bytes which basically indicates the size of the byte array
         * + entire byte array.
        */
        return DBConstants.shortSize_ + name.remaining() + DBConstants.boolSize_ + DBConstants.tsSize_ + DBConstants.intSize_ + value.remaining();
    }

    /*
     * This returns the size of the column when serialized.
     * @see com.facebook.infrastructure.db.IColumn#serializedSize()
    */
    public int serializedSize()
    {
        return size();
    }

    public void addColumn(IColumn column)
    {
        throw new UnsupportedOperationException("This operation is not supported for simple columns.");
    }

    public IColumn diff(IColumn column)
    {
        if (timestamp() < column.timestamp())
        {
            return column;
        }
        return null;
    }

    public void updateDigest(MessageDigest digest)
    {
        digest.update(name.array(),name.position()+name.arrayOffset(),name.remaining());
        digest.update(value.array(),value.position()+name.arrayOffset(),value.remaining());        
        
        DataOutputBuffer buffer = new DataOutputBuffer();
        try
        {
            buffer.writeLong(timestamp);
            buffer.writeByte((isMarkedForDelete()) ? ColumnSerializer.DELETION_MASK : 0);
        }
        catch (IOException e)
        {
            throw new RuntimeException(e);
        }
        digest.update(buffer.getData(), 0, buffer.getLength());
    }

    public int getLocalDeletionTime()
    {
        throw new IllegalStateException("column is not marked for delete");
    }

    public IColumn reconcile(IColumn column)
    {
        // tombstones take precedence.  (if both are tombstones, then it doesn't matter which one we use.)
        if (isMarkedForDelete())
            return timestamp() < column.timestamp() ? column : this;
        if (column.isMarkedForDelete())
            return timestamp() > column.timestamp() ? this : column;
        // break ties by comparing values.
        if (timestamp() == column.timestamp())
            return value().compareTo(column.value()) < 0 ? column : this;
        // neither is tombstoned and timestamps are different
        return timestamp() < column.timestamp() ? column : this;
    }

    @Override
    public boolean equals(Object o)
    {
        if (this == o)
            return true;
        if (o == null || getClass() != o.getClass())
            return false;

        Column column = (Column)o;

        if (timestamp != column.timestamp)
            return false;
        if (!name.equals(column.name))
            return false;

        return value.equals(column.value);
    }

    @Override
    public int hashCode()
    {
        int result = name != null ? name.hashCode() : 0;
        result = 31 * result + (value != null ? value.hashCode() : 0);
        result = 31 * result + (int)(timestamp ^ (timestamp >>> 32));
        return result;
    }

    @Override
    public IColumn deepCopy()
    {
        return new Column(ByteBufferUtil.clone(name), ByteBufferUtil.clone(value), timestamp);
    }
    
    public String getString(AbstractType comparator)
    {
        StringBuilder sb = new StringBuilder();
        sb.append(comparator.getString(name));
        sb.append(":");
        sb.append(isMarkedForDelete());
        sb.append(":");
        sb.append(value.remaining());
        sb.append("@");
        sb.append(timestamp());
        return sb.toString();
    }

    public boolean isLive()
    {
        return !isMarkedForDelete();
    }
}

