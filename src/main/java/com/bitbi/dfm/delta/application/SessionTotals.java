package com.bitbi.dfm.delta.application;

import com.bitbi.dfm.delta.domain.TableChangeStats;
import com.bitbi.dfm.delta.grpc.v2.ChangeRecord;
import com.bitbi.dfm.delta.grpc.v2.TableStats;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Whole-session reconciliation totals for a Delta v2 ingestion session (033).
 *
 * <p>{@code SessionEnd} verifies the client's declared per-table counts and {@code content_hash}
 * against what the server actually accepted. Until 033 that check read
 * {@code SessionChangeBuffer.accepted()}, which held every record of the session. A segmented
 * re-baseline drains the buffer on each mid-stream seal, so the totals are accumulated here as
 * records are accepted instead of being recomputed from whatever is still buffered.</p>
 *
 * <p>Cheap by construction: three counters per table plus a running SHA-256 — the records
 * themselves are not retained.</p>
 *
 * <p>Not thread-safe — a session is driven by a single gRPC stream.</p>
 *
 * @author Data Forge Team
 * @version 1.0.0
 */
public final class SessionTotals {

    /** Per-table [inserts, updates, deletes], in first-seen order. */
    private final Map<String, long[]> counts = new LinkedHashMap<>();
    private final ChangelogContentHash.Hasher hasher = new ChangelogContentHash.Hasher();

    /**
     * Fold a chunk of accepted records into the running totals. Call once per accepted record (or
     * per accepted batch), before the records may be discarded by a seal.
     *
     * @param records accepted change records in sequence order (may be empty)
     */
    public void add(List<ChangeRecord> records) {
        for (ChangeRecord record : records) {
            long[] c = counts.computeIfAbsent(record.getTable(), k -> new long[3]);
            switch (record.getOp()) {
                case INSERT -> c[0]++;
                case UPDATE -> c[1]++;
                case DELETE -> c[2]++;
                default -> {
                }
            }
        }
        hasher.update(records);
    }

    /**
     * @return per-table insert/update/delete counts accumulated over the whole session
     */
    public Map<String, TableChangeStats> statsByTable() {
        Map<String, TableChangeStats> result = new LinkedHashMap<>();
        counts.forEach((table, c) -> result.put(table, new TableChangeStats(c[0], c[1], c[2])));
        return Collections.unmodifiableMap(result);
    }

    /**
     * @param declared client-declared per-table counts from {@code SessionEnd}
     * @return whether the declared counts exactly match the whole session's accepted records
     */
    public boolean reconcile(Map<String, TableStats> declared) {
        if (!counts.keySet().equals(declared.keySet())) {
            return false;
        }
        for (Map.Entry<String, TableStats> entry : declared.entrySet()) {
            long[] actual = counts.get(entry.getKey());
            TableStats d = entry.getValue();
            if (actual[0] != d.getInserts() || actual[1] != d.getUpdates() || actual[2] != d.getDeletes()) {
                return false;
            }
        }
        return true;
    }

    /**
     * @param declaredHex client-declared {@code SessionEnd.content_hash}; blank means the client
     *                    opted out of integrity checking and is treated as a match
     * @return whether the declared hash matches the whole session's accepted records
     */
    public boolean hashMatches(String declaredHex) {
        if (declaredHex == null || declaredHex.isBlank()) {
            return true;
        }
        return hasher.hex().equalsIgnoreCase(declaredHex.trim());
    }
}
