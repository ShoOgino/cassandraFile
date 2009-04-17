/**
 * Autogenerated by Thrift
 *
 * DO NOT EDIT UNLESS YOU ARE SURE THAT YOU KNOW WHAT YOU ARE DOING
 */
package org.apache.cassandra.service;

import java.util.List;
import java.util.ArrayList;
import java.util.Map;
import java.util.HashMap;
import java.util.Set;
import java.util.HashSet;
import java.util.Collections;
import org.apache.thrift.*;
import org.apache.thrift.meta_data.*;

import org.apache.thrift.protocol.*;
import org.apache.thrift.transport.*;

public class column_t implements TBase, java.io.Serializable, Cloneable {
  private static final TStruct STRUCT_DESC = new TStruct("column_t");
  private static final TField COLUMN_NAME_FIELD_DESC = new TField("columnName", TType.STRING, (short)1);
  private static final TField VALUE_FIELD_DESC = new TField("value", TType.STRING, (short)2);
  private static final TField TIMESTAMP_FIELD_DESC = new TField("timestamp", TType.I64, (short)3);

  public String columnName;
  public static final int COLUMNNAME = 1;
  public byte[] value;
  public static final int VALUE = 2;
  public long timestamp;
  public static final int TIMESTAMP = 3;

  private final Isset __isset = new Isset();
  private static final class Isset implements java.io.Serializable {
    public boolean columnName = false;
    public boolean value = false;
    public boolean timestamp = false;
  }

  public static final Map<Integer, FieldMetaData> metaDataMap = Collections.unmodifiableMap(new HashMap<Integer, FieldMetaData>() {{
    put(COLUMNNAME, new FieldMetaData("columnName", TFieldRequirementType.DEFAULT, 
        new FieldValueMetaData(TType.STRING)));
    put(VALUE, new FieldMetaData("value", TFieldRequirementType.DEFAULT, 
        new FieldValueMetaData(TType.STRING)));
    put(TIMESTAMP, new FieldMetaData("timestamp", TFieldRequirementType.DEFAULT, 
        new FieldValueMetaData(TType.I64)));
  }});

  static {
    FieldMetaData.addStructMetaDataMap(column_t.class, metaDataMap);
  }

  public column_t() {
  }

  public column_t(
    String columnName,
    byte[] value,
    long timestamp)
  {
    this();
    this.columnName = columnName;
    this.__isset.columnName = (columnName != null);
    this.value = value;
    this.__isset.value = (value != null);
    this.timestamp = timestamp;
    this.__isset.timestamp = true;
  }

  /**
   * Performs a deep copy on <i>other</i>.
   */
  public column_t(column_t other) {
    __isset.columnName = other.__isset.columnName;
    if (other.columnName != null) {
      this.columnName = other.columnName;
    }
    __isset.value = other.__isset.value;
    if (other.value != null) {
      this.value = new byte[other.value.length];
      System.arraycopy(other.value, 0, value, 0, other.value.length);
    }
    __isset.timestamp = other.__isset.timestamp;
    this.timestamp = other.timestamp;
  }

  @Override
  public column_t clone() {
    return new column_t(this);
  }

  public String getColumnName() {
    return this.columnName;
  }

  public void setColumnName(String columnName) {
    this.columnName = columnName;
    this.__isset.columnName = (columnName != null);
  }

  public void unsetColumnName() {
    this.__isset.columnName = false;
  }

  // Returns true if field columnName is set (has been asigned a value) and false otherwise
  public boolean isSetColumnName() {
    return this.__isset.columnName;
  }

  public void setColumnNameIsSet(boolean value) {
    this.__isset.columnName = value;
  }

  public byte[] getValue() {
    return this.value;
  }

  public void setValue(byte[] value) {
    this.value = value;
    this.__isset.value = (value != null);
  }

  public void unsetValue() {
    this.__isset.value = false;
  }

  // Returns true if field value is set (has been asigned a value) and false otherwise
  public boolean isSetValue() {
    return this.__isset.value;
  }

  public void setValueIsSet(boolean value) {
    this.__isset.value = value;
  }

  public long getTimestamp() {
    return this.timestamp;
  }

  public void setTimestamp(long timestamp) {
    this.timestamp = timestamp;
    this.__isset.timestamp = true;
  }

  public void unsetTimestamp() {
    this.__isset.timestamp = false;
  }

  // Returns true if field timestamp is set (has been asigned a value) and false otherwise
  public boolean isSetTimestamp() {
    return this.__isset.timestamp;
  }

  public void setTimestampIsSet(boolean value) {
    this.__isset.timestamp = value;
  }

  public void setFieldValue(int fieldID, Object value) {
    switch (fieldID) {
    case COLUMNNAME:
      setColumnName((String)value);
      break;

    case VALUE:
      setValue((byte[])value);
      break;

    case TIMESTAMP:
      setTimestamp((Long)value);
      break;

    default:
      throw new IllegalArgumentException("Field " + fieldID + " doesn't exist!");
    }
  }

