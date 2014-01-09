/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.cassandra.cql3;

import java.nio.ByteBuffer;

import org.apache.cassandra.config.CFMetaData;
import org.apache.cassandra.config.ColumnDefinition;
import org.apache.cassandra.db.ColumnFamily;
import org.apache.cassandra.db.marshal.CollectionType;
import org.apache.cassandra.db.marshal.CounterColumnType;
import org.apache.cassandra.db.marshal.ListType;
import org.apache.cassandra.exceptions.InvalidRequestException;

/**
 * An UPDATE or DELETE operation.
 *
 * For UPDATE this includes:
 *   - setting a constant
 *   - counter operations
 *   - collections operations
 * and for DELETE:
 *   - deleting a column
 *   - deleting an element of collection column
 *
 * Fine grained operation are obtained from their raw counterpart (Operation.Raw, which
 * correspond to a parsed, non-checked operation) by provided the receiver for the operation.
 */
public abstract class Operation
{
    // Name of the column the operation applies to
    public final ColumnIdentifier columnName;

    // Term involved in the operation. In theory this should not be here since some operation
    // may require none of more than one term, but most need 1 so it simplify things a bit.
    protected final Term t;

    protected Operation(ColumnIdentifier columnName, Term t)
    {
        this.columnName = columnName;
        this.t = t;
    }

    // Whether the colum operated on is a static column (on trunk, Operation stores the ColumnDefinition directly,
    // not just the column name, so we'll be able to remove that lookup and check ColumnDefinition.isStatic field
    // directly. But for 2.0, it's simpler that way).
    public boolean isStatic(CFMetaData cfm)
    {
        if (columnName == null)
            return false;

        ColumnDefinition def = cfm.getColumnDefinition(columnName.key);
        return def != null && def.type == ColumnDefinition.Type.STATIC;
    }

    protected ColumnNameBuilder maybeUpdatePrefix(CFMetaData cfm, ColumnNameBuilder prefix)
    {
        return isStatic(cfm) ? cfm.getStaticColumnNameBuilder() : prefix;
    }

    /**
     * @return whether the operation requires a read of the previous value to be executed
     * (only lists setterByIdx, discard and discardByIdx requires that).
     */
    public boolean requiresRead()
    {
        return false;
    }

    /**
     * Collects the column specification for the bind variables of this operation.
     *
     * @param boundNames the list of column specification where to collect the
     * bind variables of this term in.
     */
    public void collectMarkerSpecification(VariableSpecifications boundNames)
    {
        if (t != null)
            t.collectMarkerSpecification(boundNames);
    }

    /**
     * Execute the operation.
     *
     * @param rowKey row key for the update.
     * @param cf the column family to which to add the updates generated by this operation.
     * @param namePrefix the prefix that identify the CQL3 row this operation applies to (callers should not reuse
     * the ColumnNameBuilder they pass here).
     * @param params parameters of the update.
     */
    public abstract void execute(ByteBuffer rowKey, ColumnFamily cf, ColumnNameBuilder namePrefix, UpdateParameters params) throws InvalidRequestException;

    /**
     * A parsed raw UPDATE operation.
     *
     * This can be one of:
     *   - Setting a value: c = v
     *   - Setting an element of a collection: c[x] = v
     *   - An addition/substraction to a variable: c = c +/- v (where v can be a collection literal)
     *   - An prepend operation: c = v + c
     */
    public interface RawUpdate
    {
        /**
         * This method validates the operation (i.e. validate it is well typed)
         * based on the specification of the receiver of the operation.
         *
         * It returns an Operation which can be though as post-preparation well-typed
         * Operation.
         *
         * @param receiver the "column" this operation applies to. Note that
         * contrarly to the method of same name in Term.Raw, the receiver should always
         * be a true column.
         * @return the prepared update operation.
         */
        public Operation prepare(CFDefinition.Name receiver) throws InvalidRequestException;

        /**
         * @return whether this operation can be applied alongside the {@code
         * other} update (in the same UPDATE statement for the same column).
         */
        public boolean isCompatibleWith(RawUpdate other);
    }

