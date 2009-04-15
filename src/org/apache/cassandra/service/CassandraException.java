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

public class CassandraException extends Exception implements TBase, java.io.Serializable, Cloneable {
  private static final TStruct STRUCT_DESC = new TStruct("CassandraException");
  private static final TField ERROR_FIELD_DESC = new TField("error", TType.STRING, (short)1);

  public String error;
  public static final int ERROR = 1;

  private final Isset __isset = new Isset();
  private static final class Isset implements java.io.Serializable {
    public boolean error = false;
  }

  public static final Map<Integer, FieldMetaData> metaDataMap = Collections.unmodifiableMap(new HashMap<Integer, FieldMetaData>() {{
    put(ERROR, new FieldMetaData("error", TFieldRequirementType.DEFAULT, 
        new FieldValueMetaData(TType.STRING)));
  }});

  static {
    FieldMetaData.addStructMetaDataMap(CassandraException.class, metaDataMap);
  }

  public CassandraException() {
  }

  public CassandraException(
    String error)
  {
    this();
    this.error = error;
    this.__isset.error = (error != null);
  }

  /**
   * Performs a deep copy on <i>other</i>.
   */
  public CassandraException(CassandraException other) {
    __isset.error = other.__isset.error;
    if (other.error != null) {
      this.error = other.error;
    }
  }

  @Override
  public CassandraException clone() {
    return new CassandraException(this);
  }

  public String getError() {
    return this.error;
  }

  public void setError(String error) {
    this.error = error;
    this.__isset.error = (error != null);
  }

  public void unsetError() {
    this.__isset.error = false;
  }

  // Returns true if field error is set (has been asigned a value) and false otherwise
  public boolean isSetError() {
    return this.__isset.error;
  }

  public void setErrorIsSet(boolean value) {
    this.__isset.error = value;
  }

  public void setFieldValue(int fieldID, Object value) {
    switch (fieldID) {
    case ERROR:
      setError((String)value);
      break;

    default:
      throw new IllegalArgumentException("Field " + fieldID + " doesn't exist!");
    }
  }

  public Object getFieldValue(int fieldID) {
    switch (fieldID) {
    case ERROR:
      return getError();

    default:
      throw new IllegalArgumentException("Field " + fieldID + " doesn't exist!");
    }
  }

  // Returns true if field corresponding to fieldID is set (has been asigned a value) and false otherwise
  public boolean isSet(int fieldID) {
    switch (fieldID) {
    case ERROR:
      return this.__isset.error;
    default:
      throw new IllegalArgumentException("Field " + fieldID + " doesn't exist!");
    }
  }

  @Override
  public boolean equals(Object that) {
    if (that == null)
      return false;
    if (that instanceof CassandraException)
      return this.equals((CassandraException)that);
    return false;
  }

  public boolean equals(CassandraException that) {
    if (that == null)
      return false;

    boolean this_present_error = true && (this.error != null);
    boolean that_present_error = true && (that.error != null);
    if (this_present_error || that_present_error) {
      if (!(this_present_error && that_present_error))
        return false;
      if (!this.error.equals(that.error))
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
        case ERROR:
          if (field.type == TType.STRING) {
            this.error = iprot.readString();
            this.__isset.error = true;
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
    if (this.error != null) {
      oprot.writeFieldBegin(ERROR_FIELD_DESC);
      oprot.writeString(this.error);
      oprot.writeFieldEnd();
    }
    oprot.writeFieldStop();
    oprot.writeStructEnd();
  }

  @Override
  public String toString() {
    StringBuilder sb = new StringBuilder("CassandraException(");
    boolean first = true;

    if (!first) sb.append(", ");
    sb.append("error:");
    sb.append(this.error);
    first = false;
    sb.append(")");
    return sb.toString();
  }

  public void validate() throws TException {
    // check for required fields
    // check that fields of type enum have valid values
  }

}

