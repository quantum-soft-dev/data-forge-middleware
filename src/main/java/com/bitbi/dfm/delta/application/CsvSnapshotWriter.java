package com.bitbi.dfm.delta.application;

import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVPrinter;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.GZIPOutputStream;

/**
 * Renders a checkpoint table's folded state to a gzipped CSV snapshot (Delta Client v2 — 022).
 *
 * <p>The header is the union of columns across rows (first-seen order); present-null values render
 * as empty fields. This is the legacy CSV projection consumed by the Bit BI baseline path.</p>
 *
 * @author Data Forge Team
 * @version 1.0.0
 */
public final class CsvSnapshotWriter {

    private CsvSnapshotWriter() {
    }

    /**
     * @param rows row-identity → row(column → value)
     * @return gzipped CSV bytes (header + one record per row)
     */
    public static byte[] toGzippedCsv(Map<String, Map<String, Object>> rows) {
        List<String> columns = orderedColumns(rows);
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (GZIPOutputStream gz = new GZIPOutputStream(baos);
             OutputStreamWriter writer = new OutputStreamWriter(gz, StandardCharsets.UTF_8);
             CSVPrinter printer = new CSVPrinter(writer,
                     CSVFormat.DEFAULT.builder().setHeader(columns.toArray(new String[0])).build())) {
            for (Map<String, Object> row : rows.values()) {
                List<Object> record = new ArrayList<>(columns.size());
                for (String column : columns) {
                    record.add(renderCell(row.get(column)));
                }
                printer.printRecord(record);
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to write CSV snapshot", e);
        }
        return baos.toByteArray();
    }

    /**
     * Render one folded-row cell for CSV. A {@code byte[]} (bytea column) is Base64-encoded —
     * commons-csv would otherwise stringify it via {@code Object.toString()} to a JVM object
     * identity like {@code [B@1a2b3c} (garbage, non-deterministic; review r4). Other types keep
     * their natural {@code toString()}; a {@code null} stays null (empty CSV field).
     */
    private static Object renderCell(Object value) {
        if (value instanceof byte[] bytes) {
            return Base64.getEncoder().encodeToString(bytes);
        }
        return value;
    }

    private static List<String> orderedColumns(Map<String, Map<String, Object>> rows) {
        Set<String> columns = new LinkedHashSet<>();
        rows.values().forEach(row -> columns.addAll(row.keySet()));
        return new ArrayList<>(columns);
    }
}
