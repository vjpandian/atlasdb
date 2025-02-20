/*
 * (c) Copyright 2019 Palantir Technologies Inc. All rights reserved.
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

package com.palantir.lock.client;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.Sets;
import com.palantir.atlasdb.timelock.api.ConjureLockToken;
import com.palantir.atlasdb.timelock.api.ConjureLockTokenV2;
import com.palantir.atlasdb.timelock.api.ConjureRefreshLocksRequestV2;
import com.palantir.atlasdb.timelock.api.ConjureRefreshLocksResponseV2;
import com.palantir.atlasdb.timelock.api.ConjureStartTransactionsRequest;
import com.palantir.atlasdb.timelock.api.ConjureStartTransactionsResponse;
import com.palantir.atlasdb.timelock.api.GetCommitTimestampsRequest;
import com.palantir.atlasdb.timelock.api.GetCommitTimestampsResponse;
import com.palantir.lock.v2.LeaderTime;
import com.palantir.lock.v2.Lease;
import com.palantir.lock.v2.LockImmutableTimestampResponse;
import com.palantir.lock.v2.LockRequest;
import com.palantir.lock.v2.LockResponse;
import com.palantir.lock.v2.LockToken;
import com.palantir.lock.v2.StartTransactionResponseV4;
import com.palantir.lock.v2.WaitForLocksRequest;
import com.palantir.lock.v2.WaitForLocksResponse;
import com.palantir.lock.watch.LockWatchVersion;
import com.palantir.logsafe.Preconditions;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@SuppressWarnings("DangerousIdentityKey")
public class LockLeaseService implements AutoCloseable {
    private final NamespacedConjureTimelockService delegate;
    private final UUID clientId;
    private final LeaderTimeGetter leaderTimeGetter;
    private final LockTokenUnlocker unlocker;
    private final BlockEnforcingLockService lockService;

    @VisibleForTesting
    LockLeaseService(
            NamespacedConjureTimelockService delegate,
            UUID clientId,
            LeaderTimeGetter leaderTimeGetter,
            LockTokenUnlocker unlocker) {
        this.delegate = delegate;
        this.clientId = clientId;
        this.leaderTimeGetter = leaderTimeGetter;
        this.lockService = BlockEnforcingLockService.create(delegate);
        this.unlocker = unlocker;
    }

    public static LockLeaseService create(
            NamespacedConjureTimelockService conjureTimelock,
            LeaderTimeGetter leaderTimeGetter,
            LockTokenUnlocker unlocker) {
        return new LockLeaseService(conjureTimelock, UUID.randomUUID(), leaderTimeGetter, unlocker);
    }

    LockImmutableTimestampResponse lockImmutableTimestamp() {
        return startTransactions(1).immutableTimestamp();
    }

    StartTransactionResponseV4 startTransactions(int batchSize) {
        ConjureStartTransactionsRequest request = ConjureStartTransactionsRequest.builder()
                .requestorId(clientId)
                .requestId(UUID.randomUUID())
                .numTransactions(batchSize)
                .lastKnownVersion(Optional.empty())
                .build();
        ConjureStartTransactionsResponse conjureResponse = delegate.startTransactions(request);
        StartTransactionResponseV4 response = StartTransactionResponseV4.of(
                conjureResponse.getImmutableTimestamp(), conjureResponse.getTimestamps(), conjureResponse.getLease());

        Lease lease = response.lease();
        LeasedLockToken leasedLockToken = LeasedLockToken.of(
                ConjureLockToken.of(response.immutableTimestamp().getLock().getRequestId()), lease);
        long immutableTs = response.immutableTimestamp().getImmutableTimestamp();

        return StartTransactionResponseV4.of(
                LockImmutableTimestampResponse.of(immutableTs, leasedLockToken), response.timestamps(), lease);
    }

    // Visible for testing
    public ConjureStartTransactionsResponse startTransactionsWithWatches(
            Optional<LockWatchVersion> maybeVersion, int batchSize) {
        ConjureStartTransactionsRequest request = ConjureStartTransactionsRequest.builder()
                .requestorId(clientId)
                .requestId(UUID.randomUUID())
                .numTransactions(batchSize)
                .lastKnownVersion(ConjureLockRequests.toConjure(maybeVersion))
                .build();
        return assignLeasedLockTokenToImmutableTimestampLock(delegate.startTransactions(request));
    }

    static ConjureStartTransactionsResponse assignLeasedLockTokenToImmutableTimestampLock(
            ConjureStartTransactionsResponse response) {
        Lease lease = response.getLease();
        LeasedLockToken leasedLockToken = LeasedLockToken.of(
                ConjureLockToken.of(response.getImmutableTimestamp().getLock().getRequestId()), lease);
        long immutableTs = response.getImmutableTimestamp().getImmutableTimestamp();
        return ConjureStartTransactionsResponse.builder()
                .lease(lease)
                .immutableTimestamp(LockImmutableTimestampResponse.of(immutableTs, leasedLockToken))
                .timestamps(response.getTimestamps())
                .lockWatchUpdate(response.getLockWatchUpdate())
                .build();
    }

    GetCommitTimestampsResponse getCommitTimestamps(Optional<LockWatchVersion> maybeVersion, int batchSize) {
        GetCommitTimestampsRequest request = GetCommitTimestampsRequest.builder()
                .numTimestamps(batchSize)
                .lastKnownVersion(ConjureLockRequests.toConjure(maybeVersion))
                .build();
        return delegate.getCommitTimestamps(request);
    }

    LockResponse lock(LockRequest request) {
        return lockService.lock(request);
    }

    WaitForLocksResponse waitForLocks(WaitForLocksRequest request) {
        return lockService.waitForLocks(request);
    }

    Set<LockToken> refreshLockLeases(Set<LockToken> uncastedTokens) {
        if (uncastedTokens.isEmpty()) {
            return uncastedTokens;
        }

        LeaderTime leaderTime = leaderTimeGetter.leaderTime();
        Set<LeasedLockToken> allTokens = leasedTokens(uncastedTokens);

        Set<LeasedLockToken> validByLease =
                allTokens.stream().filter(token -> token.isValid(leaderTime)).collect(Collectors.toSet());

        Set<LeasedLockToken> toRefresh = Sets.difference(allTokens, validByLease);
        Set<LeasedLockToken> refreshedTokens = refreshTokens(toRefresh);

        return Sets.union(refreshedTokens, validByLease);
    }

    Set<LockToken> unlock(Set<LockToken> tokens) {
        if (tokens.isEmpty()) {
            return tokens;
        }
        Set<LeasedLockToken> leasedLockTokens = leasedTokens(tokens);
        leasedLockTokens.forEach(LeasedLockToken::invalidate);

        Set<ConjureLockTokenV2> unlocked = unlocker.unlock(serverTokens(leasedLockTokens));
        return leasedLockTokens.stream()
                .filter(leasedLockToken ->
                        unlocked.contains(LockLeaseService.convertV1Token(leasedLockToken.serverToken())))
                .collect(Collectors.toSet());
    }

    private Set<LeasedLockToken> refreshTokens(Set<LeasedLockToken> leasedTokens) {
        if (leasedTokens.isEmpty()) {
            return leasedTokens;
        }

        ConjureRefreshLocksResponseV2 refreshLockResponse =
                delegate.refreshLocksV2(ConjureRefreshLocksRequestV2.of(serverTokens(leasedTokens)));
        Lease lease = refreshLockResponse.getLease();

        Set<LeasedLockToken> refreshedTokens = leasedTokens.stream()
                .filter(t -> refreshLockResponse
                        .getRefreshedTokens()
                        .contains(LockLeaseService.convertV1Token(t.serverToken())))
                .collect(Collectors.toSet());

        refreshedTokens.forEach(t -> t.updateLease(lease));
        return refreshedTokens;
    }

    @SuppressWarnings("unchecked")
    private static Set<LeasedLockToken> leasedTokens(Set<LockToken> tokens) {
        for (LockToken token : tokens) {
            Preconditions.checkArgument(
                    token instanceof LeasedLockToken, "All lock tokens should be an instance of LeasedLockToken");
        }
        return (Set<LeasedLockToken>) (Set<?>) tokens;
    }

    private static Set<ConjureLockTokenV2> serverTokens(Set<LeasedLockToken> leasedTokens) {
        return leasedTokens.stream()
                .map(LeasedLockToken::serverToken)
                .map(LockLeaseService::convertV1Token)
                .collect(Collectors.toSet());
    }

    private static ConjureLockTokenV2 convertV1Token(ConjureLockToken conjureLockToken) {
        return ConjureLockTokenV2.of(conjureLockToken.getRequestId());
    }

    @Override
    public void close() {
        leaderTimeGetter.close();
    }
}
