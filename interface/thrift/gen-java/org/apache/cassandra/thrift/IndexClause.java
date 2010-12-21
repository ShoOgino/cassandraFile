/**
 * Autogenerated by Thrift
 *
 * DO NOT EDIT UNLESS YOU ARE SURE THAT YOU KNOW WHAT YOU ARE DOING
 */
package org.apache.cassandra.thrift;
/*
 * 
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
 * 
 */


import org.apache.commons.lang.builder.HashCodeBuilder;
import java.util.List;
import java.util.ArrayList;
import java.util.Map;
import java.util.HashMap;
import java.util.EnumMap;
import java.util.Set;
import java.util.HashSet;
import java.util.EnumSet;
import java.util.Collections;
import java.util.BitSet;
import java.nio.ByteBuffer;
import java.util.Arrays;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.apache.thrift.*;
import org.apache.thrift.async.*;
import org.apache.thrift.meta_data.*;
import org.apache.thrift.transport.*;
import org.apache.thrift.protocol.*;

public class IndexClause implements TBase<IndexClause, IndexClause._Fields>, java.io.Serializable, Cloneable {
  private static final TStruct STRUCT_DESC = new TStruct("IndexClause");

  private static final TField EXPRESSIONS_FIELD_DESC = new TField("expressions", TType.LIST, (short)1);
  private static final TField START_KEY_FIELD_DESC = new TField("start_key", TType.STRING, (short)2);
  private static final TField COUNT_FIELD_DESC = new TField("count", TType.I32, (short)3);

  public List<IndexExpression> expressions;
  public ByteBuffer start_key;
  public int count;

  /** The set of fields this struct contains, along with convenience methods for finding and manipulating them. */
  public enum _Fields implements TFieldIdEnum {
    EXPRESSIONS((short)1, "expressions"),
    START_KEY((short)2, "start_key"),
    COUNT((short)3, "count");

    private static final Map<String, _Fields> byName = new HashMap<String, _Fields>();

    static {
      for (_Fields field : EnumSet.allOf(_Fields.class)) {
        byName.put(field.getFieldName(), field);
      }
    }

    /**
     * Find the _Fields constant that matches fieldId, or null if its not found.
     */
    public static _Fields findByThriftId(int fieldId) {
      switch(fieldId) {
        case 1: // EXPRESSIONS
          return EXPRESSIONS;
        case 2: // START_KEY
          return START_KEY;
        case 3: // COUNT
          return COUNT;
        default:
          return null;
      }
    }

    /**
     * Find the _Fields constant that matches fieldId, throwing an exception
     * if it is not found.
     */
    public static _Fields findByThriftIdOrThrow(int fieldId) {
      _Fields fields = findByThriftId(fieldId);
      if (fields == null) throw new IllegalArgumentException("Field " + fieldId + " doesn't exist!");
      return fields;
    }

    /**
     * Find the _Fields constant that matches name, or null if its not found.
     */
    public static _Fields findByName(String name) {
      return byName.get(name);
    }

    private final short _thriftId;
    private final String _fieldName;

    _Fields(short thriftId, String fieldName) {
      _thriftId = thriftId;
      _fieldName = fieldName;
    }

    public short getThriftFieldId() {
      return _thriftId;
    }

    public String getFieldName() {
      return _fieldName;
    }
  }

  // isset id assignments
  private static final int __COUNT_ISSET_ID = 0;
  private BitSet __isset_bit_vector = new BitSet(1);

  public static final Map<_Fields, FieldMetaData> metaDataMap;
  static {
    Map<_Fields, FieldMetaData> tmpMap = new EnumMap<_Fields, FieldMetaData>(_Fields.class);
    tmpMap.put(_Fields.EXPRESSIONS, new FieldMetaData("expressions", TFieldRequirementType.REQUIRED, 
        new ListMetaData(TType.LIST, 
            new StructMetaData(TType.STRUCT, IndexExpression.class))));
    tmpMap.put(_Fields.START_KEY, new FieldMetaData("start_key", TFieldRequirementType.REQUIRED, 
        new FieldValueMetaData(TType.STRING)));
    tmpMap.put(_Fields.COUNT, new FieldMetaData("count", TFieldRequirementType.REQUIRED, 
        new FieldValueMetaData(TType.I32)));
    metaDataMap = Collections.unmodifiableMap(tmpMap);
    FieldMetaData.addStructMetaDataMap(IndexClause.class, metaDataMap);
  }

