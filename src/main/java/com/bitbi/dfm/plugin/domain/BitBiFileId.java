package com.bitbi.dfm.plugin.domain;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Optional;
import java.util.UUID;

/**
 * The {@code fileId} of the Bit BI v2 surface (issue #387): opaque to the consumer, pinned to one
 * version of one file.
 *
 * <p><b>Why not the id of a {@code checkpoints} row.</b> The row of a table is reused by every
 * build, which replaces its seq and its key, so an id naming "the row of table T" would hand out a
 * different file than the one {@code /snapshot} listed as soon as the next build ran. The id names
 * the <em>version</em> instead — the site, its epoch, the seq and the table — and the object key is
 * derived from it by the function the build writes with, never read from the row.</p>
 *
 * <p><b>Why the epoch.</b> A history wipe and a re-baseline restart a site's seq, so the same
 * {@code (table, seq)} can later name different data. Resolution compares the epoch with the site's
 * current one and refuses an id of a discarded history.</p>
 *
 * <p><b>Not signed, and not a capability.</b> Resolution re-checks everything it relies on — the
 * key's account owns the site, the epoch is current, the table exists, the object is there — so a
 * forged id can reach nothing a valid one of the same account could not. The wire form is
 * base64url without padding of {@code <kind>:<fields>}; the kind carries its own version, so a later
 * kind is added beside the others without changing them: {@code ck1} names a checkpoint snapshot
 * (#387), {@code bp1} a completed-batch Parquet of the changes feed (#388).</p>
 *
 * <p><b>One spelling per id.</b> {@link #decode} accepts only the exact string {@link #encode}
 * writes — re-encoded and compared — so padding, a leading zero, a {@code +} sign or an upper-case
 * UUID do not name the same file a second time (review of #396).</p>
 */
public sealed interface BitBiFileId permits BitBiFileId.Checkpoint, BitBiFileId.BatchFile {

    /** Kind of a checkpoint snapshot id, format version 1. */
    String CHECKPOINT_KIND = "ck1";

    /** Kind of a completed-batch Parquet id, format version 1 (issue #388). */
    String BATCH_FILE_KIND = "bp1";

    /**
     * @return the opaque wire form
     */
    String encode();

    /**
     * Read a wire form back.
     *
     * @param wire the opaque id from the request path
     * @return the id, or empty when the string is not one this server issues
     */
    static Optional<BitBiFileId> decode(String wire) {
        if (wire == null || wire.isEmpty()) {
            return Optional.empty();
        }
        String raw;
        try {
            raw = new String(Base64.getUrlDecoder().decode(wire), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException notBase64) {
            return Optional.empty();
        }
        Optional<BitBiFileId> parsed;
        try {
            parsed = parse(raw);
        } catch (IllegalArgumentException malformed) {
            // NumberFormatException is an IllegalArgumentException, as is a constructor's refusal
            return Optional.empty();
        }
        // Strict: only the spelling this server writes names the file.
        return parsed.filter(id -> id.encode().equals(wire));
    }

    private static Optional<BitBiFileId> parse(String raw) {
        int kindEnd = raw.indexOf(':');
        String kind = kindEnd < 0 ? raw : raw.substring(0, kindEnd);
        // The table is the last field and may itself carry the separator.
        if (CHECKPOINT_KIND.equals(kind)) {
            String[] parts = raw.split(":", 5);
            if (parts.length != 5) {
                return Optional.empty();
            }
            return Optional.of(new Checkpoint(UUID.fromString(parts[1]), Long.parseLong(parts[2]),
                    Long.parseLong(parts[3]), parts[4]));
        }
        if (BATCH_FILE_KIND.equals(kind)) {
            String[] parts = raw.split(":", 4);
            if (parts.length != 4) {
                return Optional.empty();
            }
            return Optional.of(new BatchFile(UUID.fromString(parts[1]), UUID.fromString(parts[2]), parts[3]));
        }
        return Optional.empty();
    }

    private static String wire(String raw) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * One table's checkpoint snapshot at one seq of one epoch.
     *
     * @param siteId the site
     * @param epoch  the site's baseline epoch the snapshot belongs to
     * @param seq    the seq the snapshot was built at; always above 0
     * @param table  the table; never blank, never containing {@code /} (it becomes part of a key)
     */
    record Checkpoint(UUID siteId, long epoch, long seq, String table) implements BitBiFileId {

        public Checkpoint {
            if (siteId == null) {
                throw new IllegalArgumentException("siteId is required");
            }
            if (epoch < 0) {
                throw new IllegalArgumentException("epoch must not be negative, but was " + epoch);
            }
            if (seq <= 0) {
                throw new IllegalArgumentException("seq must be above 0, but was " + seq);
            }
            if (!isAddressable(table)) {
                throw new IllegalArgumentException("table must be a non-blank name without '/'");
            }
        }

        /**
         * Whether a table name can be part of a checkpoint id — it becomes a segment of an object
         * key, so a name carrying {@code /} cannot. A caller listing tables asks this rather than
         * letting the constructor's refusal fail the whole listing.
         *
         * @param table the table name
         * @return true when {@code new Checkpoint(..., table)} would accept it
         */
        public static boolean isAddressable(String table) {
            return table != null && !table.isBlank() && table.indexOf('/') < 0;
        }

        @Override
        public String encode() {
            return wire(CHECKPOINT_KIND + ":" + siteId + ":" + epoch + ":" + seq + ":" + table);
        }
    }

    /**
     * One table's completed-batch Parquet (issue #388), the artifact {@code batch_parquet_artifacts}
     * publishes once per {@code (batch, table)}.
     *
     * <p>No epoch and no seq: a {@code READY} artifact is never rewritten (an operator requeue,
     * 039, takes only {@code ABANDONED} or a lapsed claim), so {@code (batch, table)} already names
     * one version, and resolution reads the batch's own epoch instead of trusting one in the id.
     * The object key is the row's, not derived, so the table may carry any character.</p>
     *
     * @param siteId  the site
     * @param batchId the batch (= one Delta session)
     * @param table   the table; never blank
     */
    record BatchFile(UUID siteId, UUID batchId, String table) implements BitBiFileId {

        public BatchFile {
            if (siteId == null) {
                throw new IllegalArgumentException("siteId is required");
            }
            if (batchId == null) {
                throw new IllegalArgumentException("batchId is required");
            }
            if (table == null || table.isBlank()) {
                throw new IllegalArgumentException("table must be a non-blank name");
            }
        }

        @Override
        public String encode() {
            return wire(BATCH_FILE_KIND + ":" + siteId + ":" + batchId + ":" + table);
        }
    }
}
