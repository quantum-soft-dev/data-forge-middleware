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
