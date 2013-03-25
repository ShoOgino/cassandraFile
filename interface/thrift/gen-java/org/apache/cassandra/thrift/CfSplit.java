/**
 * Autogenerated by Thrift Compiler (0.9.0)
 *
 * DO NOT EDIT UNLESS YOU ARE SURE THAT YOU KNOW WHAT YOU ARE DOING
 *  @generated
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
import org.apache.thrift.scheme.IScheme;
import org.apache.thrift.scheme.SchemeFactory;
import org.apache.thrift.scheme.StandardScheme;

import org.apache.thrift.scheme.TupleScheme;
import org.apache.thrift.protocol.TTupleProtocol;
import org.apache.thrift.protocol.TProtocolException;
import org.apache.thrift.EncodingUtils;
import org.apache.thrift.TException;
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

/**
 * Represents input splits used by hadoop ColumnFamilyRecordReaders
 */
public class CfSplit implements org.apache.thrift.TBase<CfSplit, CfSplit._Fields>, java.io.Serializable, Cloneable {
  private static final org.apache.thrift.protocol.TStruct STRUCT_DESC = new org.apache.thrift.protocol.TStruct("CfSplit");

  private static final org.apache.thrift.protocol.TField START_TOKEN_FIELD_DESC = new org.apache.thrift.protocol.TField("start_token", org.apache.thrift.protocol.TType.STRING, (short)1);
  private static final org.apache.thrift.protocol.TField END_TOKEN_FIELD_DESC = new org.apache.thrift.protocol.TField("end_token", org.apache.thrift.protocol.TType.STRING, (short)2);
  private static final org.apache.thrift.protocol.TField ROW_COUNT_FIELD_DESC = new org.apache.thrift.protocol.TField("row_count", org.apache.thrift.protocol.TType.I64, (short)3);

  private static final Map<Class<? extends IScheme>, SchemeFactory> schemes = new HashMap<Class<? extends IScheme>, SchemeFactory>();
  static {
    schemes.put(StandardScheme.class, new CfSplitStandardSchemeFactory());
    schemes.put(TupleScheme.class, new CfSplitTupleSchemeFactory());
  }

  public String start_token; // required
  public String end_token; // required
  public long row_count; // required

  /** The set of fields this struct contains, along with convenience methods for finding and manipulating them. */
  public enum _Fields implements org.apache.thrift.TFieldIdEnum {
    START_TOKEN((short)1, "start_token"),
    END_TOKEN((short)2, "end_token"),
    ROW_COUNT((short)3, "row_count");

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
        case 1: // START_TOKEN
          return START_TOKEN;
        case 2: // END_TOKEN
          return END_TOKEN;
        case 3: // ROW_COUNT
          return ROW_COUNT;
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
  private static final int __ROW_COUNT_ISSET_ID = 0;
  private byte __isset_bitfield = 0;
  public static final Map<_Fields, org.apache.thrift.meta_data.FieldMetaData> metaDataMap;
  static {
    Map<_Fields, org.apache.thrift.meta_data.FieldMetaData> tmpMap = new EnumMap<_Fields, org.apache.thrift.meta_data.FieldMetaData>(_Fields.class);
    tmpMap.put(_Fields.START_TOKEN, new org.apache.thrift.meta_data.FieldMetaData("start_token", org.apache.thrift.TFieldRequirementType.REQUIRED, 
        new org.apache.thrift.meta_data.FieldValueMetaData(org.apache.thrift.protocol.TType.STRING)));
    tmpMap.put(_Fields.END_TOKEN, new org.apache.thrift.meta_data.FieldMetaData("end_token", org.apache.thrift.TFieldRequirementType.REQUIRED, 
        new org.apache.thrift.meta_data.FieldValueMetaData(org.apache.thrift.protocol.TType.STRING)));
    tmpMap.put(_Fields.ROW_COUNT, new org.apache.thrift.meta_data.FieldMetaData("row_count", org.apache.thrift.TFieldRequirementType.REQUIRED, 
        new org.apache.thrift.meta_data.FieldValueMetaData(org.apache.thrift.protocol.TType.I64)));
    metaDataMap = Collections.unmodifiableMap(tmpMap);
    org.apache.thrift.meta_data.FieldMetaData.addStructMetaDataMap(CfSplit.class, metaDataMap);
  }

