package com.bitbi.dfm.delta.application;

import com.bitbi.dfm.delta.application.ChangelogFold.FoldedRow;
import com.bitbi.dfm.delta.application.CheckpointService.FoldTooLargeException;
import com.bitbi.dfm.delta.domain.ChangelogSegment;
import com.bitbi.dfm.delta.grpc.v2.ChangeRecord;
import com.bitbi.dfm.delta.infrastructure.S3CheckpointStorage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Produces a checkpoint build's reload frame, three ways (issue #297, split out of
 * {@link CheckpointService}).
 *
 * <ul>
 *   <li><b>The fold</b> ({@link #foldSite}, then {@link #uploadFoldedFrame}): the seed frame and the
 *       new segments folded into one heap state, from which the frame is written.</li>
 *   <li><b>The streamed bootstrap</b> ({@link #streamSnapshotIntoFrame}, issue #292): an
 *       all-{@code INSERT} history written straight into a local frame file.</li>
 *   <li><b>The merge</b> ({@link #mergeIntoFrame}, issue #293): the period's delta folded into heap
 *       and the seed frame streamed past it into a local frame file.</li>
 * </ul>
 *
 * <p>Deliberately not one interface over the three: the fold hands back a heap state that the
 * snapshots are written from one scratch file at a time, while the other two hand back a local
 * file the snapshots re-read. Forcing the fold into "a file and a manifest" would change its
 * scratch peak, which is behaviour and not structure.</p>
 *
 * <p>What stays with {@code CheckpointService} is the order these are called in — the epoch check
 * before the first object is in the bucket (#136/#142) and the frame before any snapshot (#153).
 * Every line this class logs is logged under {@code CheckpointService}, the category an operator
 * turns to DEBUG to size the fold.</p>
 */
final class CheckpointFrameProducer {

    private static final Logger log = LoggerFactory.getLogger(CheckpointService.class);

    /** How full the fold budget may get before a build starts saying so. */
    private static final int FOLD_BUDGET_WARN_PERCENT = 75;

    /** How fast the merge escalates once a delta has refused to fit: the ticket's {@code K x 4}. */
    private static final int PARTITION_GROWTH = 4;

    private final ChangelogSegmentService changelogSegmentService;
    private final S3CheckpointStorage checkpointStorage;
    private final DeltaMetrics metrics;
    private final CheckpointScratch scratch;
    private final CheckpointShutdownCheck shutdown;
    /** Reload-frame ceiling: crossing it aborts the build, so it is deliberately its own key. */
    private final long maxFrameTempBytes;
    /**
     * Heap ceiling on the fold itself (issue #152) — the one bound that is not about disk, and the
     * one a growing site reaches first.
     */
    private final long maxFoldBytes;
    /**
     * How far the merge may partition a delta that does not fit the fold budget before it gives up
     * and aborts the build as it did before (issue #293). {@code 1} disables the fallback.
     */
    private final int maxMergePartitions;

    CheckpointFrameProducer(ChangelogSegmentService changelogSegmentService,
                            S3CheckpointStorage checkpointStorage,
                            DeltaMetrics metrics,
                            CheckpointScratch scratch,
                            CheckpointShutdownCheck shutdown,
                            long maxFrameTempBytes,
                            long maxFoldBytes,
                            int maxMergePartitions) {
        this.changelogSegmentService = changelogSegmentService;
        this.checkpointStorage = checkpointStorage;
        this.metrics = metrics;
        this.scratch = scratch;
        this.shutdown = shutdown;
        this.maxFrameTempBytes = maxFrameTempBytes;
        this.maxFoldBytes = maxFoldBytes;
        this.maxMergePartitions = maxMergePartitions;
    }

    // --- the fold -----------------------------------------------------------------------------

    /**
     * Fold the site: the seed frame first, then every new segment, one record at a time.
     *
     * <p>Nothing between S3 and the fold is retained (issue #152). The frame used to arrive as a
     * gzipped {@code byte[]} that {@code ChangelogCodec.parse} expanded into a {@code List} of every
     * record in the site, and the new segments were collected into a second such list before a
     * single {@code fold} call that <em>copied</em> the seed — four full-site copies at the peak, on
     * a pod whose memory limit is measured in gigabytes. Now one state is built in place and each
     * record is dropped as soon as it has been applied.</p>
     *
     * <p>The two meters keep their meaning: {@code phase=download_frame} is time spent reading the
     * frame off the network, measured through {@link TimingInputStream} because the transfer is now
     * interleaved with the fold rather than finished before it. {@code phase=fold} is everything
     * else — the segment downloads it always covered, and now also the seed frame's own fold, which
     * was untimed while it sat between the two phases.</p>
     *
     * <p>Streaming makes the peak smaller; it does not make it bounded. What is still proportional
     * to the site's row count is the fold itself, so it is folded <b>against a budget</b>
     * ({@link BudgetedFold}) and a site that outgrows the heap is refused rather than left to be
     * {@code OOMKilled} halfway through.</p>
     */
    Map<String, Map<String, FoldedRow>> foldSite(UUID siteId,
                                                 long checkpointSeq,
                                                 boolean haveFrame,
                                                 List<ChangelogSegment> newSegments) {
        BudgetedFold fold = new BudgetedFold(siteId, maxFoldBytes);
        long startedAt = System.nanoTime();
        // Written by foldFrame even when it throws — an abort halfway through the frame would
        // otherwise report download_frame=0 and charge the whole transfer to fold, on exactly the
        // build whose phases are worth looking at.
        long[] frameReadNanos = {0L};
        try {
            if (haveFrame) {
                foldFrame(siteId, checkpointSeq, fold, frameReadNanos);
            }
            for (ChangelogSegment segment : newSegments) {
                foldSegment(siteId, segment, fold::apply);
            }
        } finally {
            // Recorded even when the fold ended in an abort: a build that ran out of budget is
            // exactly the one whose phases an operator wants to see.
            if (haveFrame) {
                metrics.recordCheckpointPhase("download_frame", frameReadNanos[0]);
            }
            metrics.recordCheckpointPhase("fold", System.nanoTime() - startedAt - frameReadNanos[0]);
        }
        reportFoldSize(siteId, fold);
        return fold.state();
    }

    /**
     * Say how close this site is to the ceiling <em>before</em> it reaches it.
     *
     * <p>Without this the first word an operator gets is the abort itself, and the fold is the one
     * term of the checkpoint budget that nothing else makes visible: the scratch ceilings show up as
     * files on a volume, while the fold exists only while the build runs.</p>
     *
     * <p>Against the <b>peak</b>, not the size the fold happened to end at — the ceiling is enforced
     * on the running total, so a site whose fold rises and then falls back (a night's segments
     * inserting before they bulk-delete) would otherwise stay quiet at DEBUG right up to the tick
     * whose peak crosses the budget, which is precisely the warning this exists to give.</p>
     */
    private void reportFoldSize(UUID siteId, BudgetedFold fold) {
        reportFoldSize(siteId, fold.peakEstimatedBytes());
    }

    /** As above, for the merge path, whose peak is the largest partition's delta (issue #293). */
    private void reportFoldSize(UUID siteId, long bytes) {
        // On the meter as well as in the log, for the reason #153 put the abort on one: the band
        // below the ceiling is the only warning that precedes a permanent abort, and an alert
        // cannot be written on a log line. A build that aborted does not reach here — its size is
        // the counter's business, and recording it would put the one over-budget sample into the
        // series an operator reads as "how much room is left".
        metrics.recordCheckpointFoldBytes(bytes);
        if (bytes * 100 >= maxFoldBytes * FOLD_BUDGET_WARN_PERCENT) {
            log.warn("The checkpoint fold for site {} holds an estimated {} bytes of heap, {}% of "
                    + "delta.checkpoint.max-fold-bytes ({}). The build is refused outright once it "
                    + "crosses that, so raise the key (and the pod's heap with it) before this site "
                    + "grows further", siteId, bytes, bytes * 100 / Math.max(1L, maxFoldBytes), maxFoldBytes);
        } else {
            log.debug("The checkpoint fold for site {} holds an estimated {} bytes of heap, against "
                    + "delta.checkpoint.max-fold-bytes ({})", siteId, bytes, maxFoldBytes);
        }
    }

    /**
     * Stream the seed frame into the fold.
     *
     * <p>{@code readNanos} is filled in whichever way this ends — it is the caller's
     * {@code phase=download_frame} sample, and an abort mid-frame is when the split between
     * transfer and fold is most worth having. The {@code GetObject} itself is timed separately from
     * the body, and timed <em>even when it throws</em>: during a read outage every site of the tick
     * would otherwise contribute a zero-nanosecond sample, and the timer would read as though frame
     * downloads had got faster exactly while they were failing.</p>
     *
     * <p>Every failure of the body is renamed. The one that actually fires is
     * {@code UncheckedIOException}: {@code ChangelogCodec.forEach} wraps each read and parse failure
     * into one whose message mentions neither the site nor the key, while the checked
     * {@code IOException} can only come from the close. {@code RuntimeException} is caught with them
     * because the AWS SDK raises {@code SdkClientException} — not an {@code IOException} — for a
     * body that ends short of its content length. Before streaming, {@code download} named the
     * object in a {@code CheckpointStorageException}; that is what this restores. The one exception
     * that must keep its own type is the fold's own abort, which is re-thrown untouched.</p>
     */
    private void foldFrame(UUID siteId, long checkpointSeq, BudgetedFold fold, long[] readNanos) {
        long openedAt = System.nanoTime();
        InputStream opened;
        try {
            // Outside the body's try: openFrame already names the key it could not read, and a
            // failure here must not be re-wrapped as if the frame had been read and rejected.
            opened = checkpointStorage.openFrame(siteId, checkpointSeq);
        } finally {
            readNanos[0] = System.nanoTime() - openedAt;
        }
        try (InputStream frame = opened) {
            TimingInputStream timed = new TimingInputStream(frame);
            try {
                ChangelogCodec.forEach(timed, fold::apply);
            } finally {
                readNanos[0] += timed.readNanos();
            }
        } catch (FoldTooLargeException e) {
            throw e;
        } catch (IOException | RuntimeException e) {
            throw new S3CheckpointStorage.CheckpointStorageException(
                    "Failed to read the checkpoint frame of site " + siteId + " at seq " + checkpointSeq, e);
        }
    }

    /**
     * Stream one segment into the fold, naming it if it cannot be read.
     *
     * <p>The same renaming the frame gets, and for the same regression: {@code readRecords} used to
     * surface a mid-transfer failure as a {@code SegmentStorageException} carrying the segment key,
     * while the streaming path raises {@code UncheckedIOException("Failed to stream change
     * records")}. {@code CheckpointScheduler} logs only {@code e.getMessage()}, so without this the
     * failing segment is not in the logs at all.</p>
     */
    private void foldSegment(UUID siteId, ChangelogSegment segment,
                             java.util.function.Consumer<ChangeRecord> consumer) {
        try {
            changelogSegmentService.forEachRecord(segment.getS3Key(), consumer);
        } catch (FoldTooLargeException | BootstrapFrameWriter.NotAFullSnapshotException
                | ArtifactSizeLimitExceededException | ScratchBudgetExceededException e) {
            // The consumer's own refusals, not the segment's: renaming them would report a fold
            // that outgrew its budget, a frame that outgrew its ceiling or a full scratch directory
            // as an unreadable object, and send an operator to S3 for a local problem.
            throw e;
        } catch (RuntimeException e) {
            throw new S3CheckpointStorage.CheckpointStorageException(
                    "Failed to read changelog segment " + segment.getS3Key() + " of site " + siteId, e);
        }
    }

    /**
     * One site's fold, with a ceiling on how much heap it may hold (issue #152).
     *
     * <p>The running total is kept by {@link ChangelogFold#apply}, which returns what each record
     * did to the state's size, so the budget costs one addition per record rather than a walk over
     * the fold — the weighing itself is proportional to the width of the row that record touches,
     * no wider than the array copy the fold does for it anyway. It is an estimate — see
     * {@link ChangelogFold#estimatedRetainedBytes(String, ChangelogFold.FoldedRow)} — and it is
     * compared against a budget expressed in the same units, so the two are wrong together or not
     * at all.</p>
     *
     * <p><b>The ceiling is per build and the process holds one build at a time</b>, so it bounds the
     * process (issue #178). It did not before: two folds at 45% of the budget each crossed nothing
     * and still exhausted the heap between them, which takes a forced rebuild running beside the
     * nightly sweep — the {@code 2 x} the scratch budget still reserves for on disk. What closes it
     * is {@link CheckpointFoldBudget}, an exclusion held for the whole build rather than a running
     * total shared between folds; a build that cannot have it within
     * {@code delta.checkpoint.fold-wait-seconds} is <em>deferred</em>, never refused, so no
     * concurrency-caused outcome reaches the abort counter.</p>
     */
    private static final class BudgetedFold {

        private final Map<String, Map<String, FoldedRow>> state = new LinkedHashMap<>();
        private final UUID siteId;
        private final long maxBytes;
        private long bytes;
        private long peakBytes;

        private BudgetedFold(UUID siteId, long maxBytes) {
            this.siteId = siteId;
            this.maxBytes = maxBytes;
        }

        /**
         * Fold one record, then stop the build if the fold no longer fits.
         *
         * <p>Checked after applying rather than before, because the cost of one record is only
         * known once it has been applied — and one record over the ceiling is not what runs a pod
         * out of memory.</p>
         */
        private void apply(ChangeRecord record) {
            bytes += ChangelogFold.apply(state, record);
            peakBytes = Math.max(peakBytes, bytes);
            if (bytes > maxBytes) {
                throw new FoldTooLargeException(siteId, bytes, maxBytes);
            }
        }

        private Map<String, Map<String, FoldedRow>> state() {
            return state;
        }

        /** The largest the fold ever was — what the ceiling is enforced against, record by record. */
        private long peakEstimatedBytes() {
            return peakBytes;
        }
    }

    /**
     * Persist the new all-INSERT frame so the next build seeds from it and earlier segments can be
     * pruned. Same file-backed path as the snapshot (issue #126): one record at a time into a
     * scratch file, then {@code RequestBody.fromFile} — never a collected List and never a gzip
     * {@code byte[]}. The site fold itself stays in heap.
     *
     * <p>Its own ceiling, not the snapshot's (issue #138). The two files share a directory but not
     * a failure mode: an oversized table is skipped and repaired by the next build, while an
     * oversized frame ends the build, because the frame is the next incremental seed. One key for
     * both meant the value had to be set for the harsher of the two, which left it above the
     * deployed scratch volume and made a kubelet eviction the first thing to happen.</p>
     *
     * <p>The file is uploaded and deleted here rather than kept open across the snapshot loop, so
     * the checkpoint path holds one scratch file at a time. Batch writers cannot take the last
     * {@code max-frame-temp-bytes} of the directory budget (issue #193), which is what keeps a
     * completed-batch backlog from starving this write.</p>
     */
    void uploadFoldedFrame(UUID siteId, long seq, Map<String, Map<String, FoldedRow>> state) {
        scratch.prepareDirectory();
        Path frame = scratch.createFile(siteId, ".pb.gz");
        // Closed after the delete, not after the upload: the bytes are on the volume until the file
        // is gone, and releasing the lease earlier would let another writer be told there is room
        // that does not exist yet. Released even when the delete failed — see the same finally in
        // BatchParquetFinalizationService for why holding it would be the worse, and permanent,
        // error.
        ScratchLease lease = scratch.budget().open(ParquetScratchBudget.CHECKPOINT_FRAME);
        try {
            metrics.timeCheckpointPhase("upload", () -> {
                try (OutputStream out = new CappedOutputStream(
                        Files.newOutputStream(frame), maxFrameTempBytes, lease)) {
                    ChangelogCodec.write(CheckpointFrame.records(state), out);
                } catch (IOException e) {
                    throw new UncheckedIOException("Failed to write checkpoint frame for site " + siteId, e);
                }
                checkpointStorage.uploadFrame(siteId, seq, frame);
            });
        } catch (ArtifactSizeLimitExceededException | ScratchBudgetExceededException e) {
            throw reportFrameCeiling(siteId, seq, e);
        } finally {
            CheckpointScratch.deleteQuietly(frame, "_frame", siteId);
            lease.close();
        }
    }

    // --- a frame written to a local file --------------------------------------------------------

    /**
     * Upload a frame already written to local scratch, with its two ceilings reported the way #138
     * and #150 report them — the streamed bootstrap and the merge write and upload the same file in
     * two separate steps.
     */
    void uploadWrittenFrame(UUID siteId, long seq, Path frame) {
        try {
            metrics.timeCheckpointPhase("upload", () -> checkpointStorage.uploadFrame(siteId, seq, frame));
        } catch (ArtifactSizeLimitExceededException | ScratchBudgetExceededException e) {
            throw reportFrameCeiling(siteId, seq, e);
        }
    }

    /**
     * Stream every segment of the snapshot session into the local frame file (issue #292).
     *
     * @return what the frame holds, or {@code null} when the session broke the all-{@code INSERT}
     *         contract and the build must fold instead
     */
    CheckpointFrameWriter.FrameManifest streamSnapshotIntoFrame(UUID siteId,
                                                                List<ChangelogSegment> segments,
                                                                Path frame,
                                                                ScratchLease lease) {
        long startedAt = System.nanoTime();
        try {
            // phase=fold, because this is what replaces it: the segment downloads it always
            // covered, and the writing of the frame that the fold would otherwise have paid for
            // later. phase=download_frame stays absent, as it is on any build with no seed frame.
            CheckpointFrameWriter.FrameManifest manifest;
            try (OutputStream out = new CappedOutputStream(
                            Files.newOutputStream(frame), maxFrameTempBytes, lease);
                    BootstrapFrameWriter writer = BootstrapFrameWriter.open(out)) {
                for (ChangelogSegment segment : segments) {
                    foldSegment(siteId, segment, writer::accept);
                }
                manifest = writer.manifest();
            } catch (IOException e) {
                throw new UncheckedIOException("Failed to write checkpoint frame for site " + siteId, e);
            }
            return manifest;
        } catch (BootstrapFrameWriter.NotAFullSnapshotException e) {
            // Loud, because it says the client is not sending what the wire contract promises — and
            // the build still succeeds, so nothing else would say so.
            log.warn("The FULL_SNAPSHOT history of site {} does not satisfy the all-INSERT contract, "
                    + "so its first checkpoint is folded the general way instead of streamed: {}",
                    siteId, e.getMessage());
            return null;
        } catch (ArtifactSizeLimitExceededException | ScratchBudgetExceededException e) {
            // The frame's own ceilings, reported exactly as the general path reports them.
            throw reportFrameCeiling(siteId, segments.get(segments.size() - 1).getLastSeq(), e);
        } finally {
            metrics.recordCheckpointPhase("fold", System.nanoTime() - startedAt);
        }
    }

    /**
     * Write the new frame: the period's delta folded into heap, the old frame streamed past it
     * (issue #293).
     *
     * <h2>The fallback, when the delta itself does not fit</h2>
     *
     * <p>A delta outgrows the budget when a site's builds have not run for a long time, or when the
     * client streams a very large incremental session — the DBF client opens {@code CONTINUOUS} for
     * an incremental change set of a million records or more, so this is a shape the wire contract
     * produces rather than a hypothetical. Rather than abort, the merge is re-run in {@code K} hash
     * partitions of the row identity, applied to both sides so a row and its changes always land in
     * the same pass; each pass then holds about {@code 1/K} of the delta. {@code K} is found by
     * catching the refusal and multiplying, because the delta's size is not known before it is
     * folded and a configured constant would be wrong in both directions.</p>
     *
     * <p><b>It is the fallback and not the design</b>, and the cost says why: every partition
     * re-reads the seed frame and every segment of the period, so the work is {@code K} times the
     * work and {@code K} grows with the delta. It is here so that a build that would have been
     * refused finishes; a rate on {@code delta.checkpoint.builds.partitioned} says the budget wants
     * raising. Two properties are given up with it, both deliberately: the rows of one table come
     * out partition by partition rather than in the fold's order, and the frame is written once per
     * attempt, so an attempt that refuses is thrown away whole. Nothing durable exists at that
     * point — the frame is a local scratch file that has not been uploaded — which is what makes
     * the retry safe, and is the same property the streaming bootstrap leans on.</p>
     *
     * <p>{@code delta.checkpoint.max-merge-partitions} bounds the escalation. Past it the build
     * ends on {@code FoldTooLargeException} exactly as it did before this ticket, so
     * {@code builds.aborted{reason=fold_too_large}} keeps meaning "this site cannot be built at
     * this budget".</p>
     *
     * @param lease filled with the successful attempt's lease, which the caller holds for the whole
     *              build and closes; a refused attempt's lease is closed here
     */
    CheckpointFrameWriter.FrameManifest mergeIntoFrame(UUID siteId,
                                                       long checkpointSeq,
                                                       long seq,
                                                       List<ChangelogSegment> newSegments,
                                                       Path frame,
                                                       ScratchLease[] lease) {
        long startedAt = System.nanoTime();
        // Written by every attempt: phase=download_frame is the transfer, and a partitioned build
        // pays for it once per partition, which is exactly what an operator needs to see.
        long[] frameReadNanos = {0L};
        int partitions = 1;
        try {
            while (true) {
                lease[0] = scratch.budget().open(ParquetScratchBudget.CHECKPOINT_FRAME);
                try {
                    return mergeAttempt(siteId, checkpointSeq, newSegments, frame, lease[0],
                            partitions, frameReadNanos);
                } catch (FoldTooLargeException e) {
                    lease[0].close();
                    lease[0] = null;
                    if (partitions >= maxMergePartitions) {
                        throw e;
                    }
                    if (partitions == 1) {
                        // Once per build, not once per partition: the series counts builds that
                        // needed the fallback, and an escalation is one build still.
                        metrics.checkpointBuildPartitioned();
                    }
                    partitions = (int) Math.min(
                            (long) partitions * PARTITION_GROWTH, maxMergePartitions);
                    log.warn("The delta of site {} did not fit delta.checkpoint.max-fold-bytes "
                            + "(an estimated {} bytes against {}), so its checkpoint is merged in "
                            + "{} hash partitions instead — the build finishes, at the cost of "
                            + "re-reading the seed frame and all {} segment(s) once per partition. "
                            + "Raise the key (and the pod's heap with it) if this is not a one-off",
                            siteId, e.estimatedBytes(), e.budgetBytes(), partitions,
                            newSegments.size());
                }
            }
        } catch (ArtifactSizeLimitExceededException | ScratchBudgetExceededException e) {
            throw reportFrameCeiling(siteId, seq, e);
        } finally {
            metrics.recordCheckpointPhase("download_frame", frameReadNanos[0]);
            metrics.recordCheckpointPhase("fold", System.nanoTime() - startedAt - frameReadNanos[0]);
        }
    }

    /** One attempt at the merged frame, in {@code partitions} passes over both sides. */
    private CheckpointFrameWriter.FrameManifest mergeAttempt(UUID siteId,
                                                              long checkpointSeq,
                                                              List<ChangelogSegment> newSegments,
                                                              Path frame,
                                                              ScratchLease lease,
                                                              int partitions,
                                                              long[] frameReadNanos) {
        try (OutputStream out = new CappedOutputStream(
                        Files.newOutputStream(frame), maxFrameTempBytes, lease);
                CheckpointFrameWriter writer = CheckpointFrameWriter.open(out)) {
            long peakBytes = 0L;
            for (int partition = 0; partition < partitions; partition++) {
                shutdown.stopIfShuttingDown(siteId);
                BudgetedMerge merge = new BudgetedMerge(siteId, maxFoldBytes, partitions, partition);
                for (ChangelogSegment segment : newSegments) {
                    foldSegment(siteId, segment, merge::apply);
                }
                streamFrameThrough(siteId, checkpointSeq, merge, writer::accept, frameReadNanos);
                merge.drain(writer::accept);
                peakBytes = Math.max(peakBytes, merge.peakEstimatedBytes());
            }
            // The band below the ceiling, on the same meter the fold reports (issue #152). The
            // largest partition is the one that decides whether this build fits, so it is the
            // sample; on the unpartitioned path there is only one.
            reportFoldSize(siteId, peakBytes);
            return writer.manifest();
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to write checkpoint frame for site " + siteId, e);
        }
    }

    /**
     * Stream the seed frame through the merge and into the new frame.
     *
     * <p>{@code frameReadNanos} is filled in whichever way this ends, and is added to rather than
     * assigned: a partitioned build streams the frame once per partition and the samples belong
     * together. The {@code GetObject} itself is timed even when it throws, for the reason
     * {@link #foldFrame} states — during a read outage every site of the tick would otherwise
     * contribute a zero-nanosecond sample.</p>
     *
     * <p>The consumer <em>writes</em> here, unlike the fold's, so its own refusals — the frame's
     * two ceilings — travel out untouched. Renaming them would report a full scratch directory as
     * an unreadable object and send an operator to S3 for a local problem.</p>
     */
    private void streamFrameThrough(UUID siteId, long checkpointSeq, BudgetedMerge merge,
                                    java.util.function.Consumer<ChangeRecord> out,
                                    long[] frameReadNanos) {
        long openedAt = System.nanoTime();
        InputStream opened;
        try {
            opened = checkpointStorage.openFrame(siteId, checkpointSeq);
        } finally {
            frameReadNanos[0] += System.nanoTime() - openedAt;
        }
        try (InputStream frame = opened) {
            TimingInputStream timed = new TimingInputStream(frame);
            try {
                ChangelogCodec.forEach(timed, record -> merge.accept(record, out));
            } finally {
                frameReadNanos[0] += timed.readNanos();
            }
        } catch (FoldTooLargeException | ArtifactSizeLimitExceededException
                | ScratchBudgetExceededException e) {
            throw e;
        } catch (IOException | RuntimeException e) {
            throw new S3CheckpointStorage.CheckpointStorageException(
                    "Failed to read the checkpoint frame of site " + siteId + " at seq " + checkpointSeq, e);
        }
    }

    /**
     * One partition's merge, with the same ceiling on heap the fold has (issue #152) and expressed
     * in the same estimated bytes, so the key means one thing on both paths.
     */
    private static final class BudgetedMerge {

        private final ChangelogMerge merge;
        private final UUID siteId;
        private final long maxBytes;
        private long bytes;
        private long peakBytes;

        private BudgetedMerge(UUID siteId, long maxBytes, int partitions, int partition) {
            this.siteId = siteId;
            this.maxBytes = maxBytes;
            this.merge = new ChangelogMerge(partitions, partition);
        }

        /**
         * Fold one record of the period, then stop the build if the delta no longer fits.
         *
         * <p>Checked after applying rather than before, for the reason {@code BudgetedFold} states:
         * the cost of one record is only known once it has been applied, and one record over the
         * ceiling is not what runs a pod out of memory.</p>
         */
        private void apply(ChangeRecord record) {
            bytes += merge.apply(record);
            peakBytes = Math.max(peakBytes, bytes);
            if (bytes > maxBytes) {
                throw new FoldTooLargeException(siteId, bytes, maxBytes);
            }
        }

        private void accept(ChangeRecord base, java.util.function.Consumer<ChangeRecord> out) {
            merge.accept(base, out);
        }

        private void drain(java.util.function.Consumer<ChangeRecord> out) {
            merge.drain(out);
        }

        /** The largest this partition's delta ever was. */
        private long peakEstimatedBytes() {
            return peakBytes;
        }
    }

    /**
     * Log (and, for the deterministic one, count) a reload frame that could not be written, and
     * hand the exception back for the caller to throw. Either way the build ends: the frame is the
     * next incremental seed and there is nothing to fall back on.
     */
    private RuntimeException reportFrameCeiling(UUID siteId, long seq, RuntimeException e) {
        if (e instanceof ArtifactSizeLimitExceededException) {
            // Both ceilings raise the same exception with the same "temp-file limit of N bytes"
            // text, and the per-table one is reported by its own counter — say which guard this
            // was and name the key, or the operator has only a byte count to go on. Rethrown
            // unchanged: an oversized frame still ends the build.
            //
            // Counted as well as logged (issue #153). The failure is deterministic for a given
            // fold, so it recurs on every tick with the pointer — and therefore retention — frozen
            // in place; a log line is not something an alert can be built on, and the symptom an
            // operator would otherwise notice first is an unbounded segment table.
            //
            // Logged before it is counted: the counter validates its reason and throws on an
            // unknown one (the same contract as checkpointTableUnmaterialized, and a programming
            // error either way), which would otherwise replace this exception *and* swallow the
            // only line naming the site and the key.
            log.error("The checkpoint reload frame for site {} at seq {} crossed "
                    + "delta.checkpoint.max-frame-temp-bytes ({} bytes) — the build is abandoned "
                    + "before any snapshot was written, so nothing durable changed: the per-table "
                    + "keys and last_checkpoint_seq stay where they were. Retention is frozen at "
                    + "that pointer and the next tick will fail identically, because the fold has "
                    + "not changed. Raise that key (and the scratch volume behind it) rather than "
                    + "the per-table ceiling",
                    siteId, seq, maxFrameTempBytes);
            metrics.checkpointBuildAborted("frame_too_large");
            return e;
        }
        if (e instanceof ScratchBudgetExceededException) {
            // The frame's existing failure mode — the build ends, because the frame is the next
            // incremental seed and there is nothing to fall back on. What it is deliberately NOT is
            // a fifth value on delta.checkpoint.builds.aborted: every value there is a refusal that
            // never repairs itself (#153), and this one clears the moment the batch workers holding
            // the directory finish. delta.parquet.scratch.refused{writer=checkpoint_frame} already
            // counted it inside the budget.
            //
            // A completed-batch backlog cannot take the reserved share (#193): batch writers stop
            // at max-scratch-bytes minus this frame ceiling. Seeing this with the directory budget
            // on is therefore a reserve of zero, a misconfiguration, or a checkpoint writer
            // competing with itself — not the operator's backlog.
            log.error("The checkpoint reload frame for site {} at seq {} could not be written "
                    + "because the shared Parquet scratch directory was full — the build is "
                    + "abandoned before any snapshot was written, so nothing durable changed and "
                    + "the next tick tries again. This is contention, not a fact about the site: "
                    + "raise delta.parquet.max-scratch-bytes (and the volume behind it), or lower "
                    + "delta.batch-parquet.max-concurrent", siteId, seq, e);
            return e;
        }
        return e;
    }
}
