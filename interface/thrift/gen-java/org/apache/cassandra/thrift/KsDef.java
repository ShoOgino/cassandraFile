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
import java.util.Arrays;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.apache.thrift.*;
import org.apache.thrift.async.*;
import org.apache.thrift.meta_data.*;
import org.apache.thrift.transport.*;
import org.apache.thrift.protocol.*;

public class KsDef implements TBase<KsDef, KsDef._Fields>, java.io.Serializable, Cloneable {
  private static final TStruct STRUCT_DESC = new TStruct("KsDef");

  private static final TField NAME_FIELD_DESC = new TField("name", TType.STRING, (short)1);
  private static final TField STRATEGY_CLASS_FIELD_DESC = new TField("strategy_class", TType.STRING, (short)2);
  private static final TField REPLICATION_FACTOR_FIELD_DESC = new TField("replication_factor", TType.I32, (short)3);
  private static final TField CF_DEFS_FIELD_DESC = new TField("cf_defs", TType.LIST, (short)5);
  private static final TField USERS_ACCESS_FIELD_DESC = new TField("users_access", TType.MAP, (short)6);
  private static final TField GROUPS_ACCESS_FIELD_DESC = new TField("groups_access", TType.MAP, (short)7);

  public String name;
  public String strategy_class;
  public int replication_factor;
  public List<CfDef> cf_defs;
  public Map<String,AccessLevel> users_access;
  public Map<String,AccessLevel> groups_access;

  /** The set of fields this struct contains, along with convenience methods for finding and manipulating them. */
  public enum _Fields implements TFieldIdEnum {
    NAME((short)1, "name"),
    STRATEGY_CLASS((short)2, "strategy_class"),
    REPLICATION_FACTOR((short)3, "replication_factor"),
    CF_DEFS((short)5, "cf_defs"),
    USERS_ACCESS((short)6, "users_access"),
    GROUPS_ACCESS((short)7, "groups_access");

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
        case 1: // NAME
          return NAME;
        case 2: // STRATEGY_CLASS
          return STRATEGY_CLASS;
        case 3: // REPLICATION_FACTOR
          return REPLICATION_FACTOR;
        case 5: // CF_DEFS
          return CF_DEFS;
        case 6: // USERS_ACCESS
          return USERS_ACCESS;
        case 7: // GROUPS_ACCESS
          return GROUPS_ACCESS;
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
  private static final int __REPLICATION_FACTOR_ISSET_ID = 0;
  private BitSet __isset_bit_vector = new BitSet(1);

  public static final Map<_Fields, FieldMetaData> metaDataMap;
  static {
    Map<_Fields, FieldMetaData> tmpMap = new EnumMap<_Fields, FieldMetaData>(_Fields.class);
    tmpMap.put(_Fields.NAME, new FieldMetaData("name", TFieldRequirementType.REQUIRED, 
        new FieldValueMetaData(TType.STRING)));
    tmpMap.put(_Fields.STRATEGY_CLASS, new FieldMetaData("strategy_class", TFieldRequirementType.REQUIRED, 
        new FieldValueMetaData(TType.STRING)));
    tmpMap.put(_Fields.REPLICATION_FACTOR, new FieldMetaData("replication_factor", TFieldRequirementType.REQUIRED, 
        new FieldValueMetaData(TType.I32)));
    tmpMap.put(_Fields.CF_DEFS, new FieldMetaData("cf_defs", TFieldRequirementType.REQUIRED, 
        new ListMetaData(TType.LIST, 
            new StructMetaData(TType.STRUCT, CfDef.class))));
    tmpMap.put(_Fields.USERS_ACCESS, new FieldMetaData("users_access", TFieldRequirementType.OPTIONAL, 
        new MapMetaData(TType.MAP, 
            new FieldValueMetaData(TType.STRING), 
            new EnumMetaData(TType.ENUM, AccessLevel.class))));
    tmpMap.put(_Fields.GROUPS_ACCESS, new FieldMetaData("groups_access", TFieldRequirementType.OPTIONAL, 
        new MapMetaData(TType.MAP, 
            new FieldValueMetaData(TType.STRING), 
            new EnumMetaData(TType.ENUM, AccessLevel.class))));
    metaDataMap = Collections.unmodifiableMap(tmpMap);
    FieldMetaData.addStructMetaDataMap(KsDef.class, metaDataMap);
  }

