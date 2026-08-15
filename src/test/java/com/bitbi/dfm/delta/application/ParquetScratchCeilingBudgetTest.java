package com.bitbi.dfm.delta.application;

import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Issue #138 — the application must refuse before the pod's scratch volume fills.
 *
 * <p>Two halves, and they live in different files on purpose. The <b>defaults</b> in
 * {@code application.yml} stay where they were: a process whose scratch is a large node disk must
 * behave exactly as it did before this change, and nothing in the application can know how big the
 * directory is. The <b>deployed</b> ceilings belong beside the volume that bounds them, in
 * {@code k8s/base/configmap.yaml} next to the {@code *_TEMP_DIR} keys that put the writers on it —
 * the same split #141 used for {@code DELTA_PARQUET_SCRATCH_PRIVATE_TO_POD}.</p>
 *
 * <p>The deployed property asserted here is deliberately weak and mechanical: <b>no single file may
 * be allowed to take more than a third of the volume</b>. Three concurrent files is the smallest
 * realistic peak (two checkpoint builds — the cron sweep plus a forced rebuild on its own executor —
 * and at least one completed-batch artifact), so a ceiling above a third cannot refuse before
 * kubelet evicts. It is a floor on the guarantee, not the budget: a batch build opens one file per
 * claimed table, so the real peak scales with the table count and only a directory-wide reservation
 * can bound it. The worst-case formula lives in the "Sizing note" of
 * {@code docs/delta-client-v2-guide.md}.</p>
 */
class ParquetScratchCeilingBudgetTest {

    /**
     * What both checkpoint ceilings and the batch ceiling defaulted to before this change. The
     * frame key is new, so its default has to be this number for an upgrade to be a no-op.
     */
    private static final long HISTORIC_DEFAULT_BYTES = 10737418240L;

    private static final String SCRATCH_MOUNT_PATH = "/scratch/parquet";

    /** The per-file ceilings the deployment has to keep under the volume it declares. */
    private static final List<String> DEPLOYED_CEILING_KEYS = List.of(
            "DELTA_CHECKPOINT_MAX_TEMP_BYTES",
            "DELTA_CHECKPOINT_MAX_FRAME_TEMP_BYTES",
            "DELTA_BATCH_PARQUET_MAX_TEMP_BYTES");

    @Test
    void theFrameCeilingIsItsOwnKeyAndUpgradesToTheHistoricValue() throws IOException {
        String yaml = Files.readString(Path.of("src/main/resources/application.yml"));

        assertTrue(yaml.contains("max-temp-bytes: ${DELTA_CHECKPOINT_MAX_TEMP_BYTES:"
                        + HISTORIC_DEFAULT_BYTES + "}"),
                "delta.checkpoint.max-temp-bytes stays the per-table snapshot ceiling at its "
                        + "historic default");
        assertTrue(yaml.contains("max-frame-temp-bytes: ${DELTA_CHECKPOINT_MAX_FRAME_TEMP_BYTES:"
                        + HISTORIC_DEFAULT_BYTES + "}"),
                "delta.checkpoint.max-frame-temp-bytes must default to the value the frame was "
                        + "governed by before the split, so an unset key behaves as today");
        assertTrue(yaml.contains("max-temp-bytes: ${DELTA_BATCH_PARQUET_MAX_TEMP_BYTES:"
                        + HISTORIC_DEFAULT_BYTES + "}"),
                "the completed-batch ceiling is untouched by this split");
    }

    @Test
    void theDeployedCeilingsRefuseBeforeTheScratchVolumeFills() throws IOException {
        Map<String, Object> data = child(loadManifest("k8s/base/configmap.yaml"), "data");
        if (!SCRATCH_MOUNT_PATH.equals(data.get("DELTA_CHECKPOINT_TEMP_DIR"))
                && !SCRATCH_MOUNT_PATH.equals(data.get("DELTA_BATCH_PARQUET_TEMP_DIR"))) {
            // Nothing is on the bounded volume, so there is no budget to keep. Any other
            // arrangement has to bring its own guard rather than silently retiring this one.
            return;
        }

        long sizeLimit = scratchVolumeSizeLimitBytes();
        long perFileCeiling = sizeLimit / 3;
        for (String key : DEPLOYED_CEILING_KEYS) {
            Object declared = data.get(key);
            assertNotNull(declared,
                    key + " must be set beside the temp dirs: the application default (10 GiB) is "
                            + "above the volume, so without it the pod is evicted before the app refuses");
            long bytes = Long.parseLong(String.valueOf(declared).trim());
            assertTrue(bytes > 0, key + " must be a positive byte count, was " + declared);
            assertTrue(bytes <= perFileCeiling,
                    key + " is " + bytes + " B, above a third of the " + sizeLimit + " B scratch "
                            + "volume — two checkpoint builds and one batch artifact of that size "
                            + "fill it, and kubelet evicts the pod instead of the app skipping a table");
        }
    }

