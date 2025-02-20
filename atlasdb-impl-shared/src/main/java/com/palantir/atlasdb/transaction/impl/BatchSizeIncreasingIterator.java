/*
 * (c) Copyright 2018 Palantir Technologies Inc. All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.palantir.atlasdb.transaction.impl;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.Iterators;
import com.palantir.atlasdb.AtlasDbPerformanceConstants;
import com.palantir.common.base.ClosableIterator;
import com.palantir.common.base.ClosableIterators;
import com.palantir.logsafe.Preconditions;
import com.palantir.logsafe.logger.SafeLogger;
import com.palantir.logsafe.logger.SafeLoggerFactory;
import com.palantir.util.AssertUtils;
import java.io.Closeable;
import java.util.List;
import javax.annotation.Nullable;
import org.immutables.value.Value;

public class BatchSizeIncreasingIterator<T> implements Closeable {
    private static final SafeLogger log = SafeLoggerFactory.get(BatchSizeIncreasingIterator.class);

    private final int originalBatchSize;
    private final BatchProvider<T> batchProvider;

    private ClosableIterator<T> currentResults;
    private byte[] lastToken;

    private long numReturned = 0;
    private long numNotDeleted = 0;
    private int lastBatchSize;

    public BatchSizeIncreasingIterator(
            BatchProvider<T> batchProvider, int originalBatchSize, @Nullable ClosableIterator<T> currentResults) {
        Preconditions.checkArgument(originalBatchSize > 0);
        this.batchProvider = batchProvider;
        this.originalBatchSize = originalBatchSize;
        this.currentResults = currentResults;
        if (currentResults != null) {
            this.lastBatchSize = originalBatchSize;
        }
    }

    public void markNumResultsNotDeleted(int resultsInBatch) {
        numNotDeleted += resultsInBatch;
        AssertUtils.assertAndLog(
                log, numNotDeleted <= numReturned, "NotDeleted is bigger than the number of results we returned.");
    }

    int getBestBatchSize() {
        if (numReturned == 0) {
            return originalBatchSize;
        }
        final long batchSize;
        long maxNewBatchSize = numReturned * 4;
        if (numNotDeleted == 0) {
            // If everything we've seen has been deleted, we should be aggressive about getting more rows.
            batchSize = maxNewBatchSize;
        } else {
            batchSize = Math.min(
                    (long) Math.ceil(originalBatchSize * (numReturned / (double) numNotDeleted)), maxNewBatchSize);
        }
        return (int) Math.min(batchSize, AtlasDbPerformanceConstants.MAX_BATCH_SIZE);
    }

    private void updateResultsIfNeeded() {
        if (currentResults == null) {
            currentResults = batchProvider.getBatch(originalBatchSize, null);
            lastBatchSize = originalBatchSize;
            return;
        }

        // We have current results and have not read them.
        if (lastToken == null) {
            return;
        }

        // If the last row we got was the maximal row, then we are done.
        if (!batchProvider.hasNext(lastToken)) {
            currentResults =
                    ClosableIterators.wrapWithEmptyClose(ImmutableList.<T>of().iterator());
            return;
        }

        int bestBatchSize = getBestBatchSize();
        // Only close and throw away our old iterator if the batch size has changed by a factor of 2 or more.
        if (bestBatchSize >= lastBatchSize * 2 || bestBatchSize <= lastBatchSize / 2) {
            currentResults.close();
            currentResults = batchProvider.getBatch(bestBatchSize, lastToken);
            lastBatchSize = bestBatchSize;
        }
    }

    public BatchResult<T> getBatch() {
        updateResultsIfNeeded();
        Preconditions.checkState(lastBatchSize > 0);
        ImmutableList<T> list = ImmutableList.copyOf(Iterators.limit(currentResults, lastBatchSize));

        boolean isLastBatch = list.size() < lastBatchSize || !currentResults.hasNext();
        numReturned += list.size();
        if (!list.isEmpty()) {
            lastToken = batchProvider.getLastToken(list);
        }
        return ImmutableBatchResult.of(list, isLastBatch);
    }

    @Value.Immutable
    interface BatchResult<T> {
        @Value.Parameter
        List<T> batch();

        @Value.Parameter
        boolean isLastBatch();
    }

    @Override
    public void close() {
        if (currentResults != null) {
            currentResults.close();
            currentResults = null;
            lastToken = null;
        }
    }
}
