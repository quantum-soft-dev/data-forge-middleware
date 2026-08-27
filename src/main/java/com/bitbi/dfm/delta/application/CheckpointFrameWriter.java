package com.bitbi.dfm.delta.application;

import com.bitbi.dfm.delta.grpc.v2.ChangeRecord;
import com.bitbi.dfm.delta.grpc.v2.Op;

import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPOutputStream;

/**
 * Writes a checkpoint reload frame record by record, counting what goes into it.
 *
 * <p>Extracted from {@link BootstrapFrameWriter} when a second producer appeared (issue #293): the
 * bootstrap path re-emits a {@code FULL_SNAPSHOT} session, the incremental path emits the join of
 * the previous frame with the period's delta, and both need the same file and the same manifest.
 * What is left in {@code BootstrapFrameWriter} is only its contract check.</p>
 *
 * <p>Every record is re-emitted rather than passed through: a frame is an all-{@code INSERT}
 * changelog whose seq is frame-local, and {@link CheckpointFrame} numbers it {@code 1..N}. Keeping
 * a source record's own seq would work equally well on re-fold, but making every producer agree on
 * the shape means a frame cannot be told apart by which path wrote it.</p>
 *
 * @author Data Forge Team
 * @version 1.0.0
 */
final class CheckpointFrameWriter implements AutoCloseable {

    /**
     * What a build learned about the frame while writing it: which tables it holds, in first-seen
     * order, and how many rows each one has.
     *
     * <p>The folded path reads both off the fold ({@code state.keySet()}, {@code rows.size()});
     * a streaming path has no fold, so they are counted as the records go past. The row count is
     * what the {@code checkpoints} row records, and the table list is what the snapshot passes
     * iterate and what the reap of issue #149 compares against.</p>
     *
     * @param tables    table names in first-seen order
     * @param rowCounts rows written per table
     * @param records   total records written
     */
    record FrameManifest(List<String> tables, Map<String, Long> rowCounts, long records) {
    }

    private final GZIPOutputStream gz;
    private final Map<String, Long> rowCounts = new LinkedHashMap<>();
    private long records;

    /** Open a frame over {@code out}; the stream is closed with this writer. */
    static CheckpointFrameWriter open(OutputStream out) {
        return new CheckpointFrameWriter(out);
    }

    private CheckpointFrameWriter(OutputStream out) {
        try {
            this.gz = new GZIPOutputStream(out);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to open the checkpoint frame", e);
        }
    }

    /** Write one row into the frame. */
    void accept(ChangeRecord record) {
        try {
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

    /** What was written so far; complete once the whole source has been streamed through. */
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
}
