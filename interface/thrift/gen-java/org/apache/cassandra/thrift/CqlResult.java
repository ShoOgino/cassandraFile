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

public class CqlResult implements org.apache.thrift.TBase<CqlResult, CqlResult._Fields>, java.io.Serializable, Cloneable {
  private static final org.apache.thrift.protocol.TStruct STRUCT_DESC = new org.apache.thrift.protocol.TStruct("CqlResult");

  private static final org.apache.thrift.protocol.TField TYPE_FIELD_DESC = new org.apache.thrift.protocol.TField("type", org.apache.thrift.protocol.TType.I32, (short)1);
  private static final org.apache.thrift.protocol.TField ROWS_FIELD_DESC = new org.apache.thrift.protocol.TField("rows", org.apache.thrift.protocol.TType.LIST, (short)2);
  private static final org.apache.thrift.protocol.TField NUM_FIELD_DESC = new org.apache.thrift.protocol.TField("num", org.apache.thrift.protocol.TType.I32, (short)3);
  private static final org.apache.thrift.protocol.TField SCHEMA_FIELD_DESC = new org.apache.thrift.protocol.TField("schema", org.apache.thrift.protocol.TType.STRUCT, (short)4);

  /**
   * 
   * @see CqlResultType
   */
  public CqlResultType type; // required
  public List<CqlRow> rows; // required
  public int num; // required
  public CqlMetadata schema; // required

  /** The set of fields this struct contains, along with convenience methods for finding and manipulating them. */
  public enum _Fields implements org.apache.thrift.TFieldIdEnum {
    /**
     * 
     * @see CqlResultType
     */
    TYPE((short)1, "type"),
    ROWS((short)2, "rows"),
    NUM((short)3, "num"),
    SCHEMA((short)4, "schema");

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
        case 1: // TYPE
          return TYPE;
        case 2: // ROWS
          return ROWS;
        case 3: // NUM
          return NUM;
        case 4: // SCHEMA
          return SCHEMA;
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
  private static final int __NUM_ISSET_ID = 0;
  private BitSet __isset_bit_vector = new BitSet(1);

  public static final Map<_Fields, org.apache.thrift.meta_data.FieldMetaData> metaDataMap;
  static {
    Map<_Fields, org.apache.thrift.meta_data.FieldMetaData> tmpMap = new EnumMap<_Fields, org.apache.thrift.meta_data.FieldMetaData>(_Fields.class);
    tmpMap.put(_Fields.TYPE, new org.apache.thrift.meta_data.FieldMetaData("type", org.apache.thrift.TFieldRequirementType.REQUIRED, 
        new org.apache.thrift.meta_data.EnumMetaData(org.apache.thrift.protocol.TType.ENUM, CqlResultType.class)));
    tmpMap.put(_Fields.ROWS, new org.apache.thrift.meta_data.FieldMetaData("rows", org.apache.thrift.TFieldRequirementType.OPTIONAL, 
        new org.apache.thrift.meta_data.ListMetaData(org.apache.thrift.protocol.TType.LIST, 
            new org.apache.thrift.meta_data.StructMetaData(org.apache.thrift.protocol.TType.STRUCT, CqlRow.class))));
    tmpMap.put(_Fields.NUM, new org.apache.thrift.meta_data.FieldMetaData("num", org.apache.thrift.TFieldRequirementType.OPTIONAL, 
        new org.apache.thrift.meta_data.FieldValueMetaData(org.apache.thrift.protocol.TType.I32)));
    tmpMap.put(_Fields.SCHEMA, new org.apache.thrift.meta_data.FieldMetaData("schema", org.apache.thrift.TFieldRequirementType.OPTIONAL, 
        new org.apache.thrift.meta_data.StructMetaData(org.apache.thrift.protocol.TType.STRUCT, CqlMetadata.class)));
    metaDataMap = Collections.unmodifiableMap(tmpMap);
    org.apache.thrift.meta_data.FieldMetaData.addStructMetaDataMap(CqlResult.class, metaDataMap);
  }

  public CqlResult() {
  }

  public CqlResult(
    CqlResultType type)
  {
    this();
    this.type = type;
  }