  public IndexClause() {
    this.count = 100;

  }

  public IndexClause(
    List<IndexExpression> expressions,
    ByteBuffer start_key,
    int count)
  {
    this();
    this.expressions = expressions;
    this.start_key = start_key;
    this.count = count;
    setCountIsSet(true);
  }

  /**
   * Performs a deep copy on <i>other</i>.
   */
  public IndexClause(IndexClause other) {
    __isset_bit_vector.clear();
    __isset_bit_vector.or(other.__isset_bit_vector);
    if (other.isSetExpressions()) {
      List<IndexExpression> __this__expressions = new ArrayList<IndexExpression>();
      for (IndexExpression other_element : other.expressions) {
        __this__expressions.add(new IndexExpression(other_element));
      }
      this.expressions = __this__expressions;
    }
    if (other.isSetStart_key()) {
      this.start_key = TBaseHelper.copyBinary(other.start_key);
;
    }
    this.count = other.count;
  }

  public IndexClause deepCopy() {
    return new IndexClause(this);
  }

  @Override
  public void clear() {
    this.expressions = null;
    this.start_key = null;
    this.count = 100;

  }

  public int getExpressionsSize() {
    return (this.expressions == null) ? 0 : this.expressions.size();
  }

  public java.util.Iterator<IndexExpression> getExpressionsIterator() {
    return (this.expressions == null) ? null : this.expressions.iterator();
  }

  public void addToExpressions(IndexExpression elem) {
    if (this.expressions == null) {
      this.expressions = new ArrayList<IndexExpression>();
    }
    this.expressions.add(elem);
  }

  public List<IndexExpression> getExpressions() {
    return this.expressions;
  }

  public IndexClause setExpressions(List<IndexExpression> expressions) {
    this.expressions = expressions;
    return this;
  }

  public void unsetExpressions() {
    this.expressions = null;
  }

  /** Returns true if field expressions is set (has been asigned a value) and false otherwise */
  public boolean isSetExpressions() {
    return this.expressions != null;
  }

  public void setExpressionsIsSet(boolean value) {
    if (!value) {
      this.expressions = null;
    }
  }

  public byte[] getStart_key() {
    setStart_key(TBaseHelper.rightSize(start_key));
    return start_key.array();
  }

  public ByteBuffer BufferForStart_key() {
    return start_key;
  }

  public IndexClause setStart_key(byte[] start_key) {
    setStart_key(ByteBuffer.wrap(start_key));
    return this;
  }

  public IndexClause setStart_key(ByteBuffer start_key) {
    this.start_key = start_key;
    return this;
  }

  public void unsetStart_key() {
    this.start_key = null;
  }

  /** Returns true if field start_key is set (has been asigned a value) and false otherwise */
  public boolean isSetStart_key() {
    return this.start_key != null;
  }

  public void setStart_keyIsSet(boolean value) {
    if (!value) {
      this.start_key = null;
    }
  }

  public int getCount() {
    return this.count;
  }

  public IndexClause setCount(int count) {
    this.count = count;
    setCountIsSet(true);
    return this;
  }

  public void unsetCount() {
    __isset_bit_vector.clear(__COUNT_ISSET_ID);
  }

  /** Returns true if field count is set (has been asigned a value) and false otherwise */
  public boolean isSetCount() {
    return __isset_bit_vector.get(__COUNT_ISSET_ID);
  }

  public void setCountIsSet(boolean value) {
    __isset_bit_vector.set(__COUNT_ISSET_ID, value);
  }

