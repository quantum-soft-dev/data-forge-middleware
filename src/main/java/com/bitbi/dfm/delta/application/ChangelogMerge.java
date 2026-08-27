package com.bitbi.dfm.delta.application;

import com.bitbi.dfm.delta.application.ChangelogFold.FoldedRow;
import com.bitbi.dfm.delta.grpc.v2.ChangeRecord;
import com.bitbi.dfm.delta.grpc.v2.Op;
import com.bitbi.dfm.delta.grpc.v2.Value;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

/**
 * Applies a period's changelog to a checkpoint reload frame <b>as the frame streams past</b>, so
 * heap holds the changes rather than the site (issue #293).
 *
 * <h2>Which side is in memory</h2>
 *
 * <p>{@link ChangelogFold} folds a whole site: the seed frame and then every new segment, into one
 * map of every surviving row. That map is the site, so {@code delta.checkpoint.max-fold-bytes} is a
 * ceiling on the <em>site</em> and not on a night's work — permanently, since a site that outgrows
 * it never shrinks. This class inverts the two sides. The delta — the records above the checkpoint
 * — is folded into memory, and the frame is streamed record by record and joined against it:
 *
 * <ul>
 *   <li>the delta does not mention the row → emit it unchanged;</li>
 *   <li>the delta deleted it → emit nothing;</li>
 *   <li>the delta replaced it (an {@code INSERT}) → emit the delta's row, whole;</li>
 *   <li>the delta only patched it ({@code UPDATE}s and no {@code INSERT}) → emit the frame's row
 *       with the patched columns overwritten.</li>
 * </ul>
 *
 * <p>Rows the frame never had — inserted this period, or updated with no row behind them — are
 * emitted by {@link #drain} once the frame is exhausted. Heap is therefore the delta plus the two
 * marker sets below, and never the site.</p>
 *
 * <h2>The two marker sets, and why one bit cannot be folded into the row</h2>
 *
 * <p>A folded row cannot say whether it is a whole row or a patch — that is the one thing the fold
 * has never had to record, because with the frame folded in first there is no other row to merge
 * against. Rather than widen {@link FoldedRow}, whose per-row footprint issue #290 had just spent a
 * ticket shrinking, the classification lives beside it in two sets keyed by the same identity
 * string instance: {@code patched} (rows to merge onto the frame's) and {@code tombstoned} (rows
 * the frame must drop). Both are bounded by the delta, and both are charged to the same byte
 * budget the fold is — a tombstone pays for the identity string it now holds alone, a patch marker
 * only for its set entry, because its row is still charged for the string.</p>
 *
 * <h2>What the result is, and is not, identical to</h2>
 *
 * <p>The records are the fold's records: the same rows, the same values, and within one table the
 * same relative order, since a surviving frame row keeps its place and new rows follow in the order
 * they first appeared. What differs is the interleaving <em>between</em> tables — new rows are
 * appended after the whole frame rather than inside their own table's block — which is the
 * statement {@link BootstrapFrameWriter} already makes about the bootstrap frame, and for the same
 * reason: nothing reads a frame's record order. A snapshot is written against the declared schema
 * table by table, and a re-fold looks every column up by name.</p>
 *
 * <p>One divergence is deliberate and is a fact about column <em>order</em> inside a record, not
 * about values: the fold lays a table's columns out in first-seen order across every row of that
 * table, while a record passed through from the frame keeps its own. For the ordinary table, whose
 * rows all carry the same columns, the two are the same order. Nothing downstream reads it either.</p>
 *
 * <h2>Partitioning, for a delta that does not fit</h2>
 *
 * <p>A merge instance can be told to hold only one share of the rows —
 * {@code identityHash64(identity) mod partitions == partition} — applied to both sides, so a row
 * and its changes always land in the same pass. {@code K} passes over the frame and the segments
 * then need about {@code 1/K} of the heap. That is the fallback, not the design: the pass count
 * grows with the delta, and each pass re-reads both sides. See
 * {@code CheckpointService.buildByMerge}.</p>
 *
 * @author Data Forge Team
 * @version 1.0.0
 */
final class ChangelogMerge {

    /** The delta's surviving rows: table → identity → row, laid out by {@link ChangelogFold}. */
    private final Map<String, Map<String, FoldedRow>> delta = new LinkedHashMap<>();

