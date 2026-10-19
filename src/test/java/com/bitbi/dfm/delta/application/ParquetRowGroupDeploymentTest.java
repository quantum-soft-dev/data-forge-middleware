package com.bitbi.dfm.delta.application;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Issue #385: where the Parquet row-group budget departs from its default.
 *
 * <p>{@code delta.parquet.row-group-bytes} ships 16 MiB. BitBi's Parquet reader decodes a row
 * group whole and refuses one above six million cells, and on dev the largest checkpoint table
 * ({@code nsfwork} of {@code fyt-new}) closed its groups at about 6.2 million. Dev therefore runs at
 * 8 MiB, which changes only how a file is split: the rows are the same, a writer holds less heap,
 * the footer carries more row groups and compression is a little worse. Stage and prod keep the
 * default until their own consumer asks.</p>
 *
 * <p>Nothing the compiler or CI runs reads a manifest, so the decision is held here, over every
 * file under {@code k8s/}, with the reader of {@link S3OrphanSweepDeploymentTest} — one idea of
 * what a manifest declares, not two.</p>
 */
@DisplayName("The Parquet row group is 8 MiB on dev and the default elsewhere (#385)")
class ParquetRowGroupDeploymentTest {

    private static final String KEY = "DELTA_PARQUET_ROW_GROUP_BYTES";

    /** The relaxed-binding spelling of the same property, which Spring binds from the environment too. */
    private static final String RELAXED_KEY = "DELTA_PARQUET_ROWGROUPBYTES";

    private static final String DEV_VALUE = String.valueOf(8L * 1024 * 1024);

    private static final Path DEV_CONFIGMAP_PATCH = Path.of("k8s/overlays/dev/configmap-patch.yaml");

    @Test
    @DisplayName("the dev overlay sets the row group to 8 MiB")
    void theDevOverlayHalvesTheRowGroup() throws IOException {
        assertThat(S3OrphanSweepDeploymentTest.declaredValues(read(DEV_CONFIGMAP_PATCH), KEY))
                .as("%s must set %s to \"%s\": BitBi's reader refuses a row group above six million cells (#385)",
                        DEV_CONFIGMAP_PATCH, KEY, DEV_VALUE)
                .containsExactly(DEV_VALUE);
        assertThat(S3OrphanSweepDeploymentTest.declaredValues(read(DEV_CONFIGMAP_PATCH), RELAXED_KEY))
                .as("one spelling per decision: a second one would make the effective value depend on binding order")
                .isEmpty();
    }

    @Test
    @DisplayName("the ConfigMap key is the placeholder application.yml binds, and dev sits below its default")
    void theManifestKeyIsTheOneTheApplicationReads() throws IOException {
        assertThat(read(Path.of("src/main/resources/application.yml")))
                .as("a renamed placeholder leaves the manifest setting a variable nothing reads")
                .contains("row-group-bytes: ${" + KEY + ":" + DeltaParquetProperties.DEFAULT_ROW_GROUP_BYTES + "}");
        assertThat(Long.parseLong(DEV_VALUE))
                .as("dev lowers the budget for BitBi's reader; a value at or above the default would not")
                .isPositive()
                .isLessThan(Long.parseLong(DeltaParquetProperties.DEFAULT_ROW_GROUP_BYTES));
    }

    @Test
    @DisplayName("no other manifest sets the row group: stage and prod keep the default")
    void noOtherManifestSetsTheRowGroup() throws IOException {
        Map<Path, List<String>> offenders = new LinkedHashMap<>();
        for (Path file : manifests()) {
            if (file.equals(DEV_CONFIGMAP_PATCH)) {
                continue;
            }
            String body = read(file);
            List<String> values = new ArrayList<>(S3OrphanSweepDeploymentTest.declaredValues(body, KEY));
            values.addAll(S3OrphanSweepDeploymentTest.declaredValues(body, RELAXED_KEY));
            if (!values.isEmpty()) {
                offenders.put(file, values);
            }
        }
        assertThat(offenders)
                .as("the row group is a memory ceiling shared by every writer in the pod; moving it "
                        + "outside dev is a decision of its own, taken with that pod's heap (#385)")
                .isEmpty();
    }

    private static List<Path> manifests() throws IOException {
        try (Stream<Path> files = Files.walk(Path.of("k8s"))) {
            List<Path> yaml = files.filter(ParquetRowGroupDeploymentTest::isYaml).sorted().toList();
            assertThat(yaml).as("the scan found no manifests; a blind scan is not a clean one")
                    .contains(DEV_CONFIGMAP_PATCH, Path.of("k8s/base/configmap.yaml"),
                            Path.of("k8s/overlays/stage/configmap-patch.yaml"),
                            Path.of("k8s/overlays/prod/configmap-patch.yaml"));
            return yaml;
        }
    }

    private static boolean isYaml(Path path) {
        String name = path.getFileName().toString();
        return Files.isRegularFile(path) && (name.endsWith(".yaml") || name.endsWith(".yml"));
    }

    private static String read(Path path) throws IOException {
        return Files.readString(path);
    }
}
