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
import java.util.*;

import org.antlr.runtime.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.apache.cassandra.cql3.statements.*;
import org.apache.cassandra.transport.messages.ResultMessage;
import org.apache.cassandra.config.*;
import org.apache.cassandra.db.*;
import org.apache.cassandra.db.filter.*;
import org.apache.cassandra.db.marshal.AbstractType;
import org.apache.cassandra.service.ClientState;
import org.apache.cassandra.thrift.*;
import org.apache.cassandra.utils.FBUtilities;
import org.apache.cassandra.utils.SemanticVersion;

public class QueryProcessor
{
    public static final SemanticVersion CQL_VERSION = new SemanticVersion("3.0.0-beta1");

    private static final Logger logger = LoggerFactory.getLogger(QueryProcessor.class);

    public static void validateKey(ByteBuffer key) throws InvalidRequestException
    {
        if (key == null || key.remaining() == 0)
        {
            throw new InvalidRequestException("Key may not be empty");
        }

        // check that key can be handled by FBUtilities.writeShortByteArray
        if (key.remaining() > FBUtilities.MAX_UNSIGNED_SHORT)
        {
            throw new InvalidRequestException("Key length of " + key.remaining() +
                                              " is longer than maximum of " + FBUtilities.MAX_UNSIGNED_SHORT);
        }
    }

    public static void validateColumnNames(Iterable<ByteBuffer> columns)
    throws InvalidRequestException
    {
        for (ByteBuffer name : columns)
        {
            if (name.remaining() > IColumn.MAX_NAME_LENGTH)
                throw new InvalidRequestException(String.format("column name is too long (%s > %s)",
                                                                name.remaining(),
                                                                IColumn.MAX_NAME_LENGTH));
            if (name.remaining() == 0)
                throw new InvalidRequestException("zero-length column name");
        }
    }

    public static void validateColumnName(ByteBuffer column)
    throws InvalidRequestException
    {
        validateColumnNames(Collections.singletonList(column));
    }

    public static void validateFilter(CFMetaData metadata, IFilter filter)
    throws InvalidRequestException
    {
        if (filter instanceof SliceQueryFilter)
            validateSliceFilter(metadata, (SliceQueryFilter)filter);
        else
            validateColumnNames(((NamesQueryFilter)filter).columns);
    }

    public static void validateSliceFilter(CFMetaData metadata, SliceQueryFilter range)
    throws InvalidRequestException
    {
        try
        {
            AbstractType<?> comparator = metadata.getComparatorFor(null);
            ColumnSlice.validate(range.slices, comparator, range.reversed);
        }
        catch (IllegalArgumentException e)
        {
            throw new InvalidRequestException(e.getMessage());
        }
    }

    private static ResultMessage processStatement(CQLStatement statement, ClientState clientState, List<ByteBuffer> variables)
    throws  UnavailableException, InvalidRequestException, TimedOutException
    {
        statement.checkAccess(clientState);
        statement.validate(clientState);
        ResultMessage result = statement.execute(clientState, variables);
        return result == null ? ResultMessage.Void.instance() : result;
    }

    public static ResultMessage process(String queryString, ClientState clientState)
    throws UnavailableException, InvalidRequestException, TimedOutException
    {
        logger.trace("CQL QUERY: {}", queryString);
        return processStatement(getStatement(queryString, clientState).statement, clientState, Collections.<ByteBuffer>emptyList());
    }

    public static UntypedResultSet processInternal(String query)
    {
        try
        {
            ClientState state = new ClientState();
            ResultMessage result = processStatement(getStatement(query, state).statement, state, Collections.<ByteBuffer>emptyList());
            if (result instanceof ResultMessage.Rows)
                return new UntypedResultSet(((ResultMessage.Rows)result).result);
            else
                return null;
        }
        catch (UnavailableException e)
        {
            throw new RuntimeException(e);
        }
        catch (InvalidRequestException e)
        {
            throw new AssertionError(e);
        }
        catch (TimedOutException e)
        {
            throw new RuntimeException(e);
        }
    }

    public static UntypedResultSet resultify(String query, Row row)
    {
        try
        {
            SelectStatement ss = (SelectStatement) getStatement(query, null).statement;
            ResultSet cqlRows = ss.process(Collections.singletonList(row));
            return new UntypedResultSet(cqlRows);
        }
        catch (InvalidRequestException e)
        {
            throw new AssertionError(e);
        }
    }

    public static ResultMessage.Prepared prepare(String queryString, ClientState clientState)
    throws InvalidRequestException
    {
        logger.trace("CQL QUERY: {}", queryString);

        ParsedStatement.Prepared prepared = getStatement(queryString, clientState);
        int statementId = makeStatementId(queryString);
        clientState.getCQL3Prepared().put(statementId, prepared.statement);
        logger.trace(String.format("Stored prepared statement #%d with %d bind markers",
                                   statementId,
                                   prepared.statement.getBoundsTerms()));

        assert prepared.statement.getBoundsTerms() == prepared.boundNames.size();
        return new ResultMessage.Prepared(statementId, prepared.boundNames);
    }

    public static ResultMessage processPrepared(CQLStatement statement, ClientState clientState, List<ByteBuffer> variables)
    throws UnavailableException, InvalidRequestException, TimedOutException
    {
        // Check to see if there are any bound variables to verify
        if (!(variables.isEmpty() && (statement.getBoundsTerms() == 0)))
        {
            if (variables.size() != statement.getBoundsTerms())
                throw new InvalidRequestException(String.format("there were %d markers(?) in CQL but %d bound variables",
                                                                statement.getBoundsTerms(),
                                                                variables.size()));

            // at this point there is a match in count between markers and variables that is non-zero

            if (logger.isTraceEnabled())
                for (int i = 0; i < variables.size(); i++)
                    logger.trace("[{}] '{}'", i+1, variables.get(i));
        }

        return processStatement(statement, clientState, variables);
    }

    private static final int makeStatementId(String cql)
    {
        // use the hash of the string till something better is provided
        return cql.hashCode();
    }

    private static ParsedStatement.Prepared getStatement(String queryStr, ClientState clientState) throws InvalidRequestException
    {
        ParsedStatement statement = parseStatement(queryStr);

        // Set keyspace for statement that require login
        if (statement instanceof CFStatement)
            ((CFStatement)statement).prepareKeyspace(clientState);

        return statement.prepare();
    }

    public static ParsedStatement parseStatement(String queryStr) throws InvalidRequestException
    {
        try
        {
            // Lexer and parser
            CharStream stream = new ANTLRStringStream(queryStr);
            CqlLexer lexer = new CqlLexer(stream);
            TokenStream tokenStream = new CommonTokenStream(lexer);
            CqlParser parser = new CqlParser(tokenStream);

            // Parse the query string to a statement instance
            ParsedStatement statement = parser.query();

            // The lexer and parser queue up any errors they may have encountered
            // along the way, if necessary, we turn them into exceptions here.
            lexer.throwLastRecognitionError();
            parser.throwLastRecognitionError();

            return statement;
        }
        catch (RuntimeException re)
        {
            InvalidRequestException ire = new InvalidRequestException("Failed parsing statement: [" + queryStr + "] reason: " + re.getClass().getSimpleName() + " " + re.getMessage());
            ire.initCause(re);
            throw ire;
        }
        catch (RecognitionException e)
        {
            InvalidRequestException ire = new InvalidRequestException("Invalid or malformed CQL query string");
            ire.initCause(e);
            throw ire;
        }
    }
}
