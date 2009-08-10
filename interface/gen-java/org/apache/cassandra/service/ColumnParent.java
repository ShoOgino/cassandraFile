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
import org.apache.log4j.Logger;

import org.apache.thrift.*;
import org.apache.thrift.meta_data.*;
import org.apache.thrift.protocol.*;

public class ColumnParent implements TBase, java.io.Serializable, Cloneable {
  private static final TStruct STRUCT_DESC = new TStruct("ColumnParent");
  private static final TField COLUMN_FAMILY_FIELD_DESC = new TField("column_family", TType.STRING, (short)3);
  private static final TField SUPER_COLUMN_FIELD_DESC = new TField("super_column", TType.STRING, (short)4);

  public String column_family;
  public static final int COLUMN_FAMILY = 3;
  public byte[] super_column;
  public static final int SUPER_COLUMN = 4;

  private final Isset __isset = new Isset();
  private static final class Isset implements java.io.Serializable {
  }

  public static final Map<Integer, FieldMetaData> metaDataMap = Collections.unmodifiableMap(new HashMap<Integer, FieldMetaData>() {{
    put(COLUMN_FAMILY, new FieldMetaData("column_family", TFieldRequirementType.DEFAULT, 
        new FieldValueMetaData(TType.STRING)));
    put(SUPER_COLUMN, new FieldMetaData("super_column", TFieldRequirementType.OPTIONAL, 
        new FieldValueMetaData(TType.STRING)));
  }});

  static {
    FieldMetaData.addStructMetaDataMap(ColumnParent.class, metaDataMap);
  }

  public ColumnParent() {
  }

  public ColumnParent(
    String column_family,
    byte[] super_column)
  {
    this();
    this.column_family = column_family;
    this.super_column = super_column;
  }

  /**
   * Performs a deep copy on <i>other</i>.
   */
  public ColumnParent(ColumnParent other) {
    if (other.isSetColumn_family()) {
      this.column_family = other.column_family;
    }
    if (other.isSetSuper_column()) {
      this.super_column = new byte[other.super_column.length];
      System.arraycopy(other.super_column, 0, super_column, 0, other.super_column.length);
    }
  }

  @Override
  public ColumnParent clone() {
    return new ColumnParent(this);
  }

  public String getColumn_family() {
    return this.column_family;
  }

  public ColumnParent setColumn_family(String column_family) {
    this.column_family = column_family;
    return this;
  }

  public void unsetColumn_family() {
    this.column_family = null;
  }

  // Returns true if field column_family is set (has been asigned a value) and false otherwise
  public boolean isSetColumn_family() {
    return this.column_family != null;
  }

  public void setColumn_familyIsSet(boolean value) {
    if (!value) {
      this.column_family = null;
    }
  }

  public byte[] getSuper_column() {
    return this.super_column;
  }

  public ColumnParent setSuper_column(byte[] super_column) {
    this.super_column = super_column;
    return this;
  }

  public void unsetSuper_column() {
    this.super_column = null;
  }

  // Returns true if field super_column is set (has been asigned a value) and false otherwise
  public boolean isSetSuper_column() {
    return this.super_column != null;
  }

  public void setSuper_columnIsSet(boolean value) {
    if (!value) {
      this.super_column = null;
    }
  }

  public void setFieldValue(int fieldID, Object value) {
    switch (fieldID) {
    case COLUMN_FAMILY:
      if (value == null) {
        unsetColumn_family();
      } else {
        setColumn_family((String)value);
      }
      break;

    case SUPER_COLUMN:
      if (value == null) {
        unsetSuper_column();
      } else {
        setSuper_column((byte[])value);
      }
      break;

    default:
      throw new IllegalArgumentException("Field " + fieldID + " doesn't exist!");
    }
  }

  public Object getFieldValue(int fieldID) {
    switch (fieldID) {
    case COLUMN_FAMILY:
      return getColumn_family();

    case SUPER_COLUMN:
      return getSuper_column();

    default:
      throw new IllegalArgumentException("Field " + fieldID + " doesn't exist!");
    }
  }

  // Returns true if field corresponding to fieldID is set (has been asigned a value) and false otherwise
  public boolean isSet(int fieldID) {
    switch (fieldID) {
    case COLUMN_FAMILY:
      return isSetColumn_family();
    case SUPER_COLUMN:
      return isSetSuper_column();
    default:
      throw new IllegalArgumentException("Field " + fieldID + " doesn't exist!");
    }
  }

  @Override
  public boolean equals(Object that) {
    if (that == null)
      return false;
    if (that instanceof ColumnParent)
      return this.equals((ColumnParent)that);
    return false;
  }

  public boolean equals(ColumnParent that) {
    if (that == null)
      return false;

    boolean this_present_column_family = true && this.isSetColumn_family();
    boolean that_present_column_family = true && that.isSetColumn_family();
    if (this_present_column_family || that_present_column_family) {
      if (!(this_present_column_family && that_present_column_family))
        return false;
      if (!this.column_family.equals(that.column_family))
        return false;
    }

    boolean this_present_super_column = true && this.isSetSuper_column();
    boolean that_present_super_column = true && that.isSetSuper_column();
    if (this_present_super_column || that_present_super_column) {
      if (!(this_present_super_column && that_present_super_column))
        return false;
      if (!java.util.Arrays.equals(this.super_column, that.super_column))
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
        case COLUMN_FAMILY:
          if (field.type == TType.STRING) {
            this.column_family = iprot.readString();
          } else { 
            TProtocolUtil.skip(iprot, field.type);
          }
          break;
        case SUPER_COLUMN:
          if (field.type == TType.STRING) {
            this.super_column = iprot.readBinary();
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
    if (this.column_family != null) {
      oprot.writeFieldBegin(COLUMN_FAMILY_FIELD_DESC);
      oprot.writeString(this.column_family);
      oprot.writeFieldEnd();
    }
    if (this.super_column != null) {
      if (isSetSuper_column()) {
        oprot.writeFieldBegin(SUPER_COLUMN_FIELD_DESC);
        oprot.writeBinary(this.super_column);
        oprot.writeFieldEnd();
      }
    }
    oprot.writeFieldStop();
    oprot.writeStructEnd();
  }

  @Override
  public String toString() {
    StringBuilder sb = new StringBuilder("ColumnParent(");
    boolean first = true;

    sb.append("column_family:");
    if (this.column_family == null) {
      sb.append("null");
    } else {
      sb.append(this.column_family);
    }
    first = false;
    if (isSetSuper_column()) {
      if (!first) sb.append(", ");
      sb.append("super_column:");
      if (this.super_column == null) {
        sb.append("null");
      } else {
          int __super_column_size = Math.min(this.super_column.length, 128);
          for (int i = 0; i < __super_column_size; i++) {
            if (i != 0) sb.append(" ");
            sb.append(Integer.toHexString(this.super_column[i]).length() > 1 ? Integer.toHexString(this.super_column[i]).substring(Integer.toHexString(this.super_column[i]).length() - 2).toUpperCase() : "0" + Integer.toHexString(this.super_column[i]).toUpperCase());
          }
          if (this.super_column.length > 128) sb.append(" ...");
      }
      first = false;
    }
    sb.append(")");
    return sb.toString();
  }

  public void validate() throws TException {
    // check for required fields
    // check that fields of type enum have valid values
  }

}

