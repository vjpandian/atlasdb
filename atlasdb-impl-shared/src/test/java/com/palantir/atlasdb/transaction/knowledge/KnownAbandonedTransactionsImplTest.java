/*
 * (c) Copyright 2022 Palantir Technologies Inc. All rights reserved.
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

package com.palantir.atlasdb.transaction.knowledge;

import static org.assertj.core.api.AssertionsForClassTypes.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Range;
import com.palantir.atlasdb.AtlasDbConstants;
import com.palantir.atlasdb.transaction.knowledge.AbandonedTransactionSoftCache.TransactionSoftCacheStatus;
import com.palantir.tritium.metrics.registry.DefaultTaggedMetricRegistry;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.LongStream;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;

@RunWith(MockitoJUnitRunner.class)
public final class KnownAbandonedTransactionsImplTest {
    @Mock
    AbandonedTimestampStore abandonedTimestampStore;

    @Mock
    AbandonedTransactionSoftCache softCache;

    private KnownAbandonedTransactionsImpl knownAbortedTransactions;

    @Before
    public void before() {
        knownAbortedTransactions = new KnownAbandonedTransactionsImpl(
                abandonedTimestampStore,
                softCache,
                new DefaultTaggedMetricRegistry(),
                KnownAbandonedTransactionsImpl.MAXIMUM_CACHE_WEIGHT);
    }

    @Test
    public void testIsKnownAbortedReturnsTrueIfAbortedInSoftCache() {
        long abortedTimestamp = 27L;
        when(softCache.getSoftCacheTransactionStatus(abortedTimestamp))
                .thenReturn(TransactionSoftCacheStatus.IS_ABORTED);

        assertThat(knownAbortedTransactions.isKnownAbandoned(abortedTimestamp)).isTrue();
        verifyNoMoreInteractions(abandonedTimestampStore);
    }

    @Test
    public void testIsKnownAbortedReturnsFalseIfNotAbortedInSoftCache() {
        long abortedTimestamp = 27L;
        when(softCache.getSoftCacheTransactionStatus(abortedTimestamp))
                .thenReturn(TransactionSoftCacheStatus.IS_NOT_ABORTED);

        assertThat(knownAbortedTransactions.isKnownAbandoned(abortedTimestamp)).isFalse();
        verifyNoMoreInteractions(abandonedTimestampStore);
    }

    @Test
    public void testIsKnownAbortedLoadsFromReliableCache() {
        when(softCache.getSoftCacheTransactionStatus(anyLong()))
                .thenReturn(AbandonedTransactionSoftCache.TransactionSoftCacheStatus.PENDING_LOAD_FROM_RELIABLE);

        long abortedTimestamp = 27L;
        Bucket bucket = Bucket.forTimestamp(abortedTimestamp);
        Set<Long> abortedTimestamps = ImmutableSet.of(abortedTimestamp);

        when(abandonedTimestampStore.getAbandonedTimestampsInRange(anyLong(), anyLong()))
                .thenReturn(abortedTimestamps);

        assertThat(knownAbortedTransactions.isKnownAbandoned(abortedTimestamp)).isTrue();
        verify(abandonedTimestampStore)
                .getAbandonedTimestampsInRange(bucket.getMinTsInBucket(), bucket.getMaxTsInCurrentBucket());

        // a second call will load state from the cache
        assertThat(knownAbortedTransactions.isKnownAbandoned(abortedTimestamp + 1))
                .isFalse();
        verifyNoMoreInteractions(abandonedTimestampStore);
    }

    @Test
    public void testIsKnownAbortedLoadsFromRemoteIfBucketNotInReliableCache() {
        when(softCache.getSoftCacheTransactionStatus(anyLong()))
                .thenReturn(AbandonedTransactionSoftCache.TransactionSoftCacheStatus.PENDING_LOAD_FROM_RELIABLE);

        long abortedTimestampBucket1 = 27L;
        Bucket bucket1 = Bucket.forTimestamp(abortedTimestampBucket1);

        long abortedTimestampBucket2 = AtlasDbConstants.ABORTED_TIMESTAMPS_BUCKET_SIZE + 27L;
        Bucket bucket2 = Bucket.forTimestamp(abortedTimestampBucket2);

        when(abandonedTimestampStore.getAbandonedTimestampsInRange(anyLong(), anyLong()))
                .thenReturn(ImmutableSet.of(abortedTimestampBucket1))
                .thenReturn(ImmutableSet.of(abortedTimestampBucket2));

        // First call for bucket1 loads from remote
        assertThat(knownAbortedTransactions.isKnownAbandoned(abortedTimestampBucket1))
                .isTrue();
        verify(abandonedTimestampStore)
                .getAbandonedTimestampsInRange(bucket1.getMinTsInBucket(), bucket1.getMaxTsInCurrentBucket());

        // First call for bucket2 loads from remote
        assertThat(knownAbortedTransactions.isKnownAbandoned(abortedTimestampBucket2))
                .isTrue();
        verify(abandonedTimestampStore)
                .getAbandonedTimestampsInRange(bucket2.getMinTsInBucket(), bucket2.getMaxTsInCurrentBucket());

        // a second call will load state from the cache
        assertThat(knownAbortedTransactions.isKnownAbandoned(abortedTimestampBucket1 + 1))
                .isFalse();
        assertThat(knownAbortedTransactions.isKnownAbandoned(abortedTimestampBucket2 + 1))
                .isFalse();
        verifyNoMoreInteractions(abandonedTimestampStore);
    }

    @Test
    public void testReliableCacheEvictsIfWeightLimitReached() {
        when(softCache.getSoftCacheTransactionStatus(anyLong()))
                .thenReturn(AbandonedTransactionSoftCache.TransactionSoftCacheStatus.PENDING_LOAD_FROM_RELIABLE);

        long numAbortedTimestampsInBucket = Math.min(
                AtlasDbConstants.ABORTED_TIMESTAMPS_BUCKET_SIZE, KnownAbandonedTransactionsImpl.MAXIMUM_CACHE_WEIGHT);
        when(abandonedTimestampStore.getAbandonedTimestampsInRange(anyLong(), anyLong()))
                .thenAnswer(invocation -> {
                    long start = invocation.getArgument(0);
                    return LongStream.range(start, start + numAbortedTimestampsInBucket)
                            .boxed()
                            .collect(Collectors.toSet());
                });

        Bucket bucket = Bucket.ofIndex(1);
        Range<Long> rangeForBucket = Range.closed(bucket.getMinTsInBucket(), bucket.getMaxTsInCurrentBucket());

        // First query for bucket 1 goes to the store
        knownAbortedTransactions.isKnownAbandoned(rangeForBucket.lowerEndpoint());
        verify(abandonedTimestampStore)
                .getAbandonedTimestampsInRange(eq(rangeForBucket.lowerEndpoint()), eq(rangeForBucket.upperEndpoint()));

        // Subsequent queries for bucket 1 are resolved from cache
        knownAbortedTransactions.isKnownAbandoned(rangeForBucket.lowerEndpoint());
        verifyNoMoreInteractions(abandonedTimestampStore);

        Bucket bucket2 = Bucket.ofIndex(2);
        // caching a second bucket will cross the threshold weight of cache, marking first bucket for eviction
        knownAbortedTransactions.isKnownAbandoned(bucket2.getMinTsInBucket());

        knownAbortedTransactions.cleanup();

        // Now the query for bucket 1 will go to the futile store due to cache eviction
        knownAbortedTransactions.isKnownAbandoned(rangeForBucket.lowerEndpoint());
        verify(abandonedTimestampStore, atLeastOnce())
                .getAbandonedTimestampsInRange(eq(rangeForBucket.lowerEndpoint()), eq(rangeForBucket.upperEndpoint()));
    }

    @Test
    public void testAddAbortedTransactionsDelegatesToFutileStore() {
        ImmutableSet<Long> abortedTimestamps = ImmutableSet.of(25L, 49L);
        knownAbortedTransactions.addAbandonedTimestamps(abortedTimestamps);
        abortedTimestamps.forEach(ts -> verify(abandonedTimestampStore).markAbandoned(ts));
    }
}
