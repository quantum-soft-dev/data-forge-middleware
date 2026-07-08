package com.bitbi.dfm.delta.application;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.GZIPInputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * T3.3 — CSV snapshot writer renders folded rows to valid gzipped CSV with a header.
 */
class CsvSnapshotWriterTest {

    @Test
    void writesHeaderAndRows() throws IOException {
        Map<String, Map<String, Object>> rows = new LinkedHashMap<>();
        rows.put("id=1", row("id", 1L, "name", "Ann"));
        rows.put("id=2", row("id", 2L, "name", "Bob"));

        String csv = ungzip(CsvSnapshotWriter.toGzippedCsv(rows));
        String[] lines = csv.split("\\R");

        assertEquals("id,name", lines[0]);
        assertEquals("1,Ann", lines[1]);
        assertEquals("2,Bob", lines[2]);
    }

    @Test
    void encodesByteArrayAsBase64NotObjectIdentity() throws IOException {
        // bytea columns arrive as byte[]; CSVPrinter's toString() would render '[B@1a2b3c'
        // (JVM object identity, different every run) — irreversible garbage for Bit BI (review r4).
        Map<String, Map<String, Object>> rows = new LinkedHashMap<>();
        rows.put("id=1", row("id", 1L, "blob", new byte[]{0x41, 0x42, 0x43}));

        String csv = ungzip(CsvSnapshotWriter.toGzippedCsv(rows));
        String[] lines = csv.split("\\R");

        assertEquals("id,blob", lines[0]);
        assertEquals("1,QUJD", lines[1], "byte[] must be Base64 (ABC -> QUJD), not [B@... identity");
    }

    private static Map<String, Object> row(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put((String) kv[i], kv[i + 1]);
        }
        return m;
    }

    private static String ungzip(byte[] gz) throws IOException {
        try (GZIPInputStream in = new GZIPInputStream(new ByteArrayInputStream(gz))) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
