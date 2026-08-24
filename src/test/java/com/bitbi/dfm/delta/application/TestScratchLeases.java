package com.bitbi.dfm.delta.application;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

/**
 * A real, unbounded {@link ParquetScratchBudget} for the writer tests that are about a
 * <em>per-file</em> ceiling (issue #150).
 *
 * <p>A stub lease would be cheaper and would prove less: the writers charge the budget from the same
 * {@code checkCapacity} that enforces their own ceiling, so a test that passes a no-op cannot tell a
 * writer that charges correctly from one that does not charge at all. The directory bound itself is
 * covered by {@link ParquetScratchBudgetTest} and the per-writer refusal by the writers' own
 * tests.</p>
 */
final class TestScratchLeases {

    private TestScratchLeases() {
    }

    /** A lease on a budget that refuses nothing, for a test about some other limit. */
    static ScratchLease unbounded() {
        return unboundedBudget().open(ParquetScratchBudget.BATCH_ARTIFACT);
    }

    /** A budget that refuses nothing. */
    static ParquetScratchBudget unboundedBudget() {
        return new ParquetScratchBudget(new SimpleMeterRegistry(), 0L);
    }

    /** A budget with room for exactly {@code maxBytes} across every live writer. */
    static ParquetScratchBudget budgetOf(long maxBytes) {
        return new ParquetScratchBudget(new SimpleMeterRegistry(), maxBytes);
    }

    /**
     * A budget whose batch writers stop at {@code maxBytes - checkpointReserveBytes}, so a
     * checkpoint frame always has a reserved share (issue #193).
     */
    static ParquetScratchBudget budgetOf(long maxBytes, long checkpointReserveBytes) {
        return new ParquetScratchBudget(new SimpleMeterRegistry(), maxBytes, checkpointReserveBytes);
    }
}
