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
package com.palantir.atlasdb;

import static org.mockito.Mockito.spy;

import com.google.common.util.concurrent.MoreExecutors;
import com.palantir.atlasdb.cache.DefaultTimestampCache;
import com.palantir.atlasdb.keyvalue.api.KeyValueService;
import com.palantir.atlasdb.keyvalue.api.TableReference;
import com.palantir.atlasdb.keyvalue.impl.InMemoryKeyValueService;
import com.palantir.atlasdb.keyvalue.impl.StatsTrackingKeyValueService;
import com.palantir.atlasdb.keyvalue.impl.TracingKeyValueService;
import com.palantir.atlasdb.keyvalue.impl.TrackingKeyValueService;
import com.palantir.atlasdb.sweep.queue.SpecialTimestampsSupplier;
import com.palantir.atlasdb.sweep.queue.TargetedSweeper;
import com.palantir.atlasdb.transaction.api.AtlasDbConstraintCheckingMode;
import com.palantir.atlasdb.transaction.api.ConflictHandler;
import com.palantir.atlasdb.transaction.impl.CachingTestTransactionManager;
import com.palantir.atlasdb.transaction.impl.ConflictDetectionManager;
import com.palantir.atlasdb.transaction.impl.ConflictDetectionManagers;
import com.palantir.atlasdb.transaction.impl.SweepStrategyManager;
import com.palantir.atlasdb.transaction.impl.SweepStrategyManagers;
import com.palantir.atlasdb.transaction.impl.TestTransactionManager;
import com.palantir.atlasdb.transaction.impl.TestTransactionManagerImpl;
import com.palantir.atlasdb.transaction.impl.TransactionTables;
import com.palantir.atlasdb.transaction.knowledge.TransactionKnowledgeComponents;
import com.palantir.atlasdb.transaction.service.TransactionService;
import com.palantir.atlasdb.transaction.service.TransactionServices;
import com.palantir.atlasdb.util.AtlasDbMetrics;
import com.palantir.atlasdb.util.MetricsManager;
import com.palantir.atlasdb.util.MetricsManagers;
import com.palantir.common.concurrent.PTExecutors;
import com.palantir.lock.LockClient;
import com.palantir.lock.LockService;
import com.palantir.lock.v2.TimelockService;
import com.palantir.timelock.paxos.InMemoryTimeLockRule;
import com.palantir.timestamp.TimestampService;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;

public class AtlasDbTestCase {
    private static final String CLIENT = "fake lock client";

    protected final MetricsManager metricsManager = MetricsManagers.createForTests();

    protected LockClient lockClient;
    protected LockService lockService;
    protected TrackingKeyValueService keyValueService;
    protected TimelockService timelockService;
    protected TimestampService timestampService;
    protected ConflictDetectionManager conflictDetectionManager;
    protected SweepStrategyManager sweepStrategyManager;
    protected TestTransactionManager serializableTxManager;
    protected TestTransactionManager txManager;
    protected TransactionService transactionService;
    protected TargetedSweeper sweepQueue;
    protected SpecialTimestampsSupplier sweepTimestampSupplier;
    protected int sweepQueueShards = 128;

    protected TransactionKnowledgeComponents knowledge;

    @Rule
    public InMemoryTimeLockRule inMemoryTimeLockRule = new InMemoryTimeLockRule(CLIENT);

    @Before
    public void setUp() throws Exception {
        lockClient = LockClient.of(CLIENT);
        lockService = inMemoryTimeLockRule.getLockService();
        timelockService = inMemoryTimeLockRule.getLegacyTimelockService();
        timestampService = inMemoryTimeLockRule.getTimestampService();
        keyValueService = trackingKeyValueService(getBaseKeyValueService());
        TransactionTables.createTables(keyValueService);
        transactionService = spy(TransactionServices.createRaw(keyValueService, timestampService, false));
        conflictDetectionManager = ConflictDetectionManagers.createWithoutWarmingCache(keyValueService);
        sweepStrategyManager = SweepStrategyManagers.createDefault(keyValueService);
        sweepQueue = spy(TargetedSweeper.createUninitializedForTest(() -> sweepQueueShards));
        knowledge = TransactionKnowledgeComponents.createForTests(keyValueService, metricsManager.getTaggedRegistry());
        setUpTransactionManagers();
        sweepQueue.initialize(serializableTxManager);
        sweepTimestampSupplier = new SpecialTimestampsSupplier(
                () -> txManager.getUnreadableTimestamp(), () -> txManager.getImmutableTimestamp());
    }

    private void setUpTransactionManagers() {
        serializableTxManager = constructTestTransactionManager();

        txManager = new CachingTestTransactionManager(serializableTxManager);
    }

    private TrackingKeyValueService trackingKeyValueService(KeyValueService originalKeyValueService) {
        return spy(new TrackingKeyValueService(new StatsTrackingKeyValueService(originalKeyValueService)));
    }

    protected TestTransactionManager constructTestTransactionManager() {
        return new TestTransactionManagerImpl(
                metricsManager,
                keyValueService,
                inMemoryTimeLockRule.get(),
                lockService,
                transactionService,
                conflictDetectionManager,
                sweepStrategyManager,
                DefaultTimestampCache.createForTests(),
                sweepQueue,
                knowledge,
                MoreExecutors.newDirectExecutorService());
    }

    protected KeyValueService getBaseKeyValueService() {
        ExecutorService executor = PTExecutors.newSingleThreadExecutor();
        InMemoryKeyValueService inMemoryKvs = new InMemoryKeyValueService(false, executor);
        KeyValueService tracingKvs = TracingKeyValueService.create(inMemoryKvs);
        return AtlasDbMetrics.instrument(metricsManager.getRegistry(), KeyValueService.class, tracingKvs);
    }

    @After
    public void tearDown() throws Exception {
        // JUnit keeps instantiated test cases in memory, so we need to null out
        // some fields to prevent OOMs.
        keyValueService.close();
        keyValueService = null;
        transactionService.close();
        transactionService = null;
        sweepQueue.close();
        sweepQueue = null;
        timestampService = null;
        txManager.close();
        txManager = null;
    }

    protected void overrideConflictHandlerForTable(TableReference table, ConflictHandler conflictHandler) {
        txManager.overrideConflictHandlerForTable(table, Optional.of(conflictHandler));
    }

    protected void setConstraintCheckingMode(AtlasDbConstraintCheckingMode mode) {
        txManager = new TestTransactionManagerImpl(
                metricsManager,
                keyValueService,
                inMemoryTimeLockRule.getTimestampManagementService(),
                inMemoryTimeLockRule.getLegacyTimelockService(),
                lockService,
                inMemoryTimeLockRule.getLockWatchManager(),
                transactionService,
                mode,
                TransactionKnowledgeComponents.createForTests(keyValueService, metricsManager.getTaggedRegistry()));
    }

    protected void clearTablesWrittenTo() {
        keyValueService.clearTablesWrittenTo();
    }

    protected void clearTablesReadFrom() {
        keyValueService.clearTablesReadFrom();
    }

    protected Set<TableReference> getTablesWrittenTo() {
        return keyValueService.getTablesWrittenTo();
    }

    protected Set<TableReference> getTablesReadFrom() {
        return keyValueService.getTablesReadFrom();
    }

    protected boolean wasTableWrittenTo(TableReference tableName) {
        return getTablesWrittenTo().contains(tableName);
    }

    protected boolean wasTableReadFrom(TableReference tableName) {
        return getTablesReadFrom().contains(tableName);
    }
}