  /**
   * Performs a deep copy on <i>other</i>.
   */
  public CqlResult(CqlResult other) {
    __isset_bit_vector.clear();
    __isset_bit_vector.or(other.__isset_bit_vector);
    if (other.isSetType()) {
      this.type = other.type;
    }
    if (other.isSetRows()) {
      List<CqlRow> __this__rows = new ArrayList<CqlRow>();
      for (CqlRow other_element : other.rows) {
        __this__rows.add(new CqlRow(other_element));
      }
      this.rows = __this__rows;
    }
    this.num = other.num;
    if (other.isSetSchema()) {
      this.schema = new CqlMetadata(other.schema);
    }
  }

  public CqlResult deepCopy() {
    return new CqlResult(this);
  }

  @Override
  public void clear() {
    this.type = null;
    this.rows = null;
    setNumIsSet(false);
    this.num = 0;
    this.schema = null;
  }

  /**
   * 
   * @see CqlResultType
   */
  public CqlResultType getType() {
    return this.type;
  }

  /**
   * 
   * @see CqlResultType
   */
  public CqlResult setType(CqlResultType type) {
    this.type = type;
    return this;
  }

  public void unsetType() {
    this.type = null;
  }

  /** Returns true if field type is set (has been assigned a value) and false otherwise */
  public boolean isSetType() {
    return this.type != null;
  }

  public void setTypeIsSet(boolean value) {
    if (!value) {
      this.type = null;
    }
  }

  public int getRowsSize() {
    return (this.rows == null) ? 0 : this.rows.size();
  }

  public java.util.Iterator<CqlRow> getRowsIterator() {
    return (this.rows == null) ? null : this.rows.iterator();
  }

  public void addToRows(CqlRow elem) {
    if (this.rows == null) {
      this.rows = new ArrayList<CqlRow>();
    }
    this.rows.add(elem);
  }

  public List<CqlRow> getRows() {
    return this.rows;
  }

  public CqlResult setRows(List<CqlRow> rows) {
    this.rows = rows;
    return this;
  }

  public void unsetRows() {
    this.rows = null;
  }

  /** Returns true if field rows is set (has been assigned a value) and false otherwise */
  public boolean isSetRows() {
    return this.rows != null;
  }

  public void setRowsIsSet(boolean value) {
    if (!value) {
      this.rows = null;
    }
  }

  public int getNum() {
    return this.num;
  }

  public CqlResult setNum(int num) {
    this.num = num;
    setNumIsSet(true);
    return this;
  }

  public void unsetNum() {
    __isset_bit_vector.clear(__NUM_ISSET_ID);
  }

  /** Returns true if field num is set (has been assigned a value) and false otherwise */
  public boolean isSetNum() {
    return __isset_bit_vector.get(__NUM_ISSET_ID);
  }

  public void setNumIsSet(boolean value) {
    __isset_bit_vector.set(__NUM_ISSET_ID, value);
  }

  public CqlMetadata getSchema() {
    return this.schema;
  }

  public CqlResult setSchema(CqlMetadata schema) {
    this.schema = schema;
    return this;
  }

  public void unsetSchema() {
    this.schema = null;
  }

  /** Returns true if field schema is set (has been assigned a value) and false otherwise */
  public boolean isSetSchema() {
    return this.schema != null;
  }

  public void setSchemaIsSet(boolean value) {
    if (!value) {
      this.schema = null;
    }
  }

  public void setFieldValue(_Fields field, Object value) {
    switch (field) {
    case TYPE:
      if (value == null) {
        unsetType();
      } else {
        setType((CqlResultType)value);
      }
      break;

    case ROWS:
      if (value == null) {
        unsetRows();
      } else {
        setRows((List<CqlRow>)value);
      }
      break;

    case NUM:
      if (value == null) {
        unsetNum();
      } else {
        setNum((Integer)value);
      }
      break;

    case SCHEMA:
      if (value == null) {
        unsetSchema();
      } else {
        setSchema((CqlMetadata)value);
      }
      break;

    }
  }

