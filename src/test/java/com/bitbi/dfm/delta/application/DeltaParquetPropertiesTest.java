package com.bitbi.dfm.delta.application;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * T01 (issue #112) — the Parquet row-group budget is an explicit, validated configuration value
 * shared by every V2 Parquet path, not the parquet-mr implicit default (~128 MB uncompressed),
 * which multiplied by the open writers is the last uncontrolled heap multiplier on a 2–3 Gi pod.
 */
class DeltaParquetPropertiesTest {

    private static final Path APPLICATION_YAML = Path.of("src/main/resources/application.yml");

    @Test
    void exposesTheConfiguredRowGroupBudget() {
        assertEquals(4_194_304L, new DeltaParquetProperties(4_194_304L).rowGroupBytes());
    }

    @Test
    void rejectsANonPositiveRowGroupBudgetAtStartup() {
        // A zero or negative budget would be handed to parquet-mr as the block size and either
        // flush a row group per record or never flush at all — fail the context, not the build.
        assertThrows(IllegalArgumentException.class, () -> new DeltaParquetProperties(0L));
        assertThrows(IllegalArgumentException.class, () -> new DeltaParquetProperties(-1L));
    }

    @Test
    void declaresTheDocumentedDefaultInApplicationYaml() throws IOException {
        // The default lives in exactly two places that must agree: the @Value fallback that keeps
        // unit-constructed beans honest, and the operator-visible key in application.yml.
        String yaml = Files.readString(APPLICATION_YAML);
        assertTrue(yaml.contains("row-group-bytes: ${DELTA_PARQUET_ROW_GROUP_BYTES:"
                        + DeltaParquetProperties.DEFAULT_ROW_GROUP_BYTES + "}"),
                "application.yml must declare delta.parquet.row-group-bytes with the documented default");
    }
}
