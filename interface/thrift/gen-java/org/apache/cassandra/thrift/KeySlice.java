/**
 * Autogenerated by Thrift
 *
 * DO NOT EDIT UNLESS YOU ARE SURE THAT YOU KNOW WHAT YOU ARE DOING
 */
package org.apache.cassandra.thrift;

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
import org.apache.thrift.meta_data.*;
import org.apache.thrift.protocol.*;

/**
 * A KeySlice is key followed by the data it maps to. A collection of KeySlice is returned by the get_range_slice operation.
 * 
 * @param key. a row key
 * @param columns. List of data represented by the key. Typically, the list is pared down to only the columns specified by
 *                 a SlicePredicate.
 */
public class KeySlice implements TBase<KeySlice._Fields>, java.io.Serializable, Cloneable, Comparable<KeySlice> {
  private static final TStruct STRUCT_DESC = new TStruct("KeySlice");

  private static final TField KEY_FIELD_DESC = new TField("key", TType.STRING, (short)1);
  private static final TField COLUMNS_FIELD_DESC = new TField("columns", TType.LIST, (short)2);

  public byte[] key;
  public List<ColumnOrSuperColumn> columns;

  /** The set of fields this struct contains, along with convenience methods for finding and manipulating them. */
  public enum _Fields implements TFieldIdEnum {
    KEY((short)1, "key"),
    COLUMNS((short)2, "columns");

    private static final Map<Integer, _Fields> byId = new HashMap<Integer, _Fields>();
    private static final Map<String, _Fields> byName = new HashMap<String, _Fields>();

    static {
      for (_Fields field : EnumSet.allOf(_Fields.class)) {
        byId.put((int)field._thriftId, field);
        byName.put(field.getFieldName(), field);
      }
    }