  public Object getFieldValue(_Fields field) {
    switch (field) {
    case TYPE:
      return getType();

    case ROWS:
      return getRows();

    case NUM:
      return Integer.valueOf(getNum());

    case SCHEMA:
      return getSchema();

    }
    throw new IllegalStateException();
  }

  /** Returns true if field corresponding to fieldID is set (has been assigned a value) and false otherwise */
  public boolean isSet(_Fields field) {
    if (field == null) {
      throw new IllegalArgumentException();
    }

    switch (field) {
    case TYPE:
      return isSetType();
    case ROWS:
      return isSetRows();
    case NUM:
      return isSetNum();
    case SCHEMA:
      return isSetSchema();
    }
    throw new IllegalStateException();
  }

  @Override
  public boolean equals(Object that) {
    if (that == null)
      return false;
    if (that instanceof CqlResult)
      return this.equals((CqlResult)that);
    return false;
  }

  public boolean equals(CqlResult that) {
    if (that == null)
      return false;

    boolean this_present_type = true && this.isSetType();
    boolean that_present_type = true && that.isSetType();
    if (this_present_type || that_present_type) {
      if (!(this_present_type && that_present_type))
        return false;
      if (!this.type.equals(that.type))
        return false;
    }

    boolean this_present_rows = true && this.isSetRows();
    boolean that_present_rows = true && that.isSetRows();
    if (this_present_rows || that_present_rows) {
      if (!(this_present_rows && that_present_rows))
        return false;
      if (!this.rows.equals(that.rows))
        return false;
    }

    boolean this_present_num = true && this.isSetNum();
    boolean that_present_num = true && that.isSetNum();
    if (this_present_num || that_present_num) {
      if (!(this_present_num && that_present_num))
        return false;
      if (this.num != that.num)
        return false;
    }

    boolean this_present_schema = true && this.isSetSchema();
    boolean that_present_schema = true && that.isSetSchema();
    if (this_present_schema || that_present_schema) {
      if (!(this_present_schema && that_present_schema))
        return false;
      if (!this.schema.equals(that.schema))
        return false;
    }

    return true;
  }

  @Override
  public int hashCode() {
    HashCodeBuilder builder = new HashCodeBuilder();

    boolean present_type = true && (isSetType());
    builder.append(present_type);
    if (present_type)
      builder.append(type.getValue());

    boolean present_rows = true && (isSetRows());
    builder.append(present_rows);
    if (present_rows)
      builder.append(rows);

    boolean present_num = true && (isSetNum());
    builder.append(present_num);
    if (present_num)
      builder.append(num);

    boolean present_schema = true && (isSetSchema());
    builder.append(present_schema);
    if (present_schema)
      builder.append(schema);

    return builder.toHashCode();
  }

