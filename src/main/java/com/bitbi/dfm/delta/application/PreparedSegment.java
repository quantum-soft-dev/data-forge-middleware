package com.bitbi.dfm.delta.application;

import com.bitbi.dfm.delta.domain.TableChangeStats;

import java.util.Map;
import java.util.UUID;

/**
 * A changelog segment whose bytes are already in object storage but whose metadata row has not been
 * written yet (issue #147).
 *
 * <p>Splitting the two halves is what keeps the segment {@code PutObject} out of the ingestion
 * commit transaction: {@link ChangelogSegmentService#prepare} uploads and returns this value with
 * no transaction open, and {@link DeltaSessionCommitTransaction} then opens the transaction and
 * turns it into a row. The object is keyed by {@link #segmentId}, minted during the upload, so a
 * segment whose transaction later rolls back leaves an unreferenced object nobody can reach — the
 * same harmless orphan this path already tolerated when the upload sat <em>inside</em> the
 * transaction, since nothing deleted it on rollback then either.</p>
 *
 * <p>Carries the row's whole content, {@code batchId} and {@code mode} included, so writing it is a
 * single argument-free decision. An application-layer value object: not a wire DTO, not persisted,
 * and never serialized.</p>
 *
 * @param segmentId   the segment's identity, shared by the object key and the future row
 * @param siteId      site the segment belongs to
 * @param batchId     batch (session) the segment was sealed under
 * @param mode        session mode (DELTA | CONTINUOUS | FULL_SNAPSHOT)
 * @param firstSeq    first sequence covered by the segment
 * @param lastSeq     last sequence covered ({@code firstSeq - 1} for an empty record list)
 * @param recordCount number of change records serialized into the object
 * @param contentHash SHA-256 (hex) of the uploaded bytes
 * @param s3Key       key the object was stored at
 * @param stats       per-table insert/update/delete counts
 * @author Data Forge Team
 * @version 1.0.0
 */
public record PreparedSegment(UUID segmentId,
                              UUID siteId,
                              UUID batchId,
                              String mode,
                              long firstSeq,
                              long lastSeq,
                              int recordCount,
                              String contentHash,
                              String s3Key,
                              Map<String, TableChangeStats> stats) {
}
