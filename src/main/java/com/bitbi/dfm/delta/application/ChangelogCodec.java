package com.bitbi.dfm.delta.application;

import com.bitbi.dfm.delta.grpc.v2.ChangeRecord;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;
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
     */
    public static byte[] serialize(List<ChangeRecord> records) {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (GZIPOutputStream gz = new GZIPOutputStream(baos)) {
            for (ChangeRecord record : records) {
                record.writeDelimitedTo(gz);
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to serialize change records", e);
        }
        return baos.toByteArray();
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
}
