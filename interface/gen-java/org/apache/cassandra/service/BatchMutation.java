/**
 * Autogenerated by Thrift
 *
 * DO NOT EDIT UNLESS YOU ARE SURE THAT YOU KNOW WHAT YOU ARE DOING
 */
package org.apache.cassandra.service;
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

public class BatchMutation implements TBase, java.io.Serializable, Cloneable {
  private static final TStruct STRUCT_DESC = new TStruct("BatchMutation");
  private static final TField KEY_FIELD_DESC = new TField("key", TType.STRING, (short)1);
  private static final TField CFMAP_FIELD_DESC = new TField("cfmap", TType.MAP, (short)2);

  public String key;
  public static final int KEY = 1;
  public Map<String,List<ColumnOrSuperColumn>> cfmap;
  public static final int CFMAP = 2;

  private final Isset __isset = new Isset();
  private static final class Isset implements java.io.Serializable {
  }

  public static final Map<Integer, FieldMetaData> metaDataMap = Collections.unmodifiableMap(new HashMap<Integer, FieldMetaData>() {{
    put(KEY, new FieldMetaData("key", TFieldRequirementType.DEFAULT, 
        new FieldValueMetaData(TType.STRING)));
    put(CFMAP, new FieldMetaData("cfmap", TFieldRequirementType.DEFAULT, 
        new FieldValueMetaData(TType.MAP)));
  }});

  static {
    FieldMetaData.addStructMetaDataMap(BatchMutation.class, metaDataMap);
  }

  public BatchMutation() {
  }

  public BatchMutation(
    String key,
    Map<String,List<ColumnOrSuperColumn>> cfmap)
  {
    this();
    this.key = key;
    this.cfmap = cfmap;
  }

  /**
   * Performs a deep copy on <i>other</i>.
   */
  public BatchMutation(BatchMutation other) {
    if (other.isSetKey()) {
      this.key = other.key;
    }
    if (other.isSetCfmap()) {
      this.cfmap = other.cfmap;
    }
  }

  @Override
  public BatchMutation clone() {
    return new BatchMutation(this);
  }

  public String getKey() {
    return this.key;
  }

  public BatchMutation setKey(String key) {
    this.key = key;
    return this;
  }

  public void unsetKey() {
    this.key = null;
  }

  // Returns true if field key is set (has been asigned a value) and false otherwise
  public boolean isSetKey() {
    return this.key != null;
  }

  public void setKeyIsSet(boolean value) {
    if (!value) {
      this.key = null;
    }
  }

  public Map<String,List<ColumnOrSuperColumn>> getCfmap() {
    return this.cfmap;
  }

  public BatchMutation setCfmap(Map<String,List<ColumnOrSuperColumn>> cfmap) {
    this.cfmap = cfmap;
    return this;
  }

  public void unsetCfmap() {
    this.cfmap = null;
  }

  // Returns true if field cfmap is set (has been asigned a value) and false otherwise
  public boolean isSetCfmap() {
    return this.cfmap != null;
  }

  public void setCfmapIsSet(boolean value) {
    if (!value) {
      this.cfmap = null;
    }
  }

  public void setFieldValue(int fieldID, Object value) {
    switch (fieldID) {
    case KEY:
      if (value == null) {
        unsetKey();
      } else {
        setKey((String)value);
      }
      break;

    case CFMAP:
      if (value == null) {
        unsetCfmap();
      } else {
        setCfmap((Map<String,List<ColumnOrSuperColumn>>)value);
      }
      break;

    default:
      throw new IllegalArgumentException("Field " + fieldID + " doesn't exist!");
    }
  }

  public Object getFieldValue(int fieldID) {
    switch (fieldID) {
    case KEY:
      return getKey();

    case CFMAP:
      return getCfmap();

    default:
      throw new IllegalArgumentException("Field " + fieldID + " doesn't exist!");
    }
  }

  // Returns true if field corresponding to fieldID is set (has been asigned a value) and false otherwise
  public boolean isSet(int fieldID) {
    switch (fieldID) {
    case KEY:
      return isSetKey();
    case CFMAP:
      return isSetCfmap();
    default:
      throw new IllegalArgumentException("Field " + fieldID + " doesn't exist!");
    }
  }

  @Override
  public boolean equals(Object that) {
    if (that == null)
      return false;
    if (that instanceof BatchMutation)
      return this.equals((BatchMutation)that);
    return false;
  }

  public boolean equals(BatchMutation that) {
    if (that == null)
      return false;

    boolean this_present_key = true && this.isSetKey();
    boolean that_present_key = true && that.isSetKey();
    if (this_present_key || that_present_key) {
      if (!(this_present_key && that_present_key))
        return false;
      if (!this.key.equals(that.key))
        return false;
    }

    boolean this_present_cfmap = true && this.isSetCfmap();
    boolean that_present_cfmap = true && that.isSetCfmap();
    if (this_present_cfmap || that_present_cfmap) {
      if (!(this_present_cfmap && that_present_cfmap))
        return false;
      if (!this.cfmap.equals(that.cfmap))
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
        case KEY:
          if (field.type == TType.STRING) {
            this.key = iprot.readString();
          } else { 
            TProtocolUtil.skip(iprot, field.type);
          }
          break;
        case CFMAP:
          if (field.type == TType.MAP) {
            {
              TMap _map8 = iprot.readMapBegin();
              this.cfmap = new HashMap<String,List<ColumnOrSuperColumn>>(2*_map8.size);
              for (int _i9 = 0; _i9 < _map8.size; ++_i9)
              {
                String _key10;
                List<ColumnOrSuperColumn> _val11;
                _key10 = iprot.readString();
                {
                  TList _list12 = iprot.readListBegin();
                  _val11 = new ArrayList<ColumnOrSuperColumn>(_list12.size);
                  for (int _i13 = 0; _i13 < _list12.size; ++_i13)
                  {
                    ColumnOrSuperColumn _elem14;
                    _elem14 = new ColumnOrSuperColumn();
                    _elem14.read(iprot);
                    _val11.add(_elem14);
                  }
                  iprot.readListEnd();
                }
                this.cfmap.put(_key10, _val11);
              }
              iprot.readMapEnd();
            }
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
    if (this.key != null) {
      oprot.writeFieldBegin(KEY_FIELD_DESC);
      oprot.writeString(this.key);
      oprot.writeFieldEnd();
    }
    if (this.cfmap != null) {
      oprot.writeFieldBegin(CFMAP_FIELD_DESC);
      {
        oprot.writeMapBegin(new TMap(TType.STRING, TType.LIST, this.cfmap.size()));
        for (Map.Entry<String, List<ColumnOrSuperColumn>> _iter15 : this.cfmap.entrySet())        {
          oprot.writeString(_iter15.getKey());
          {
            oprot.writeListBegin(new TList(TType.STRUCT, _iter15.getValue().size()));
            for (ColumnOrSuperColumn _iter16 : _iter15.getValue())            {
              _iter16.write(oprot);
            }
            oprot.writeListEnd();
          }
        }
        oprot.writeMapEnd();
      }
      oprot.writeFieldEnd();
    }
    oprot.writeFieldStop();
    oprot.writeStructEnd();
  }

  @Override
  public String toString() {
    StringBuilder sb = new StringBuilder("BatchMutation(");
    boolean first = true;

    sb.append("key:");
    if (this.key == null) {
      sb.append("null");
    } else {
      sb.append(this.key);
    }
    first = false;
    if (!first) sb.append(", ");
    sb.append("cfmap:");
    if (this.cfmap == null) {
      sb.append("null");
    } else {
      sb.append(this.cfmap);
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