  public KsDef() {
  }

  public KsDef(
    String name,
    String strategy_class,
    int replication_factor,
    List<CfDef> cf_defs)
  {
    this();
    this.name = name;
    this.strategy_class = strategy_class;
    this.replication_factor = replication_factor;
    setReplication_factorIsSet(true);
    this.cf_defs = cf_defs;
  }

  /**
   * Performs a deep copy on <i>other</i>.
   */
  public KsDef(KsDef other) {
    __isset_bit_vector.clear();
    __isset_bit_vector.or(other.__isset_bit_vector);
    if (other.isSetName()) {
      this.name = other.name;
    }
    if (other.isSetStrategy_class()) {
      this.strategy_class = other.strategy_class;
    }
    this.replication_factor = other.replication_factor;
    if (other.isSetCf_defs()) {
      List<CfDef> __this__cf_defs = new ArrayList<CfDef>();
      for (CfDef other_element : other.cf_defs) {
        __this__cf_defs.add(new CfDef(other_element));
      }
      this.cf_defs = __this__cf_defs;
    }
    if (other.isSetUsers_access()) {
      Map<String,AccessLevel> __this__users_access = new HashMap<String,AccessLevel>();
      for (Map.Entry<String, AccessLevel> other_element : other.users_access.entrySet()) {

        String other_element_key = other_element.getKey();
        AccessLevel other_element_value = other_element.getValue();

        String __this__users_access_copy_key = other_element_key;

        AccessLevel __this__users_access_copy_value = other_element_value;

        __this__users_access.put(__this__users_access_copy_key, __this__users_access_copy_value);
      }
      this.users_access = __this__users_access;
    }
    if (other.isSetGroups_access()) {
      Map<String,AccessLevel> __this__groups_access = new HashMap<String,AccessLevel>();
      for (Map.Entry<String, AccessLevel> other_element : other.groups_access.entrySet()) {

        String other_element_key = other_element.getKey();
        AccessLevel other_element_value = other_element.getValue();

        String __this__groups_access_copy_key = other_element_key;

        AccessLevel __this__groups_access_copy_value = other_element_value;

        __this__groups_access.put(__this__groups_access_copy_key, __this__groups_access_copy_value);
      }
      this.groups_access = __this__groups_access;
    }
  }

  public KsDef deepCopy() {
    return new KsDef(this);
  }

  @Deprecated
  public KsDef clone() {
    return new KsDef(this);
  }

  public String getName() {
    return this.name;
  }

  public KsDef setName(String name) {
    this.name = name;
    return this;
  }

  public void unsetName() {
    this.name = null;
  }

  /** Returns true if field name is set (has been asigned a value) and false otherwise */
  public boolean isSetName() {
    return this.name != null;
  }

  public void setNameIsSet(boolean value) {
    if (!value) {
      this.name = null;
    }
  }

  public String getStrategy_class() {
    return this.strategy_class;
  }

  public KsDef setStrategy_class(String strategy_class) {
    this.strategy_class = strategy_class;
    return this;
  }

  public void unsetStrategy_class() {
    this.strategy_class = null;
  }

  /** Returns true if field strategy_class is set (has been asigned a value) and false otherwise */
  public boolean isSetStrategy_class() {
    return this.strategy_class != null;
  }

  public void setStrategy_classIsSet(boolean value) {
    if (!value) {
      this.strategy_class = null;
    }
  }

  public int getReplication_factor() {
    return this.replication_factor;
  }

  public KsDef setReplication_factor(int replication_factor) {
    this.replication_factor = replication_factor;
    setReplication_factorIsSet(true);
    return this;
  }

  public void unsetReplication_factor() {
    __isset_bit_vector.clear(__REPLICATION_FACTOR_ISSET_ID);
  }

  /** Returns true if field replication_factor is set (has been asigned a value) and false otherwise */
  public boolean isSetReplication_factor() {
    return __isset_bit_vector.get(__REPLICATION_FACTOR_ISSET_ID);
  }

  public void setReplication_factorIsSet(boolean value) {
    __isset_bit_vector.set(__REPLICATION_FACTOR_ISSET_ID, value);
  }

