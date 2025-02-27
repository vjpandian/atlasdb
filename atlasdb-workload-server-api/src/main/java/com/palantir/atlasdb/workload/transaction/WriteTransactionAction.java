/*
 * (c) Copyright 2023 Palantir Technologies Inc. All rights reserved.
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

package com.palantir.atlasdb.workload.transaction;

import com.palantir.atlasdb.workload.store.WorkloadCell;
import com.palantir.atlasdb.workload.transaction.witnessed.ImmutableWitnessedWriteTransactionAction;
import com.palantir.atlasdb.workload.transaction.witnessed.WitnessedWriteTransactionAction;
import org.immutables.value.Value;

@Value.Immutable
public interface WriteTransactionAction extends TransactionAction {

    @Override
    @Value.Parameter
    String table();

    @Override
    @Value.Parameter
    WorkloadCell cell();

    /**
     * Value for the given cell.
     */
    @Value.Parameter
    Integer value();

    @Override
    default <T> T accept(TransactionActionVisitor<T> visitor) {
        return visitor.visit(this);
    }

    default WitnessedWriteTransactionAction witness() {
        return ImmutableWitnessedWriteTransactionAction.builder()
                .table(table())
                .cell(cell())
                .value(value())
                .build();
    }

    static WriteTransactionAction of(String table, WorkloadCell cell, Integer value) {
        return ImmutableWriteTransactionAction.of(table, cell, value);
    }
}
