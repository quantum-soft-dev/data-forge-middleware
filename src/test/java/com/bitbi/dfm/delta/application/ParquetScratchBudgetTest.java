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
 * file-backed Parquet scratch, where every ceiling before it bounded one file — and the
 * reserved share that keeps a completed-batch backlog from starving the nightly checkpoint
 * (issue #193).
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

    private ParquetScratchBudget budget(long maxBytes, long checkpointReserveBytes) {
        return new ParquetScratchBudget(registry, maxBytes, checkpointReserveBytes, 0L);
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
    void aClosedLeaseCannotTakeTheDirectoryBackFromTheProcess() {
        // The failure this guards is permanent rather than transient: through a closed lease
        // `granted` is zero again, so a charge would take the whole file's bytes from the
        // directory with nobody left to release them, and the pod would run a smaller budget than
        // it was configured with until it restarted.
        ParquetScratchBudget budget = budget(8 * MIB);

        ScratchLease lease = budget.open("batch_artifact");
        lease.charge(MIB);
        lease.close();
        lease.charge(4 * MIB);

        assertEquals(0.0, liveBytes(), "a closed lease holds nothing");
        try (ScratchLease next = budget.open("batch_artifact")) {
            next.charge(8 * MIB);
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
    void countsOneRefusalPerFileHoweverManyWritesTheWriterUnwindsThrough() {
        // FileOutputFile does not latch the way CappedOutputStream does — Parquet unwinds a write
        // failure through a close() that still emits its footer — so a per-write increment would
        // report two or more refusals for one refused artifact, and a different number for the
        // frame. An alert on this counter has to mean files, not writes.
        ParquetScratchBudget budget = budget(MIB);

        try (ScratchLease lease = budget.open("batch_artifact")) {
            assertThrows(ScratchBudgetExceededException.class, () -> lease.charge(2 * MIB));
            assertThrows(ScratchBudgetExceededException.class, () -> lease.charge(2 * MIB));
            assertThrows(ScratchBudgetExceededException.class, () -> lease.charge(2 * MIB));
        }

        assertEquals(1.0, refusals("batch_artifact"));
    }

    @Test
    void doesNotCountRefusedBytesAsWritten() {
        // The bytes a refusal is about to unwind never landed. Counting them would leave this
        // lease's idea of the file permanently ahead of it, and — since the writer keeps writing
        // after the first refusal — over-reserve against the gauge this budget asks operators to
        // size the key from.
        ParquetScratchBudget budget = budget(4 * MIB);

        try (ScratchLease lease = budget.open("batch_artifact")) {
            lease.charge(1024);
            assertThrows(ScratchBudgetExceededException.class, () -> lease.charge(8 * MIB));
            assertEquals(ParquetScratchBudget.CHUNK_BYTES, liveBytes(),
                    "the refused charge must not push the lease into a second chunk");
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
            // The free bytes, not the budget, are what tells "the directory is busy" apart from
            // "this artifact is too big" — and DeltaParquetWriter copies this text verbatim into
            // batch_parquet_artifacts.last_error, where it is the operator's primary diagnostic.
            assertTrue(refused.getMessage().contains("only " + (4 * MIB) + " of"),
                    "the free bytes must be in the message: " + refused.getMessage());
        }

        try (ScratchLease holder = budget.open("batch_artifact")) {
            holder.charge(4 * MIB);
            try (ScratchLease lease = budget.open("checkpoint_table")) {
                ScratchBudgetExceededException refused = assertThrows(
                        ScratchBudgetExceededException.class, () -> lease.charge(8 * MIB));
                assertTrue(refused.getMessage().contains("only 0 of"),
                        "a directory held by a neighbour must not read as a whole free budget: "
                                + refused.getMessage());
            }
        }
    }

    @Test
    void batchWritersCannotConsumeTheBytesReservedForACheckpointFrame() {
        // Issue #193. Two ten-table batches can fill the deployed 5 GiB on their own, and a
        // backlog keeps them there for the length of the 02:00 sweep — which then aborts every
        // site at its first write, the frame. Batch may use at most budget minus the frame
        // ceiling, so that file always has somewhere to land.
        ParquetScratchBudget budget = budget(8 * MIB, 3 * MIB);

        try (ScratchLease first = budget.open("batch_artifact")) {
            first.charge(5 * MIB);
            try (ScratchLease second = budget.open("batch_artifact")) {
                assertThrows(ScratchBudgetExceededException.class, () -> second.charge(1),
                        "the reserved share is not leftover slack a second batch writer may take");
            }
            try (ScratchLease frame = budget.open("checkpoint_frame")) {
                frame.charge(3 * MIB);
            }
        }
        assertEquals(0.0, liveBytes());
    }

    @Test
    void aTableSnapshotCanUseTheSameReservedShareAfterTheFrameIsGone() {
        // The checkpoint path holds one scratch file at a time (#178, and the frame is deleted
        // before the snapshot loop). The reserve is that one file's worth, not a second copy, so
        // releasing the frame must not hand the reserved bytes to a batch backlog waiting in the
        // gap — the table snapshot is next and it ends the build too if it has nowhere to go.
        ParquetScratchBudget budget = budget(8 * MIB, 3 * MIB);

        ScratchLease batch = budget.open("batch_artifact");
        batch.charge(5 * MIB);
        try (ScratchLease frame = budget.open("checkpoint_frame")) {
            frame.charge(3 * MIB);
        }
        try (ScratchLease stillBatch = budget.open("batch_artifact")) {
            assertThrows(ScratchBudgetExceededException.class, () -> stillBatch.charge(1),
                    "releasing the frame must not let a backlog steal the reserved share");
        }
        try (ScratchLease table = budget.open("checkpoint_table")) {
            table.charge(3 * MIB);
        }
        batch.close();
        assertEquals(0.0, liveBytes());
    }

    @Test
    void aCheckpointHoldingTheReserveDoesNotShrinkTheBatchShare() {
        // The reserve is a cap on what batch may hold, not on total live. A frame in flight
        // already occupies its share; subtracting it from the batch ceiling a second time would
        // refuse a legitimate completed-batch build for the length of the 02:00 sweep — the
        // opposite of a reserved share.
        ParquetScratchBudget budget = budget(8 * MIB, 3 * MIB);

        ScratchLease frame = budget.open("checkpoint_frame");
        frame.charge(3 * MIB);
        ScratchLease batch = budget.open("batch_artifact");
        batch.charge(5 * MIB);
        try (ScratchLease stillBatch = budget.open("batch_artifact")) {
            assertThrows(ScratchBudgetExceededException.class, () -> stillBatch.charge(1),
                    "batch at its cap must stay at its cap while a frame occupies the reserve");
        }
        batch.close();
        frame.close();
        assertEquals(0.0, liveBytes());
    }

    @Test
    void aCheckpointWriterIsNotCappedAtTheBatchShare() {
        // The reserve is a floor for checkpoint, not a ceiling. An idle directory must still let
        // a frame use the whole budget — the per-file ceiling is what bounds that file.
        ParquetScratchBudget budget = budget(8 * MIB, 3 * MIB);

        try (ScratchLease frame = budget.open("checkpoint_frame")) {
            frame.charge(8 * MIB);
        }
        assertEquals(0.0, liveBytes());
    }

    @Test
    void anUnboundedDirectoryIgnoresTheCheckpointReserve() {
        // Unbounded is the shipped default: there is no budget to reserve a share of, and a
        // leftover frame-ceiling value must not start refusing batch writers on an upgrade that
        // has not turned the directory key on.
        ParquetScratchBudget budget = budget(0L, 64 * MIB);

        try (ScratchLease batch = budget.open("batch_artifact")) {
            batch.charge(64 * MIB);
        }
        assertEquals(0.0, liveBytes());
        assertEquals(0.0, refusals("batch_artifact"));
    }

    @Test
    void aReserveLargerThanTheBudgetLeavesNoneOfItForBatchWriters() {
        ParquetScratchBudget budget = budget(4 * MIB, 8 * MIB);

        try (ScratchLease batch = budget.open("batch_artifact")) {
            assertThrows(ScratchBudgetExceededException.class, () -> batch.charge(1));
        }
        try (ScratchLease frame = budget.open("checkpoint_frame")) {
            frame.charge(4 * MIB);
        }
        assertEquals(0.0, liveBytes());
    }

    @Test
    void aNegativeReserveIsReadAsNone() {
        ParquetScratchBudget budget = budget(8 * MIB, -1L);

        try (ScratchLease batch = budget.open("batch_artifact")) {
            batch.charge(8 * MIB);
        }
        assertEquals(0.0, liveBytes());
    }

    @Test
    void namesTheReservedShareInABatchRefusal() {
        ParquetScratchBudget budget = budget(8 * MIB, 3 * MIB);

        try (ScratchLease batch = budget.open("batch_artifact")) {
            batch.charge(5 * MIB);
            ScratchBudgetExceededException refused = assertThrows(
                    ScratchBudgetExceededException.class, () -> batch.charge(1));
            assertNotNull(refused.getMessage());
            assertTrue(refused.getMessage().contains("delta.checkpoint.max-frame-temp-bytes"),
                    "the key that sized the reserve must be named: " + refused.getMessage());
            assertTrue(refused.getMessage().contains(String.valueOf(3 * MIB)),
                    "the reserved bytes must be in the message: " + refused.getMessage());
            assertTrue(refused.getMessage().contains("only 0 of"),
                    "a batch writer at its cap must not read as a free directory: "
                            + refused.getMessage());
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
