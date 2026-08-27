package com.bitbi.dfm.delta.application;

import com.bitbi.dfm.delta.grpc.v2.ChangeRecord;
import com.bitbi.dfm.delta.grpc.v2.Op;

import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.ToLongFunction;
import java.util.zip.GZIPOutputStream;

/**
 * Writes a checkpoint reload frame straight from a {@code FULL_SNAPSHOT} record stream, without
 * folding the site into heap first (issue #292).
 *
 * <h2>Why there is nothing to fold</h2>
 *
 * <p>{@code delta-ingestion.proto} declares {@code FULL_SNAPSHOT = 1; // bootstrap or re-baseline;
 * all records are INSERTs}. That is a guarantee of the wire contract, not an observation: a snapshot
 * of the source contains no repeated key, so folding it is the identity map — every record already
 * <em>is</em> the row that survives. A checkpoint frame is by definition an all-{@code INSERT}
 * changelog ({@link CheckpointFrame}), so for a build whose whole history is one such session the
 * frame is the input, re-emitted. The general path builds a hash map of every row in the site purely
 * to detect collapses the contract says cannot happen — and that map is the checkpoint build's one
 * remaining full-site copy (issue #152), so on a large enough site the bootstrap build is refused
 * for a fold it never needed.</p>
 *
 * <h2>The frame this writes, against the frame the fold would write</h2>
 *
 * <p>The same records, and deliberately not the same order. The fold groups rows by table, so
 * {@link CheckpointFrame} emits table by table; this writer emits them as they arrive, interleaved.
 * Neither the order nor the frame-local {@code seq} carries meaning — a frame is re-folded from
 * empty, and the fold's own comment says the seq is "ignored on re-fold" — and the <em>relative</em>
 * order within one table is identical, since the fold keeps its rows in insertion order. So the next
 * build seeds from either frame identically, and each table's snapshot rows come out in the same
 * order.</p>
 *
 * <h2>What happens if the contract is broken</h2>
 *
 * <p>A repeated key, or a record that is not an {@code INSERT}, means the premise is false for this
 * site — and it is the one input on which this path and the fold would <b>disagree</b>: the fold
 * collapses the two records into one row, this writer would emit two rows sharing a key. So it is
 * refused ({@link NotAFullSnapshotException}) and the caller falls back to the general path, which
 * is correct whatever the data does. Nothing durable has been written at that point: the frame is a
 * local scratch file that has not been uploaded.</p>
 *
 * <p>The repeat guard is a set of <b>64-bit hashes</b> of the row identity, per table — about 80 MB
 * for five million rows, against the gigabytes the identity strings themselves would cost, which is
 * the ceiling this whole path exists to avoid. A hash collision therefore reads as a repeat and
 * sends the build down the general path: harmless, and at ~10^-6 for five million keys, rare. The
 * identity is {@link ChangelogFold#identityOf} — the same function the fold keys rows by, so the two
 * paths agree on what "the same row" means.</p>
 *
 * @author Data Forge Team
 * @version 1.0.0
 */
final class BootstrapFrameWriter implements AutoCloseable {

    /**
     * What this build learned about the frame while writing it: which tables it holds, in first-seen
     * order, and how many rows each one has.
     *
     * <p>The general path reads both off the fold ({@code state.keySet()},
     * {@code rows.size()}); there is no fold here, so they are counted as the records go past. The
     * row count is what the {@code checkpoints} row records, and the table list is what the snapshot
     * passes iterate and what the reap of issue #149 compares against.</p>
     *
     * @param tables    table names in first-seen order
     * @param rowCounts rows written per table
     * @param records   total records written
     */
    record FrameManifest(List<String> tables, Map<String, Long> rowCounts, long records) {
    }

    /** The wire contract's promise was false for this input — fall back to the general fold path. */
    static final class NotAFullSnapshotException extends RuntimeException {

        private static final long serialVersionUID = 1L;

        NotAFullSnapshotException(String message) {
            super(message);
        }
    }

    private final GZIPOutputStream gz;
    private final ToLongFunction<String> hash;
    private final Map<String, LongHashSet> seen = new LinkedHashMap<>();
    private final Map<String, Long> rowCounts = new LinkedHashMap<>();
    private long records;

    /** Open a frame over {@code out}; the stream is closed with this writer. */
    static BootstrapFrameWriter open(OutputStream out) {
        return open(out, BootstrapFrameWriter::hash64);
    }

    /** Testing seam: the identity hash, so a collision can be driven deliberately. */
    static BootstrapFrameWriter open(OutputStream out, ToLongFunction<String> hash) {
        return new BootstrapFrameWriter(out, hash);
    }

    private BootstrapFrameWriter(OutputStream out, ToLongFunction<String> hash) {
        this.hash = hash;
        try {
            this.gz = new GZIPOutputStream(out);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to open the checkpoint frame", e);
        }
    }