    @Test
    void noOverlayRaisesTheDeployedCeilingsOrShrinksTheVolume() throws IOException {
        // Same reasoning as ParquetScratchOrphanSweeperTest#noOverlayRedirectsTheScratchBehindThatGuard:
        // the guard above reads the base manifests, so an overlay that redefines either side of the
        // comparison must widen it rather than slip past it.
        Pattern override = Pattern.compile(
                "^\\s*(" + String.join("|", DEPLOYED_CEILING_KEYS) + "|sizeLimit)\\s*:",
                Pattern.MULTILINE);
        try (Stream<Path> overlays = Files.walk(Path.of("k8s/overlays"))) {
            for (Path file : overlays.filter(ParquetScratchCeilingBudgetTest::isYaml).toList()) {
                assertFalse(override.matcher(Files.readString(file)).find(),
                        file + " redefines a scratch ceiling or the volume sizeLimit; "
                                + "theDeployedCeilingsRefuseBeforeTheScratchVolumeFills no longer "
                                + "covers the rendered configuration and must be widened to it");
            }
        }
    }

    private static long scratchVolumeSizeLimitBytes() throws IOException {
        Map<String, Object> deployment = loadManifest("k8s/base/deployment-backend.yaml");
        Map<String, Object> podSpec = child(child(child(deployment, "spec"), "template"), "spec");
        for (Map<String, Object> volume : children(podSpec, "volumes")) {
            Object emptyDir = volume.get("emptyDir");
            if (!(emptyDir instanceof Map<?, ?> settings)) {
                continue;
            }
            Object sizeLimit = settings.get("sizeLimit");
            if (sizeLimit != null) {
                return quantityToBytes(String.valueOf(sizeLimit));
            }
        }
        throw new AssertionError("the backend pod declares no sizeLimit for its scratch volume — "
                + "the deployed ceilings have nothing to sit below");
    }

    /** Kubernetes binary/decimal suffixes, enough of them for a volume budget. */
    private static long quantityToBytes(String quantity) {
        Map<String, Long> units = new LinkedHashMap<>();
        units.put("Ki", 1024L);
        units.put("Mi", 1024L * 1024);
        units.put("Gi", 1024L * 1024 * 1024);
        units.put("Ti", 1024L * 1024 * 1024 * 1024);
        units.put("k", 1000L);
        units.put("M", 1000_000L);
        units.put("G", 1000_000_000L);
        for (Map.Entry<String, Long> unit : units.entrySet()) {
            if (quantity.endsWith(unit.getKey())) {
                String amount = quantity.substring(0, quantity.length() - unit.getKey().length());
                return (long) (Double.parseDouble(amount.trim()) * unit.getValue());
            }
        }
        return Long.parseLong(quantity.trim());
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> loadManifest(String path) throws IOException {
        return new Yaml().loadAs(Files.readString(Path.of(path)), Map.class);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> child(Map<String, Object> parent, String key) {
        Object value = parent.get(key);
        assertTrue(value instanceof Map<?, ?>, key + " must be a mapping, was " + value);
        return (Map<String, Object>) value;
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> children(Map<String, Object> parent, String key) {
        Object value = parent.getOrDefault(key, List.of());
        assertTrue(value instanceof List<?>, key + " must be a sequence, was " + value);
        return (List<Map<String, Object>>) value;
    }

    private static boolean isYaml(Path path) {
        String name = path.getFileName().toString();
        return Files.isRegularFile(path) && (name.endsWith(".yaml") || name.endsWith(".yml"));
    }
}