  public int getCf_defsSize() {
    return (this.cf_defs == null) ? 0 : this.cf_defs.size();
  }

  public java.util.Iterator<CfDef> getCf_defsIterator() {
    return (this.cf_defs == null) ? null : this.cf_defs.iterator();
  }

  public void addToCf_defs(CfDef elem) {
    if (this.cf_defs == null) {
      this.cf_defs = new ArrayList<CfDef>();
    }
    this.cf_defs.add(elem);
  }

  public List<CfDef> getCf_defs() {
    return this.cf_defs;
  }

  public KsDef setCf_defs(List<CfDef> cf_defs) {
    this.cf_defs = cf_defs;
    return this;
  }

  public void unsetCf_defs() {
    this.cf_defs = null;
  }

  /** Returns true if field cf_defs is set (has been asigned a value) and false otherwise */
  public boolean isSetCf_defs() {
    return this.cf_defs != null;
  }

  public void setCf_defsIsSet(boolean value) {
    if (!value) {
      this.cf_defs = null;
    }
  }

  public int getUsers_accessSize() {
    return (this.users_access == null) ? 0 : this.users_access.size();
  }

  public void putToUsers_access(String key, AccessLevel val) {
    if (this.users_access == null) {
      this.users_access = new HashMap<String,AccessLevel>();
    }
    this.users_access.put(key, val);
  }

  public Map<String,AccessLevel> getUsers_access() {
    return this.users_access;
  }

  public KsDef setUsers_access(Map<String,AccessLevel> users_access) {
    this.users_access = users_access;
    return this;
  }

  public void unsetUsers_access() {
    this.users_access = null;
  }

  /** Returns true if field users_access is set (has been asigned a value) and false otherwise */
  public boolean isSetUsers_access() {
    return this.users_access != null;
  }

  public void setUsers_accessIsSet(boolean value) {
    if (!value) {
      this.users_access = null;
    }
  }

  public int getGroups_accessSize() {
    return (this.groups_access == null) ? 0 : this.groups_access.size();
  }

  public void putToGroups_access(String key, AccessLevel val) {
    if (this.groups_access == null) {
      this.groups_access = new HashMap<String,AccessLevel>();
    }
    this.groups_access.put(key, val);
  }

  public Map<String,AccessLevel> getGroups_access() {
    return this.groups_access;
  }

  public KsDef setGroups_access(Map<String,AccessLevel> groups_access) {
    this.groups_access = groups_access;
    return this;
  }

  public void unsetGroups_access() {
    this.groups_access = null;
  }

  /** Returns true if field groups_access is set (has been asigned a value) and false otherwise */
  public boolean isSetGroups_access() {
    return this.groups_access != null;
  }

  public void setGroups_accessIsSet(boolean value) {
    if (!value) {
      this.groups_access = null;
    }
  }

  public void setFieldValue(_Fields field, Object value) {
    switch (field) {
    case NAME:
      if (value == null) {
        unsetName();
      } else {
        setName((String)value);
      }
      break;

    case STRATEGY_CLASS:
      if (value == null) {
        unsetStrategy_class();
      } else {
        setStrategy_class((String)value);
      }
      break;

    case REPLICATION_FACTOR:
      if (value == null) {
        unsetReplication_factor();
      } else {
        setReplication_factor((Integer)value);
      }
      break;

    case CF_DEFS:
      if (value == null) {
        unsetCf_defs();
      } else {
        setCf_defs((List<CfDef>)value);
      }
      break;

    case USERS_ACCESS:
      if (value == null) {
        unsetUsers_access();
      } else {
        setUsers_access((Map<String,AccessLevel>)value);
      }
      break;

    case GROUPS_ACCESS:
      if (value == null) {
        unsetGroups_access();
      } else {
        setGroups_access((Map<String,AccessLevel>)value);
      }
      break;

    }
  }

  public void setFieldValue(int fieldID, Object value) {
    setFieldValue(_Fields.findByThriftIdOrThrow(fieldID), value);
  }

  public Object getFieldValue(_Fields field) {
    switch (field) {
    case NAME:
      return getName();

    case STRATEGY_CLASS:
      return getStrategy_class();

    case REPLICATION_FACTOR:
      return new Integer(getReplication_factor());

    case CF_DEFS:
      return getCf_defs();

    case USERS_ACCESS:
      return getUsers_access();

    case GROUPS_ACCESS:
      return getGroups_access();

    }
    throw new IllegalStateException();
  }