    /**
     * A parsed raw DELETE operation.
     *
     * This can be one of:
     *   - Deleting a column
     *   - Deleting an element of a collection
     */
    public interface RawDeletion
    {
        /**
         * The name of the column affected by this delete operation.
         */
        public ColumnIdentifier affectedColumn();

        /**
         * This method validates the operation (i.e. validate it is well typed)
         * based on the specification of the column affected by the operation (i.e the
         * one returned by affectedColumn()).
         *
         * It returns an Operation which can be though as post-preparation well-typed
         * Operation.
         *
         * @param receiver the "column" this operation applies to.
         * @return the prepared delete operation.
         */
        public Operation prepare(ColumnSpecification receiver) throws InvalidRequestException;
    }

    public static class SetValue implements RawUpdate
    {
        private final Term.Raw value;

        public SetValue(Term.Raw value)
        {
            this.value = value;
        }

        public Operation prepare(CFDefinition.Name receiver) throws InvalidRequestException
        {
            Term v = value.prepare(receiver);

            if (receiver.type instanceof CounterColumnType)
                throw new InvalidRequestException(String.format("Cannot set the value of counter column %s (counters can only be incremented/decremented, not set)", receiver));

            if (!(receiver.type instanceof CollectionType))
                return new Constants.Setter(receiver.kind == CFDefinition.Name.Kind.VALUE_ALIAS ? null : receiver.name, v);

            switch (((CollectionType)receiver.type).kind)
            {
                case LIST:
                    return new Lists.Setter(receiver.name, v);
                case SET:
                    return new Sets.Setter(receiver.name, v);
                case MAP:
                    return new Maps.Setter(receiver.name, v);
            }
            throw new AssertionError();
        }

        protected String toString(ColumnSpecification column)
        {
            return String.format("%s = %s", column, value);
        }

        public boolean isCompatibleWith(RawUpdate other)
        {
            // We don't allow setting multiple time the same column, because 1)
            // it's stupid and 2) the result would seem random to the user.
            return false;
        }
    }

    public static class SetElement implements RawUpdate
    {
        private final Term.Raw selector;
        private final Term.Raw value;

        public SetElement(Term.Raw selector, Term.Raw value)
        {
            this.selector = selector;
            this.value = value;
        }

        public Operation prepare(CFDefinition.Name receiver) throws InvalidRequestException
        {
            if (!(receiver.type instanceof CollectionType))
                throw new InvalidRequestException(String.format("Invalid operation (%s) for non collection column %s", toString(receiver), receiver));

            switch (((CollectionType)receiver.type).kind)
            {
                case LIST:
                    Term idx = selector.prepare(Lists.indexSpecOf(receiver));
                    Term lval = value.prepare(Lists.valueSpecOf(receiver));
                    return new Lists.SetterByIndex(receiver.name, idx, lval);
                case SET:
                    throw new InvalidRequestException(String.format("Invalid operation (%s) for set column %s", toString(receiver), receiver));
                case MAP:
                    Term key = selector.prepare(Maps.keySpecOf(receiver));
                    Term mval = value.prepare(Maps.valueSpecOf(receiver));
                    return new Maps.SetterByKey(receiver.name, key, mval);
            }
            throw new AssertionError();
        }

        protected String toString(ColumnSpecification column)
        {
            return String.format("%s[%s] = %s", column, selector, value);
        }

        public boolean isCompatibleWith(RawUpdate other)
        {
            // TODO: we could check that the other operation is not setting the same element
            // too (but since the index/key set may be a bind variables we can't always do it at this point)
            return !(other instanceof SetValue);
        }
    }

    public static class Addition implements RawUpdate
    {
        private final Term.Raw value;

        public Addition(Term.Raw value)
        {
            this.value = value;
        }

        public Operation prepare(CFDefinition.Name receiver) throws InvalidRequestException
        {
            Term v = value.prepare(receiver);

            if (!(receiver.type instanceof CollectionType))
            {
                if (!(receiver.type instanceof CounterColumnType))
                    throw new InvalidRequestException(String.format("Invalid operation (%s) for non counter column %s", toString(receiver), receiver));
                return new Constants.Adder(receiver.kind == CFDefinition.Name.Kind.VALUE_ALIAS ? null : receiver.name, v);
            }

            switch (((CollectionType)receiver.type).kind)
            {
                case LIST:
                    return new Lists.Appender(receiver.name, v);
                case SET:
                    return new Sets.Adder(receiver.name, v);
                case MAP:
                    return new Maps.Putter(receiver.name, v);
            }
            throw new AssertionError();
        }

