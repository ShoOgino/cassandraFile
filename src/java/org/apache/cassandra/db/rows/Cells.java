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
package org.apache.cassandra.db.rows;

import java.nio.ByteBuffer;
import java.util.Comparator;
import java.util.Iterator;

import org.apache.cassandra.config.ColumnDefinition;
import org.apache.cassandra.db.*;
import org.apache.cassandra.db.partitions.PartitionStatisticsCollector;
import org.apache.cassandra.db.index.SecondaryIndexManager;

/**
 * Static methods to work on cells.
 */
public abstract class Cells
{
    private Cells() {}

    /**
     * Collect statistics ont a given cell.
     *
     * @param cell the cell for which to collect stats.
     * @param collector the stats collector.
     */
    public static void collectStats(Cell cell, PartitionStatisticsCollector collector)
    {
        collector.update(cell);

        if (cell.isCounterCell())
            collector.updateHasLegacyCounterShards(CounterCells.hasLegacyShards(cell));
    }

    /**
     * Reconciles/merges two cells, one being an update to an existing cell,
     * yielding index updates if appropriate.
     * <p>
     * Note that this method assumes that the provided cells can meaningfully
     * be reconciled together, that is that those cells are for the same row and same
     * column (and same cell path if the column is complex).
     * <p>
     * Also note that which cell is provided as {@code existing} and which is
     * provided as {@code update} matters for index updates.
     *
     * @param clustering the clustering for the row the cells to merge originate from.
     * This is only used for index updates, so this can be {@code null} if
     * {@code indexUpdater == SecondaryIndexManager.nullUpdater}.
     * @param existing the pre-existing cell, the one that is updated. This can be
     * {@code null} if this reconciliation correspond to an insertion.
     * @param update the newly added cell, the update. This can be {@code null} out
     * of convenience, in which case this function simply copy {@code existing} to
     * {@code writer}.
     * @param deletion the deletion time that applies to the cells being considered.
     * This deletion time may delete both {@code existing} or {@code update}.
     * @param builder the row builder to which the result of the reconciliation is written.
     * @param nowInSec the current time in seconds (which plays a role during reconciliation
     * because deleted cells always have precedence on timestamp equality and deciding if a
     * cell is a live or not depends on the current time due to expiring cells).
     * @param indexUpdater an index updater to which the result of the reconciliation is
     * signaled (if relevant, that is if the update is not simply ignored by the reconciliation).
     * This cannot be {@code null} but {@code SecondaryIndexManager.nullUpdater} can be passed.
     *
     * @return the timestamp delta between existing and update, or {@code Long.MAX_VALUE} if one
     * of them is {@code null} or deleted by {@code deletion}).
     */
    public static long reconcile(Clustering clustering,
                                 Cell existing,
                                 Cell update,
                                 DeletionTime deletion,
                                 Row.Builder builder,
                                 int nowInSec,
                                 SecondaryIndexManager.Updater indexUpdater)
    {
        existing = existing == null || deletion.deletes(existing) ? null : existing;
        update = update == null || deletion.deletes(update) ? null : update;
        if (existing == null || update == null)
        {
            if (update != null)
            {
                // It's inefficient that we call maybeIndex (which is for primary key indexes) on every cell, but
                // we'll need to fix that damn 2ndary index API to avoid that.
                updatePKIndexes(clustering, update, nowInSec, indexUpdater);
                indexUpdater.insert(clustering, update);
                builder.addCell(update);
            }
            else if (existing != null)
            {
                builder.addCell(existing);
            }
            return Long.MAX_VALUE;
        }

        Cell reconciled = reconcile(existing, update, nowInSec);
        builder.addCell(reconciled);

        // Note that this test rely on reconcile returning either 'existing' or 'update'. That's not true for counters but we don't index them
        if (reconciled == update)
        {
            updatePKIndexes(clustering, update, nowInSec, indexUpdater);
            indexUpdater.update(clustering, existing, reconciled);
        }
        return Math.abs(existing.timestamp() - update.timestamp());
    }

    private static void updatePKIndexes(Clustering clustering, Cell cell, int nowInSec, SecondaryIndexManager.Updater indexUpdater)
    {
        if (indexUpdater != SecondaryIndexManager.nullUpdater && cell.isLive(nowInSec))
            indexUpdater.maybeIndex(clustering, cell.timestamp(), cell.ttl(), DeletionTime.LIVE);
    }

