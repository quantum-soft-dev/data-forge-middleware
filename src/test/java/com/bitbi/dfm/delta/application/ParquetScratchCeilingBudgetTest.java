package com.bitbi.dfm.delta.application;

import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
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
 * <p>What is asserted about the deployed values is the "Sizing note" of
 * {@code docs/delta-client-v2-guide.md}, recomputed from the manifests. Since issue #150 that is one
 * subtraction rather than a multiplier estimate:</p>
 *
 * <pre>
 * delta.parquet.max-scratch-bytes + restart residue &lt;= sizeLimit
 * </pre>
 *
 * <p><b>What the multiplier could not do</b> is why. It read
 * {@code 2 x max(table, frame) + max-concurrent x batch}, whose last term assumed <em>one claimed
 * table per batch build</em> — a real build opens one scratch file per claimed table (#038), so a
 * two-table batch already doubled it and a 6Gi volume could be overrun however low the per-file
 * ceilings were set. A count is not something a per-file ceiling can bound. The directory budget
 * bounds the sum directly, so the per-file ceilings drop out of the inequality and keep only their
 * per-artifact job; what remains outside it is scratch a dead process left behind (#127, #141),
 * which no lease covers, and that is what the reserve below is for. Since issue #193 the last
 * {@code DELTA_CHECKPOINT_MAX_FRAME_TEMP_BYTES} of the directory budget is reserved for the
 * checkpoint path — a different reserve — so the remainder must still fit at least one
 * completed-batch artifact.</p>
 */
class ParquetScratchCeilingBudgetTest {

    /**
     * What both checkpoint ceilings and the batch ceiling defaulted to before this change. The
     * frame key is new, so its default has to be this number for an upgrade to be a no-op.
     */
    private static final long HISTORIC_DEFAULT_BYTES = 10737418240L;

    private static final String SCRATCH_MOUNT_PATH = "/scratch/parquet";

    /**
     * Kept free above the budget. Two things live outside it and would otherwise turn "fits
     * exactly" into an eviction: scratch a dead container left behind, which survives on the
     * pod-private volume until the next sweep tick (#141) and whose owner is gone, so no lease
     * covers it; and the fact that kubelet acts on usage <em>exceeding</em> the limit rather than
     * reaching it. It is still an allowance rather than a proof — a dead build leaves one file per
     * claimed table, and nothing bounds that count once the process that held the leases is gone.
     */
    private static final long RESIDUE_RESERVE_BYTES = 1024L * 1024 * 1024;

    private static final String SNAPSHOT_CEILING_KEY = "DELTA_CHECKPOINT_MAX_TEMP_BYTES";
    private static final String FRAME_CEILING_KEY = "DELTA_CHECKPOINT_MAX_FRAME_TEMP_BYTES";
    private static final String BATCH_CEILING_KEY = "DELTA_BATCH_PARQUET_MAX_TEMP_BYTES";
    private static final String DIRECTORY_BUDGET_KEY = "DELTA_PARQUET_MAX_SCRATCH_BYTES";

    @Test
    void theFrameCeilingIsItsOwnKeyAndUpgradesToTheHistoricValue() throws IOException {
        String yaml = applicationYaml();

        assertTrue(yaml.contains("max-temp-bytes: ${" + SNAPSHOT_CEILING_KEY + ":"
                        + HISTORIC_DEFAULT_BYTES + "}"),
                "delta.checkpoint.max-temp-bytes stays the per-table snapshot ceiling at its "
                        + "historic default");
        assertTrue(yaml.contains("max-frame-temp-bytes: ${" + FRAME_CEILING_KEY
                        + ":${delta.checkpoint.max-temp-bytes}}"),
                "an unset frame ceiling must inherit the resolved per-table ceiling — the property, "
                        + "not the environment variable, so it follows a value set through a profile "
                        + "yml or SPRING_APPLICATION_JSON too. Falling back to a literal would "
                        + "silently unbound the frame for an operator who had lowered the single key "
                        + "for a small disk");
        assertTrue(yaml.contains("max-temp-bytes: ${" + BATCH_CEILING_KEY + ":"
                        + HISTORIC_DEFAULT_BYTES + "}"),
                "the completed-batch default is untouched by this split");
    }

    @Test
    void theDeployedCeilingsRefuseBeforeTheScratchVolumeFills() throws IOException {
        Map<String, Object> data = child(loadManifest("k8s/base/configmap.yaml"), "data");
        if (!onScratchVolume(data.get("DELTA_CHECKPOINT_TEMP_DIR"))
                && !onScratchVolume(data.get("DELTA_BATCH_PARQUET_TEMP_DIR"))) {
            // Neither writer is on the bounded volume, so there is no budget to keep — but the
            // guard must not fail *open* on a typo. If the pod still mounts the volume, this is a
            // drift between the two manifests, not a deliberate move: fail and make someone say
            // which it is. (`onScratchVolume` accepts a subdirectory of the mount, which is on the
            // volume too — only a genuinely different path retires the budget.)
            assertFalse(mountsScratchVolume(),
                    "the pod still mounts " + SCRATCH_MOUNT_PATH + " but neither *_TEMP_DIR points "
                            + "into it: either the writers moved off the bounded volume — and this "
                            + "guard must be widened to wherever they went — or one of the two keys "
                            + "is a typo and the scratch is silently on the writable layer");
            return;
        }

        long snapshotCeiling = deployedBytes(data, SNAPSHOT_CEILING_KEY);
        long frameCeiling = deployedBytes(data, FRAME_CEILING_KEY);
        long batchCeiling = deployedBytes(data, BATCH_CEILING_KEY);
        long directoryBudget = deployedBytes(data, DIRECTORY_BUDGET_KEY);

        assertTrue(frameCeiling >= snapshotCeiling,
                "the frame ceiling must be the wider of the two: crossing it aborts the build and "
                        + "freezes the pointer, while a table skip is visible and rematerialized by "
                        + "a later build — " + FRAME_CEILING_KEY + "=" + frameCeiling + " B is below "
                        + SNAPSHOT_CEILING_KEY + "=" + snapshotCeiling + " B");

        // A per-file ceiling above the directory budget can never be reached: the directory refuses
        // first, and it refuses with different semantics — transient rather than a verdict on the
        // artifact — so the per-file key would be dead configuration that reads as if it were live.
        for (String ceilingKey : List.of(SNAPSHOT_CEILING_KEY, FRAME_CEILING_KEY, BATCH_CEILING_KEY)) {
            long ceiling = deployedBytes(data, ceilingKey);
            assertTrue(ceiling <= directoryBudget,
                    ceilingKey + "=" + ceiling + " B is above " + DIRECTORY_BUDGET_KEY + "="
                            + directoryBudget + " B, so the directory refuses before that ceiling "
                            + "can ever speak and its documented failure mode never happens");
        }

        // Issue #193, widened by #292: batch writers stop at the directory minus what the
        // checkpoint path holds AT ONE TIME, so a completed-batch backlog cannot fill the bytes the
        // nightly build needs. That used to be the frame alone — it was written, uploaded and
        // deleted before the first table's file existed. The streamed bootstrap build's snapshot
        // passes READ the frame, so it stays on the volume beside a snapshot file, and the reserve
        // is the two together.
        long checkpointReserve = frameCeiling + snapshotCeiling;
        assertTrue(checkpointReserve <= directoryBudget,
                "the checkpoint path holds its frame (" + FRAME_CEILING_KEY + "=" + frameCeiling
                        + " B) and a snapshot (" + SNAPSHOT_CEILING_KEY + "=" + snapshotCeiling
                        + " B) at once since issue #292, which is " + checkpointReserve + " B "
                        + "against " + DIRECTORY_BUDGET_KEY + "=" + directoryBudget + " B — so the "
                        + "directory can refuse a first checkpoint that is inside both per-file "
                        + "ceilings, every night, with the pointer frozen");

        // Only ONE snapshot, not delta.checkpoint.snapshot-writers of them, and for the same reason
        // this test does not multiply the batch ceiling by delta.batch-parquet.max-concurrent: the
        // per-file keys are safety ceilings, not size estimates, and the directory is charged by
        // bytes actually written (#150). What the arithmetic guarantees is PROGRESS — the streamed
        // build can always write its frame and one snapshot — while the writers after the first take
        // whatever is free and a refusal ends that build with the next tick retrying.
        long batchShare = directoryBudget - checkpointReserve;
        assertTrue(batchShare >= batchCeiling,
                "the reserved checkpoint share leaves " + batchShare + " B for batch writers, below "
                        + BATCH_CEILING_KEY + "=" + batchCeiling + " B, so a single completed-batch "
                        + "artifact can never reach its own ceiling");

        long sizeLimit = scratchVolumeSizeLimitBytes();
        long budget = sizeLimit - RESIDUE_RESERVE_BYTES;
        assertTrue(directoryBudget <= budget,
                "the whole scratch directory may hold " + directoryBudget + " B against a budget of "
                        + budget + " B (" + sizeLimit + " B volume less " + RESIDUE_RESERVE_BYTES
                        + " B kept free for restart residue), so kubelet can still evict the pod "
                        + "before the application refuses. Move " + DIRECTORY_BUDGET_KEY + " and the "
                        + "volume together — see the sizing note in docs/delta-client-v2-guide.md");
    }

    @Test
    void theDirectoryBudgetIsUnboundedUntilADeploymentDeclaresOne() throws IOException {
        // The application cannot see how large the directory it was handed is, so the shipped
        // default must change nothing on an upgrade — the same split #138 used for the per-file
        // ceilings and #141 for the pod-private flag. The gauge is what makes that safe rather than
        // merely quiet: it measures either way, so the key can be sized before it is turned on.
        assertTrue(applicationYaml().contains(
                        "max-scratch-bytes: ${" + DIRECTORY_BUDGET_KEY + ":0}"),
                "delta.parquet.max-scratch-bytes must default to 0 (unbounded)");
    }

    @Test
    void noOverlayRedefinesEitherSideOfThatBudget() throws IOException {
        // Same reasoning as ParquetScratchOrphanSweeperTest#noOverlayRedirectsTheScratchBehindThatGuard:
        // the guard above reads the base manifests, so an overlay that redefines either side must
        // widen it rather than slip past it. Both ConfigMap forms count — a `KEY: value` entry and
        // a `- KEY=value` configMapGenerator literal.
        Pattern ceilingOverride = Pattern.compile(
                "(^|\\s|-\\s)(" + SNAPSHOT_CEILING_KEY + "|" + FRAME_CEILING_KEY + "|"
                        + BATCH_CEILING_KEY + "|" + DIRECTORY_BUDGET_KEY + ")\\s*[:=]",
                Pattern.MULTILINE);
        // A sizeLimit belonging to some other volume (dev's Redis, say) is none of this test's
        // business; one in a file that also names the scratch volume or its mount is.
        Pattern sizeLimitOverride = Pattern.compile("^\\s*sizeLimit\\s*:", Pattern.MULTILINE);
        try (Stream<Path> overlays = Files.walk(Path.of("k8s/overlays"))) {
            for (Path file : overlays.filter(ParquetScratchCeilingBudgetTest::isYaml).toList()) {
                // Comments stripped first: an overlay that merely *documents* the budget is not
                // redefining it, and failing it with "redefines a scratch ceiling" would be a lie.
                String body = withoutComments(Files.readString(file));
                boolean touchesScratch = body.contains("parquet-scratch")
                        || body.contains(SCRATCH_MOUNT_PATH);
                assertFalse(ceilingOverride.matcher(body).find()
                                || (touchesScratch && sizeLimitOverride.matcher(body).find()),
                        file + " redefines a scratch ceiling, the directory budget, or "
                                + "the volume sizeLimit; theDeployedCeilingsRefuseBeforeTheScratchVolumeFills "
                                + "no longer covers the rendered configuration and must be widened to it");
            }
        }
    }

    /**
     * Drop YAML comments, so a documented key is not read as a declared one. A {@code #} inside a
     * quoted scalar would be stripped too; no manifest here has one, and the cost of that mistake
     * is a false negative on one line, not a false alarm.
     */
    private static String withoutComments(String yaml) {
        return yaml.lines()
                .map(line -> line.replaceFirst("(^|\\s)#.*$", ""))
                .reduce(new StringBuilder(), (buffer, line) -> buffer.append(line).append('\n'),
                        StringBuilder::append)
                .toString();
    }

    /** A configured directory is on the bounded volume if it is the mount or lives under it. */
    private static boolean onScratchVolume(Object configuredDirectory) {
        if (configuredDirectory == null) {
            return false;
        }
        String directory = String.valueOf(configuredDirectory).trim();
        while (directory.length() > 1 && directory.endsWith("/")) {
            directory = directory.substring(0, directory.length() - 1);
        }
        return directory.equals(SCRATCH_MOUNT_PATH) || directory.startsWith(SCRATCH_MOUNT_PATH + "/");
    }

    private static boolean mountsScratchVolume() throws IOException {
        Map<String, Object> deployment = loadManifest("k8s/base/deployment-backend.yaml");
        Map<String, Object> podSpec = child(child(child(deployment, "spec"), "template"), "spec");
        for (Map<String, Object> container : children(podSpec, "containers")) {
            for (Map<String, Object> mount : children(container, "volumeMounts")) {
                if (SCRATCH_MOUNT_PATH.equals(mount.get("mountPath"))) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * A deployed ceiling is read by Spring into a {@code long}, so it must be a plain byte count:
     * {@code "2Gi"} would fail the context, not merely this parse.
     */
    private static long deployedBytes(Map<String, Object> data, String key) {
        Object declared = data.get(key);
        assertNotNull(declared,
                key + " must be set beside the temp dirs: the application default (10 GiB) is "
                        + "above the volume, so without it the pod is evicted before the app refuses");
        String raw = String.valueOf(declared).trim();
        assertTrue(raw.matches("\\d+"),
                key + " must be a plain byte count — Spring binds it to a long, so a Kubernetes "
                        + "quantity like \"2Gi\" crashes the context on startup. Was: " + raw);
        long bytes = Long.parseLong(raw);
        assertTrue(bytes > 0, key + " must be positive, was " + bytes);
        return bytes;
    }

    /**
     * The volume actually mounted at the scratch path, not merely the first bounded {@code
     * emptyDir} in the pod: a second one would otherwise be measured instead.
     */
    private static long scratchVolumeSizeLimitBytes() throws IOException {
        Map<String, Object> deployment = loadManifest("k8s/base/deployment-backend.yaml");
        Map<String, Object> podSpec = child(child(child(deployment, "spec"), "template"), "spec");

        Map<String, Map<String, Object>> volumesByName = new HashMap<>();
        for (Map<String, Object> volume : children(podSpec, "volumes")) {
            volumesByName.put(String.valueOf(volume.get("name")), volume);
        }
        for (Map<String, Object> container : children(podSpec, "containers")) {
            for (Map<String, Object> mount : children(container, "volumeMounts")) {
                if (!SCRATCH_MOUNT_PATH.equals(mount.get("mountPath"))) {
                    continue;
                }
                Map<String, Object> volume = volumesByName.get(String.valueOf(mount.get("name")));
                assertNotNull(volume, "no volume named " + mount.get("name") + " backs "
                        + SCRATCH_MOUNT_PATH);
                Object emptyDir = volume.get("emptyDir");
                assertTrue(emptyDir instanceof Map<?, ?>,
                        SCRATCH_MOUNT_PATH + " must be backed by an emptyDir, was " + volume);
                Object sizeLimit = ((Map<?, ?>) emptyDir).get("sizeLimit");
                assertNotNull(sizeLimit, "the scratch volume declares no sizeLimit — the deployed "
                        + "ceilings have nothing to sit below");
                return quantityToBytes(String.valueOf(sizeLimit));
            }
        }
        throw new AssertionError("nothing mounts " + SCRATCH_MOUNT_PATH + ", yet the ConfigMap "
                + "points the writers at it — they would land on the unbounded writable layer");
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

    private static String applicationYaml() throws IOException {
        return Files.readString(Path.of("src/main/resources/application.yml"));
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
