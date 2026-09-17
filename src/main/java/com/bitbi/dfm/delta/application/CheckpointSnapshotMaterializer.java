package com.bitbi.dfm.delta.application;

import com.bitbi.dfm.delta.application.ChangelogFold.FoldedRow;
import com.bitbi.dfm.delta.domain.Checkpoint;
import com.bitbi.dfm.delta.domain.CheckpointRepository;
import com.bitbi.dfm.delta.domain.SiteEpoch;
import com.bitbi.dfm.delta.grpc.v2.ChangeRecord;
import com.bitbi.dfm.delta.grpc.v2.Value;
import com.bitbi.dfm.delta.infrastructure.S3CheckpointStorage;
import com.bitbi.dfm.site.application.SiteSchemaService;
import com.bitbi.dfm.site.domain.TableSchema;
import org.apache.avro.Schema;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Materializes a checkpoint build's per-table Parquet snapshots and keeps the {@code checkpoints}
 * rows that name them (issue #297, split out of {@link CheckpointService}).
 *
 * <p>Two sources, one set of outcomes. {@link #writeSnapshots} writes from a folded heap state,
 * one table and one scratch file at a time; {@link #writeSnapshotsFromFrame} re-reads a local frame
 * file, {@code W} tables per pass (issue #292). Whether a table is written at all, how a written
 * one is published and how a failure is classified are the same code for both:
 * {@link #prepareTable}, {@link #publishTable}, {@link #failTable}, and after the tables
 * {@link #settleSiteWide} or {@link #reapTablesAbsentFrom}.</p>
 *
 * <p>Every row write goes through {@link CheckpointEpochGuard}, so a build whose site was wiped or
 * re-baselined mid-flight stops here instead of re-inserting the rows that just went. Every line
 * this class logs is logged under {@code CheckpointService}, as it was before the split.</p>
 */
final class CheckpointSnapshotMaterializer {

    private static final Logger log = LoggerFactory.getLogger(CheckpointService.class);

    private final CheckpointRepository checkpointRepository;
    private final S3CheckpointStorage checkpointStorage;
    private final SiteSchemaService siteSchemaService;
    private final DeltaMetrics metrics;
    private final DeltaParquetProperties parquetProperties;
    private final CheckpointEpochGuard epochGuard;
    private final CheckpointRetryProperties retryProperties;
    private final CheckpointScratch scratch;
    private final CheckpointShutdownCheck shutdown;
    /** Per-table snapshot ceiling: crossing it skips that table (issue #138). */
    private final long maxTempBytes;
    /**
     * How many table snapshots the streaming path keeps open at once, and therefore how many passes
     * it makes over the local frame (issue #292) — see {@code CheckpointService} for the trade.
     */
    private final int snapshotWriters;

    CheckpointSnapshotMaterializer(CheckpointRepository checkpointRepository,
                                   S3CheckpointStorage checkpointStorage,
                                   SiteSchemaService siteSchemaService,
                                   DeltaMetrics metrics,
                                   DeltaParquetProperties parquetProperties,
                                   CheckpointEpochGuard epochGuard,
                                   CheckpointRetryProperties retryProperties,
                                   CheckpointScratch scratch,
                                   CheckpointShutdownCheck shutdown,
                                   long maxTempBytes,
                                   int snapshotWriters) {
        this.checkpointRepository = checkpointRepository;
        this.checkpointStorage = checkpointStorage;
        this.siteSchemaService = siteSchemaService;
        this.metrics = metrics;
        this.parquetProperties = parquetProperties;
        this.epochGuard = epochGuard;
        this.retryProperties = retryProperties;
        this.scratch = scratch;
        this.shutdown = shutdown;
        this.maxTempBytes = maxTempBytes;
        this.snapshotWriters = snapshotWriters;
    }

    // --- from a folded state ------------------------------------------------------------------

    /**
     * Write (or retry) each table's Parquet snapshot at {@code seq}.
     *
     * <p>{@link SnapshotPass#INCREMENTAL} advances seq and detaches a failed key.
     * {@link SnapshotPass#RETRY_MISSING} and {@link SnapshotPass#FORCE} stay on the recorded
     * pointer and keep a last-good key if the rewrite fails.</p>
     *
     * <p>Every row write goes through {@link CheckpointEpochGuard}, so a build whose site was wiped
     * or re-baselined mid-flight stops here instead of re-inserting the rows that just went.</p>
     */
    void writeSnapshots(UUID siteId,
                        Map<String, Map<String, FoldedRow>> state,
                        long seq,
                        SnapshotPass pass,
                        SiteEpoch epoch) {
        shutdown.stopIfShuttingDown(siteId);
        Map<String, TableSchema> schemas = siteSchemaService.getTableSchemas(siteId);
        scratch.prepareDirectory();

        // Per-segment delta Parquet is event-driven (Task 8, DeltaEgressService); the checkpoint
        // additionally materializes the full per-table load as typed Parquet (the only format V2
        // produces since issue #113) plus the frame seed.
        state.forEach((tableName, rows) -> {
            // Between tables, not only inside the catch: once the context is closing every
            // remaining table would fail identically, and each failure is another opportunity to
            // mistake "this process is ending" for "this table cannot be materialized".
            shutdown.stopIfShuttingDown(siteId);
            Checkpoint checkpoint =
                    prepareTable(siteId, tableName, rows.size(), seq, pass, epoch, schemas.get(tableName));
            if (checkpoint == null) {
                return;
            }
            TableSchema tableSchema = schemas.get(tableName);

            // One table at a time: write this table's rows to disk, hand the file to S3, drop
            // it. Materialization therefore costs one row-group buffer and one scratch file at
            // a time instead of one encoded Parquet per table. The new frame (issue #126) went
            // through the same directory just before this loop and its file is already gone, so
            // "one at a time" covers the whole build and not only its snapshot half.
            //
            // One table's coercion failure (schema drift, bad value) must not abort the whole
            // build: the pointer would freeze, retention would stop, and segments would grow
            // unbounded. Skip that table and keep going — the same skip-and-continue contract
            // as DeltaEgressService.
            Path snapshot = scratch.createFile(siteId);
            ScratchLease lease = scratch.budget().open(ParquetScratchBudget.CHECKPOINT_TABLE);
            try {
                // Captured rather than returned through timeCheckpointPhase so the phase keeps
                // being timed as a Runnable: which overload times a phase is incidental to this
                // change, and CheckpointServiceTest pins the shape as part of the #111 phase guard.
                java.util.concurrent.atomic.AtomicReference<DecimalDegradeTally> nonFinite = new java.util.concurrent.atomic.AtomicReference<>();
                metrics.timeCheckpointPhase("parquet", () ->
                        nonFinite.set(ParquetCheckpointWriter.writeParquet(snapshot, tableName, tableSchema,
                                dataRows(rows), maxTempBytes, parquetProperties.rowGroupBytes(),
                                lease)));

                publishTable(siteId, checkpoint, tableName, seq, snapshot, nonFinite.get(), epoch);
            } catch (RuntimeException e) {
                failTable(siteId, checkpoint, tableName, pass, epoch, e);
            } finally {
                // The scratch file is this build's litter whichever way the table ended: kept,
                // it would fill the node one checkpoint cycle at a time.
                CheckpointScratch.deleteQuietly(snapshot, tableName, siteId);
                lease.close();
            }
        });

        // After the loop, not before it. The rows this build is about to write exist by now, so
        // `checkpoints` is never transiently empty for a site that has tables — and a reader
        // landing in that window would not be a cosmetic problem: CheckpointFileQueryService keys
        // its pre-Delta fallback on the site having no checkpoint rows at all, and would hand a
        // Bit BI client historical uploaded CSVs as if they were its current baseline.
        shutdown.stopIfShuttingDown(siteId);
        if (state.isEmpty()) {
            // Every row would be reaped, and the reap must never empty a site (see below) — so
            // without this the site would be folded every night forever and never spend an
            // attempt, because the per-table settle lives inside the loop above and an empty fold
            // never enters it. That is the unbounded retry this ticket removes, minus even the
            // visibility. Settle it site-wide instead, exactly as a history_gone abort does: the
            // rows drain to the cap, the site stops naming itself, and
            // delta.checkpoint.tables.given-up carries it from then on.
            settleSiteWide(siteId, epoch, pass);
            return;
        }
        reapTablesAbsentFromTheFold(siteId, state, epoch);
    }

    /**
     * A lazily iterated view of one table's folded rows — the writer traverses it (twice at most,
     * for the decimal envelope) instead of receiving a materialized copy of the state.
     */
    private static Iterable<Map<String, Value>> dataRows(Map<String, FoldedRow> rows) {
        return () -> rows.values().stream().map(FoldedRow::data).iterator();
    }

    // --- from a local frame file --------------------------------------------------------------

    /**
     * Write each table's Parquet snapshot by re-reading the frame this build just wrote, {@code W}
     * tables at a time (issue #292).
     *
     * <p>Everything about a table's outcome is the folded path's: {@link #prepareTable} decides
     * whether it is written at all, {@link #publishTable} uploads and saves it, {@link #failTable}
     * classifies a failure. What differs is only that the rows arrive interleaved, so a table cannot
     * be rendered by iterating a collection of its own.</p>
     *
     * @return how many passes were made over the local frame
     */
    int writeSnapshotsFromFrame(UUID siteId,
                                Path frame,
                                CheckpointFrameWriter.FrameManifest manifest,
                                long seq,
                                SiteEpoch epoch,
                                SnapshotPass pass) {
        shutdown.stopIfShuttingDown(siteId);
        Map<String, TableSchema> schemas = siteSchemaService.getTableSchemas(siteId);

        Map<String, Checkpoint> pending = new LinkedHashMap<>();
        for (String tableName : manifest.tables()) {
            shutdown.stopIfShuttingDown(siteId);
            Checkpoint checkpoint = prepareTable(siteId, tableName,
                    manifest.rowCounts().getOrDefault(tableName, 0L), seq,
                    pass, epoch, schemas.get(tableName));
            if (checkpoint != null) {
                pending.put(tableName, checkpoint);
            }
        }

        int passes = 0;
        Map<String, Schema> avroSchemas = new LinkedHashMap<>();
        if (closeDecimalEnvelopes(frame, pending.keySet(), schemas, avroSchemas)) {
            passes++;
        }

        List<String> tables = List.copyOf(pending.keySet());
        for (int from = 0; from < tables.size(); from += snapshotWriters) {
            shutdown.stopIfShuttingDown(siteId);
            writeSnapshotGroup(siteId, frame, seq, epoch, schemas, avroSchemas, pending,
                    tables.subList(from, Math.min(from + snapshotWriters, tables.size())), pass);
            passes++;
        }

        shutdown.stopIfShuttingDown(siteId);
        if (manifest.tables().isEmpty()) {
            // The empty-fold answer of the folded path, for the same reason: the per-table settle
            // lives inside the loop above, so a site whose snapshot carried no record at all would
            // otherwise be revisited nightly forever without ever spending an attempt.
            settleSiteWide(siteId, epoch, pass);
            return passes;
        }
        reapTablesAbsentFrom(siteId, Set.copyOf(manifest.tables()), epoch);
        return passes;
    }

    /**
     * One pass over the local frame that closes every table's decimal envelope.
     *
     * <p>{@code writeParquet} affords two traversals of a table's rows because the folded path
     * holds them; here the second traversal would be a second set of passes over the frame, so every
     * table is measured together in this one. Tables that declare no decimal column need no
     * measuring at all, and when none of them does the pass is skipped outright.</p>
     *
     * @param avroSchemas filled with each table's record schema, widened where it was measured
     * @return whether a pass over the frame was actually made
     */
    private boolean closeDecimalEnvelopes(Path frame,
                                          Set<String> tables,
                                          Map<String, TableSchema> schemas,
                                          Map<String, Schema> avroSchemas) {
        Map<String, ParquetCheckpointWriter.DecimalEnvelope> envelopes = new LinkedHashMap<>();
        for (String tableName : tables) {
            Schema declared = ParquetSchemaMapper.toAvroSchema(tableName, schemas.get(tableName));
            ParquetCheckpointWriter.DecimalEnvelope envelope =
                    ParquetCheckpointWriter.decimalEnvelope(declared);
            avroSchemas.put(tableName, declared);
            if (envelope.measuresAnything()) {
                envelopes.put(tableName, envelope);
            }
        }
        if (envelopes.isEmpty()) {
            return false;
        }
        readFrame(frame, record -> {
            ParquetCheckpointWriter.DecimalEnvelope envelope = envelopes.get(record.getTable());
            if (envelope != null) {
                envelope.observe(record.getDataMap());
            }
        });
        envelopes.forEach((tableName, envelope) -> avroSchemas.put(tableName, envelope.widened()));
        return true;
    }

    /**
     * Write one group of tables in a single pass over the local frame.
     *
     * <p>A table that fails mid-pass stops being written and is recorded through {@link #failTable}
     * when the pass ends — the same skip-and-continue contract the folded path has, except that the
     * pass carries the other tables of the group on rather than moving to the next table. A refusal
     * by the shared scratch directory is systemic and ends the build where it happens, as it does
     * there.</p>
     */
    private void writeSnapshotGroup(UUID siteId,
                                    Path frame,
                                    long seq,
                                    SiteEpoch epoch,
                                    Map<String, TableSchema> schemas,
                                    Map<String, Schema> avroSchemas,
                                    Map<String, Checkpoint> pending,
                                    List<String> group,
                                    SnapshotPass pass) {
        Map<String, OpenSnapshot> open = new LinkedHashMap<>();
        try {
            for (String tableName : group) {
                Path file = scratch.createFile(siteId);
                ScratchLease lease = scratch.budget().open(ParquetScratchBudget.CHECKPOINT_TABLE);
                open.put(tableName, new OpenSnapshot(file, lease,
                        ParquetCheckpointWriter.openTable(file, tableName, schemas.get(tableName),
                                avroSchemas.get(tableName), maxTempBytes,
                                parquetProperties.rowGroupBytes(), lease)));
            }

            metrics.timeCheckpointPhase("parquet", () -> readFrame(frame, record -> {
                OpenSnapshot snapshot = open.get(record.getTable());
                if (snapshot == null || snapshot.failure != null) {
                    return;
                }
                try {
                    snapshot.writer.write(record.getDataMap());
                } catch (RuntimeException e) {
                    if (isScratchBudgetRefusal(e)) {
                        throw scratchDirectoryFull(siteId, record.getTable(), e);
                    }
                    snapshot.failure = e;
                }
            }));

            for (Map.Entry<String, OpenSnapshot> entry : open.entrySet()) {
                OpenSnapshot snapshot = entry.getValue();
                try {
                    snapshot.writer.close();
                    snapshot.closed = true;
                } catch (RuntimeException e) {
                    if (isScratchBudgetRefusal(e)) {
                        throw scratchDirectoryFull(siteId, entry.getKey(), e);
                    }
                    if (snapshot.failure == null) {
                        snapshot.failure = e;
                    }
                }
            }

            for (Map.Entry<String, OpenSnapshot> entry : open.entrySet()) {
                String tableName = entry.getKey();
                OpenSnapshot snapshot = entry.getValue();
                Checkpoint checkpoint = pending.get(tableName);
                try {
                    if (snapshot.failure != null) {
                        throw snapshot.failure;
                    }
                    ParquetCheckpointWriter.warnDegraded(tableName, snapshot.writer.tally());
                    publishTable(siteId, checkpoint, tableName, seq, snapshot.file,
                            snapshot.writer.tally(), epoch);
                } catch (RuntimeException e) {
                    failTable(siteId, checkpoint, tableName, pass, epoch, e);
                }
            }
        } finally {
            open.forEach((tableName, snapshot) -> {
                if (!snapshot.closed) {
                    // Best effort: the group is unwinding on something systemic, and a writer left
                    // open would keep its scratch file undeletable on the platforms that care.
                    try {
                        snapshot.writer.close();
                    } catch (RuntimeException ignored) {
                        // the file is deleted next, and the failure that is unwinding is the story
                    }
                }
                CheckpointScratch.deleteQuietly(snapshot.file, tableName, siteId);
                snapshot.lease.close();
            });
        }
    }

    /** One table's open snapshot file within a group pass. */
    private static final class OpenSnapshot {

        private final Path file;
        private final ScratchLease lease;
        private final ParquetCheckpointWriter.OpenTable writer;
        private RuntimeException failure;
        private boolean closed;

        private OpenSnapshot(Path file, ScratchLease lease, ParquetCheckpointWriter.OpenTable writer) {
            this.file = file;
            this.lease = lease;
            this.writer = writer;
        }
    }

    /** Read the locally written frame back, record by record. */
    private static void readFrame(Path frame, java.util.function.Consumer<ChangeRecord> consumer) {
        try (InputStream in = Files.newInputStream(frame)) {
            ChangelogCodec.forEach(in, consumer);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to read the local checkpoint frame " + frame, e);
        }
    }

    // --- the checkpoints rows -----------------------------------------------------------------

    /**
     * The {@code checkpoints} row bookkeeping that precedes a table's Parquet, shared by the folded
     * and the streaming path (issue #292).
     *
     * <p>The order matters and is the one {@code writeSnapshots} has always had: the dedicated-retry
     * skips first, then the row (created or advanced to this seq and row count), then a forced
     * rebuild's re-arm, and only then the declared schema — a table with no schema still gets its
     * row, so the hole is visible rather than absent.</p>
     *
     * @return the row to write this table into, or {@code null} when the table must be skipped —
     *         already materialized on a dedicated retry, past the retry cap, or missing its schema
     *         (which is reported and charged here, since there is nothing left to attempt)
     */
    private Checkpoint prepareTable(UUID siteId, String tableName, long rowCount, long seq,
                                    SnapshotPass pass, SiteEpoch epoch, TableSchema tableSchema) {
        if (pass == SnapshotPass.RETRY_MISSING) {
            Optional<Checkpoint> existing =
                    checkpointRepository.findBySiteIdAndTableName(siteId, tableName);
            if (existing.isPresent() && existing.get().getS3KeyParquet() != null) {
                return null;
            }
            // The bound on the retry (issue #149). A row that has spent its attempts is not
            // going to materialize tonight either: the causes that survive this many nights —
            // a schema the client never submits, a value Parquet cannot render — are not the
            // kind that pass with time. Only the *dedicated* retry stops; an incremental build
            // below still writes this table with the rest of its fold.
            if (existing.isPresent()
                    && existing.get().hasGivenUpMaterializing(
                            retryProperties.maxMaterializeAttempts())) {
                return null;
            }
        }
        Checkpoint checkpoint = findOrCreate(siteId, tableName, seq, rowCount);
        if (pass == SnapshotPass.FORCE) {
            // The operator's exit from the cap, and the reason giving up is not a dead end:
            // asking for a rebuild says the cause has been dealt with, so the row goes back
            // into the nightly population whether this attempt succeeds or not.
            checkpoint.rearmMaterialization();
        }

        if (tableSchema == null) {
            // Parquet needs the declared schema, and there is no CSV left to fall back on: this
            // table simply has nothing to download until a schema arrives. The client is
            // required to SubmitSchema before its first session, so this means the site is
            // misconfigured — count it so the hole is visible rather than silent.
            // Write first, then report — the reverse of failTable, and deliberately so: there is
            // no cause to preserve here, and an epoch refusal must leave the meter alone. A
            // discarded build has no tables to report a hole for.
            if (abandonStaleSnapshot(checkpoint, pass)) {
                checkpoint.recordFailedMaterialization();
                epochGuard.inEpoch(siteId, epoch, () -> checkpointRepository.save(checkpoint));
            }
            metrics.checkpointTableUnmaterialized("no_schema");
            log.warn("No declared schema for table {} of site {} — checkpoint row recorded "
                    + "without a downloadable artifact (the client must SubmitSchema)",
                    tableName, siteId);
            return null;
        }
        return checkpoint;
    }

    /**
     * Publish one written snapshot: upload it, save the row through the epoch guard, then count the
     * cells that had to be degraded (issue #292 — shared by both snapshot paths).
     */
    private void publishTable(UUID siteId, Checkpoint checkpoint, String tableName, long seq,
                              Path snapshot, DecimalDegradeTally nonFinite, SiteEpoch epoch) {
        metrics.timeCheckpointPhase("upload", () ->
                checkpoint.attachParquet(checkpointStorage.uploadParquet(
                        siteId, tableName, seq, snapshot)));
        epochGuard.inEpoch(siteId, epoch, () -> checkpointRepository.save(checkpoint));
        // After the epoch guard, not merely after the upload (review round 3): a wipe or
        // re-baseline landing mid-build makes the guard throw and discards everything the
        // build produced, so counting earlier credited cells to an artifact that was never
        // published -- and the next build re-renders and counts them again.
        metrics.unrepresentableDecimalsDegraded(nonFinite.nonFiniteCount(), false);
        metrics.unrepresentableDecimalsDegraded(nonFinite.malformedCount(), true);
    }

    /**
     * Classify one table's failure: end the build, or record it against the table and carry on
     * (issue #292 — shared by both snapshot paths).
     */
    private void failTable(UUID siteId, Checkpoint checkpoint, String tableName, SnapshotPass pass,
                           SiteEpoch epoch, RuntimeException e) {
        if (e instanceof CheckpointEpochGuard.EpochChangedException) {
            // A replaced baseline is not a fact about this table: nothing this build produced
            // may be published, so it must escape the per-table skip below and end the build.
            throw e;
        }
        if (e instanceof CheckpointService.BuildEndedByShutdownException) {
            throw e;
        }
        // A full scratch directory is a SYSTEMIC scratch failure, so it ends the build —
        // the same answer CheckpointScratch.prepareDirectory() gives an unusable directory, for the
        // reason stated there: skipping would detach every last-good snapshot while the
        // pointer advanced. Skipping this one table looks gentler and is not (issue #150,
        // review round 2). The pointer would move to the new seq with this table's row left
        // at the old one, and nothing would mark it as owing a rewrite: the nightly
        // rematerialize keys on a NULL s3_key_parquet, so a site that then goes quiet
        // serves a snapshot silently missing every change in between, indefinitely, while
        // retention has already pruned the segments below the new pointer. Detaching
        // instead would fix the retry and 404 a healthy artifact for a neighbour's disk
        // use. And on a site's FIRST build, findOrCreate's row is not saved either, so a
        // refusal across every table leaves `checkpoints` empty with the pointer advanced —
        // which CheckpointFileQueryService reads as "not a Delta site yet" and answers with
        // the historical uploaded CSVs as if they were the current baseline.
        //
        // Ending the build has none of those: no object, no row, no pointer, no attempt
        // spent, retention frozen for one night and the whole seq redone on the next tick.
        // Deliberately NOT on delta.checkpoint.builds.aborted (#153's tag values never
        // repair themselves); delta.parquet.scratch.refused{writer=checkpoint_table}
        // counted it inside the budget, and issue #193 tracks the asymmetry with the
        // completed-batch side, which degrades one artifact at a time.
        if (isScratchBudgetRefusal(e)) {
            throw scratchDirectoryFull(siteId, tableName, e);
        }
        // A failure seen while the context is closing is a fact about the process, not
        // about this table (issue #162). The S3Client and the DataSource are destroyed
        // right after ContextClosedEvent is published, so every call from here on fails
        // with an exception that reads exactly like a broken table. Recording it would
        // detach a healthy snapshot on an advancing seq, and the row would 404 for Bit BI
        // and Parquet Export until the next nightly rematerialize.
        if (shutdown.isShuttingDown()) {
            throw new CheckpointService.BuildEndedByShutdownException(siteId, tableName, e);
        }
        // Report the cause first: the detach below goes through the epoch guard, which
        // throws rather than returns when the site was wiped mid-build, and this table's
        // actual failure (schema drift, an oversized table) would leave no trace at all.
        metrics.checkpointTableUnmaterialized("parquet_failed");
        log.warn("Checkpoint Parquet failed for table {} of site {} — the table has no "
                + "artifact this build (check the declared schema against the data, or "
                + "delta.checkpoint.max-temp-bytes against the table's size)",
                tableName, siteId, e);
        // When seq advanced, the previous key would sit beside a newer seq and be served
        // as its snapshot — detach it. On a same-seq rematerialize the last-good object
        // is still at that key; keep the row pointing at it.
        if (abandonStaleSnapshot(checkpoint, pass)) {
            // The row ends this build owing a snapshot, so the attempt is spent (issue
            // #149). A failure that leaves a still-valid last-good key is deliberately not
            // counted: the retry exists for rows with nothing to serve, and charging one
            // to a healthy row would eventually retire a table nobody is waiting on.
            checkpoint.recordFailedMaterialization();
            epochGuard.inEpoch(siteId, epoch, () -> checkpointRepository.save(checkpoint));
        }
    }

    /**
     * Charge (or re-arm) every unmaterialized row of a site for an outcome that belongs to the
     * whole build rather than to any one table.
     *
     * <p>A forced rebuild re-arms where a scheduled one spends: it is the operator asserting the
     * cause has been dealt with, and it is the documented recovery from both states that reach
     * here, so it must not be the fastest way to exhaust the retry it is meant to restore.</p>
     */
    void settleSiteWide(UUID siteId, SiteEpoch epoch, SnapshotPass pass) {
        if (pass == SnapshotPass.FORCE) {
            rearmEveryUnmaterializedTable(siteId, epoch);
        } else {
            spendAnAttemptOnEveryRetryableTable(siteId, epoch);
        }
    }

    /**
     * Charge a site-wide abort to the rows that keep the site on the nightly work list.
     *
     * <p>The abort happens before any table is reached, so no per-table catch can record it — yet
     * it is exactly as final for those rows as an unrenderable value would be, and without this
     * they would be retried nightly forever for a build that cannot start.</p>
     */
    private void spendAnAttemptOnEveryRetryableTable(UUID siteId, SiteEpoch epoch) {
        for (Checkpoint checkpoint : checkpointRepository.findBySiteId(siteId)) {
            if (checkpoint.hasGivenUpMaterializing(retryProperties.maxMaterializeAttempts())
                    || checkpoint.getS3KeyParquet() != null) {
                continue;
            }
            checkpoint.recordFailedMaterialization();
            epochGuard.inEpoch(siteId, epoch, () -> checkpointRepository.save(checkpoint));
        }
    }

    /**
     * The forced-rebuild counterpart of {@link #spendAnAttemptOnEveryRetryableTable}: put every
     * unmaterialized row of the site back into the nightly population.
     *
     * <p>A forced rebuild that ends in a site-wide abort still means what a forced rebuild always
     * means — the operator asserting the cause has been dealt with. Charging it an attempt would
     * make the documented recovery action the fastest way to exhaust the retry.</p>
     */
    private void rearmEveryUnmaterializedTable(UUID siteId, SiteEpoch epoch) {
        for (Checkpoint checkpoint : checkpointRepository.findBySiteId(siteId)) {
            if (checkpoint.getS3KeyParquet() != null || checkpoint.materializeAttempts() == 0) {
                continue;
            }
            checkpoint.rearmMaterialization();
            epochGuard.inEpoch(siteId, epoch, () -> checkpointRepository.save(checkpoint));
        }
    }

    /**
     * Delete the checkpoint rows of tables the site no longer has (issue #149).
     *
     * <p>The fold is the whole of the site's state at this build's seq — the frame is a complete
     * all-INSERT snapshot and every surviving segment above it is folded on top — so a table with a
     * {@code checkpoints} row and no entry in the fold is a table that no longer exists. It got
     * there by having its last row {@code DELETE}d: the build that saw the deletion still had the
     * (now empty) table in its fold and wrote it, but {@link CheckpointFrame} emits no record for a
     * table with no rows, so the frame it wrote never mentions the table again.</p>
     *
     * <p>Before this, nothing could clear such a row. Both snapshot passes iterate the fold, so the
     * loop never reached the table; only a wipe or a re-baseline deletes checkpoint rows; and with
     * the row's key still null it named its site on the tick's work list every night, forever, for
     * work no build — not even a forced rebuild — could do. Reaping it is the exit, and it is the
     * truthful answer for a row that <em>did</em> keep a key too: that snapshot describes a table
     * the site dropped, and serving it as current would be a lie.</p>
     *
     * <p><b>Promptly for the first, eventually for the second.</b> This runs inside
     * {@code writeSnapshots}, which a scheduled build reaches only when it has work — new segments,
     * or a still-retryable unmaterialized row (the idle probe in {@code CheckpointService.build}
     * returns before the fold otherwise, which is the whole point of issue #149's cheap idle visit).
     * A dropped table whose row kept a live key therefore survives on a site that is completely idle,
     * until the next build with any work at all, or a forced rebuild. That is deliberate: making the
     * reap its own reason to fold a whole site nightly would reintroduce the cost this ticket
     * removed, for a stale listing entry rather than a missing artifact.</p>
     *
     * <p>The object the row named is left in {@code checkpoints/{siteId}/} as an orphan, which is
     * what every superseded snapshot has always been there (the row carries one key and each build
     * replaces it): a site wipe and {@code DeltaS3OrphanSweeper} (#158) both collect it. Deleting it
     * here would put an S3 round trip on the build for no new guarantee.</p>
     *
     * <p>Deletes run through the epoch guard like every other write of a build, so a wipe or a
     * re-baseline committing mid-build ends the build instead of deleting rows of a baseline it
     * knows nothing about.</p>
     *
     * <p><b>Never called with an empty fold.</b> Every row would go, and "this site has no
     * checkpoint rows" is a load-bearing state elsewhere: {@code CheckpointFileQueryService} reads
     * it as "not a Delta site yet" and falls back to the pre-Delta uploaded CSVs, which is exactly
     * what it must not hand a Bit BI client as a current baseline. A site whose every table was
     * emptied is settled site-wide by the caller instead — see {@link #settleSiteWide}.</p>
     */
    private void reapTablesAbsentFromTheFold(UUID siteId,
                                             Map<String, Map<String, FoldedRow>> state,
                                             SiteEpoch epoch) {
        reapTablesAbsentFrom(siteId, state.keySet(), epoch);
    }

    /** See {@link #reapTablesAbsentFromTheFold}; the streaming path knows its tables by name only. */
    private void reapTablesAbsentFrom(UUID siteId, Set<String> tables, SiteEpoch epoch) {
        for (Checkpoint checkpoint : checkpointRepository.findBySiteId(siteId)) {
            if (tables.contains(checkpoint.getTableName())) {
                continue;
            }
            log.info("Dropping the checkpoint row for table {} of site {}: the table is absent from "
                    + "the folded state, so its last row was deleted at the source",
                    checkpoint.getTableName(), siteId);
            epochGuard.inEpoch(siteId, epoch, () -> checkpointRepository.deleteById(checkpoint.getId()));
        }
    }

    /**
     * Does this site still owe a rematerialize that the nightly pass is allowed to attempt?
     *
     * <p>"Unmaterialized" alone is not the question (issue #149): a row that has spent its attempts
     * is unmaterialized and will stay that way, and answering yes for it is what made an idle visit
     * pay a frame download and a whole-site fold every night for work the pass would then skip.</p>
     */
    boolean hasRetryableUnmaterializedTables(UUID siteId) {
        int maxAttempts = retryProperties.maxMaterializeAttempts();
        return checkpointRepository.findBySiteId(siteId).stream()
                .anyMatch(checkpoint -> checkpoint.getS3KeyParquet() == null
                        && !checkpoint.hasGivenUpMaterializing(maxAttempts));
    }

    /**
     * End the build: the shared scratch directory had no room for this table's snapshot.
     *
     * <p>Returns the exception rather than throwing it, so the call site reads
     * {@code throw scratchDirectoryFull(...)} and the compiler can see the branch ends. A
     * completed-batch backlog cannot take the reserved share (#193); seeing this with the
     * directory budget on is a reserve of zero or a misconfiguration, not the operator's
     * backlog.</p>
     */
    private static RuntimeException scratchDirectoryFull(UUID siteId, String tableName,
                                                        RuntimeException error) {
        log.error("The checkpoint snapshot for table {} of site {} could not be written because the "
                + "shared Parquet scratch directory was full — the build is abandoned, so nothing "
                + "durable changed: the per-table keys and last_checkpoint_seq stay where they were "
                + "and the next tick tries again. This is contention, not a fact about the site: "
                + "raise delta.parquet.max-scratch-bytes (and the volume behind it), or lower "
                + "delta.batch-parquet.max-concurrent", tableName, siteId, error);
        return error;
    }

    /**
     * Is this failure the shared scratch directory refusing room (issue #150)?
     *
     * <p>The whole cause chain, as {@code DeltaParquetWriter.failure()} already walks it for the
     * per-file ceiling's exception. Nothing wraps this one today, so a direct {@code instanceof}
     * would work — but a future wrap would be silently <em>worse</em> here than on the batch path:
     * the refusal would fall through to {@code parquet_failed}, which detaches a healthy last-good
     * snapshot on an advancing seq and spends a materialize attempt against
     * {@code delta.checkpoint.tables.given-up} (raised in review).</p>
     */
    private static boolean isScratchBudgetRefusal(Throwable error) {
        for (Throwable cause = error; cause != null; cause = cause.getCause()) {
            if (cause instanceof ScratchBudgetExceededException) {
                return true;
            }
            if (cause.getCause() == cause) {
                return false;
            }
        }
        return false;
    }

    /**
     * Detach the snapshot key only when keeping it would lie (seq moved, or there was never a
     * key). A same-seq rematerialize that fails must leave a still-valid last-good key in place.
     *
     * @return {@code true} when the row changed and must be saved
     */
    private static boolean abandonStaleSnapshot(Checkpoint checkpoint, SnapshotPass pass) {
        if (pass == SnapshotPass.INCREMENTAL || checkpoint.getS3KeyParquet() == null) {
            checkpoint.detachParquet();
            return true;
        }
        return false;
    }

    private Checkpoint findOrCreate(UUID siteId, String tableName, long seq, long rowCount) {
        return checkpointRepository.findBySiteIdAndTableName(siteId, tableName)
                .map(existing -> {
                    existing.update(seq, rowCount);
                    return existing;
                })
                .orElseGet(() -> Checkpoint.create(siteId, tableName, seq, rowCount));
    }
}