    /**
     * Write one record of the snapshot into the frame.
     *
     * @throws NotAFullSnapshotException when the record is not an {@code INSERT}, or its key has
     *                                   already been seen in its table
     */
    void accept(ChangeRecord record) {
        if (record.getOp() != Op.INSERT) {
            throw new NotAFullSnapshotException(
                    "Record " + record.getSeq() + " of table " + record.getTable() + " is "
                            + record.getOp() + ", but a FULL_SNAPSHOT session declares every record "
                            + "an INSERT");
        }
        LongHashSet keys = seen.computeIfAbsent(record.getTable(), table -> new LongHashSet());
        if (!keys.add(hash.applyAsLong(ChangelogFold.identityOf(record.getKeyMap())))) {
            throw new NotAFullSnapshotException(
                    "Record " + record.getSeq() + " of table " + record.getTable() + " repeats a key "
                            + "already seen in this FULL_SNAPSHOT session (or its identity hash "
                            + "collided with one)");
        }
        try {
            // Re-emitted rather than passed through: the frame is an all-INSERT changelog whose
            // seq is frame-local, and CheckpointFrame numbers it 1..N. Keeping the session's own
            // seq would work equally well on re-fold, but making the two producers agree on the
            // shape means a frame cannot be told apart by which path wrote it.
            ChangeRecord.newBuilder()
                    .setTable(record.getTable())
                    .setOp(Op.INSERT)
                    .setSeq(++records)
                    .putAllKey(record.getKeyMap())
                    .putAllData(record.getDataMap())
                    .build()
                    .writeDelimitedTo(gz);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to write the checkpoint frame", e);
        }
        rowCounts.merge(record.getTable(), 1L, Long::sum);
    }

    /** What was written so far; complete once every segment has been streamed through. */
    FrameManifest manifest() {
        return new FrameManifest(List.copyOf(rowCounts.keySet()), Map.copyOf(rowCounts), records);
    }

    /**
     * Finish the gzip member and close the underlying stream.
     *
     * <p>Must run before the file is read back or uploaded: the last deflate block and the gzip
     * trailer are only written here.</p>
     */
    @Override
    public void close() {
        try {
            gz.close();
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to close the checkpoint frame", e);
        }
    }

    /**
     * 64-bit FNV-1a over the identity's UTF-8 bytes.
     *
     * <p>Not a cryptographic hash and it does not need to be: nothing adversarial reaches here, and
     * a collision is a false positive that costs a fallback to the correct path. What it does need
     * is to spread well over the shapes real keys take — short ASCII strings differing in a few
     * characters — which is what FNV-1a is for.</p>
     */
    private static long hash64(String identity) {
        long h = 0xcbf29ce484222325L;
        for (byte b : identity.getBytes(StandardCharsets.UTF_8)) {
            h ^= (b & 0xffL);
            h *= 0x100000001b3L;
        }
        return h;
    }

    /**
     * An open-addressed set of 64-bit hashes — eight bytes a row plus the load factor's slack, where
     * a {@code HashSet<Long>} would be forty and a {@code HashSet<String>} of identities far more.
     *
     * <p>Zero is the empty slot, so a hash of exactly zero is remapped to a fixed non-zero constant;
     * the only cost is that the two values share a slot, which is one extra collision in 2^64.</p>
     */
    static final class LongHashSet {

        private static final long ZERO_SUBSTITUTE = 0x9e3779b97f4a7c15L;

        private long[] slots = new long[1 << 10];
        private int size;

        /** @return {@code true} if the value was not already present */
        boolean add(long value) {
            long stored = value == 0L ? ZERO_SUBSTITUTE : value;
            int mask = slots.length - 1;
            int at = (int) (mix(stored) & mask);
            while (slots[at] != 0L) {
                if (slots[at] == stored) {
                    return false;
                }
                at = (at + 1) & mask;
            }
            slots[at] = stored;
            size++;
            // Grown at half full: linear probing degrades sharply past that, and the whole point of
            // this set is that its cost stays a small multiple of the row count.
            if (size * 2 > slots.length) {
                grow();
            }
            return true;
        }

        private void grow() {
            long[] older = slots;
            slots = new long[older.length << 1];
            int mask = slots.length - 1;
            for (long stored : older) {
                if (stored == 0L) {
                    continue;
                }
                int at = (int) (mix(stored) & mask);
                while (slots[at] != 0L) {
                    at = (at + 1) & mask;
                }
                slots[at] = stored;
            }
        }

        /**
         * Spread the hash across its low bits before it is truncated to a slot index: the identity
         * hash is good, but taking its low bits alone would cluster whenever the keys do.
         */
        private static long mix(long value) {
            long h = value;
            h ^= h >>> 33;
            h *= 0xff51afd7ed558ccdL;
            h ^= h >>> 33;
            return h & Long.MAX_VALUE;
        }
    }
}