  public void setFieldValue(_Fields field, Object value) {
    switch (field) {
    case EXPRESSIONS:
      if (value == null) {
        unsetExpressions();
      } else {
        setExpressions((List<IndexExpression>)value);
      }
      break;

    case START_KEY:
      if (value == null) {
        unsetStart_key();
      } else {
        setStart_key((ByteBuffer)value);
      }
      break;

    case COUNT:
      if (value == null) {
        unsetCount();
      } else {
        setCount((Integer)value);
      }
      break;

    }
  }

  public Object getFieldValue(_Fields field) {
    switch (field) {
    case EXPRESSIONS:
      return getExpressions();

    case START_KEY:
      return getStart_key();

    case COUNT:
      return new Integer(getCount());

    }
    throw new IllegalStateException();
  }

  /** Returns true if field corresponding to fieldID is set (has been asigned a value) and false otherwise */
  public boolean isSet(_Fields field) {
    if (field == null) {
      throw new IllegalArgumentException();
    }

    switch (field) {
    case EXPRESSIONS:
      return isSetExpressions();
    case START_KEY:
      return isSetStart_key();
    case COUNT:
      return isSetCount();
    }
    throw new IllegalStateException();
  }

  @Override
  public boolean equals(Object that) {
    if (that == null)
      return false;
    if (that instanceof IndexClause)
      return this.equals((IndexClause)that);
    return false;
  }

  public boolean equals(IndexClause that) {
    if (that == null)
      return false;

    boolean this_present_expressions = true && this.isSetExpressions();
    boolean that_present_expressions = true && that.isSetExpressions();
    if (this_present_expressions || that_present_expressions) {
      if (!(this_present_expressions && that_present_expressions))
        return false;
      if (!this.expressions.equals(that.expressions))
        return false;
    }

    boolean this_present_start_key = true && this.isSetStart_key();
    boolean that_present_start_key = true && that.isSetStart_key();
    if (this_present_start_key || that_present_start_key) {
      if (!(this_present_start_key && that_present_start_key))
        return false;
      if (!this.start_key.equals(that.start_key))
        return false;
    }

    boolean this_present_count = true;
    boolean that_present_count = true;
    if (this_present_count || that_present_count) {
      if (!(this_present_count && that_present_count))
        return false;
      if (this.count != that.count)
        return false;
    }

    return true;
  }

  @Override
  public int hashCode() {
    HashCodeBuilder builder = new HashCodeBuilder();

    boolean present_expressions = true && (isSetExpressions());
    builder.append(present_expressions);
    if (present_expressions)
      builder.append(expressions);

    boolean present_start_key = true && (isSetStart_key());
    builder.append(present_start_key);
    if (present_start_key)
      builder.append(start_key);

    boolean present_count = true;
    builder.append(present_count);
    if (present_count)
      builder.append(count);

    return builder.toHashCode();
  }

  public int compareTo(IndexClause other) {
    if (!getClass().equals(other.getClass())) {
      return getClass().getName().compareTo(other.getClass().getName());
    }

    int lastComparison = 0;
    IndexClause typedOther = (IndexClause)other;

    lastComparison = Boolean.valueOf(isSetExpressions()).compareTo(typedOther.isSetExpressions());
    if (lastComparison != 0) {
      return lastComparison;
    }
    if (isSetExpressions()) {
      lastComparison = TBaseHelper.compareTo(this.expressions, typedOther.expressions);
      if (lastComparison != 0) {
        return lastComparison;
      }
    }
    lastComparison = Boolean.valueOf(isSetStart_key()).compareTo(typedOther.isSetStart_key());
    if (lastComparison != 0) {
      return lastComparison;
    }
    if (isSetStart_key()) {
      lastComparison = TBaseHelper.compareTo(this.start_key, typedOther.start_key);
      if (lastComparison != 0) {
        return lastComparison;
      }
    }
    lastComparison = Boolean.valueOf(isSetCount()).compareTo(typedOther.isSetCount());
    if (lastComparison != 0) {
      return lastComparison;
    }
    if (isSetCount()) {
      lastComparison = TBaseHelper.compareTo(this.count, typedOther.count);
      if (lastComparison != 0) {
        return lastComparison;
      }
    }
    return 0;
  }

