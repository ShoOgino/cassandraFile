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

public class InvalidRequestException extends Exception implements TBase, java.io.Serializable, Cloneable {
  private static final TStruct STRUCT_DESC = new TStruct("InvalidRequestException");
  private static final TField WHY_FIELD_DESC = new TField("why", TType.STRING, (short)1);

  public String why;
  public static final int WHY = 1;

  private final Isset __isset = new Isset();
  private static final class Isset implements java.io.Serializable {
  }

  public static final Map<Integer, FieldMetaData> metaDataMap = Collections.unmodifiableMap(new HashMap<Integer, FieldMetaData>() {{
    put(WHY, new FieldMetaData("why", TFieldRequirementType.DEFAULT, 
        new FieldValueMetaData(TType.STRING)));
  }});

  static {
    FieldMetaData.addStructMetaDataMap(InvalidRequestException.class, metaDataMap);
  }

  public InvalidRequestException() {
  }

  public InvalidRequestException(
    String why)
  {
    this();
    this.why = why;
  }

  /**
   * Performs a deep copy on <i>other</i>.
   */
  public InvalidRequestException(InvalidRequestException other) {
    if (other.isSetWhy()) {
      this.why = other.why;
    }
  }

  @Override
  public InvalidRequestException clone() {
    return new InvalidRequestException(this);
  }

  public String getWhy() {
    return this.why;
  }

  public void setWhy(String why) {
    this.why = why;
  }

  public void unsetWhy() {
    this.why = null;
  }

  // Returns true if field why is set (has been asigned a value) and false otherwise
  public boolean isSetWhy() {
    return this.why != null;
  }

  public void setWhyIsSet(boolean value) {
    if (!value) {
      this.why = null;
    }
  }

  public void setFieldValue(int fieldID, Object value) {
    switch (fieldID) {
    case WHY:
      if (value == null) {
        unsetWhy();
      } else {
        setWhy((String)value);
      }
      break;

    default:
      throw new IllegalArgumentException("Field " + fieldID + " doesn't exist!");
    }
  }

  public Object getFieldValue(int fieldID) {
    switch (fieldID) {
    case WHY:
      return getWhy();

    default:
      throw new IllegalArgumentException("Field " + fieldID + " doesn't exist!");
    }
  }

  // Returns true if field corresponding to fieldID is set (has been asigned a value) and false otherwise
  public boolean isSet(int fieldID) {
    switch (fieldID) {
    case WHY:
      return isSetWhy();
    default:
      throw new IllegalArgumentException("Field " + fieldID + " doesn't exist!");
    }
  }

  @Override
  public boolean equals(Object that) {
    if (that == null)
      return false;
    if (that instanceof InvalidRequestException)
      return this.equals((InvalidRequestException)that);
    return false;
  }

  public boolean equals(InvalidRequestException that) {
    if (that == null)
      return false;

    boolean this_present_why = true && this.isSetWhy();
    boolean that_present_why = true && that.isSetWhy();
    if (this_present_why || that_present_why) {
      if (!(this_present_why && that_present_why))
        return false;
      if (!this.why.equals(that.why))
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
        case WHY:
          if (field.type == TType.STRING) {
            this.why = iprot.readString();
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
    if (this.why != null) {
      oprot.writeFieldBegin(WHY_FIELD_DESC);
      oprot.writeString(this.why);
      oprot.writeFieldEnd();
    }
    oprot.writeFieldStop();
    oprot.writeStructEnd();
  }

  @Override
  public String toString() {
    StringBuilder sb = new StringBuilder("InvalidRequestException(");
    boolean first = true;

    sb.append("why:");
    if (this.why == null) {
      sb.append("null");
    } else {
      sb.append(this.why);
    }
    first = false;
    sb.append(")");
    return sb.toString();
  }

  public void validate() throws TException {
    // check for required fields
    // check that fields of type enum have valid values
  }

}

