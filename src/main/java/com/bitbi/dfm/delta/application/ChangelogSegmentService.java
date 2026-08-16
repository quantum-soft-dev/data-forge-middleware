package com.bitbi.dfm.delta.application;

import com.bitbi.dfm.delta.domain.ChangelogSegment;
import com.bitbi.dfm.delta.domain.ChangelogSegmentRepository;
import com.bitbi.dfm.delta.domain.TableChangeStats;
import com.bitbi.dfm.delta.grpc.v2.ChangeRecord;
import com.bitbi.dfm.delta.infrastructure.S3ChangelogSegmentStorage;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * Persists a session's accepted change records as an immutable changelog segment
 * (Delta Client v2 — 022): serialize → object storage (bronze) → metadata row.
 *
 * <p>Those steps are two separately callable halves — {@link #prepare} (serialize and upload) and
 * {@link #persistPrepared} (write the row) — so the ingestion commit can open its transaction only
 * around the second one (issue #147).</p>
 *
 * @author Data Forge Team
 * @version 1.0.0
 */
@Service
public class ChangelogSegmentService {

    private final S3ChangelogSegmentStorage storage;
    private final ChangelogSegmentRepository repository;

    public ChangelogSegmentService(S3ChangelogSegmentStorage storage, ChangelogSegmentRepository repository) {
        this.storage = storage;
        this.repository = repository;
    }

    /**
     * Serialize the accepted records and upload them, without writing anything to the database
     * (issue #147).
     *
     * <p>This is the slow half of persisting a segment — a {@code PutObject} that takes seconds for
     * a large FULL_SNAPSHOT tail — and it deliberately runs <em>before</em> the ingestion commit
     * transaction opens. Keeping it inside meant every row lock that transaction had taken (since
     * issue #142, the {@code site_sync_state} row {@link DeltaRebaselineService#reset} locks as its
     * first statement) was held for the length of a network upload, and a HikariCP connection with
     * them.</p>
     *
     * <p>The segment id is minted here so the storage key carries the segment's own identity — a
     * session's batch owns many segments (029), a batch-derived key would collide — and so an
     * upload whose transaction never commits is an unreachable orphan rather than a key someone
     * else may reuse.</p>
     *
     * @param siteId   site identifier
     * @param batchId  batch (session) identifier — carried through to the row
     * @param mode     session mode (DELTA | CONTINUOUS | FULL_SNAPSHOT)
     * @param firstSeq first sequence of the segment
     * @param records  accepted change records (in sequence order)
     * @return the uploaded segment, ready for {@link #persistPrepared}
     * @throws IllegalStateException when called with a transaction already open
     */
    public PreparedSegment prepare(UUID siteId, UUID batchId, String mode, long firstSeq,
                                   List<ChangeRecord> records) {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException(
                    "Refusing to upload a changelog segment inside an active transaction: the upload "
                            + "would hold that transaction's row locks and its connection for the length "
                            + "of a network call (issue #147). Call prepare() first, then persistPrepared() "
                            + "inside the transaction.");
        }
        byte[] content = ChangelogCodec.serialize(records);
        String contentHash = sha256Hex(content);
        UUID segmentId = UUID.randomUUID();
        String s3Key = storage.uploadSegment(siteId, segmentId, content);

        long lastSeq = records.isEmpty() ? firstSeq - 1 : records.get(records.size() - 1).getSeq();
        Map<String, TableChangeStats> stats = ChangeRecordStats.computeByTable(records);
        return new PreparedSegment(segmentId, siteId, batchId, mode, firstSeq, lastSeq, records.size(),
                contentHash, s3Key, stats);
    }

    /**
     * Write the metadata row of an already-uploaded segment (issue #147) — the database half of
     * {@link #prepare}, and the only half that belongs in the ingestion commit transaction.
     *
     * @param prepared the segment whose object is already stored
     * @return the persisted segment metadata
     */
    @Transactional
    public ChangelogSegment persistPrepared(PreparedSegment prepared) {
        return save(prepared, false);
    }

    /**
     * As {@link #persistPrepared}, but the row is {@code provisional} (033): invisible to the
     * checkpoint fold, the delta-Parquet egress queue and the Bit BI SQL queue until
     * {@code SessionEnd} publishes the whole batch. This is what lets a snapshot larger than the
     * session buffer stream at all, without exposing a half-replaced dataset to readers.
     *
     * @return the persisted, still-invisible segment metadata
     */
    @Transactional
    public ChangelogSegment persistPreparedProvisional(PreparedSegment prepared) {
        return save(prepared, true);
    }

    /**
     * Upload and record a segment in one call — a <strong>test-fixture convenience</strong>, and
     * deliberately nothing more: no production path calls it, and new production code belongs on
     * the split pair instead. The ingestion commit uses {@link #prepare} and
     * {@link #persistPrepared} separately so that only the second half runs with the transaction
     * open.
     *
     * <p>Two things to know before reaching for this pair. It <strong>throws</strong> when called
     * with a transaction open — {@link #prepare} refuses — where the single {@code persist} it
     * replaces would have quietly joined the caller's. And the row half runs <em>unproxied</em>:
     * calling {@link #persistPrepared} on {@code this} bypasses its {@code @Transactional}, so the
     * write happens in the transaction Spring Data opens around {@code save}. That is correct only
     * while the row half stays a single statement — a second write added to {@code save} would
     * silently stop being atomic here. Keep it one statement, or route the caller through
     * {@link DeltaSessionCommitTransaction} as the ingestion path does.</p>
     *
     * @param siteId   site identifier
     * @param batchId  batch (session) identifier
     * @param mode     session mode (DELTA | FULL_SNAPSHOT)
     * @param firstSeq first sequence of the session
     * @param records  accepted change records (in sequence order)
     * @return the persisted segment metadata
     * @throws IllegalStateException when called with a transaction already open
     */
    public ChangelogSegment persist(UUID siteId, UUID batchId, String mode, long firstSeq, List<ChangeRecord> records) {
        return persistPrepared(prepare(siteId, batchId, mode, firstSeq, records));
    }

    /**
     * {@link #persist} for a mid-snapshot seal of a re-baseline session (033).
     *
     * @return the persisted, still-invisible segment metadata
     */
    public ChangelogSegment persistProvisional(UUID siteId, UUID batchId, String mode, long firstSeq,
                                               List<ChangeRecord> records) {
        return persistPreparedProvisional(prepare(siteId, batchId, mode, firstSeq, records));
    }

    private ChangelogSegment save(PreparedSegment prepared, boolean provisional) {
        ChangelogSegment segment = ChangelogSegment.create(
                prepared.segmentId(), prepared.siteId(), prepared.batchId(), prepared.firstSeq(),
                prepared.lastSeq(), prepared.recordCount(), prepared.contentHash(), prepared.s3Key(),
                prepared.mode(), prepared.stats());
        if (provisional) {
            segment.markProvisional();
        }
        return repository.save(segment);
    }

    /**
     * Publish a completed re-baseline: clear {@code provisional} on every segment of the snapshot's
     * batch, so the fold and both work queues see the whole new baseline at once (033). Runs inside
     * the commit transaction, right after the previous baseline is discarded.
     *
     * @param batchId the snapshot session's batch
     * @return number of segments published
     */
    @Transactional
    public int publishProvisional(UUID batchId) {
        return repository.flipProvisionalByBatchId(batchId);
    }

    /**
     * Move a session's provisional segments onto a different batch (033 review) — used when a resume
     * runs under a replacement batch, so publication (which is batch-keyed) still covers them.
     *
     * @param fromBatchId batch the segments were sealed under
     * @param toBatchId   batch the resumed session now owns
     * @return number of segments moved
     */
    @Transactional
    public int reassignProvisionalBatch(UUID fromBatchId, UUID toBatchId) {
        return repository.reassignProvisionalBatch(fromBatchId, toBatchId);
    }

    /**
     * Read back the change records of a persisted segment.
     *
     * @param s3Key segment key
     * @return the segment's change records in order
     */
    public List<ChangeRecord> readRecords(String s3Key) {
        return ChangelogCodec.parse(storage.download(s3Key));
    }

    /**
     * Stream a raw segment in wire order without retaining its records. The stream is closed here
     * rather than inside the codec: a truncated or corrupt object makes {@code GZIPInputStream}
     * throw while reading the header, and the wrapper that would have closed it is never created.
     */
    public void forEachRecord(String s3Key, Consumer<ChangeRecord> consumer) {
        forEachRecord(s3Key, consumer, null);
    }

    /**
     * Stream a raw segment. When {@code clock} is present, GetObject / stream {@code read} is
     * download and parse-minus-consumer is decode — including when {@code open} throws.
     */
    void forEachRecord(String s3Key, Consumer<ChangeRecord> consumer, PhaseClock clock) {
        long openedAt = System.nanoTime();
        InputStream content = null;
        try {
            content = storage.open(s3Key);
            if (clock != null) {
                clock.addDownload(System.nanoTime() - openedAt);
            }
            replay(content, consumer, clock);
        } catch (RuntimeException e) {
            if (clock != null && content == null) {
                clock.addDownload(System.nanoTime() - openedAt);
            }
            // Close it here, and let a close failure only be *suppressed*: the reason the caller is
            // unwinding is the fact that matters, and the finally below would have replaced it.
            // Closing a partially consumed S3 stream is exactly the case that fails, and it is
            // exactly what an abandoned replay produces — a checkpoint fold that ran out of its
            // heap budget (issue #152) would otherwise reach CheckpointService as an
            // UncheckedIOException, missing its counter and the ERROR that names the site.
            if (content != null) {
                try {
                    content.close();
                } catch (IOException closeFailure) {
                    e.addSuppressed(closeFailure);
                }
                content = null;
            }
            throw e;
        } finally {
            if (content != null) {
                try {
                    content.close();
                } catch (IOException e) {
                    throw new UncheckedIOException("Failed to close changelog segment " + s3Key, e);
                }
            }
        }
    }

    /**
     * Stream records from an already-open segment. When {@code clock} is present, stream
     * {@code read} time is download and parse-minus-consumer is decode.
     */
    static void replay(InputStream content, Consumer<ChangeRecord> consumer, PhaseClock clock) {
        if (clock == null) {
            ChangelogCodec.forEach(content, consumer);
            return;
        }
        TimingInputStream timed = content instanceof TimingInputStream already
                ? already
                : new TimingInputStream(content);
        long wallStarted = System.nanoTime();
        long[] consumerNanos = {0L};
        try {
            ChangelogCodec.forEach(timed, record -> {
                long consumeStarted = System.nanoTime();
                try {
                    consumer.accept(record);
                } finally {
                    consumerNanos[0] += System.nanoTime() - consumeStarted;
                }
            });
        } finally {
            clock.addDownload(timed.readNanos());
            clock.addDecode(System.nanoTime() - wallStarted - timed.readNanos() - consumerNanos[0]);
        }
    }

    /**
     * The most recent segments of a site, newest first (Delta Sync UI, B6 — admin surface).
     *
     * @param siteId site identifier
     * @param limit  maximum segments to return
     * @return up to {@code limit} segments ordered by createdAt desc
     */
    @Transactional(readOnly = true)
    public List<ChangelogSegment> listRecentSegments(UUID siteId, int limit) {
        return repository.findRecentBySiteId(siteId, limit);
    }

    /**
     * Delete all changelog segments (object storage + metadata) for a batch. Called before a batch is
     * removed by retention/admin so the {@code changelog_segments.batch_id} foreign key does not block
     * the delete and no S3 segment object is orphaned.
     *
     * @param batchId batch identifier
     */
    @Transactional
    public void deleteByBatchId(UUID batchId) {
        deleteMetadataByBatchId(batchId).forEach(storage::delete);
    }

    /**
     * Remove only segment metadata and return the object keys. A wider batch transaction uses this
     * variant so it can defer irreversible object deletion until the database commit succeeds.
     */
    @Transactional
    public List<String> deleteMetadataByBatchId(UUID batchId) {
        List<String> keys = new ArrayList<>();
        for (ChangelogSegment segment : repository.findByBatchId(batchId)) {
            keys.add(segment.getS3Key());
            repository.deleteById(segment.getId());
        }
        return keys;
    }

    private static String sha256Hex(byte[] content) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(content));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
