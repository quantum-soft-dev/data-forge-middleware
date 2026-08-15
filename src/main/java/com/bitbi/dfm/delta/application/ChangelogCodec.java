package com.bitbi.dfm.delta.application;

import com.bitbi.dfm.delta.grpc.v2.ChangeRecord;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
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
     * <p>Segments still use this (they already hold the records). Checkpoint frames stream
     * through {@link #write(Iterable, OutputStream)} onto a caller-owned file.</p>
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
     * read either producer. Disk policy (path, ceiling) is the caller's — wrap {@code out}
     * in {@link CappedOutputStream} when the write must not fill the node.</p>
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