    /**
     * Find the _Fields constant that matches fieldId, or null if its not found.
     */
    public static _Fields findByThriftId(int fieldId) {
      return byId.get(fieldId);
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

  public static final Map<_Fields, FieldMetaData> metaDataMap = Collections.unmodifiableMap(new EnumMap<_Fields, FieldMetaData>(_Fields.class) {{
    put(_Fields.KEY, new FieldMetaData("key", TFieldRequirementType.REQUIRED, 
        new FieldValueMetaData(TType.STRING)));
    put(_Fields.COLUMNS, new FieldMetaData("columns", TFieldRequirementType.REQUIRED, 
        new ListMetaData(TType.LIST, 
            new StructMetaData(TType.STRUCT, ColumnOrSuperColumn.class))));
  }});

  static {
    FieldMetaData.addStructMetaDataMap(KeySlice.class, metaDataMap);
  }

  public KeySlice() {
  }

  public KeySlice(
    byte[] key,
    List<ColumnOrSuperColumn> columns)
  {
    this();
    this.key = key;
    this.columns = columns;
  }

  /**
   * Performs a deep copy on <i>other</i>.
   */
  public KeySlice(KeySlice other) {
    if (other.isSetKey()) {
      this.key = new byte[other.key.length];
      System.arraycopy(other.key, 0, key, 0, other.key.length);
    }
    if (other.isSetColumns()) {
      List<ColumnOrSuperColumn> __this__columns = new ArrayList<ColumnOrSuperColumn>();
      for (ColumnOrSuperColumn other_element : other.columns) {
        __this__columns.add(new ColumnOrSuperColumn(other_element));
      }
      this.columns = __this__columns;
    }
  }

  public KeySlice deepCopy() {
    return new KeySlice(this);
  }

  @Deprecated
  public KeySlice clone() {
    return new KeySlice(this);
  }

  public byte[] getKey() {
    return this.key;
  }

  public KeySlice setKey(byte[] key) {
    this.key = key;
    return this;
  }

  public void unsetKey() {
    this.key = null;
  }

  /** Returns true if field key is set (has been asigned a value) and false otherwise */
  public boolean isSetKey() {
    return this.key != null;
  }

  public void setKeyIsSet(boolean value) {
    if (!value) {
      this.key = null;
    }
  }

  public int getColumnsSize() {
    return (this.columns == null) ? 0 : this.columns.size();
  }

  public java.util.Iterator<ColumnOrSuperColumn> getColumnsIterator() {
    return (this.columns == null) ? null : this.columns.iterator();
  }

  public void addToColumns(ColumnOrSuperColumn elem) {
    if (this.columns == null) {
      this.columns = new ArrayList<ColumnOrSuperColumn>();
    }
    this.columns.add(elem);
  }

  public List<ColumnOrSuperColumn> getColumns() {
    return this.columns;
  }

  public KeySlice setColumns(List<ColumnOrSuperColumn> columns) {
    this.columns = columns;
    return this;
  }

  public void unsetColumns() {
    this.columns = null;
  }

  /** Returns true if field columns is set (has been asigned a value) and false otherwise */
  public boolean isSetColumns() {
    return this.columns != null;
  }

  public void setColumnsIsSet(boolean value) {
    if (!value) {
      this.columns = null;
    }
  }

  public void setFieldValue(_Fields field, Object value) {
    switch (field) {
    case KEY:
      if (value == null) {
        unsetKey();
      } else {
        setKey((byte[])value);
      }
      break;

    case COLUMNS:
      if (value == null) {
        unsetColumns();
      } else {
        setColumns((List<ColumnOrSuperColumn>)value);
      }
      break;

    }
  }

  public void setFieldValue(int fieldID, Object value) {
    setFieldValue(_Fields.findByThriftIdOrThrow(fieldID), value);
  }

  public Object getFieldValue(_Fields field) {
    switch (field) {
    case KEY:
      return getKey();

    case COLUMNS:
      return getColumns();

    }
    throw new IllegalStateException();
  }

  public Object getFieldValue(int fieldId) {
    return getFieldValue(_Fields.findByThriftIdOrThrow(fieldId));
  }

  /** Returns true if field corresponding to fieldID is set (has been asigned a value) and false otherwise */
  public boolean isSet(_Fields field) {
    switch (field) {
    case KEY:
      return isSetKey();
    case COLUMNS:
      return isSetColumns();
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
    if (that instanceof KeySlice)
      return this.equals((KeySlice)that);
    return false;
  }

  public boolean equals(KeySlice that) {
    if (that == null)
      return false;

    boolean this_present_key = true && this.isSetKey();
    boolean that_present_key = true && that.isSetKey();
    if (this_present_key || that_present_key) {
      if (!(this_present_key && that_present_key))
        return false;
      if (!java.util.Arrays.equals(this.key, that.key))
        return false;
    }

    boolean this_present_columns = true && this.isSetColumns();
    boolean that_present_columns = true && that.isSetColumns();
    if (this_present_columns || that_present_columns) {
      if (!(this_present_columns && that_present_columns))
        return false;
      if (!this.columns.equals(that.columns))
        return false;
    }

    return true;
  }

  @Override
  public int hashCode() {
    return 0;
  }

  public int compareTo(KeySlice other) {
    if (!getClass().equals(other.getClass())) {
      return getClass().getName().compareTo(other.getClass().getName());
    }

    int lastComparison = 0;
    KeySlice typedOther = (KeySlice)other;

    lastComparison = Boolean.valueOf(isSetKey()).compareTo(typedOther.isSetKey());
    if (lastComparison != 0) {
      return lastComparison;
    }
    if (isSetKey()) {      lastComparison = TBaseHelper.compareTo(key, typedOther.key);
      if (lastComparison != 0) {
        return lastComparison;
      }
    }
    lastComparison = Boolean.valueOf(isSetColumns()).compareTo(typedOther.isSetColumns());
    if (lastComparison != 0) {
      return lastComparison;
    }
    if (isSetColumns()) {      lastComparison = TBaseHelper.compareTo(columns, typedOther.columns);
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
        case 1: // KEY
          if (field.type == TType.STRING) {
            this.key = iprot.readBinary();
          } else { 
            TProtocolUtil.skip(iprot, field.type);
          }
          break;
        case 2: // COLUMNS
          if (field.type == TType.LIST) {
            {
              TList _list12 = iprot.readListBegin();
              this.columns = new ArrayList<ColumnOrSuperColumn>(_list12.size);
              for (int _i13 = 0; _i13 < _list12.size; ++_i13)
              {
                ColumnOrSuperColumn _elem14;
                _elem14 = new ColumnOrSuperColumn();
                _elem14.read(iprot);
                this.columns.add(_elem14);
              }
              iprot.readListEnd();
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
    validate();
  }

  public void write(TProtocol oprot) throws TException {
    validate();

    oprot.writeStructBegin(STRUCT_DESC);
    if (this.key != null) {
      oprot.writeFieldBegin(KEY_FIELD_DESC);
      oprot.writeBinary(this.key);
      oprot.writeFieldEnd();
    }
    if (this.columns != null) {
      oprot.writeFieldBegin(COLUMNS_FIELD_DESC);
      {
        oprot.writeListBegin(new TList(TType.STRUCT, this.columns.size()));
        for (ColumnOrSuperColumn _iter15 : this.columns)
        {
          _iter15.write(oprot);
        }
        oprot.writeListEnd();
      }
      oprot.writeFieldEnd();
    }
    oprot.writeFieldStop();
    oprot.writeStructEnd();
  }

  @Override
  public String toString() {
    StringBuilder sb = new StringBuilder("KeySlice(");
    boolean first = true;

    sb.append("key:");
    if (this.key == null) {
      sb.append("null");
    } else {
        int __key_size = Math.min(this.key.length, 128);
        for (int i = 0; i < __key_size; i++) {
          if (i != 0) sb.append(" ");
          sb.append(Integer.toHexString(this.key[i]).length() > 1 ? Integer.toHexString(this.key[i]).substring(Integer.toHexString(this.key[i]).length() - 2).toUpperCase() : "0" + Integer.toHexString(this.key[i]).toUpperCase());
        }
        if (this.key.length > 128) sb.append(" ...");
    }
    first = false;
    if (!first) sb.append(", ");
    sb.append("columns:");
    if (this.columns == null) {
      sb.append("null");
    } else {
      sb.append(this.columns);
    }
    first = false;
    sb.append(")");
    return sb.toString();
  }

  public void validate() throws TException {
    // check for required fields
    if (key == null) {
      throw new TProtocolException("Required field 'key' was not present! Struct: " + toString());
    }
    if (columns == null) {
      throw new TProtocolException("Required field 'columns' was not present! Struct: " + toString());
    }
  }

}

