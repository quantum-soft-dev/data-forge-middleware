package com.bitbi.dfm.plugin.domain;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.Optional;
import java.util.UUID;

/**
 * The page cursor of the Bit BI v2 changes feed (issue #388): the position of the last batch a page
 * held, {@code (started_at, id)} — the feed's order — bound to the site it was issued for.
 *
 * <p>A position, not a seq: the consumer's watermark is {@code afterSeq}, and the cursor only walks
 * the batches above it page by page. Because a site runs one session at a time, every batch of a
 * later epoch started after every batch of an earlier one, so a cursor taken in an epoch that has
 * since been replaced still leads to the new epoch's first batch.</p>
 *
 * <p>Opaque base64url without padding of {@code fc1:<siteId>:<started_at in µs since the epoch,
 * UTC>:<batchId>}; {@code started_at} is a UTC wall clock at PostgreSQL's microsecond precision, so
 * the round trip is exact. Strict like {@link BitBiFileId}: only the spelling {@link #encode}
 * writes is accepted.</p>
 *
 * @param siteId    the site the cursor walks
 * @param startedAt {@code started_at} of the last batch of the page
 * @param batchId   its id
 */
public record BitBiChangesCursor(UUID siteId, LocalDateTime startedAt, UUID batchId) {

    private static final String KIND = "fc1";

    public BitBiChangesCursor {
        if (siteId == null || startedAt == null || batchId == null) {
            throw new IllegalArgumentException("siteId, startedAt and batchId are required");
        }
        startedAt = startedAt.truncatedTo(ChronoUnit.MICROS);
    }

    /**
     * @return the opaque wire form
     */
    public String encode() {
        long micros = ChronoUnit.MICROS.between(LocalDateTime.of(1970, 1, 1, 0, 0), startedAt);
        String raw = KIND + ":" + siteId + ":" + micros + ":" + batchId;
        return Base64.getUrlEncoder().withoutPadding().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Read a wire form back.
     *
     * @param wire the cursor from the request
     * @return the cursor, or empty when the string is not one this server issues
     */
    public static Optional<BitBiChangesCursor> decode(String wire) {
        if (wire == null || wire.isEmpty()) {
            return Optional.empty();
        }
        try {
            String raw = new String(Base64.getUrlDecoder().decode(wire), StandardCharsets.UTF_8);
            String[] parts = raw.split(":", -1);
            if (parts.length != 4 || !KIND.equals(parts[0])) {
                return Optional.empty();
            }
            long micros = Long.parseLong(parts[2]);
            LocalDateTime startedAt = LocalDateTime.ofEpochSecond(Math.floorDiv(micros, 1_000_000L),
                    (int) Math.floorMod(micros, 1_000_000L) * 1_000, ZoneOffset.UTC);
            BitBiChangesCursor cursor = new BitBiChangesCursor(UUID.fromString(parts[1]), startedAt,
                    UUID.fromString(parts[3]));
            return Optional.of(cursor).filter(c -> c.encode().equals(wire));
        } catch (IllegalArgumentException | java.time.DateTimeException malformed) {
            return Optional.empty();
        }
    }
}