  public int compareTo(CqlResult other) {
    if (!getClass().equals(other.getClass())) {
      return getClass().getName().compareTo(other.getClass().getName());
    }

    int lastComparison = 0;
    CqlResult typedOther = (CqlResult)other;

    lastComparison = Boolean.valueOf(isSetType()).compareTo(typedOther.isSetType());
    if (lastComparison != 0) {
      return lastComparison;
    }
    if (isSetType()) {
      lastComparison = org.apache.thrift.TBaseHelper.compareTo(this.type, typedOther.type);
      if (lastComparison != 0) {
        return lastComparison;
      }
    }
    lastComparison = Boolean.valueOf(isSetRows()).compareTo(typedOther.isSetRows());
    if (lastComparison != 0) {
      return lastComparison;
    }
    if (isSetRows()) {
      lastComparison = org.apache.thrift.TBaseHelper.compareTo(this.rows, typedOther.rows);
      if (lastComparison != 0) {
        return lastComparison;
      }
    }
    lastComparison = Boolean.valueOf(isSetNum()).compareTo(typedOther.isSetNum());
    if (lastComparison != 0) {
      return lastComparison;
    }
    if (isSetNum()) {
      lastComparison = org.apache.thrift.TBaseHelper.compareTo(this.num, typedOther.num);
      if (lastComparison != 0) {
        return lastComparison;
      }
    }
    lastComparison = Boolean.valueOf(isSetSchema()).compareTo(typedOther.isSetSchema());
    if (lastComparison != 0) {
      return lastComparison;
    }
    if (isSetSchema()) {
      lastComparison = org.apache.thrift.TBaseHelper.compareTo(this.schema, typedOther.schema);
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
        case 1: // TYPE
          if (field.type == org.apache.thrift.protocol.TType.I32) {
            this.type = CqlResultType.findByValue(iprot.readI32());
          } else { 
            org.apache.thrift.protocol.TProtocolUtil.skip(iprot, field.type);
          }
          break;
        case 2: // ROWS
          if (field.type == org.apache.thrift.protocol.TType.LIST) {
            {
              org.apache.thrift.protocol.TList _list87 = iprot.readListBegin();
              this.rows = new ArrayList<CqlRow>(_list87.size);
              for (int _i88 = 0; _i88 < _list87.size; ++_i88)
              {
                CqlRow _elem89; // required
                _elem89 = new CqlRow();
                _elem89.read(iprot);
                this.rows.add(_elem89);
              }
              iprot.readListEnd();
            }
          } else { 
            org.apache.thrift.protocol.TProtocolUtil.skip(iprot, field.type);
          }
          break;
        case 3: // NUM
          if (field.type == org.apache.thrift.protocol.TType.I32) {
            this.num = iprot.readI32();
            setNumIsSet(true);
          } else { 
            org.apache.thrift.protocol.TProtocolUtil.skip(iprot, field.type);
          }
          break;
        case 4: // SCHEMA
          if (field.type == org.apache.thrift.protocol.TType.STRUCT) {
            this.schema = new CqlMetadata();
            this.schema.read(iprot);
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
    validate();
  }

  public void write(org.apache.thrift.protocol.TProtocol oprot) throws org.apache.thrift.TException {
    validate();

    oprot.writeStructBegin(STRUCT_DESC);
    if (this.type != null) {
      oprot.writeFieldBegin(TYPE_FIELD_DESC);
      oprot.writeI32(this.type.getValue());
      oprot.writeFieldEnd();
    }
    if (this.rows != null) {
      if (isSetRows()) {
        oprot.writeFieldBegin(ROWS_FIELD_DESC);
        {
          oprot.writeListBegin(new org.apache.thrift.protocol.TList(org.apache.thrift.protocol.TType.STRUCT, this.rows.size()));
          for (CqlRow _iter90 : this.rows)
          {
            _iter90.write(oprot);
          }
          oprot.writeListEnd();
        }
        oprot.writeFieldEnd();
      }
    }
    if (isSetNum()) {
      oprot.writeFieldBegin(NUM_FIELD_DESC);
      oprot.writeI32(this.num);
      oprot.writeFieldEnd();
    }
    if (this.schema != null) {
      if (isSetSchema()) {
        oprot.writeFieldBegin(SCHEMA_FIELD_DESC);
        this.schema.write(oprot);
        oprot.writeFieldEnd();
      }
    }
    oprot.writeFieldStop();
    oprot.writeStructEnd();
  }

  @Override
  public String toString() {
    StringBuilder sb = new StringBuilder("CqlResult(");
    boolean first = true;

    sb.append("type:");
    if (this.type == null) {
      sb.append("null");
    } else {
      sb.append(this.type);
    }
    first = false;
    if (isSetRows()) {
      if (!first) sb.append(", ");
      sb.append("rows:");
      if (this.rows == null) {
        sb.append("null");
      } else {
        sb.append(this.rows);
      }
      first = false;
    }
    if (isSetNum()) {
      if (!first) sb.append(", ");
      sb.append("num:");
      sb.append(this.num);
      first = false;
    }
    if (isSetSchema()) {
      if (!first) sb.append(", ");
      sb.append("schema:");
      if (this.schema == null) {
        sb.append("null");
      } else {
        sb.append(this.schema);
      }
      first = false;
    }
    sb.append(")");
    return sb.toString();
  }

  public void validate() throws org.apache.thrift.TException {
    // check for required fields
    if (type == null) {
      throw new org.apache.thrift.protocol.TProtocolException("Required field 'type' was not present! Struct: " + toString());
    }
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