    /** Identities whose row holds only the columns their {@code UPDATE}s named. */
    private final Map<String, Set<String>> patched = new LinkedHashMap<>();

    /** Identities the delta deleted — the frame's row, if it has one, is dropped. */
    private final Map<String, Set<String>> tombstoned = new LinkedHashMap<>();

    /**
     * Identities the delta deleted and then created again.
     *
     * <p>Only about <em>order</em>, and it is here so the merge's records come out in the fold's
     * order rather than merely holding the fold's rows. A fold removes the row on {@code DELETE} and
     * appends it again when it returns, so it leaves its old place and lands at the end of its
     * table. Emitting it where the frame had it would put it back where the fold does not.</p>
     */
    private final Map<String, Set<String>> recreated = new LinkedHashMap<>();

    private final int partitions;
    private final int partition;

    /** A merge over every row. */
    ChangelogMerge() {
        this(1, 0);
    }

    /**
     * A merge over one share of the rows.
     *
     * @param partitions how many shares the rows are split into ({@code 1} — no split)
     * @param partition  which share this instance holds, {@code 0 <= partition < partitions}
     */
    ChangelogMerge(int partitions, int partition) {
        if (partitions < 1) {
            throw new IllegalArgumentException("partitions must be at least 1, but was " + partitions);
        }
        if (partition < 0 || partition >= partitions) {
            throw new IllegalArgumentException(
                    "partition must be in [0, " + partitions + "), but was " + partition);
        }
        this.partitions = partitions;
        this.partition = partition;
    }

    /** Whether this instance is the one that owns {@code identity}. */
    private boolean holds(String identity) {
        return partitions == 1
                || Math.floorMod(ChangelogFold.identityHash64(identity), partitions) == partition;
    }

    /**
     * Fold one changelog record of the period into the delta.
     *
     * <p>Records outside this instance's partition are ignored and cost nothing — the pass that
     * owns them will see them again.</p>
     *
     * @param record the next changelog record, in sequence order
     * @return the change in the delta's estimated retained heap, in bytes, in the same units
     *         {@link ChangelogFold#apply(Map, ChangeRecord)} reports and therefore in the units
     *         {@code delta.checkpoint.max-fold-bytes} is expressed in
     */
    long apply(ChangeRecord record) {
        String identity = ChangelogFold.identityOf(record.getKeyMap());
        if (!holds(identity)) {
            return 0L;
        }
        String table = record.getTable();
        return switch (record.getOp()) {
            case INSERT -> {
                // An INSERT is the whole row: whatever the frame holds is replaced rather than
                // merged, so any patch marker and any tombstone for this identity are void.
                long bytes = dropMarker(patched, table, identity, false)
                        + clearTombstone(table, identity);
                yield bytes + ChangelogFold.apply(delta, record, identity);
            }
            case UPDATE -> {
                Map<String, FoldedRow> rows = delta.get(table);
                if (rows != null && rows.containsKey(identity)) {
                    // Accumulating onto what the delta already holds, whole row or patch alike:
                    // the fold overwrites the columns this record names and touches nothing else,
                    // so the marker keeps its meaning.
                    yield ChangelogFold.apply(delta, record, identity);
                }
                if (tombstoned.getOrDefault(table, Set.of()).contains(identity)) {
                    // Deleted and then updated: the frame's row went with the DELETE, so this
                    // record is the whole of the row, seeded from its key exactly as a fold with
                    // no prior row seeds it.
                    yield clearTombstone(table, identity)
                            + ChangelogFold.apply(delta, record, identity);
                }
                long bytes = ChangelogFold.applyPatch(delta, record, identity);
                patched.computeIfAbsent(table, name -> new HashSet<>()).add(identity);
                yield bytes + ChangelogFold.identityReferenceBytes();
            }
            case DELETE -> {
                long bytes = ChangelogFold.apply(delta, record, identity)
                        + dropMarker(patched, table, identity, false)
                        + dropMarker(recreated, table, identity, false);
                // Recorded even when the delta never held the row: the point of a tombstone is the
                // row the *frame* holds, which this instance has not seen yet.
                if (tombstoned.computeIfAbsent(table, name -> new LinkedHashSet<>()).add(identity)) {
                    bytes += ChangelogFold.identityRetainedBytes(identity);
                }
                yield bytes;
            }
            default -> 0L;
        };
    }

