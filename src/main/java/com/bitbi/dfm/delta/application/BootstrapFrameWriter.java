package com.bitbi.dfm.delta.application;

import com.bitbi.dfm.delta.grpc.v2.ChangeRecord;
import com.bitbi.dfm.delta.grpc.v2.Op;

import java.io.OutputStream;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.ToLongFunction;

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

    /** The wire contract's promise was false for this input — fall back to the general fold path. */
    static final class NotAFullSnapshotException extends RuntimeException {

        private static final long serialVersionUID = 1L;

        NotAFullSnapshotException(String message) {
            super(message);
        }
    }

    private final CheckpointFrameWriter frame;
    private final ToLongFunction<String> hash;
    private final Map<String, LongHashSet> seen = new LinkedHashMap<>();

    /** Open a frame over {@code out}; the stream is closed with this writer. */
    static BootstrapFrameWriter open(OutputStream out) {
        return open(out, ChangelogFold::identityHash64);
    }

    /** Testing seam: the identity hash, so a collision can be driven deliberately. */
    static BootstrapFrameWriter open(OutputStream out, ToLongFunction<String> hash) {
        return new BootstrapFrameWriter(out, hash);
    }

    private BootstrapFrameWriter(OutputStream out, ToLongFunction<String> hash) {
        this.hash = hash;
        this.frame = CheckpointFrameWriter.open(out);
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
        frame.accept(record);
    }

    /** What was written so far; complete once every segment has been streamed through. */
    CheckpointFrameWriter.FrameManifest manifest() {
        return frame.manifest();
    }

    /**
     * Finish the gzip member and close the underlying stream.
     *
     * <p>Must run before the file is read back or uploaded: the last deflate block and the gzip
     * trailer are only written here.</p>
     */
    @Override
    public void close() {
        frame.close();
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
