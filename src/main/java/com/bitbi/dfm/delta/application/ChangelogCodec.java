package com.bitbi.dfm.delta.application;

import com.bitbi.dfm.delta.grpc.v2.ChangeRecord;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * Wire codec for a list of {@link ChangeRecord}s (Delta Client v2 — 022): gzipped, length-delimited
 * protobuf. Shared by changelog segments and checkpoint frames so both use one stable on-disk form.
 *
 * @author Data Forge Team
 * @version 1.0.0
 */
public final class ChangelogCodec {

    private ChangelogCodec() {
    }

    /**
     * Serialize records to gzipped length-delimited protobuf bytes.
     *
     * <p>Segments still use this (they already hold the records). Checkpoint frames must not:
     * they stream through {@link #write(Iterable, Path, long)} so the site is never copied
     * into a {@code List} and a gzip {@code byte[]} at once (issue #126).</p>
     */
    public static byte[] serialize(List<ChangeRecord> records) {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        write(records, baos);
        return baos.toByteArray();
    }

    /**
     * Write gzipped length-delimited protobuf records to {@code out}. Closes {@code out}.
     *
     * <p>The on-disk form is the same as {@link #serialize}: {@link #parse} and {@link #forEach}
     * read either producer.</p>
     */
    public static void write(Iterable<ChangeRecord> records, OutputStream out) {
        try (GZIPOutputStream gz = new GZIPOutputStream(out)) {
            for (ChangeRecord record : records) {
                record.writeDelimitedTo(gz);
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to serialize change records", e);
        }
    }

    /**
     * Stream records into a local file, refusing to write more than {@code maxBytes}.
     *
     * <p>The ceiling is the compressed size on disk — the same policy {@code FileOutputFile}
     * applies to a Parquet snapshot, and the same {@code delta.checkpoint.max-temp-bytes}
     * the checkpoint build already exposes.</p>
     */
    public static void write(Iterable<ChangeRecord> records, Path file, long maxBytes) {
        try (OutputStream out = Files.newOutputStream(file);
             OutputStream limited = new SizeLimitedOutputStream(out, maxBytes)) {
            write(records, limited);
        } catch (ArtifactSizeLimitExceededException e) {
            throw e;
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to serialize change records", e);
        }
    }

    /**
     * Counts compressed bytes as they hit disk and stops the write at {@code maxBytes}.
     *
     * <p>Once the ceiling is crossed, further writes (gzip trailer on close) are dropped so
     * the close path cannot raise a second, suppressed exception on top of the one the
     * caller is meant to see.</p>
     */
    private static final class SizeLimitedOutputStream extends FilterOutputStream {

        private final long maxBytes;
        private long written;
        private boolean exceeded;

        private SizeLimitedOutputStream(OutputStream out, long maxBytes) {
            super(out);
            this.maxBytes = maxBytes;
        }

        @Override
        public void write(int b) throws IOException {
            if (exceeded) {
                return;
            }
            checkCapacity(1);
            out.write(b);
            written++;
        }

        @Override
        public void write(byte[] bytes, int offset, int length) throws IOException {
            if (exceeded) {
                return;
            }
            checkCapacity(length);
            out.write(bytes, offset, length);
            written += length;
        }

        private void checkCapacity(int length) {
            if (length > maxBytes - written) {
                exceeded = true;
                throw new ArtifactSizeLimitExceededException(maxBytes);
            }
        }
    }

    /**
     * Parse gzipped length-delimited protobuf bytes back into records (in order).
     */
    public static List<ChangeRecord> parse(byte[] content) {
        List<ChangeRecord> records = new ArrayList<>();
        try (GZIPInputStream gz = new GZIPInputStream(new ByteArrayInputStream(content))) {
            ChangeRecord record;
            while ((record = ChangeRecord.parseDelimitedFrom(gz)) != null) {
                records.add(record);
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to parse change records", e);
        }
        return records;
    }

    /** Stream gzipped, length-delimited records without accumulating them in a list. */
    public static void forEach(InputStream content, Consumer<ChangeRecord> consumer) {
        try (GZIPInputStream gz = new GZIPInputStream(content)) {
            ChangeRecord record;
            while ((record = ChangeRecord.parseDelimitedFrom(gz)) != null) {
                consumer.accept(record);
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to stream change records", e);
        }
    }
}
