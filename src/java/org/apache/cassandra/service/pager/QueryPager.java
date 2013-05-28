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
package org.apache.cassandra.service.pager;

import java.util.List;

import org.apache.cassandra.db.Row;
import org.apache.cassandra.exceptions.RequestExecutionException;
import org.apache.cassandra.exceptions.RequestValidationException;

/**
 * Perform a query, paging it by page of a given size.
 *
 * This is essentially an iterator of pages. Each call to fetchPage() will
 * return the next page (i.e. the next list of rows) and isExhausted()
 * indicates whether there is more page to fetch. The pageSize will
 * either be in term of cells or in term of CQL3 row, depending on the
 * parameters of the command we page.
 *
 * Please note that the pager might page within rows, so there is no guarantee
 * that successive pages won't return the same row (though with different
 * columns every time).
 *
 * Also, there is no guarantee that fetchPage() won't return an empty list,
 * even if isExhausted() return false (but it is guaranteed to return an empty
 * list *if* isExhausted() return true). Indeed, isExhausted() does *not*
 * trigger a query so in some (failry rare) case we might not know the paging
 * is done even though it is.
 */
public interface QueryPager
{
    /**
     * Fetches the next page.
     *
     * @param pageSize the maximum number of elements to return in the next page.
     * @return the page of result.
     */
    public List<Row> fetchPage(int pageSize) throws RequestValidationException, RequestExecutionException;

    /**
     * Whether or not this pager is exhausted, i.e. whether or not a call to
     * fetchPage may return more result.
     *
     * @return whether the pager is exhausted.
     */
    public boolean isExhausted();

    /**
     * The maximum number of cells/CQL3 row that we may still have to return.
     * In other words, that's the initial user limit minus what we've already
     * returned (note that it's not how many we *will* return, just the upper
     * limit on it).
     */
    public int maxRemaining();

    /**
     * The timestamp used by the last page.
     */
    public long timestamp();
}