  public Object getFieldValue(int fieldId) {
    return getFieldValue(_Fields.findByThriftIdOrThrow(fieldId));
  }

  /** Returns true if field corresponding to fieldID is set (has been asigned a value) and false otherwise */
  public boolean isSet(_Fields field) {
    switch (field) {
    case NAME:
      return isSetName();
    case STRATEGY_CLASS:
      return isSetStrategy_class();
    case REPLICATION_FACTOR:
      return isSetReplication_factor();
    case CF_DEFS:
      return isSetCf_defs();
    case USERS_ACCESS:
      return isSetUsers_access();
    case GROUPS_ACCESS:
      return isSetGroups_access();
    }
    throw new IllegalStateException();
  }

  public boolean isSet(int fieldID) {
    return isSet(_Fields.findByThriftIdOrThrow(fieldID));
  }

  @Override
  public boolean equals(Object that) {
    if (that == null)
      return false;
    if (that instanceof KsDef)
      return this.equals((KsDef)that);
    return false;
  }

  public boolean equals(KsDef that) {
    if (that == null)
      return false;

    boolean this_present_name = true && this.isSetName();
    boolean that_present_name = true && that.isSetName();
    if (this_present_name || that_present_name) {
      if (!(this_present_name && that_present_name))
        return false;
      if (!this.name.equals(that.name))
        return false;
    }

    boolean this_present_strategy_class = true && this.isSetStrategy_class();
    boolean that_present_strategy_class = true && that.isSetStrategy_class();
    if (this_present_strategy_class || that_present_strategy_class) {
      if (!(this_present_strategy_class && that_present_strategy_class))
        return false;
      if (!this.strategy_class.equals(that.strategy_class))
        return false;
    }

    boolean this_present_replication_factor = true;
    boolean that_present_replication_factor = true;
    if (this_present_replication_factor || that_present_replication_factor) {
      if (!(this_present_replication_factor && that_present_replication_factor))
        return false;
      if (this.replication_factor != that.replication_factor)
        return false;
    }

    boolean this_present_cf_defs = true && this.isSetCf_defs();
    boolean that_present_cf_defs = true && that.isSetCf_defs();
    if (this_present_cf_defs || that_present_cf_defs) {
      if (!(this_present_cf_defs && that_present_cf_defs))
        return false;
      if (!this.cf_defs.equals(that.cf_defs))
        return false;
    }

    boolean this_present_users_access = true && this.isSetUsers_access();
    boolean that_present_users_access = true && that.isSetUsers_access();
    if (this_present_users_access || that_present_users_access) {
      if (!(this_present_users_access && that_present_users_access))
        return false;
      if (!this.users_access.equals(that.users_access))
        return false;
    }

    boolean this_present_groups_access = true && this.isSetGroups_access();
    boolean that_present_groups_access = true && that.isSetGroups_access();
    if (this_present_groups_access || that_present_groups_access) {
      if (!(this_present_groups_access && that_present_groups_access))
        return false;
      if (!this.groups_access.equals(that.groups_access))
        return false;
    }

    return true;
  }

  @Override
  public int hashCode() {
    return 0;
  }

