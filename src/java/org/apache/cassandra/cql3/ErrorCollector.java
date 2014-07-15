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

import java.util.LinkedList;

import org.antlr.runtime.BaseRecognizer;
import org.antlr.runtime.Parser;
import org.antlr.runtime.RecognitionException;
import org.antlr.runtime.Token;
import org.antlr.runtime.TokenStream;
import org.apache.cassandra.exceptions.SyntaxException;

/**
 * <code>ErrorListener</code> that collect and enhance the errors send by the CQL lexer and parser.
 */
public final class ErrorCollector implements ErrorListener
{
    /**
     * The offset of the first token of the snippet.
     */
    private static final int FIRST_TOKEN_OFFSET = 10;

    /**
     * The offset of the last token of the snippet.
     */
    private static final int LAST_TOKEN_OFFSET = 2;

    /**
     * The CQL query.
     */
    private final String query;

    /**
     * The error messages.
     */
    private final LinkedList<String> errorMsgs = new LinkedList<>();

    /**
     * Creates a new <code>ErrorCollector</code> instance to collect the syntax errors associated to the specified CQL
     * query.
     *
     * @param query the CQL query that will be parsed
     */
    public ErrorCollector(String query)
    {
        this.query = query;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void syntaxError(BaseRecognizer recognizer, String[] tokenNames, RecognitionException e)
    {
        String hdr = recognizer.getErrorHeader(e);
        String msg = recognizer.getErrorMessage(e, tokenNames);

        StringBuilder builder = new StringBuilder().append(hdr)
                .append(' ')
                .append(msg);

        if (recognizer instanceof Parser)
            appendQuerySnippet((Parser) recognizer, builder);

        errorMsgs.add(builder.toString());
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void syntaxError(BaseRecognizer recognizer, String errorMsg)
    {
        errorMsgs.add(errorMsg);
    }

    /**
     * Throws the last syntax error found by the lexer or the parser if it exists.
     *
     * @throws SyntaxException the syntax error.
     */
    public void throwLastSyntaxError() throws SyntaxException
    {
        if (!errorMsgs.isEmpty())
            throw new SyntaxException(errorMsgs.getLast());
    }

    /**
     * Appends a query snippet to the message to help the user to understand the problem.
     *
     * @param parser the parser used to parse the query
     * @param builder the <code>StringBuilder</code> used to build the error message
     */
    private void appendQuerySnippet(Parser parser, StringBuilder builder)
    {
        TokenStream tokenStream = parser.getTokenStream();
        int index = tokenStream.index();
        int size = tokenStream.size();

        Token from = tokenStream.get(getSnippetFirstTokenIndex(index));
        Token to = tokenStream.get(getSnippetLastTokenIndex(index, size));
        Token offending = tokenStream.get(index);

        appendSnippet(builder, from, to, offending);
    }

    /**
     * Appends a query snippet to the message to help the user to understand the problem.
     *
     * @param from the first token to include within the snippet
     * @param to the last token to include within the snippet
     * @param offending the token which is responsible for the error
     */
    final void appendSnippet(StringBuilder builder,
                             Token from,
                             Token to,
                             Token offending)
    {
        String[] lines = query.split("\n");

        boolean includeQueryStart = (from.getLine() == 1) && (from.getCharPositionInLine() == 0);
        boolean includeQueryEnd = (to.getLine() == lines.length)
                && (getLastCharPositionInLine(to) == lines[lines.length - 1].length());

        builder.append(" (");

        if (!includeQueryStart)
            builder.append("...");

        lines[lineIndex(to)] = lines[lineIndex(to)].substring(0, getLastCharPositionInLine(to));
        lines[lineIndex(offending)] = highlightToken(lines[lineIndex(offending)], offending);
        lines[lineIndex(from)] = lines[lineIndex(from)].substring(from.getCharPositionInLine());

        for (int i = lineIndex(from), m = lineIndex(to); i <= m; i++)
            builder.append(lines[i]);

        if (!includeQueryEnd)
            builder.append("...");

        builder.append(")");
    }

    /**
     * Puts the specified token within square brackets.
     *
     * @param line the line containing the token
     * @param token the token to put within square brackets
     */
    private static String highlightToken(String line, Token token)
    {
        String newLine = insertChar(line, getLastCharPositionInLine(token), ']');
        return insertChar(newLine, token.getCharPositionInLine(), '[');
    }

    /**
     * Returns the index of the last character relative to the beginning of the line 0..n-1
     *
     * @param token the token
     * @return the index of the last character relative to the beginning of the line 0..n-1
     */
    private static int getLastCharPositionInLine(Token token)
    {
        return token.getCharPositionInLine() + getLength(token);
    }

    /**
     * Return the token length.
     *
     * @param token the token
     * @return the token length
     */
    private static int getLength(Token token)
    {
        return token.getText().length();
    }

    /**
     * Inserts a character at a given position within a <code>String</code>.
     *
     * @param s the <code>String</code> in which the character must be inserted
     * @param index the position where the character must be inserted
     * @param c the character to insert
     * @return the modified <code>String</code>
     */
    private static String insertChar(String s, int index, char c)
    {
        return new StringBuilder().append(s.substring(0, index))
                .append(c)
                .append(s.substring(index))
                .toString();
    }

    /**
     * Returns the index of the line number on which this token was matched; index=0..n-1
     *
     * @param token the token
     * @return the index of the line number on which this token was matched; index=0..n-1
     */
    private static int lineIndex(Token token)
    {
        return token.getLine() - 1;
    }

    /**
     * Returns the index of the last token which is part of the snippet.
     *
     * @param index the index of the token causing the error
     * @param size the total number of tokens
     * @return the index of the last token which is part of the snippet.
     */
    private static int getSnippetLastTokenIndex(int index, int size)
    {
        return Math.min(size - 1, index + LAST_TOKEN_OFFSET);
    }

    /**
     * Returns the index of the first token which is part of the snippet.
     *
     * @param index the index of the token causing the error
     * @return the index of the first token which is part of the snippet.
     */
    private static int getSnippetFirstTokenIndex(int index)
    {
        return Math.max(0, index - FIRST_TOKEN_OFFSET);
    }
}
