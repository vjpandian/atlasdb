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

package com.palantir.atlasdb.transaction.encoding;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.google.common.collect.Iterables;
import com.google.protobuf.ByteString;
import com.palantir.atlasdb.encoding.PtBytes;
import com.palantir.atlasdb.keyvalue.api.Cell;
import com.palantir.atlasdb.transaction.service.TransactionStatus;
import com.palantir.logsafe.exceptions.SafeIllegalArgumentException;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.LongStream;
import org.junit.Test;

public class TicketsEncodingStrategyTest {
    private static final TicketsEncodingStrategy STRATEGY = TicketsEncodingStrategy.INSTANCE;

    @Test
    public void canDistinguishNumericallyCloseTimestamps() {
        assertStartTimestampsCanBeDistinguished(LongStream.range(0, 1000).toArray());
    }

    @Test
    public void canDistinguishTimestampsAcrossRows() {
        long numRows = TicketsEncodingStrategy.ROWS_PER_QUANTUM;
        long offset = 318557;
        assertStartTimestampsCanBeDistinguished(offset, numRows + offset, 2 * numRows + offset);
    }

    @Test
    public void canDistinguishTimestampsAcrossQuanta() {
        long quantum = TicketsEncodingStrategy.PARTITIONING_QUANTUM;
        long offset = 318557;
        assertStartTimestampsCanBeDistinguished(offset, quantum + offset, 2 * quantum + offset);
    }

    @Test
    public void canDistinguishTimestampsAroundPartitioningQuantum() {
        long quantum = TicketsEncodingStrategy.PARTITIONING_QUANTUM;
        assertStartTimestampsCanBeDistinguished(
                0, 1, quantum - 1, quantum, quantum + 1, 2 * quantum - 1, 2 * quantum, 2 * quantum + 1);
    }

    @Test
    public void canDistinguishTimestampsAroundRowBoundary() {
        long numRows = TicketsEncodingStrategy.ROWS_PER_QUANTUM;
        assertStartTimestampsCanBeDistinguished(0, 1, numRows - 1, numRows, numRows + 1, 2 * numRows - 1);
    }

    @Test
    public void cellEncodeAndDecodeAreInverses() {
        fuzzOneThousandTrials(() -> {
            long timestamp = ThreadLocalRandom.current().nextLong(1, Long.MAX_VALUE);
            Cell encoded = STRATEGY.encodeStartTimestampAsCell(timestamp);
            assertThat(STRATEGY.decodeCellAsStartTimestamp(encoded)).isEqualTo(timestamp);
        });
    }

    @Test
    public void commitTimestampEncodeAndDecodeAreInverses() {
        fuzzOneThousandTrials(() -> {
            long startTimestamp = ThreadLocalRandom.current().nextLong(0, Long.MAX_VALUE - 1);
            long commitTimestamp = ThreadLocalRandom.current().nextLong(startTimestamp, Long.MAX_VALUE);
            byte[] encoded =
                    STRATEGY.encodeCommitStatusAsValue(startTimestamp, TransactionStatus.committed(commitTimestamp));
            assertThat(STRATEGY.decodeValueAsCommitStatus(startTimestamp, encoded))
                    .isEqualTo(TransactionStatus.committed(commitTimestamp));
        });
    }

    @Test
    public void storesLargeCommitTimestampsCompactly() {
        long highTimestamp = Long.MAX_VALUE - 1;
        byte[] commitTimestampEncoding =
                STRATEGY.encodeCommitStatusAsValue(highTimestamp, TransactionStatus.committed(highTimestamp + 1));
        assertThat(commitTimestampEncoding).hasSize(1);
        assertThat(STRATEGY.decodeValueAsCommitStatus(highTimestamp, commitTimestampEncoding))
                .isEqualTo(TransactionStatus.committed(highTimestamp + 1));
    }

    @Test
    public void distributesTimestampsEvenlyAcrossRows() {
        int elementsExpectedPerRow = 37;
        int timestampsPerPartition = TicketsEncodingStrategy.ROWS_PER_QUANTUM * elementsExpectedPerRow;
        int numPartitions = 13;

        Set<Cell> associatedCells = IntStream.range(0, numPartitions)
                .mapToObj(partitionNumber -> IntStream.range(0, timestampsPerPartition)
                        .mapToLong(timestampIndex ->
                                TicketsEncodingStrategy.PARTITIONING_QUANTUM * partitionNumber + timestampIndex)
                        .mapToObj(STRATEGY::encodeStartTimestampAsCell))
                .flatMap(x -> x)
                .collect(Collectors.toSet());

        // groupingBy evaluates arrays on instance equality, which is a no-go, so we need ByteStrings
        Map<ByteString, List<Cell>> cellsGroupedByRow =
                associatedCells.stream().collect(Collectors.groupingBy(cell -> ByteString.copyFrom(cell.getRowName())));
        for (List<Cell> cellsInEachRow : cellsGroupedByRow.values()) {
            assertThat(cellsInEachRow).hasSize(elementsExpectedPerRow);
        }
    }

