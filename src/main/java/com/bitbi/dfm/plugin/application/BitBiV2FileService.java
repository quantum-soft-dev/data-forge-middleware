package com.bitbi.dfm.plugin.application;

import com.bitbi.dfm.batch.infrastructure.S3PresignedUrlService;
import com.bitbi.dfm.delta.infrastructure.S3CheckpointStorage;
import com.bitbi.dfm.delta.infrastructure.S3CheckpointStorage.CheckpointStorageException;
import com.bitbi.dfm.plugin.application.BitBiV2QueryService.SnapshotSource;
import com.bitbi.dfm.plugin.application.BitBiV2QueryService.TableVersion;
import com.bitbi.dfm.plugin.domain.BitBiFileId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * The file half of the Bit BI v2 read surface (issue #387): {@code /snapshot} and
 * {@code /files/{fileId}} — the latter for a checkpoint id ({@code ck1}) and, since #388, for a
 * completed-batch id ({@code bp1}) the changes feed hands out.
 *
 * <p><b>One seq for every table.</b> The listing is at the site's checkpoint pointer
 * ({@code site_sync_state.last_checkpoint_seq}, the {@code checkpointSeq} of {@code /state}) and at
 * no other seq. A checkpoint build saves each table's row at the new seq <em>before</em> it moves
 * the pointer, and a build that ends part way — a scratch refusal, a pod shutdown — leaves its rows
 * like that until the next night; v1's {@code /files} then mixes two builds (BitBi's question 55). So
 * a row that has moved past the pointer is answered from the object the previous build left
 * <em>at</em> the pointer, which still exists: the orphan sweep keeps every checkpoint object at or
 * above the pointer (#158). That costs one HEAD per such table, only while rows and pointer
 * disagree. A table with nothing at the pointer — no snapshot (given up, #149), a row below the
 * pointer, or a table first seen by the running build — is listed as {@link TableStatus#MISSING}
 * rather than left out.</p>
 *
 * <p><b>A redirect, not a proxy.</b> {@code /files} answers with a presigned URL valid for
 * {@link #PRESIGN_TTL}; the bytes never pass through this application. The key comes from the id
 * ({@link BitBiFileId}) through the function the build writes with, so the URL names the object
 * {@code /snapshot} listed even after the next build has replaced the table's row — until retention
 * or the orphan sweep removes that object, after which the answer is a 404.</p>
 *
 * <p><b>No S3 call inside a transaction</b> (#164): the database is read through
 * {@link BitBiV2QueryService}'s read-only methods, which return before any HEAD or presign here.</p>
 */
@Service
public class BitBiV2FileService {

    private static final Logger log = LoggerFactory.getLogger(BitBiV2FileService.class);

    /**
     * How long a {@code /files} redirect stays valid. Short, because it is followed at once and the
     * key is sent on every request; S3 checks the signature when a transfer starts, so a large file
     * still downloads to the end.
     */
    static final Duration PRESIGN_TTL = Duration.ofSeconds(60);

    private final BitBiV2QueryService queryService;
    private final BitBiV2ChangesService changesService;
    private final S3CheckpointStorage checkpointStorage;
    private final S3PresignedUrlService presignedUrlService;

    public BitBiV2FileService(BitBiV2QueryService queryService,
                              BitBiV2ChangesService changesService,
                              S3CheckpointStorage checkpointStorage,
                              S3PresignedUrlService presignedUrlService) {
        this.queryService = queryService;
        this.changesService = changesService;
        this.checkpointStorage = checkpointStorage;
        this.presignedUrlService = presignedUrlService;
    }

    /**
     * One checkpoint Parquet per table, all at {@code checkpointSeq}.
     *
     * @param accountId the account the API key belongs to
     * @param siteId    the site
     * @return the epoch, the seq and each table's file; no tables while there is no checkpoint
     * @throws com.bitbi.dfm.site.application.SiteService.SiteNotFoundException when the site does
     *                                                                           not exist or is another account's
     * @throws CheckpointStorageException when S3 cannot say whether a table's object at the pointer exists
     */
    public Snapshot snapshot(UUID accountId, UUID siteId) {
        SnapshotSource source = queryService.snapshotSource(accountId, siteId);
        long seq = source.checkpointSeq();
        List<SnapshotTable> tables = new ArrayList<>(source.tables().size());
        for (TableVersion row : source.tables()) {
            tables.add(atPointer(source, row)
                    ? new SnapshotTable(row.table(), TableStatus.READY,
                    new BitBiFileId.Checkpoint(siteId, source.epoch(), seq, row.table()).encode())
                    : new SnapshotTable(row.table(), TableStatus.MISSING, null));
        }
        return new Snapshot(siteId, source.epoch(), seq, List.copyOf(tables));
    }

    /**
     * Is there a snapshot of this table at the pointer?
     */
    private boolean atPointer(SnapshotSource source, TableVersion row) {
        if (!BitBiFileId.Checkpoint.isAddressable(row.table())) {
            // No id can name it (its name would be a path in the key): listed, not a 500 for the
            // whole snapshot (#396 review).
            return false;
        }
        long pointer = source.checkpointSeq();
        if (row.seq() == pointer) {
            // The row still describes the pointer; its key is the object, or there is none (#149).
            return row.s3Key() != null;
        }
        if (row.seq() < pointer) {
            // An older state of the table than the pointer: serving it would mix two builds.
            return false;
        }
        // The running (or a broken-off) build has moved the row on; the pointer's object is kept
        // until the pointer passes it.
        String key = S3CheckpointStorage.snapshotKey(source.siteId(), row.table(), pointer);
        return switch (checkpointStorage.presence(key)) {
            case PRESENT -> true;
            case ABSENT -> false;
            case UNKNOWN -> throw new CheckpointStorageException("Cannot tell whether " + key + " exists", null);
        };
    }

    /**
     * Resolve a {@code fileId} to a short-lived presigned URL of the object it names.
     *
     * @param accountId the account the API key belongs to
     * @param fileId    the opaque id from the request path
     * @return the presigned URL
     * @throws FileNotFoundException when the id is not one this server issued, names another
     *                               account's file, belongs to a discarded epoch, names a batch file
     *                               that is not ready, or its object is gone
     * @throws CheckpointStorageException when S3 cannot say whether the object exists
     */
    public String presignedUrl(UUID accountId, String fileId) {
        BitBiFileId id = BitBiFileId.decode(fileId).orElseThrow(() -> new FileNotFoundException(fileId));
        return switch (id) {
            case BitBiFileId.Checkpoint checkpoint -> checkpointUrl(accountId, fileId, checkpoint);
            case BitBiFileId.BatchFile batchFile -> batchFileUrl(accountId, fileId, batchFile);
        };
    }

    private String checkpointUrl(UUID accountId, String fileId, BitBiFileId.Checkpoint id) {
        // One answer for every refusal, so an id cannot be used to learn which sites exist.
        if (!queryService.isResolvable(accountId, id)) {
            throw new FileNotFoundException(fileId);
        }
        String key = S3CheckpointStorage.snapshotKey(id.siteId(), id.table(), id.seq());
        switch (checkpointStorage.presence(key)) {
            case PRESENT -> { }
            case ABSENT -> {
                log.info("Bit BI v2 file {} names {}, which is gone (retention or the orphan sweep)", fileId, key);
                throw new FileNotFoundException(fileId);
            }
            case UNKNOWN -> throw new CheckpointStorageException("Cannot tell whether " + key + " exists", null);
        }
        String fileName = id.table() + "_seq" + id.seq() + ".parquet";
        return presignedUrlService.generatePresignedUrl(key, fileName, PRESIGN_TTL).url();
    }

    /**
     * A completed-batch Parquet (issue #388). The {@code READY} row is the authority and no HEAD is
     * made: the object is published before the row turns {@code READY}, is never rewritten, and is
     * removed only after its row (retention, batch or site deletion), so a row still {@code READY}
     * names an object that exists.
     */
    private String batchFileUrl(UUID accountId, String fileId, BitBiFileId.BatchFile id) {
        String key = changesService.readyBatchFileKey(accountId, id)
                .orElseThrow(() -> new FileNotFoundException(fileId));
        String safeTable = id.table().replaceAll("[^A-Za-z0-9._-]", "_");
        String fileName = safeTable + "_batch-" + id.batchId() + ".parquet";
        return presignedUrlService.generatePresignedUrl(key, fileName, PRESIGN_TTL).url();
    }

    /**
     * A site's snapshot listing.
     *
     * @param siteId        the site
     * @param epoch         the site's baseline epoch
     * @param checkpointSeq the seq every ready file is at; 0 = no checkpoint yet
     * @param tables        one entry per table with a checkpoint row, sorted by table
     */
    public record Snapshot(UUID siteId, long epoch, long checkpointSeq, List<SnapshotTable> tables) {
    }

    /**
     * One table of a snapshot listing.
     *
     * @param table  the table
     * @param status whether a file exists at {@code checkpointSeq}
     * @param fileId the file's id when {@link TableStatus#READY}; null otherwise
     */
    public record SnapshotTable(String table, TableStatus status, String fileId) {
    }

    /**
     * Whether a table has a snapshot at {@code checkpointSeq}.
     */
    public enum TableStatus {
        /** A file exists at the pointer. */
        READY("ready"),
        /** No file at the pointer: given up (#149), behind the pointer, or first seen by a running build. */
        MISSING("missing");

        private final String wireValue;

        TableStatus(String wireValue) {
            this.wireValue = wireValue;
        }

        /**
         * @return the value written to the wire
         */
        public String wireValue() {
            return wireValue;
        }
    }

    /**
     * No file for this id — the 404 of {@code /files/{fileId}}, one message for every reason.
     */
    public static class FileNotFoundException extends RuntimeException {
        public FileNotFoundException(String fileId) {
            super("File not found: " + fileId);
        }
    }
}
