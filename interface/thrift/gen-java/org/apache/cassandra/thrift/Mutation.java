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

/**
 * A Mutation is either an insert (represented by filling column_or_supercolumn) or a deletion (represented by filling the deletion attribute).
 * @param column_or_supercolumn. An insert to a column or supercolumn (possibly counter column or supercolumn)
 * @param deletion. A deletion of a column or supercolumn
 */
public class Mutation implements org.apache.thrift.TBase<Mutation, Mutation._Fields>, java.io.Serializable, Cloneable {
  private static final org.apache.thrift.protocol.TStruct STRUCT_DESC = new org.apache.thrift.protocol.TStruct("Mutation");

  private static final org.apache.thrift.protocol.TField COLUMN_OR_SUPERCOLUMN_FIELD_DESC = new org.apache.thrift.protocol.TField("column_or_supercolumn", org.apache.thrift.protocol.TType.STRUCT, (short)1);
  private static final org.apache.thrift.protocol.TField DELETION_FIELD_DESC = new org.apache.thrift.protocol.TField("deletion", org.apache.thrift.protocol.TType.STRUCT, (short)2);

  public ColumnOrSuperColumn column_or_supercolumn;
  public Deletion deletion;

  /** The set of fields this struct contains, along with convenience methods for finding and manipulating them. */
  public enum _Fields implements org.apache.thrift.TFieldIdEnum {
    COLUMN_OR_SUPERCOLUMN((short)1, "column_or_supercolumn"),
    DELETION((short)2, "deletion");

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
        case 1: // COLUMN_OR_SUPERCOLUMN
          return COLUMN_OR_SUPERCOLUMN;
        case 2: // DELETION
          return DELETION;
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

  public static final Map<_Fields, org.apache.thrift.meta_data.FieldMetaData> metaDataMap;
  static {
    Map<_Fields, org.apache.thrift.meta_data.FieldMetaData> tmpMap = new EnumMap<_Fields, org.apache.thrift.meta_data.FieldMetaData>(_Fields.class);
    tmpMap.put(_Fields.COLUMN_OR_SUPERCOLUMN, new org.apache.thrift.meta_data.FieldMetaData("column_or_supercolumn", org.apache.thrift.TFieldRequirementType.OPTIONAL, 
        new org.apache.thrift.meta_data.StructMetaData(org.apache.thrift.protocol.TType.STRUCT, ColumnOrSuperColumn.class)));
    tmpMap.put(_Fields.DELETION, new org.apache.thrift.meta_data.FieldMetaData("deletion", org.apache.thrift.TFieldRequirementType.OPTIONAL, 
        new org.apache.thrift.meta_data.StructMetaData(org.apache.thrift.protocol.TType.STRUCT, Deletion.class)));
    metaDataMap = Collections.unmodifiableMap(tmpMap);
    org.apache.thrift.meta_data.FieldMetaData.addStructMetaDataMap(Mutation.class, metaDataMap);
  }

  public Mutation() {
  }

  /**
   * Performs a deep copy on <i>other</i>.
   */
  public Mutation(Mutation other) {
    if (other.isSetColumn_or_supercolumn()) {
      this.column_or_supercolumn = new ColumnOrSuperColumn(other.column_or_supercolumn);
    }
    if (other.isSetDeletion()) {
      this.deletion = new Deletion(other.deletion);
    }
  }

  public Mutation deepCopy() {
    return new Mutation(this);
  }

  @Override
  public void clear() {
    this.column_or_supercolumn = null;
    this.deletion = null;
  }

  public ColumnOrSuperColumn getColumn_or_supercolumn() {
    return this.column_or_supercolumn;
  }

  public Mutation setColumn_or_supercolumn(ColumnOrSuperColumn column_or_supercolumn) {
    this.column_or_supercolumn = column_or_supercolumn;
    return this;
  }

  public void unsetColumn_or_supercolumn() {
    this.column_or_supercolumn = null;
  }

  /** Returns true if field column_or_supercolumn is set (has been assigned a value) and false otherwise */
  public boolean isSetColumn_or_supercolumn() {
    return this.column_or_supercolumn != null;
  }

  public void setColumn_or_supercolumnIsSet(boolean value) {
    if (!value) {
      this.column_or_supercolumn = null;
    }
  }

  public Deletion getDeletion() {
    return this.deletion;
  }

  public Mutation setDeletion(Deletion deletion) {
    this.deletion = deletion;
    return this;
  }

  public void unsetDeletion() {
    this.deletion = null;
  }

  /** Returns true if field deletion is set (has been assigned a value) and false otherwise */
  public boolean isSetDeletion() {
    return this.deletion != null;
  }

  public void setDeletionIsSet(boolean value) {
    if (!value) {
      this.deletion = null;
    }
  }

  public void setFieldValue(_Fields field, Object value) {
    switch (field) {
    case COLUMN_OR_SUPERCOLUMN:
      if (value == null) {
        unsetColumn_or_supercolumn();
      } else {
        setColumn_or_supercolumn((ColumnOrSuperColumn)value);
      }
      break;

    case DELETION:
      if (value == null) {
        unsetDeletion();
      } else {
        setDeletion((Deletion)value);
      }
      break;

    }
  }