  public CfSplit() {
  }

  public CfSplit(
    String start_token,
    String end_token,
    long row_count)
  {
    this();
    this.start_token = start_token;
    this.end_token = end_token;
    this.row_count = row_count;
    setRow_countIsSet(true);
  }

  /**
   * Performs a deep copy on <i>other</i>.
   */
  public CfSplit(CfSplit other) {
    __isset_bitfield = other.__isset_bitfield;
    if (other.isSetStart_token()) {
      this.start_token = other.start_token;
    }
    if (other.isSetEnd_token()) {
      this.end_token = other.end_token;
    }
    this.row_count = other.row_count;
  }

  public CfSplit deepCopy() {
    return new CfSplit(this);
  }

  @Override
  public void clear() {
    this.start_token = null;
    this.end_token = null;
    setRow_countIsSet(false);
    this.row_count = 0;
  }

  public String getStart_token() {
    return this.start_token;
  }

  public CfSplit setStart_token(String start_token) {
    this.start_token = start_token;
    return this;
  }

  public void unsetStart_token() {
    this.start_token = null;
  }

  /** Returns true if field start_token is set (has been assigned a value) and false otherwise */
  public boolean isSetStart_token() {
    return this.start_token != null;
  }

  public void setStart_tokenIsSet(boolean value) {
    if (!value) {
      this.start_token = null;
    }
  }

  public String getEnd_token() {
    return this.end_token;
  }

  public CfSplit setEnd_token(String end_token) {
    this.end_token = end_token;
    return this;
  }

  public void unsetEnd_token() {
    this.end_token = null;
  }

  /** Returns true if field end_token is set (has been assigned a value) and false otherwise */
  public boolean isSetEnd_token() {
    return this.end_token != null;
  }

  public void setEnd_tokenIsSet(boolean value) {
    if (!value) {
      this.end_token = null;
    }
  }

  public long getRow_count() {
    return this.row_count;
  }

  public CfSplit setRow_count(long row_count) {
    this.row_count = row_count;
    setRow_countIsSet(true);
    return this;
  }

  public void unsetRow_count() {
    __isset_bitfield = EncodingUtils.clearBit(__isset_bitfield, __ROW_COUNT_ISSET_ID);
  }

  /** Returns true if field row_count is set (has been assigned a value) and false otherwise */
  public boolean isSetRow_count() {
    return EncodingUtils.testBit(__isset_bitfield, __ROW_COUNT_ISSET_ID);
  }

  public void setRow_countIsSet(boolean value) {
    __isset_bitfield = EncodingUtils.setBit(__isset_bitfield, __ROW_COUNT_ISSET_ID, value);
  }

  public void setFieldValue(_Fields field, Object value) {
    switch (field) {
    case START_TOKEN:
      if (value == null) {
        unsetStart_token();
      } else {
        setStart_token((String)value);
      }
      break;

    case END_TOKEN:
      if (value == null) {
        unsetEnd_token();
      } else {
        setEnd_token((String)value);
      }
      break;

    case ROW_COUNT:
      if (value == null) {
        unsetRow_count();
      } else {
        setRow_count((Long)value);
      }
      break;

    }
  }

  public Object getFieldValue(_Fields field) {
    switch (field) {
    case START_TOKEN:
      return getStart_token();

    case END_TOKEN:
      return getEnd_token();

    case ROW_COUNT:
      return Long.valueOf(getRow_count());

    }
    throw new IllegalStateException();
  }

  /** Returns true if field corresponding to fieldID is set (has been assigned a value) and false otherwise */
  public boolean isSet(_Fields field) {
    if (field == null) {
      throw new IllegalArgumentException();
    }

    switch (field) {
    case START_TOKEN:
      return isSetStart_token();
    case END_TOKEN:
      return isSetEnd_token();
    case ROW_COUNT:
      return isSetRow_count();
    }
    throw new IllegalStateException();
  }

  @Override
  public boolean equals(Object that) {
    if (that == null)
      return false;
    if (that instanceof CfSplit)
      return this.equals((CfSplit)that);
    return false;
  }