        protected String toString(ColumnSpecification column)
        {
            return String.format("%s = %s + %s", column, column, value);
        }

        public boolean isCompatibleWith(RawUpdate other)
        {
            return !(other instanceof SetValue);
        }
    }

    public static class Substraction implements RawUpdate
    {
        private final Term.Raw value;

        public Substraction(Term.Raw value)
        {
            this.value = value;
        }

        public Operation prepare(CFDefinition.Name receiver) throws InvalidRequestException
        {
            Term v = value.prepare(receiver);

            if (!(receiver.type instanceof CollectionType))
            {
                if (!(receiver.type instanceof CounterColumnType))
                    throw new InvalidRequestException(String.format("Invalid operation (%s) for non counter column %s", toString(receiver), receiver));
                return new Constants.Substracter(receiver.kind == CFDefinition.Name.Kind.VALUE_ALIAS ? null : receiver.name, v);
            }

            switch (((CollectionType)receiver.type).kind)
            {
                case LIST:
                    return new Lists.Discarder(receiver.name, v);
                case SET:
                    return new Sets.Discarder(receiver.name, v);
                case MAP:
                    throw new InvalidRequestException(String.format("Invalid operation (%s) for map column %s", toString(receiver), receiver));
            }
            throw new AssertionError();
        }

        protected String toString(ColumnSpecification column)
        {
            return String.format("%s = %s - %s", column, column, value);
        }

        public boolean isCompatibleWith(RawUpdate other)
        {
            return !(other instanceof SetValue);
        }
    }

    public static class Prepend implements RawUpdate
    {
        private final Term.Raw value;

        public Prepend(Term.Raw value)
        {
            this.value = value;
        }

        public Operation prepare(CFDefinition.Name receiver) throws InvalidRequestException
        {
            Term v = value.prepare(receiver);

            if (!(receiver.type instanceof ListType))
                throw new InvalidRequestException(String.format("Invalid operation (%s) for non list column %s", toString(receiver), receiver));

            return new Lists.Prepender(receiver.name, v);
        }

        protected String toString(ColumnSpecification column)
        {
            return String.format("%s = %s - %s", column, value, column);
        }

        public boolean isCompatibleWith(RawUpdate other)
        {
            return !(other instanceof SetValue);
        }
    }

    public static class ColumnDeletion implements RawDeletion
    {
        private final ColumnIdentifier id;

        public ColumnDeletion(ColumnIdentifier id)
        {
            this.id = id;
        }

        public ColumnIdentifier affectedColumn()
        {
            return id;
        }

        public Operation prepare(ColumnSpecification receiver) throws InvalidRequestException
        {
            // No validation, deleting a column is always "well typed"
            return new Constants.Deleter(id, receiver.type instanceof CollectionType);
        }
    }

    public static class ElementDeletion implements RawDeletion
    {
        private final ColumnIdentifier id;
        private final Term.Raw element;

        public ElementDeletion(ColumnIdentifier id, Term.Raw element)
        {
            this.id = id;
            this.element = element;
        }

        public ColumnIdentifier affectedColumn()
        {
            return id;
        }

        public Operation prepare(ColumnSpecification receiver) throws InvalidRequestException
        {
            if (!(receiver.type instanceof CollectionType))
                throw new InvalidRequestException(String.format("Invalid deletion operation for non collection column %s", receiver));

            switch (((CollectionType)receiver.type).kind)
            {
                case LIST:
                    Term idx = element.prepare(Lists.indexSpecOf(receiver));
                    return new Lists.DiscarderByIndex(id, idx);
                case SET:
                    Term elt = element.prepare(Sets.valueSpecOf(receiver));
                    return new Sets.Discarder(id, elt);
                case MAP:
                    Term key = element.prepare(Maps.keySpecOf(receiver));
                    return new Maps.DiscarderByKey(id, key);
            }
            throw new AssertionError();
        }
    }
}
