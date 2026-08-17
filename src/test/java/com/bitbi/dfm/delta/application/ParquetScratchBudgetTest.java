package com.bitbi.dfm.delta.application;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The directory-wide scratch budget (issue #150) — the first bound on the <em>sum</em> of the
 * file-backed Parquet scratch, where every ceiling before it bounded one file.
 */
class ParquetScratchBudgetTest {

    private static final long MIB = 1024L * 1024;

    private MeterRegistry registry;

    @BeforeEach
    void setUp() {
        registry = new SimpleMeterRegistry();
    }

    private ParquetScratchBudget budget(long maxBytes) {
        return new ParquetScratchBudget(registry, maxBytes);
    }

    @Test
    void boundsTheSumOfLiveScratchRatherThanOneFile() {
        ParquetScratchBudget budget = budget(8 * MIB);

        ScratchLease first = budget.open("batch_artifact");
        ScratchLease second = budget.open("batch_artifact");
        first.charge(5 * MIB);

        // Neither writer has crossed a per-file ceiling; together they have crossed the directory.
        assertThrows(ScratchBudgetExceededException.class, () -> second.charge(5 * MIB));
    }

    @Test
    void releasingALeaseReturnsItsBytesToTheDirectory() {
        ParquetScratchBudget budget = budget(8 * MIB);

        try (ScratchLease first = budget.open("checkpoint_table")) {
            first.charge(7 * MIB);
        }

        try (ScratchLease second = budget.open("checkpoint_table")) {
            second.charge(7 * MIB);
        }
        assertEquals(0.0, liveBytes(), "every lease released its bytes");
    }

    @Test
    void closingALeaseTwiceReleasesItsBytesOnlyOnce() {
        ParquetScratchBudget budget = budget(8 * MIB);

        ScratchLease lease = budget.open("checkpoint_frame");
        lease.charge(4 * MIB);
        lease.close();
        lease.close();

        assertEquals(0.0, liveBytes());
        // A double release that went negative would silently hand the next writer more than the
        // budget — the one way an accounting slip becomes an eviction.
        try (ScratchLease next = budget.open("checkpoint_frame")) {
            next.charge(8 * MIB);
            assertThrows(ScratchBudgetExceededException.class, () -> next.charge(1));
        }
    }

    @Test
    void aChargeThatDoesNotFitLeavesTheDirectoryUntouched() {
        ParquetScratchBudget budget = budget(4 * MIB);

        try (ScratchLease lease = budget.open("batch_artifact")) {
            assertThrows(ScratchBudgetExceededException.class, () -> lease.charge(5 * MIB));
            // A refusal must not leave the refused bytes reserved: the writer is about to be
            // unwound and its file deleted, so charging for them would shrink the directory for
            // everybody else until the pod restarts.
            assertTrue(liveBytes() <= 4 * MIB, "a refused charge reserves nothing beyond the budget");
        }
        assertEquals(0.0, liveBytes());
    }

    @Test
    void tracksLiveBytesEvenWhenNoBudgetIsConfigured() {
        ParquetScratchBudget budget = budget(0L);

        try (ScratchLease lease = budget.open("checkpoint_table")) {
            lease.charge(64 * MIB);
            // Unbounded is the shipped default, so this gauge is the only way an operator can size
            // the key before turning it on. Refusing nothing must not mean measuring nothing.
            assertTrue(liveBytes() >= 64 * MIB, "the gauge must follow the writers when unbounded");
        }
        assertEquals(0.0, liveBytes());
    }

    @Test
    void neverRefusesWhenNoBudgetIsConfigured() {
        ParquetScratchBudget budget = budget(0L);

        try (ScratchLease lease = budget.open("checkpoint_frame")) {
            lease.charge(Long.MAX_VALUE / 4);
            lease.charge(Long.MAX_VALUE / 4);
        }
        assertEquals(0.0, liveBytes());
    }

    @Test
    void aNegativeBudgetIsReadAsUnbounded() {
        ParquetScratchBudget budget = budget(-1L);

        try (ScratchLease lease = budget.open("batch_artifact")) {
            lease.charge(64 * MIB);
        }
    }

    @Test
    void countsARefusalPerWriterAndRegistersEveryWriterAtZero() {
        ParquetScratchBudget budget = budget(MIB);

        assertEquals(0.0, refusals("checkpoint_frame"),
                "an alert on this meter must be writable before the first refusal");
        assertEquals(0.0, refusals("checkpoint_table"));
        assertEquals(0.0, refusals("batch_artifact"));

        try (ScratchLease lease = budget.open("checkpoint_frame")) {
            assertThrows(ScratchBudgetExceededException.class, () -> lease.charge(2 * MIB));
        }
        assertEquals(1.0, refusals("checkpoint_frame"));
        assertEquals(0.0, refusals("checkpoint_table"));
    }

    @Test
    void refusesAnUnknownWriterRatherThanInventingASeries() {
        ParquetScratchBudget budget = budget(MIB);

        assertThrows(IllegalArgumentException.class, () -> budget.open("something_new"));
    }

    @Test
    void namesTheBudgetAndTheKeyInTheRefusal() {
        ParquetScratchBudget budget = budget(4 * MIB);

        try (ScratchLease lease = budget.open("checkpoint_table")) {
            ScratchBudgetExceededException refused = assertThrows(
                    ScratchBudgetExceededException.class, () -> lease.charge(8 * MIB));
            assertNotNull(refused.getMessage());
            assertTrue(refused.getMessage().contains("delta.parquet.max-scratch-bytes"),
                    "the operator's only lever must be named: " + refused.getMessage());
            assertTrue(refused.getMessage().contains(String.valueOf(4 * MIB)),
                    "the configured budget must be in the message: " + refused.getMessage());
        }
    }

    @Test
    void chargesTheDirectoryInChunksRatherThanPerByte() {
        ParquetScratchBudget budget = budget(64 * MIB);

        try (ScratchLease lease = budget.open("batch_artifact")) {
            for (int i = 0; i < 1024; i++) {
                lease.charge(1);
            }
            // Chunked reservation is what keeps a per-byte write off the shared counter; the
            // over-reservation it costs is bounded by one chunk per live writer.
            assertTrue(liveBytes() >= 1024, "the writer's bytes are reserved");
            assertTrue(liveBytes() <= ParquetScratchBudget.CHUNK_BYTES,
                    "one chunk covers a kilobyte of single-byte writes, was " + liveBytes());
        }
    }

    private double liveBytes() {
        return registry.get("delta.parquet.scratch.bytes").gauge().value();
    }

    private double refusals(String writer) {
        return registry.get("delta.parquet.scratch.refused").tag("writer", writer).counter().count();
    }
}