  public boolean equals(CfSplit that) {
    if (that == null)
      return false;

    boolean this_present_start_token = true && this.isSetStart_token();
    boolean that_present_start_token = true && that.isSetStart_token();
    if (this_present_start_token || that_present_start_token) {
      if (!(this_present_start_token && that_present_start_token))
        return false;
      if (!this.start_token.equals(that.start_token))
        return false;
    }

    boolean this_present_end_token = true && this.isSetEnd_token();
    boolean that_present_end_token = true && that.isSetEnd_token();
    if (this_present_end_token || that_present_end_token) {
      if (!(this_present_end_token && that_present_end_token))
        return false;
      if (!this.end_token.equals(that.end_token))
        return false;
    }

    boolean this_present_row_count = true;
    boolean that_present_row_count = true;
    if (this_present_row_count || that_present_row_count) {
      if (!(this_present_row_count && that_present_row_count))
        return false;
      if (this.row_count != that.row_count)
        return false;
    }

    return true;
  }

  @Override
  public int hashCode() {
    HashCodeBuilder builder = new HashCodeBuilder();

    boolean present_start_token = true && (isSetStart_token());
    builder.append(present_start_token);
    if (present_start_token)
      builder.append(start_token);

    boolean present_end_token = true && (isSetEnd_token());
    builder.append(present_end_token);
    if (present_end_token)
      builder.append(end_token);

    boolean present_row_count = true;
    builder.append(present_row_count);
    if (present_row_count)
      builder.append(row_count);

    return builder.toHashCode();
  }

  public int compareTo(CfSplit other) {
    if (!getClass().equals(other.getClass())) {
      return getClass().getName().compareTo(other.getClass().getName());
    }

    int lastComparison = 0;
    CfSplit typedOther = (CfSplit)other;

    lastComparison = Boolean.valueOf(isSetStart_token()).compareTo(typedOther.isSetStart_token());
    if (lastComparison != 0) {
      return lastComparison;
    }
    if (isSetStart_token()) {
      lastComparison = org.apache.thrift.TBaseHelper.compareTo(this.start_token, typedOther.start_token);
      if (lastComparison != 0) {
        return lastComparison;
      }
    }
    lastComparison = Boolean.valueOf(isSetEnd_token()).compareTo(typedOther.isSetEnd_token());
    if (lastComparison != 0) {
      return lastComparison;
    }
    if (isSetEnd_token()) {
      lastComparison = org.apache.thrift.TBaseHelper.compareTo(this.end_token, typedOther.end_token);
      if (lastComparison != 0) {
        return lastComparison;
      }
    }
    lastComparison = Boolean.valueOf(isSetRow_count()).compareTo(typedOther.isSetRow_count());
    if (lastComparison != 0) {
      return lastComparison;
    }
    if (isSetRow_count()) {
      lastComparison = org.apache.thrift.TBaseHelper.compareTo(this.row_count, typedOther.row_count);
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
    schemes.get(iprot.getScheme()).getScheme().read(iprot, this);
  }

  public void write(org.apache.thrift.protocol.TProtocol oprot) throws org.apache.thrift.TException {
    schemes.get(oprot.getScheme()).getScheme().write(oprot, this);
  }

  @Override
  public String toString() {
    StringBuilder sb = new StringBuilder("CfSplit(");
    boolean first = true;

    sb.append("start_token:");
    if (this.start_token == null) {
      sb.append("null");
    } else {
      sb.append(this.start_token);
    }
    first = false;
    if (!first) sb.append(", ");
    sb.append("end_token:");
    if (this.end_token == null) {
      sb.append("null");
    } else {
      sb.append(this.end_token);
    }
    first = false;
    if (!first) sb.append(", ");
    sb.append("row_count:");
    sb.append(this.row_count);
    first = false;
    sb.append(")");
    return sb.toString();
  }

  public void validate() throws org.apache.thrift.TException {
    // check for required fields
    if (start_token == null) {
      throw new org.apache.thrift.protocol.TProtocolException("Required field 'start_token' was not present! Struct: " + toString());
    }
    if (end_token == null) {
      throw new org.apache.thrift.protocol.TProtocolException("Required field 'end_token' was not present! Struct: " + toString());
    }
    // alas, we cannot check 'row_count' because it's a primitive and you chose the non-beans generator.
    // check for sub-struct validity
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
      __isset_bitfield = 0;
      read(new org.apache.thrift.protocol.TCompactProtocol(new org.apache.thrift.transport.TIOStreamTransport(in)));
    } catch (org.apache.thrift.TException te) {
      throw new java.io.IOException(te);
    }
  }