  public Object getFieldValue(_Fields field) {
    switch (field) {
    case COLUMN_OR_SUPERCOLUMN:
      return getColumn_or_supercolumn();

    case DELETION:
      return getDeletion();

    }
    throw new IllegalStateException();
  }

  /** Returns true if field corresponding to fieldID is set (has been assigned a value) and false otherwise */
  public boolean isSet(_Fields field) {
    if (field == null) {
      throw new IllegalArgumentException();
    }

    switch (field) {
    case COLUMN_OR_SUPERCOLUMN:
      return isSetColumn_or_supercolumn();
    case DELETION:
      return isSetDeletion();
    }
    throw new IllegalStateException();
  }

  @Override
  public boolean equals(Object that) {
    if (that == null)
      return false;
    if (that instanceof Mutation)
      return this.equals((Mutation)that);
    return false;
  }

  public boolean equals(Mutation that) {
    if (that == null)
      return false;

    boolean this_present_column_or_supercolumn = true && this.isSetColumn_or_supercolumn();
    boolean that_present_column_or_supercolumn = true && that.isSetColumn_or_supercolumn();
    if (this_present_column_or_supercolumn || that_present_column_or_supercolumn) {
      if (!(this_present_column_or_supercolumn && that_present_column_or_supercolumn))
        return false;
      if (!this.column_or_supercolumn.equals(that.column_or_supercolumn))
        return false;
    }

    boolean this_present_deletion = true && this.isSetDeletion();
    boolean that_present_deletion = true && that.isSetDeletion();
    if (this_present_deletion || that_present_deletion) {
      if (!(this_present_deletion && that_present_deletion))
        return false;
      if (!this.deletion.equals(that.deletion))
        return false;
    }

    return true;
  }

  @Override
  public int hashCode() {
    HashCodeBuilder builder = new HashCodeBuilder();

    boolean present_column_or_supercolumn = true && (isSetColumn_or_supercolumn());
    builder.append(present_column_or_supercolumn);
    if (present_column_or_supercolumn)
      builder.append(column_or_supercolumn);

    boolean present_deletion = true && (isSetDeletion());
    builder.append(present_deletion);
    if (present_deletion)
      builder.append(deletion);

    return builder.toHashCode();
  }

  public int compareTo(Mutation other) {
    if (!getClass().equals(other.getClass())) {
      return getClass().getName().compareTo(other.getClass().getName());
    }

    int lastComparison = 0;
    Mutation typedOther = (Mutation)other;

    lastComparison = Boolean.valueOf(isSetColumn_or_supercolumn()).compareTo(typedOther.isSetColumn_or_supercolumn());
    if (lastComparison != 0) {
      return lastComparison;
    }
    if (isSetColumn_or_supercolumn()) {
      lastComparison = org.apache.thrift.TBaseHelper.compareTo(this.column_or_supercolumn, typedOther.column_or_supercolumn);
      if (lastComparison != 0) {
        return lastComparison;
      }
    }
    lastComparison = Boolean.valueOf(isSetDeletion()).compareTo(typedOther.isSetDeletion());
    if (lastComparison != 0) {
      return lastComparison;
    }
    if (isSetDeletion()) {
      lastComparison = org.apache.thrift.TBaseHelper.compareTo(this.deletion, typedOther.deletion);
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
        case 1: // COLUMN_OR_SUPERCOLUMN
          if (field.type == org.apache.thrift.protocol.TType.STRUCT) {
            this.column_or_supercolumn = new ColumnOrSuperColumn();
            this.column_or_supercolumn.read(iprot);
          } else { 
            org.apache.thrift.protocol.TProtocolUtil.skip(iprot, field.type);
          }
          break;
        case 2: // DELETION
          if (field.type == org.apache.thrift.protocol.TType.STRUCT) {
            this.deletion = new Deletion();
            this.deletion.read(iprot);
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
    if (this.column_or_supercolumn != null) {
      if (isSetColumn_or_supercolumn()) {
        oprot.writeFieldBegin(COLUMN_OR_SUPERCOLUMN_FIELD_DESC);
        this.column_or_supercolumn.write(oprot);
        oprot.writeFieldEnd();
      }
    }
    if (this.deletion != null) {
      if (isSetDeletion()) {
        oprot.writeFieldBegin(DELETION_FIELD_DESC);
        this.deletion.write(oprot);
        oprot.writeFieldEnd();
      }
    }
    oprot.writeFieldStop();
    oprot.writeStructEnd();
  }

  @Override
  public String toString() {
    StringBuilder sb = new StringBuilder("Mutation(");
    boolean first = true;

    if (isSetColumn_or_supercolumn()) {
      sb.append("column_or_supercolumn:");
      if (this.column_or_supercolumn == null) {
        sb.append("null");
      } else {
        sb.append(this.column_or_supercolumn);
      }
      first = false;
    }
    if (isSetDeletion()) {
      if (!first) sb.append(", ");
      sb.append("deletion:");
      if (this.deletion == null) {
        sb.append("null");
      } else {
        sb.append(this.deletion);
      }
      first = false;
    }
    sb.append(")");
    return sb.toString();
  }

  public void validate() throws org.apache.thrift.TException {
    // check for required fields
  }

}