    /**
     * Reconciles/merge two cells.
     * <p>
     * Note that this method assumes that the provided cells can meaningfully
     * be reconciled together, that is that cell are for the same row and same
     * column (and same cell path if the column is complex).
     * <p>
     * This method is commutative over it's cells arguments: {@code reconcile(a, b, n) == reconcile(b, a, n)}.
     *
     * @param c1 the first cell participating in the reconciliation.
     * @param c2 the second cell participating in the reconciliation.
     * @param nowInSec the current time in seconds (which plays a role during reconciliation
     * because deleted cells always have precedence on timestamp equality and deciding if a
     * cell is a live or not depends on the current time due to expiring cells).
     *
     * @return a cell corresponding to the reconciliation of {@code c1} and {@code c2}.
     * For non-counter cells, this will always be either {@code c1} or {@code c2}, but for
     * counter cells this can be a newly allocated cell.
     */
    public static Cell reconcile(Cell c1, Cell c2, int nowInSec)
    {
        if (c1 == null)
            return c2 == null ? null : c2;
        if (c2 == null)
            return c1;

        if (c1.isCounterCell() || c2.isCounterCell())
        {
            Conflicts.Resolution res = Conflicts.resolveCounter(c1.timestamp(),
                                                                c1.isLive(nowInSec),
                                                                c1.value(),
                                                                c2.timestamp(),
                                                                c2.isLive(nowInSec),
                                                                c2.value());

            switch (res)
            {
                case LEFT_WINS: return c1;
                case RIGHT_WINS: return c2;
                default:
                    ByteBuffer merged = Conflicts.mergeCounterValues(c1.value(), c2.value());
                    long timestamp = Math.max(c1.timestamp(), c2.timestamp());

                    // We save allocating a new cell object if it turns out that one cell was
                    // a complete superset of the other
                    if (merged == c1.value() && timestamp == c1.timestamp())
                        return c1;
                    else if (merged == c2.value() && timestamp == c2.timestamp())
                        return c2;
                    else // merge clocks and timestamps.
                        return new BufferCell(c1.column(), timestamp, Cell.NO_TTL, Cell.NO_DELETION_TIME, merged, c1.path());
            }
        }

        Conflicts.Resolution res = Conflicts.resolveRegular(c1.timestamp(),
                                                            c1.isLive(nowInSec),
                                                            c1.localDeletionTime(),
                                                            c1.value(),
                                                            c2.timestamp(),
                                                            c2.isLive(nowInSec),
                                                            c2.localDeletionTime(),
                                                            c2.value());
        assert res != Conflicts.Resolution.MERGE;
        return res == Conflicts.Resolution.LEFT_WINS ? c1 : c2;
    }

    /**
     * Computes the reconciliation of a complex column given its pre-existing
     * cells and the ones it is updated with, and generating index update if
     * appropriate.
     * <p>
     * Note that this method assumes that the provided cells can meaningfully
     * be reconciled together, that is that the cells are for the same row and same
     * complex column.
     * <p>
     * Also note that which cells is provided as {@code existing} and which are
     * provided as {@code update} matters for index updates.
     *
     * @param clustering the clustering for the row the cells to merge originate from.
     * This is only used for index updates, so this can be {@code null} if
     * {@code indexUpdater == SecondaryIndexManager.nullUpdater}.
     * @param column the complex column the cells are for.
     * @param existing the pre-existing cells, the ones that are updated. This can be
     * {@code null} if this reconciliation correspond to an insertion.
     * @param update the newly added cells, the update. This can be {@code null} out
     * of convenience, in which case this function simply copy the cells from
     * {@code existing} to {@code writer}.
     * @param deletion the deletion time that applies to the cells being considered.
     * This deletion time may delete cells in both {@code existing} and {@code update}.
     * @param builder the row build to which the result of the reconciliation is written.
     * @param nowInSec the current time in seconds (which plays a role during reconciliation
     * because deleted cells always have precedence on timestamp equality and deciding if a
     * cell is a live or not depends on the current time due to expiring cells).
     * @param indexUpdater an index updater to which the result of the reconciliation is
     * signaled (if relevant, that is if the updates are not simply ignored by the reconciliation).
     * This cannot be {@code null} but {@code SecondaryIndexManager.nullUpdater} can be passed.
     *
     * @return the smallest timestamp delta between corresponding cells from existing and update. A
     * timestamp delta being computed as the difference between a cell from {@code update} and the
     * cell in {@code existing} having the same cell path (if such cell exists). If the intersection
     * of cells from {@code existing} and {@code update} having the same cell path is empty, this
     * returns {@code Long.MAX_VALUE}.
     */
    public static long reconcileComplex(Clustering clustering,
                                        ColumnDefinition column,
                                        Iterator<Cell> existing,
                                        Iterator<Cell> update,
                                        DeletionTime deletion,
                                        Row.Builder builder,
                                        int nowInSec,
                                        SecondaryIndexManager.Updater indexUpdater)
    {
        Comparator<CellPath> comparator = column.cellPathComparator();
        Cell nextExisting = getNext(existing);
        Cell nextUpdate = getNext(update);
        long timeDelta = Long.MAX_VALUE;
        while (nextExisting != null || nextUpdate != null)
        {
            int cmp = nextExisting == null ? 1
                     : (nextUpdate == null ? -1
                     : comparator.compare(nextExisting.path(), nextUpdate.path()));
            if (cmp < 0)
            {
                reconcile(clustering, nextExisting, null, deletion, builder, nowInSec, indexUpdater);
                nextExisting = getNext(existing);
            }
            else if (cmp > 0)
            {
                reconcile(clustering, null, nextUpdate, deletion, builder, nowInSec, indexUpdater);
                nextUpdate = getNext(update);
            }
            else
            {
                timeDelta = Math.min(timeDelta, reconcile(clustering, nextExisting, nextUpdate, deletion, builder, nowInSec, indexUpdater));
                nextExisting = getNext(existing);
                nextUpdate = getNext(update);
            }
        }
        return timeDelta;
    }

    private static Cell getNext(Iterator<Cell> iterator)
    {
        return iterator == null || !iterator.hasNext() ? null : iterator.next();
    }
}