  public Object getFieldValue(int fieldID) {
    switch (fieldID) {
    case COLUMNNAME:
      return getColumnName();

    case VALUE:
      return getValue();

    case TIMESTAMP:
      return new Long(getTimestamp());

    default:
      throw new IllegalArgumentException("Field " + fieldID + " doesn't exist!");
    }
  }

  // Returns true if field corresponding to fieldID is set (has been asigned a value) and false otherwise
  public boolean isSet(int fieldID) {
    switch (fieldID) {
    case COLUMNNAME:
      return this.__isset.columnName;
    case VALUE:
      return this.__isset.value;
    case TIMESTAMP:
      return this.__isset.timestamp;
    default:
      throw new IllegalArgumentException("Field " + fieldID + " doesn't exist!");
    }
  }

  @Override
  public boolean equals(Object that) {
    if (that == null)
      return false;
    if (that instanceof column_t)
      return this.equals((column_t)that);
    return false;
  }

  public boolean equals(column_t that) {
    if (that == null)
      return false;

    boolean this_present_columnName = true && (this.columnName != null);
    boolean that_present_columnName = true && (that.columnName != null);
    if (this_present_columnName || that_present_columnName) {
      if (!(this_present_columnName && that_present_columnName))
        return false;
      if (!this.columnName.equals(that.columnName))
        return false;
    }

    boolean this_present_value = true && (this.value != null);
    boolean that_present_value = true && (that.value != null);
    if (this_present_value || that_present_value) {
      if (!(this_present_value && that_present_value))
        return false;
      if (!java.util.Arrays.equals(this.value, that.value))
        return false;
    }

    boolean this_present_timestamp = true;
    boolean that_present_timestamp = true;
    if (this_present_timestamp || that_present_timestamp) {
      if (!(this_present_timestamp && that_present_timestamp))
        return false;
      if (this.timestamp != that.timestamp)
        return false;
    }

    return true;
  }

  @Override
  public int hashCode() {
    return 0;
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
      switch (field.id)
      {
        case COLUMNNAME:
          if (field.type == TType.STRING) {
            this.columnName = iprot.readString();
            this.__isset.columnName = true;
          } else { 
            TProtocolUtil.skip(iprot, field.type);
          }
          break;
        case VALUE:
          if (field.type == TType.STRING) {
            this.value = iprot.readBinary();
            this.__isset.value = true;
          } else { 
            TProtocolUtil.skip(iprot, field.type);
          }
          break;
        case TIMESTAMP:
          if (field.type == TType.I64) {
            this.timestamp = iprot.readI64();
            this.__isset.timestamp = true;
          } else { 
            TProtocolUtil.skip(iprot, field.type);
          }
          break;
        default:
          TProtocolUtil.skip(iprot, field.type);
          break;
      }
      iprot.readFieldEnd();
    }
    iprot.readStructEnd();


    // check for required fields of primitive type, which can't be checked in the validate method
    validate();
  }

  public void write(TProtocol oprot) throws TException {
    validate();

    oprot.writeStructBegin(STRUCT_DESC);
    if (this.columnName != null) {
      oprot.writeFieldBegin(COLUMN_NAME_FIELD_DESC);
      oprot.writeString(this.columnName);
      oprot.writeFieldEnd();
    }
    if (this.value != null) {
      oprot.writeFieldBegin(VALUE_FIELD_DESC);
      oprot.writeBinary(this.value);
      oprot.writeFieldEnd();
    }
    oprot.writeFieldBegin(TIMESTAMP_FIELD_DESC);
    oprot.writeI64(this.timestamp);
    oprot.writeFieldEnd();
    oprot.writeFieldStop();
    oprot.writeStructEnd();
  }

  @Override
  public String toString() {
    StringBuilder sb = new StringBuilder("column_t(");
    boolean first = true;

    if (!first) sb.append(", ");
    sb.append("columnName:");
    sb.append(this.columnName);
    first = false;
    if (!first) sb.append(", ");
    sb.append("value:");
    if (value == null) { 
      sb.append("null");
    } else {
      int __value_size = Math.min(this.value.length, 128);
      for (int i = 0; i < __value_size; i++) {
        if (i != 0) sb.append(" ");
        sb.append(Integer.toHexString(this.value[i]).length() > 1 ? Integer.toHexString(this.value[i]).substring(Integer.toHexString(this.value[i]).length() - 2).toUpperCase() : "0" + Integer.toHexString(this.value[i]).toUpperCase());
      }
      if (this.value.length > 128) sb.append(" ...");
    }
    first = false;
    if (!first) sb.append(", ");
    sb.append("timestamp:");
    sb.append(this.timestamp);
    first = false;
    sb.append(")");
    return sb.toString();
  }

  public void validate() throws TException {
    // check for required fields
    // check that fields of type enum have valid values
  }

}