  public int compareTo(KsDef other) {
    if (!getClass().equals(other.getClass())) {
      return getClass().getName().compareTo(other.getClass().getName());
    }

    int lastComparison = 0;
    KsDef typedOther = (KsDef)other;

    lastComparison = Boolean.valueOf(isSetName()).compareTo(typedOther.isSetName());
    if (lastComparison != 0) {
      return lastComparison;
    }
    if (isSetName()) {      lastComparison = TBaseHelper.compareTo(this.name, typedOther.name);
      if (lastComparison != 0) {
        return lastComparison;
      }
    }
    lastComparison = Boolean.valueOf(isSetStrategy_class()).compareTo(typedOther.isSetStrategy_class());
    if (lastComparison != 0) {
      return lastComparison;
    }
    if (isSetStrategy_class()) {      lastComparison = TBaseHelper.compareTo(this.strategy_class, typedOther.strategy_class);
      if (lastComparison != 0) {
        return lastComparison;
      }
    }
    lastComparison = Boolean.valueOf(isSetReplication_factor()).compareTo(typedOther.isSetReplication_factor());
    if (lastComparison != 0) {
      return lastComparison;
    }
    if (isSetReplication_factor()) {      lastComparison = TBaseHelper.compareTo(this.replication_factor, typedOther.replication_factor);
      if (lastComparison != 0) {
        return lastComparison;
      }
    }
    lastComparison = Boolean.valueOf(isSetCf_defs()).compareTo(typedOther.isSetCf_defs());
    if (lastComparison != 0) {
      return lastComparison;
    }
    if (isSetCf_defs()) {      lastComparison = TBaseHelper.compareTo(this.cf_defs, typedOther.cf_defs);
      if (lastComparison != 0) {
        return lastComparison;
      }
    }
    lastComparison = Boolean.valueOf(isSetUsers_access()).compareTo(typedOther.isSetUsers_access());
    if (lastComparison != 0) {
      return lastComparison;
    }
    if (isSetUsers_access()) {      lastComparison = TBaseHelper.compareTo(this.users_access, typedOther.users_access);
      if (lastComparison != 0) {
        return lastComparison;
      }
    }
    lastComparison = Boolean.valueOf(isSetGroups_access()).compareTo(typedOther.isSetGroups_access());
    if (lastComparison != 0) {
      return lastComparison;
    }
    if (isSetGroups_access()) {      lastComparison = TBaseHelper.compareTo(this.groups_access, typedOther.groups_access);
      if (lastComparison != 0) {
        return lastComparison;
      }
    }
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
      switch (field.id) {
        case 1: // NAME
          if (field.type == TType.STRING) {
            this.name = iprot.readString();
          } else { 
            TProtocolUtil.skip(iprot, field.type);
          }
          break;
        case 2: // STRATEGY_CLASS
          if (field.type == TType.STRING) {
            this.strategy_class = iprot.readString();
          } else { 
            TProtocolUtil.skip(iprot, field.type);
          }
          break;
        case 3: // REPLICATION_FACTOR
          if (field.type == TType.I32) {
            this.replication_factor = iprot.readI32();
            setReplication_factorIsSet(true);
          } else { 
            TProtocolUtil.skip(iprot, field.type);
          }
          break;
        case 5: // CF_DEFS
          if (field.type == TType.LIST) {
            {
              TList _list33 = iprot.readListBegin();
              this.cf_defs = new ArrayList<CfDef>(_list33.size);
              for (int _i34 = 0; _i34 < _list33.size; ++_i34)
              {
                CfDef _elem35;
                _elem35 = new CfDef();
                _elem35.read(iprot);
                this.cf_defs.add(_elem35);
              }
              iprot.readListEnd();
            }
          } else { 
            TProtocolUtil.skip(iprot, field.type);
          }
          break;
        case 6: // USERS_ACCESS
          if (field.type == TType.MAP) {
            {
              TMap _map36 = iprot.readMapBegin();
              this.users_access = new HashMap<String,AccessLevel>(2*_map36.size);
              for (int _i37 = 0; _i37 < _map36.size; ++_i37)
              {
                String _key38;
                AccessLevel _val39;
                _key38 = iprot.readString();
                _val39 = AccessLevel.findByValue(iprot.readI32());
                this.users_access.put(_key38, _val39);
              }
              iprot.readMapEnd();
            }
          } else { 
            TProtocolUtil.skip(iprot, field.type);
          }
          break;
        case 7: // GROUPS_ACCESS
          if (field.type == TType.MAP) {
            {
              TMap _map40 = iprot.readMapBegin();
              this.groups_access = new HashMap<String,AccessLevel>(2*_map40.size);
              for (int _i41 = 0; _i41 < _map40.size; ++_i41)
              {
                String _key42;
                AccessLevel _val43;
                _key42 = iprot.readString();
                _val43 = AccessLevel.findByValue(iprot.readI32());
                this.groups_access.put(_key42, _val43);
              }
              iprot.readMapEnd();
            }
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
    if (!isSetReplication_factor()) {
      throw new TProtocolException("Required field 'replication_factor' was not found in serialized data! Struct: " + toString());
    }
    validate();
  }

  public void write(TProtocol oprot) throws TException {
    validate();

    oprot.writeStructBegin(STRUCT_DESC);
    if (this.name != null) {
      oprot.writeFieldBegin(NAME_FIELD_DESC);
      oprot.writeString(this.name);
      oprot.writeFieldEnd();
    }
    if (this.strategy_class != null) {
      oprot.writeFieldBegin(STRATEGY_CLASS_FIELD_DESC);
      oprot.writeString(this.strategy_class);
      oprot.writeFieldEnd();
    }
    oprot.writeFieldBegin(REPLICATION_FACTOR_FIELD_DESC);
    oprot.writeI32(this.replication_factor);
    oprot.writeFieldEnd();
    if (this.cf_defs != null) {
      oprot.writeFieldBegin(CF_DEFS_FIELD_DESC);
      {
        oprot.writeListBegin(new TList(TType.STRUCT, this.cf_defs.size()));
        for (CfDef _iter44 : this.cf_defs)
        {
          _iter44.write(oprot);
        }
        oprot.writeListEnd();
      }
      oprot.writeFieldEnd();
    }
    if (this.users_access != null) {
      if (isSetUsers_access()) {
        oprot.writeFieldBegin(USERS_ACCESS_FIELD_DESC);
        {
          oprot.writeMapBegin(new TMap(TType.STRING, TType.I32, this.users_access.size()));
          for (Map.Entry<String, AccessLevel> _iter45 : this.users_access.entrySet())
          {
            oprot.writeString(_iter45.getKey());
            oprot.writeI32(_iter45.getValue().getValue());
          }
          oprot.writeMapEnd();
        }
        oprot.writeFieldEnd();
      }
    }
    if (this.groups_access != null) {
      if (isSetGroups_access()) {
        oprot.writeFieldBegin(GROUPS_ACCESS_FIELD_DESC);
        {
          oprot.writeMapBegin(new TMap(TType.STRING, TType.I32, this.groups_access.size()));
          for (Map.Entry<String, AccessLevel> _iter46 : this.groups_access.entrySet())
          {
            oprot.writeString(_iter46.getKey());
            oprot.writeI32(_iter46.getValue().getValue());
          }
          oprot.writeMapEnd();
        }
        oprot.writeFieldEnd();
      }
    }
    oprot.writeFieldStop();
    oprot.writeStructEnd();
  }

  @Override
  public String toString() {
    StringBuilder sb = new StringBuilder("KsDef(");
    boolean first = true;

    sb.append("name:");
    if (this.name == null) {
      sb.append("null");
    } else {
      sb.append(this.name);
    }
    first = false;
    if (!first) sb.append(", ");
    sb.append("strategy_class:");
    if (this.strategy_class == null) {
      sb.append("null");
    } else {
      sb.append(this.strategy_class);
    }
    first = false;
    if (!first) sb.append(", ");
    sb.append("replication_factor:");
    sb.append(this.replication_factor);
    first = false;
    if (!first) sb.append(", ");
    sb.append("cf_defs:");
    if (this.cf_defs == null) {
      sb.append("null");
    } else {
      sb.append(this.cf_defs);
    }
    first = false;
    if (isSetUsers_access()) {
      if (!first) sb.append(", ");
      sb.append("users_access:");
      if (this.users_access == null) {
        sb.append("null");
      } else {
        sb.append(this.users_access);
      }
      first = false;
    }
    if (isSetGroups_access()) {
      if (!first) sb.append(", ");
      sb.append("groups_access:");
      if (this.groups_access == null) {
        sb.append("null");
      } else {
        sb.append(this.groups_access);
      }
      first = false;
    }
    sb.append(")");
    return sb.toString();
  }

  public void validate() throws TException {
    // check for required fields
    if (name == null) {
      throw new TProtocolException("Required field 'name' was not present! Struct: " + toString());
    }
    if (strategy_class == null) {
      throw new TProtocolException("Required field 'strategy_class' was not present! Struct: " + toString());
    }
    // alas, we cannot check 'replication_factor' because it's a primitive and you chose the non-beans generator.
    if (cf_defs == null) {
      throw new TProtocolException("Required field 'cf_defs' was not present! Struct: " + toString());
    }
  }

}