    /**
     * Join one record of the streamed frame against the delta.
     *
     * @param base the frame's record (an {@code INSERT}, as every frame record is)
     * @param out  receives the row that survives, if any
     */
    void accept(ChangeRecord base, Consumer<ChangeRecord> out) {
        String identity = ChangelogFold.identityOf(base.getKeyMap());
        if (!holds(identity)) {
            return;
        }
        String table = base.getTable();
        Set<String> graves = tombstoned.get(table);
        if (graves != null && graves.contains(identity)) {
            return;
        }
        Set<String> returned = recreated.get(table);
        if (returned != null && returned.contains(identity)) {
            // Its place is at the end of its table, where the fold put it back. Left in the delta
            // for drain to emit.
            return;
        }
        Map<String, FoldedRow> rows = delta.get(table);
        // Removed, not read: the row has met its frame row and will not be emitted again, so the
        // delta gives its heap back as the frame streams rather than at the end of the build.
        FoldedRow row = rows == null ? null : rows.remove(identity);
        if (row == null) {
            out.accept(base);
            return;
        }
        Set<String> patches = patched.get(table);
        if (patches != null && patches.remove(identity)) {
            Map<String, Value> merged = new LinkedHashMap<>(base.getDataMap());
            merged.putAll(row.data());
            // The frame's key, not the update's: the fold merges into a row that already carries
            // its key columns and never rewrites them, so neither does this.
            out.accept(insert(table, base.getKeyMap(), merged));
            return;
        }
        out.accept(insert(table, row.key(), row.data()));
    }

    /**
     * Emit every row the frame did not have — inserted this period, or updated with nothing behind
     * them. Call once the frame is exhausted.
     */
    void drain(Consumer<ChangeRecord> out) {
        delta.forEach((table, rows) -> {
            Set<String> patches = patched.get(table);
            rows.forEach((identity, row) -> {
                if (patches != null && patches.contains(identity)) {
                    // No frame row to merge onto, so the row is its key plus the columns the
                    // updates named — the same seeding ChangelogFold does for an UPDATE with no
                    // prior row.
                    Map<String, Value> seeded = new LinkedHashMap<>(row.key());
                    seeded.putAll(row.data());
                    out.accept(insert(table, row.key(), seeded));
                    return;
                }
                out.accept(insert(table, row.key(), row.data()));
            });
        });
    }

    /**
     * Take {@code identity} off the tombstone list and remember that it came back, so
     * {@link #accept} leaves its frame row alone and {@link #drain} emits the new one at the end of
     * its table — the place a fold gives a row it removed and re-appended.
     *
     * @return the change in estimated retained heap: the tombstone gives up the identity string,
     *         and the marker that replaces it only references the one the row now holds
     */
    private long clearTombstone(String table, String identity) {
        Set<String> graves = tombstoned.get(table);
        if (graves == null || !graves.remove(identity)) {
            return 0L;
        }
        recreated.computeIfAbsent(table, name -> new HashSet<>()).add(identity);
        return ChangelogFold.identityReferenceBytes() - ChangelogFold.identityRetainedBytes(identity);
    }

    /**
     * Forget a marker for {@code identity}.
     *
     * @param holdsTheString whether the set was the only holder of the identity string
     * @return the heap that gives back, as a negative number
     */
    private static long dropMarker(Map<String, Set<String>> markers, String table, String identity,
                                   boolean holdsTheString) {
        Set<String> set = markers.get(table);
        if (set == null || !set.remove(identity)) {
            return 0L;
        }
        return holdsTheString
                ? -ChangelogFold.identityRetainedBytes(identity)
                : -ChangelogFold.identityReferenceBytes();
    }

    /**
     * One frame record. The sequence number is left unset: a frame's seq is frame-local and
     * {@link CheckpointFrameWriter} numbers the records it writes.
     */
    private static ChangeRecord insert(String table, Map<String, Value> key, Map<String, Value> data) {
        return ChangeRecord.newBuilder()
                .setTable(table)
                .setOp(Op.INSERT)
                .putAllKey(key)
                .putAllData(data)
                .build();
    }
}
