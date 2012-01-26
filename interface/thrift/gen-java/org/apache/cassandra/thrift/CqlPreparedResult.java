/**
 * Autogenerated by Thrift Compiler (0.7.0)
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

public class CqlPreparedResult implements org.apache.thrift.TBase<CqlPreparedResult, CqlPreparedResult._Fields>, java.io.Serializable, Cloneable {
  private static final org.apache.thrift.protocol.TStruct STRUCT_DESC = new org.apache.thrift.protocol.TStruct("CqlPreparedResult");

  private static final org.apache.thrift.protocol.TField ITEM_ID_FIELD_DESC = new org.apache.thrift.protocol.TField("itemId", org.apache.thrift.protocol.TType.I32, (short)1);
  private static final org.apache.thrift.protocol.TField COUNT_FIELD_DESC = new org.apache.thrift.protocol.TField("count", org.apache.thrift.protocol.TType.I32, (short)2);
  private static final org.apache.thrift.protocol.TField VARIABLE_TYPES_FIELD_DESC = new org.apache.thrift.protocol.TField("variable_types", org.apache.thrift.protocol.TType.LIST, (short)3);

  public int itemId; // required
  public int count; // required
  public List<String> variable_types; // required

  /** The set of fields this struct contains, along with convenience methods for finding and manipulating them. */
  public enum _Fields implements org.apache.thrift.TFieldIdEnum {
    ITEM_ID((short)1, "itemId"),
    COUNT((short)2, "count"),
    VARIABLE_TYPES((short)3, "variable_types");

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
        case 1: // ITEM_ID
          return ITEM_ID;
        case 2: // COUNT
          return COUNT;
        case 3: // VARIABLE_TYPES
          return VARIABLE_TYPES;
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
  private static final int __ITEMID_ISSET_ID = 0;
  private static final int __COUNT_ISSET_ID = 1;
  private BitSet __isset_bit_vector = new BitSet(2);

  public static final Map<_Fields, org.apache.thrift.meta_data.FieldMetaData> metaDataMap;
  static {
    Map<_Fields, org.apache.thrift.meta_data.FieldMetaData> tmpMap = new EnumMap<_Fields, org.apache.thrift.meta_data.FieldMetaData>(_Fields.class);
    tmpMap.put(_Fields.ITEM_ID, new org.apache.thrift.meta_data.FieldMetaData("itemId", org.apache.thrift.TFieldRequirementType.REQUIRED, 
        new org.apache.thrift.meta_data.FieldValueMetaData(org.apache.thrift.protocol.TType.I32)));
    tmpMap.put(_Fields.COUNT, new org.apache.thrift.meta_data.FieldMetaData("count", org.apache.thrift.TFieldRequirementType.REQUIRED, 
        new org.apache.thrift.meta_data.FieldValueMetaData(org.apache.thrift.protocol.TType.I32)));
    tmpMap.put(_Fields.VARIABLE_TYPES, new org.apache.thrift.meta_data.FieldMetaData("variable_types", org.apache.thrift.TFieldRequirementType.OPTIONAL, 
        new org.apache.thrift.meta_data.ListMetaData(org.apache.thrift.protocol.TType.LIST, 
            new org.apache.thrift.meta_data.FieldValueMetaData(org.apache.thrift.protocol.TType.STRING))));
    metaDataMap = Collections.unmodifiableMap(tmpMap);
    org.apache.thrift.meta_data.FieldMetaData.addStructMetaDataMap(CqlPreparedResult.class, metaDataMap);
  }

  public CqlPreparedResult() {
  }

  public CqlPreparedResult(
    int itemId,
    int count)
  {
    this();
    this.itemId = itemId;
    setItemIdIsSet(true);
    this.count = count;
    setCountIsSet(true);
  }

  /**
   * Performs a deep copy on <i>other</i>.
   */
  public CqlPreparedResult(CqlPreparedResult other) {
    __isset_bit_vector.clear();
    __isset_bit_vector.or(other.__isset_bit_vector);
    this.itemId = other.itemId;
    this.count = other.count;
    if (other.isSetVariable_types()) {
      List<String> __this__variable_types = new ArrayList<String>();
      for (String other_element : other.variable_types) {
        __this__variable_types.add(other_element);
      }
      this.variable_types = __this__variable_types;
    }
  }

  public CqlPreparedResult deepCopy() {
    return new CqlPreparedResult(this);
  }

  @Override
  public void clear() {
    setItemIdIsSet(false);
    this.itemId = 0;
    setCountIsSet(false);
    this.count = 0;
    this.variable_types = null;
  }

  public int getItemId() {
    return this.itemId;
  }

  public CqlPreparedResult setItemId(int itemId) {
    this.itemId = itemId;
    setItemIdIsSet(true);
    return this;
  }

  public void unsetItemId() {
    __isset_bit_vector.clear(__ITEMID_ISSET_ID);
  }

  /** Returns true if field itemId is set (has been assigned a value) and false otherwise */
  public boolean isSetItemId() {
    return __isset_bit_vector.get(__ITEMID_ISSET_ID);
  }

  public void setItemIdIsSet(boolean value) {
    __isset_bit_vector.set(__ITEMID_ISSET_ID, value);
  }

  public int getCount() {
    return this.count;
  }

  public CqlPreparedResult setCount(int count) {
    this.count = count;
    setCountIsSet(true);
    return this;
  }

  public void unsetCount() {
    __isset_bit_vector.clear(__COUNT_ISSET_ID);
  }

  /** Returns true if field count is set (has been assigned a value) and false otherwise */
  public boolean isSetCount() {
    return __isset_bit_vector.get(__COUNT_ISSET_ID);
  }

  public void setCountIsSet(boolean value) {
    __isset_bit_vector.set(__COUNT_ISSET_ID, value);
  }

  public int getVariable_typesSize() {
    return (this.variable_types == null) ? 0 : this.variable_types.size();
  }

  public java.util.Iterator<String> getVariable_typesIterator() {
    return (this.variable_types == null) ? null : this.variable_types.iterator();
  }

  public void addToVariable_types(String elem) {
    if (this.variable_types == null) {
      this.variable_types = new ArrayList<String>();
    }
    this.variable_types.add(elem);
  }

  public List<String> getVariable_types() {
    return this.variable_types;
  }

  public CqlPreparedResult setVariable_types(List<String> variable_types) {
    this.variable_types = variable_types;
    return this;
  }

  public void unsetVariable_types() {
    this.variable_types = null;
  }

  /** Returns true if field variable_types is set (has been assigned a value) and false otherwise */
  public boolean isSetVariable_types() {
    return this.variable_types != null;
  }

  public void setVariable_typesIsSet(boolean value) {
    if (!value) {
      this.variable_types = null;
    }
  }

  public void setFieldValue(_Fields field, Object value) {
    switch (field) {
    case ITEM_ID:
      if (value == null) {
        unsetItemId();
      } else {
        setItemId((Integer)value);
      }
      break;

    case COUNT:
      if (value == null) {
        unsetCount();
      } else {
        setCount((Integer)value);
      }
      break;

    case VARIABLE_TYPES:
      if (value == null) {
        unsetVariable_types();
      } else {
        setVariable_types((List<String>)value);
      }
      break;

    }
  }

  public Object getFieldValue(_Fields field) {
    switch (field) {
    case ITEM_ID:
      return Integer.valueOf(getItemId());

    case COUNT:
      return Integer.valueOf(getCount());

    case VARIABLE_TYPES:
      return getVariable_types();

    }
    throw new IllegalStateException();
  }

  /** Returns true if field corresponding to fieldID is set (has been assigned a value) and false otherwise */
  public boolean isSet(_Fields field) {
    if (field == null) {
      throw new IllegalArgumentException();
    }

    switch (field) {
    case ITEM_ID:
      return isSetItemId();
    case COUNT:
      return isSetCount();
    case VARIABLE_TYPES:
      return isSetVariable_types();
    }
    throw new IllegalStateException();
  }

  @Override
  public boolean equals(Object that) {
    if (that == null)
      return false;
    if (that instanceof CqlPreparedResult)
      return this.equals((CqlPreparedResult)that);
    return false;
  }

  public boolean equals(CqlPreparedResult that) {
    if (that == null)
      return false;

    boolean this_present_itemId = true;
    boolean that_present_itemId = true;
    if (this_present_itemId || that_present_itemId) {
      if (!(this_present_itemId && that_present_itemId))
        return false;
      if (this.itemId != that.itemId)
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

    boolean this_present_variable_types = true && this.isSetVariable_types();
    boolean that_present_variable_types = true && that.isSetVariable_types();
    if (this_present_variable_types || that_present_variable_types) {
      if (!(this_present_variable_types && that_present_variable_types))
        return false;
      if (!this.variable_types.equals(that.variable_types))
        return false;
    }

    return true;
  }

  @Override
  public int hashCode() {
    HashCodeBuilder builder = new HashCodeBuilder();

    boolean present_itemId = true;
    builder.append(present_itemId);
    if (present_itemId)
      builder.append(itemId);

    boolean present_count = true;
    builder.append(present_count);
    if (present_count)
      builder.append(count);

    boolean present_variable_types = true && (isSetVariable_types());
    builder.append(present_variable_types);
    if (present_variable_types)
      builder.append(variable_types);

    return builder.toHashCode();
  }

  public int compareTo(CqlPreparedResult other) {
    if (!getClass().equals(other.getClass())) {
      return getClass().getName().compareTo(other.getClass().getName());
    }

    int lastComparison = 0;
    CqlPreparedResult typedOther = (CqlPreparedResult)other;

    lastComparison = Boolean.valueOf(isSetItemId()).compareTo(typedOther.isSetItemId());
    if (lastComparison != 0) {
      return lastComparison;
    }
    if (isSetItemId()) {
      lastComparison = org.apache.thrift.TBaseHelper.compareTo(this.itemId, typedOther.itemId);
      if (lastComparison != 0) {
        return lastComparison;
      }
    }
    lastComparison = Boolean.valueOf(isSetCount()).compareTo(typedOther.isSetCount());
    if (lastComparison != 0) {
      return lastComparison;
    }
    if (isSetCount()) {
      lastComparison = org.apache.thrift.TBaseHelper.compareTo(this.count, typedOther.count);
      if (lastComparison != 0) {
        return lastComparison;
      }
    }
    lastComparison = Boolean.valueOf(isSetVariable_types()).compareTo(typedOther.isSetVariable_types());
    if (lastComparison != 0) {
      return lastComparison;
    }
    if (isSetVariable_types()) {
      lastComparison = org.apache.thrift.TBaseHelper.compareTo(this.variable_types, typedOther.variable_types);
      if (lastComparison != 0) {
        return lastComparison;
      }
    }
    return 0;
  }

  public _Fields fieldForId(int fieldId) {
    return _Fields.findByThriftId(fieldId);
  }

  public void read(org.apache.thrift.protocol.TProtocol iprot) throws org.apache.thrift.TException {
    org.apache.thrift.protocol.TField field;
    iprot.readStructBegin();
    while (true)
    {
      field = iprot.readFieldBegin();
      if (field.type == org.apache.thrift.protocol.TType.STOP) { 
        break;
      }
      switch (field.id) {
        case 1: // ITEM_ID
          if (field.type == org.apache.thrift.protocol.TType.I32) {
            this.itemId = iprot.readI32();
            setItemIdIsSet(true);
          } else { 
            org.apache.thrift.protocol.TProtocolUtil.skip(iprot, field.type);
          }
          break;
        case 2: // COUNT
          if (field.type == org.apache.thrift.protocol.TType.I32) {
            this.count = iprot.readI32();
            setCountIsSet(true);
          } else { 
            org.apache.thrift.protocol.TProtocolUtil.skip(iprot, field.type);
          }
          break;
        case 3: // VARIABLE_TYPES
          if (field.type == org.apache.thrift.protocol.TType.LIST) {
            {
              org.apache.thrift.protocol.TList _list91 = iprot.readListBegin();
              this.variable_types = new ArrayList<String>(_list91.size);
              for (int _i92 = 0; _i92 < _list91.size; ++_i92)
              {
                String _elem93; // required
                _elem93 = iprot.readString();
                this.variable_types.add(_elem93);
              }
              iprot.readListEnd();
            }
          } else { 
            org.apache.thrift.protocol.TProtocolUtil.skip(iprot, field.type);
          }
          break;
        default:
          org.apache.thrift.protocol.TProtocolUtil.skip(iprot, field.type);
      }
      iprot.readFieldEnd();
    }
    iprot.readStructEnd();

    // check for required fields of primitive type, which can't be checked in the validate method
    if (!isSetItemId()) {
      throw new org.apache.thrift.protocol.TProtocolException("Required field 'itemId' was not found in serialized data! Struct: " + toString());
    }
    if (!isSetCount()) {
      throw new org.apache.thrift.protocol.TProtocolException("Required field 'count' was not found in serialized data! Struct: " + toString());
    }
    validate();
  }

  public void write(org.apache.thrift.protocol.TProtocol oprot) throws org.apache.thrift.TException {
    validate();

    oprot.writeStructBegin(STRUCT_DESC);
    oprot.writeFieldBegin(ITEM_ID_FIELD_DESC);
    oprot.writeI32(this.itemId);
    oprot.writeFieldEnd();
    oprot.writeFieldBegin(COUNT_FIELD_DESC);
    oprot.writeI32(this.count);
    oprot.writeFieldEnd();
    if (this.variable_types != null) {
      if (isSetVariable_types()) {
        oprot.writeFieldBegin(VARIABLE_TYPES_FIELD_DESC);
        {
          oprot.writeListBegin(new org.apache.thrift.protocol.TList(org.apache.thrift.protocol.TType.STRING, this.variable_types.size()));
          for (String _iter94 : this.variable_types)
          {
            oprot.writeString(_iter94);
          }
          oprot.writeListEnd();
        }
        oprot.writeFieldEnd();
      }
    }
    oprot.writeFieldStop();
    oprot.writeStructEnd();
  }

  @Override
  public String toString() {
    StringBuilder sb = new StringBuilder("CqlPreparedResult(");
    boolean first = true;

    sb.append("itemId:");
    sb.append(this.itemId);
    first = false;
    if (!first) sb.append(", ");
    sb.append("count:");
    sb.append(this.count);
    first = false;
    if (isSetVariable_types()) {
      if (!first) sb.append(", ");
      sb.append("variable_types:");
      if (this.variable_types == null) {
        sb.append("null");
      } else {
        sb.append(this.variable_types);
      }
      first = false;
    }
    sb.append(")");
    return sb.toString();
  }

  public void validate() throws org.apache.thrift.TException {
    // check for required fields
    // alas, we cannot check 'itemId' because it's a primitive and you chose the non-beans generator.
    // alas, we cannot check 'count' because it's a primitive and you chose the non-beans generator.
  }

  private void writeObject(java.io.ObjectOutputStream out) throws java.io.IOException {
    try {
      write(new org.apache.thrift.protocol.TCompactProtocol(new org.apache.thrift.transport.TIOStreamTransport(out)));
    } catch (org.apache.thrift.TException te) {
      throw new java.io.IOException(te);
    }
  }

  private void readObject(java.io.ObjectInputStream in) throws java.io.IOException, ClassNotFoundException {
    try {
      // it doesn't seem like you should have to do this, but java serialization is wacky, and doesn't call the default constructor.
      __isset_bit_vector = new BitSet(1);
      read(new org.apache.thrift.protocol.TCompactProtocol(new org.apache.thrift.transport.TIOStreamTransport(in)));
    } catch (org.apache.thrift.TException te) {
      throw new java.io.IOException(te);
    }
  }

}