    @Test
    public void distributesRowsEvenlyAcrossBitSpace() {
        int numPartitions = 16;
        Set<ByteString> rowNamesForInitialRows = IntStream.range(0, numPartitions)
                .mapToObj(STRATEGY::encodeStartTimestampAsCell)
                .map(Cell::getRowName)
                .map(ByteString::copyFrom)
                .collect(Collectors.toSet());
        assertThat(rowNamesForInitialRows).hasSize(numPartitions);

        // This test may be too strong.
        Map<Integer, Integer> rowsKeyedByFirstFourBits =
                rowNamesForInitialRows.stream()
                        .collect(Collectors.groupingBy(byteString -> byteString.byteAt(0) >> 4))
                        .entrySet()
                        .stream()
                        .collect(Collectors.toMap(
                                Map.Entry::getKey, entry -> entry.getValue().size()));
        assertThat(rowsKeyedByFirstFourBits.values()).containsOnly(1);
        assertThat(rowsKeyedByFirstFourBits).hasSize(16);
    }

    @Test
    public void canStoreTransactionsCommittingInstantaneously() {
        assertThatCode(() -> STRATEGY.encodeCommitStatusAsValue(10, TransactionStatus.committed(10L)))
                .doesNotThrowAnyException();
    }

    @Test
    public void canStoreAbortedTransactionCompactly() {
        assertThat(STRATEGY.encodeCommitStatusAsValue(537369, TransactionStatus.aborted()))
                .hasSize(0);
    }

    @Test
    public void encodingIllegalCommitThrows() {
        assertThatThrownBy(() -> STRATEGY.encodeCommitStatusAsValue(12327758, TransactionStatus.inProgress()))
                .isInstanceOf(SafeIllegalArgumentException.class);
        assertThatThrownBy(() -> STRATEGY.encodeCommitStatusAsValue(12327758, TransactionStatus.unknown()))
                .isInstanceOf(SafeIllegalArgumentException.class);
    }

    @Test
    public void canDecodeEmptyByteArrayAsAbortedTransaction() {
        assertThat(STRATEGY.decodeValueAsCommitStatus(1, PtBytes.EMPTY_BYTE_ARRAY))
                .isEqualTo(TransactionStatus.aborted());
        assertThat(STRATEGY.decodeValueAsCommitStatus(862846378267L, PtBytes.EMPTY_BYTE_ARRAY))
                .isEqualTo(TransactionStatus.aborted());
    }

    @Test
    public void canDecodeNullAsInProgressTransaction() {
        assertThat(STRATEGY.decodeValueAsCommitStatus(1, null)).isEqualTo(TransactionStatus.inProgress());
        assertThat(STRATEGY.decodeValueAsCommitStatus(862846378267L, null)).isEqualTo(TransactionStatus.inProgress());
    }

    @Test
    public void encodeRangeOfTimestampsAsRowsIncludesAllRowsInPartitioning() {
        assertThat(STRATEGY.getRowSetCoveringTimestampRange(2, 7)).containsExactlyElementsOf(allRowsInPartition(0));
    }

    @Test
    public void encodeRangeOfTimestampsAsRowsIncludesAllRowsInEachPartitioning() {
        assertThat(STRATEGY.getRowSetCoveringTimestampRange(
                        2 * TicketsEncodingStrategy.PARTITIONING_QUANTUM - 1,
                        4 * TicketsEncodingStrategy.PARTITIONING_QUANTUM + 1))
                .containsExactlyElementsOf(Iterables.concat(
                        allRowsInPartition(1), allRowsInPartition(2), allRowsInPartition(3), allRowsInPartition(4)));
    }

    @Test
    public void encodeRangeOfTimestampsAsRowsIncludesAllRowsInEachPartitioningRangesClosed() {
        assertThat(STRATEGY.getRowSetCoveringTimestampRange(
                        2 * TicketsEncodingStrategy.PARTITIONING_QUANTUM,
                        4 * TicketsEncodingStrategy.PARTITIONING_QUANTUM))
                .containsExactlyElementsOf(
                        Iterables.concat(allRowsInPartition(2), allRowsInPartition(3), allRowsInPartition(4)));
    }

    private static List<byte[]> allRowsInPartition(int partition) {
        return LongStream.range(0, TicketsEncodingStrategy.ROWS_PER_QUANTUM)
                .map(offset -> offset + partition * TicketsEncodingStrategy.PARTITIONING_QUANTUM)
                .mapToObj(TicketsEncodingStrategy.INSTANCE::encodeStartTimestampAsCell)
                .map(Cell::getRowName)
                .collect(Collectors.toList());
    }

    private static void fuzzOneThousandTrials(Runnable test) {
        IntStream.range(0, 1000).forEach(unused -> test.run());
    }

    private static void assertStartTimestampsCanBeDistinguished(long... timestamps) {
        Set<Cell> convertedCells = Arrays.stream(timestamps)
                .boxed()
                .map(STRATEGY::encodeStartTimestampAsCell)
                .collect(Collectors.toSet());
        assertThat(convertedCells).hasSameSizeAs(timestamps);
    }
}