  public _Fields fieldForId(int fieldId) {
    return _Fields.findByThriftId(fieldId);
  }

  public void read(TProtocol iprot) throws TException {
    TField field;
    iprot.readStructBegin();
    while (true)
    {
      field = iprot.readFieldBegin();
      if (field.type == TType.STOP) { 
        break;
      }
      switch (field.id) {
        case 1: // EXPRESSIONS
          if (field.type == TType.LIST) {
            {
              TList _list12 = iprot.readListBegin();
              this.expressions = new ArrayList<IndexExpression>(_list12.size);
              for (int _i13 = 0; _i13 < _list12.size; ++_i13)
              {
                IndexExpression _elem14;
                _elem14 = new IndexExpression();
                _elem14.read(iprot);
                this.expressions.add(_elem14);
              }
              iprot.readListEnd();
            }
          } else { 
            TProtocolUtil.skip(iprot, field.type);
          }
          break;
        case 2: // START_KEY
          if (field.type == TType.STRING) {
            this.start_key = iprot.readBinary();
          } else { 
            TProtocolUtil.skip(iprot, field.type);
          }
          break;
        case 3: // COUNT
          if (field.type == TType.I32) {
            this.count = iprot.readI32();
            setCountIsSet(true);
          } else { 
            TProtocolUtil.skip(iprot, field.type);
          }
          break;
        default:
          TProtocolUtil.skip(iprot, field.type);
      }
      iprot.readFieldEnd();
    }
    iprot.readStructEnd();

    // check for required fields of primitive type, which can't be checked in the validate method
    if (!isSetCount()) {
      throw new TProtocolException("Required field 'count' was not found in serialized data! Struct: " + toString());
    }
    validate();
  }

  public void write(TProtocol oprot) throws TException {
    validate();

    oprot.writeStructBegin(STRUCT_DESC);
    if (this.expressions != null) {
      oprot.writeFieldBegin(EXPRESSIONS_FIELD_DESC);
      {
        oprot.writeListBegin(new TList(TType.STRUCT, this.expressions.size()));
        for (IndexExpression _iter15 : this.expressions)
        {
          _iter15.write(oprot);
        }
        oprot.writeListEnd();
      }
      oprot.writeFieldEnd();
    }
    if (this.start_key != null) {
      oprot.writeFieldBegin(START_KEY_FIELD_DESC);
      oprot.writeBinary(this.start_key);
      oprot.writeFieldEnd();
    }
    oprot.writeFieldBegin(COUNT_FIELD_DESC);
    oprot.writeI32(this.count);
    oprot.writeFieldEnd();
    oprot.writeFieldStop();
    oprot.writeStructEnd();
  }

  @Override
  public String toString() {
    StringBuilder sb = new StringBuilder("IndexClause(");
    boolean first = true;

    sb.append("expressions:");
    if (this.expressions == null) {
      sb.append("null");
    } else {
      sb.append(this.expressions);
    }
    first = false;
    if (!first) sb.append(", ");
    sb.append("start_key:");
    if (this.start_key == null) {
      sb.append("null");
    } else {
      TBaseHelper.toString(this.start_key, sb);
    }
    first = false;
    if (!first) sb.append(", ");
    sb.append("count:");
    sb.append(this.count);
    first = false;
    sb.append(")");
    return sb.toString();
  }

  public void validate() throws TException {
    // check for required fields
    if (expressions == null) {
      throw new TProtocolException("Required field 'expressions' was not present! Struct: " + toString());
    }
    if (start_key == null) {
      throw new TProtocolException("Required field 'start_key' was not present! Struct: " + toString());
    }
    // alas, we cannot check 'count' because it's a primitive and you chose the non-beans generator.
  }

}

