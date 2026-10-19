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
 * kind (the completed-batch artifacts of #388) is added beside this one without changing it.</p>
 */
public sealed interface BitBiFileId permits BitBiFileId.Checkpoint {

    /** Kind of a checkpoint snapshot id, format version 1. */
    String CHECKPOINT_KIND = "ck1";

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
        // The table is the last field and may itself carry the separator.
        String[] parts = raw.split(":", 5);
        if (parts.length != 5 || !CHECKPOINT_KIND.equals(parts[0])) {
            return Optional.empty();
        }
        try {
            return Optional.of(new Checkpoint(UUID.fromString(parts[1]), Long.parseLong(parts[2]),
                    Long.parseLong(parts[3]), parts[4]));
        } catch (IllegalArgumentException malformed) {
            // NumberFormatException is an IllegalArgumentException, as is the constructor's refusal
            return Optional.empty();
        }
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
            if (table == null || table.isBlank() || table.indexOf('/') >= 0) {
                throw new IllegalArgumentException("table must be a non-blank name without '/'");
            }
        }

        @Override
        public String encode() {
            String raw = CHECKPOINT_KIND + ":" + siteId + ":" + epoch + ":" + seq + ":" + table;
            return Base64.getUrlEncoder().withoutPadding().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
        }
    }
}