  private static class CfSplitStandardSchemeFactory implements SchemeFactory {
    public CfSplitStandardScheme getScheme() {
      return new CfSplitStandardScheme();
    }
  }

  private static class CfSplitStandardScheme extends StandardScheme<CfSplit> {

    public void read(org.apache.thrift.protocol.TProtocol iprot, CfSplit struct) throws org.apache.thrift.TException {
      org.apache.thrift.protocol.TField schemeField;
      iprot.readStructBegin();
      while (true)
      {
        schemeField = iprot.readFieldBegin();
        if (schemeField.type == org.apache.thrift.protocol.TType.STOP) { 
          break;
        }
        switch (schemeField.id) {
          case 1: // START_TOKEN
            if (schemeField.type == org.apache.thrift.protocol.TType.STRING) {
              struct.start_token = iprot.readString();
              struct.setStart_tokenIsSet(true);
            } else { 
              org.apache.thrift.protocol.TProtocolUtil.skip(iprot, schemeField.type);
            }
            break;
          case 2: // END_TOKEN
            if (schemeField.type == org.apache.thrift.protocol.TType.STRING) {
              struct.end_token = iprot.readString();
              struct.setEnd_tokenIsSet(true);
            } else { 
              org.apache.thrift.protocol.TProtocolUtil.skip(iprot, schemeField.type);
            }
            break;
          case 3: // ROW_COUNT
            if (schemeField.type == org.apache.thrift.protocol.TType.I64) {
              struct.row_count = iprot.readI64();
              struct.setRow_countIsSet(true);
            } else { 
              org.apache.thrift.protocol.TProtocolUtil.skip(iprot, schemeField.type);
            }
            break;
          default:
            org.apache.thrift.protocol.TProtocolUtil.skip(iprot, schemeField.type);
        }
        iprot.readFieldEnd();
      }
      iprot.readStructEnd();

      // check for required fields of primitive type, which can't be checked in the validate method
      if (!struct.isSetRow_count()) {
        throw new org.apache.thrift.protocol.TProtocolException("Required field 'row_count' was not found in serialized data! Struct: " + toString());
      }
      struct.validate();
    }

    public void write(org.apache.thrift.protocol.TProtocol oprot, CfSplit struct) throws org.apache.thrift.TException {
      struct.validate();

      oprot.writeStructBegin(STRUCT_DESC);
      if (struct.start_token != null) {
        oprot.writeFieldBegin(START_TOKEN_FIELD_DESC);
        oprot.writeString(struct.start_token);
        oprot.writeFieldEnd();
      }
      if (struct.end_token != null) {
        oprot.writeFieldBegin(END_TOKEN_FIELD_DESC);
        oprot.writeString(struct.end_token);
        oprot.writeFieldEnd();
      }
      oprot.writeFieldBegin(ROW_COUNT_FIELD_DESC);
      oprot.writeI64(struct.row_count);
      oprot.writeFieldEnd();
      oprot.writeFieldStop();
      oprot.writeStructEnd();
    }

  }

  private static class CfSplitTupleSchemeFactory implements SchemeFactory {
    public CfSplitTupleScheme getScheme() {
      return new CfSplitTupleScheme();
    }
  }

  private static class CfSplitTupleScheme extends TupleScheme<CfSplit> {

    @Override
    public void write(org.apache.thrift.protocol.TProtocol prot, CfSplit struct) throws org.apache.thrift.TException {
      TTupleProtocol oprot = (TTupleProtocol) prot;
      oprot.writeString(struct.start_token);
      oprot.writeString(struct.end_token);
      oprot.writeI64(struct.row_count);
    }

    @Override
    public void read(org.apache.thrift.protocol.TProtocol prot, CfSplit struct) throws org.apache.thrift.TException {
      TTupleProtocol iprot = (TTupleProtocol) prot;
      struct.start_token = iprot.readString();
      struct.setStart_tokenIsSet(true);
      struct.end_token = iprot.readString();
      struct.setEnd_tokenIsSet(true);
      struct.row_count = iprot.readI64();
      struct.setRow_countIsSet(true);
    }
  }

}

