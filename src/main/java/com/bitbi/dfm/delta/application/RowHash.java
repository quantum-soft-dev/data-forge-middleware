package com.bitbi.dfm.delta.application;

import com.bitbi.dfm.delta.grpc.v2.ChangeRecord;
import com.google.protobuf.ByteString;

import java.util.HexFormat;

/**
 * The client's row identity, {@code ChangeRecord.row_hash} (issue #369), read one way everywhere.
 *
 * <p>The value is opaque: a 32-byte SHA-256 of the client's canonical row form that the server
 * never recomputes or verifies. What the server does decide is what counts as <em>present</em>:
 * exactly {@value #LENGTH} bytes. Empty is an older client — or an older segment or frame, since
 * field 7 did not exist before this change — and any other length is a malformed value that
 * ingestion drops ({@link #dropMalformed}) rather than failing the session over, because there is no
 * {@code ErrorCode} for it and the shipped client does not send one. Every consumer (fold, frames,
 * Parquet) goes through {@link #of}, so the two absent shapes cannot disagree between them.</p>
 *
 * <p>Deliberately not part of the fold identity nor of the session {@code content_hash}: {@code key}
 * stays authoritative, and a client that already computes the content hash must keep getting the
 * same one.</p>
 *
 * @author Data Forge Team
 * @version 1.0.0
 */
public final class RowHash {

    /** The length of a SHA-256 digest — the only length that counts as a row hash. */
    public static final int LENGTH = 32;

    private RowHash() {
    }

    /**
     * The record's row hash, or {@code null} when it has none — empty or not {@value #LENGTH} bytes.
     *
     * @param record a change record, from the wire, a segment or a frame
     * @return the 32-byte hash, or {@code null}
     */
    public static ByteString of(ChangeRecord record) {
        ByteString hash = record.getRowHash();
        return hash.size() == LENGTH ? hash : null;
    }

    /**
     * Whether the record carries a value that is not a row hash: non-empty and not {@value #LENGTH}
     * bytes long.
     *
     * @param record an incoming change record
     * @return {@code true} if ingestion must drop the value
     */
    public static boolean isMalformed(ChangeRecord record) {
        ByteString hash = record.getRowHash();
        return !hash.isEmpty() && hash.size() != LENGTH;
    }

    /**
     * The record with a malformed row hash cleared, so the value never reaches a segment; any other
     * record is returned as it is.
     *
     * @param record an incoming change record
     * @return the record to stage
     */
    public static ChangeRecord dropMalformed(ChangeRecord record) {
        return isMalformed(record) ? record.toBuilder().clearRowHash().build() : record;
    }

    /**
     * The Parquet form of a row hash: 64 lowercase hex characters, the way the client's own
     * {@code state.db} and logs show it (owner's decision on #369).
     *
     * @param hash a 32-byte row hash, or {@code null}
     * @return the hex string, or {@code null} for a row without a hash
     */
    public static String hex(ByteString hash) {
        return hash == null ? null : HexFormat.of().formatHex(hash.toByteArray());
    }
}
