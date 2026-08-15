package com.git_pull.libtmux.batch;

import java.util.List;
import java.util.Optional;

/**
 * What became of every operation in one batch.
 *
 * @param operations one result per submitted operation, in submission order
 */
public record BatchResult(List<OperationResult> operations) {

    public BatchResult {
        operations = List.copyOf(operations);
    }

    /** Whether tmux ran every operation and all of them succeeded. */
    public boolean succeeded() {
        return operations.stream().allMatch(OperationResult::succeeded);
    }

    /** The operation that failed, if one did. Everything after it was discarded by tmux. */
    public Optional<OperationResult> failure() {
        return operations.stream()
                .filter(operation -> operation.outcome() == OperationOutcome.FAILED)
                .findFirst();
    }

    @Override
    public String toString() {
        return "BatchResult[operations=" + operations.size() + ", succeeded=" + succeeded() + "]";
    }
}
