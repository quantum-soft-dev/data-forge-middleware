package com.bitbi.dfm.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Issue #350: what the backend scales on, and why it carries no PriorityClass.
 *
 * <p>These decisions live in the manifests, and nothing the compiler or CI runs reads them. So
 * they are held here, over every file under {@code k8s/}: the base and every overlay. A document
 * counts as the backend's HPA when its {@code scaleTargetRef} names {@code forge-backend}, or,
 * for an overlay patch that carries no target, when its name starts with {@code forge-backend}.
 * That is the selection {@code BackgroundConnectionDemandTest} uses for {@code maxReplicas}.</p>
 *
 * <p><strong>CPU only.</strong> The JVM runs with a fixed maximum heap and does not give the heap
 * back. On dev the pod that built the night's checkpoint held 1.5–1.76 GiB for the rest of the day,
 * against an idle pod's 0.65–0.7 GiB. A memory target therefore measures how large the heap once
 * grew, not how busy the pod is. It scaled the deployment up on 17.09, and every scale-down of the
 * week was a "memory below target" that came hours late.</p>
 *
 * <p><strong>Scale-up waits out the nightly build.</strong> The checkpoint sweep at 02:00 runs on
 * one pod at about one core. It took 13, 16, 22, 14, 25 and 31 minutes on the nights of 18, 19, 21,
 * 23, 24 and 25.09, and grows with the site. With the old 60-second window the HPA added replicas at
 * 02:01 every such night. A replica started after 02:00 takes no part in that night's sweep: the
 * cron has already fired. A gRPC ingest session stays on the pod it was opened on, so a new replica
 * relieves only sessions opened after it is ready. The window is therefore longer than any measured
 * sweep with room for growth. The price is that a real overload waits the same window before
 * another replica comes. {@code minReplicas} is the ingest headroom, not the scale-up.</p>
 *
 * <p><strong>No PriorityClass.</strong> Every preemption of a forge pod on dev in the 30 days before
 * this ticket (five of them, 02.09–22.09) was by a GKE system pod: kube-dns or
 * konnectivity-agent. The same pods also preempted bitbi's. GKE runs those at
 * {@code system-cluster-critical} (2,000,000,000), and a user PriorityClass is capped at
 * 1,000,000,000, so no class of ours would have stopped any of them. On a cluster shared with bitbi,
 * such a class would only let forge preempt bitbi's pods, and nothing observed asks for that. A
 * later ticket that wants one must bring evidence of a preemption a user class can prevent, and
 * bitbi's agreement, and remove this assertion together with that decision.</p>
 */
@DisplayName("The backend scales on CPU only, not on the nightly build, and has no PriorityClass (#350)")
class HpaScalingPolicyDeploymentTest {

    private static final String BACKEND_DEPLOYMENT = "forge-backend";

    /** The longest nightly checkpoint sweep measured on dev (25.09: 02:01–02:31). */
    static final int LONGEST_MEASURED_NIGHTLY_SWEEP_SECONDS = 31 * 60;

    /**
     * The shortest scale-up window this deployment accepts: the longest measured sweep plus half
     * again for the site to grow. The shipped value (3600) is above it. Raising the floor is always
     * safe. Lowering it needs a new measurement of the sweep.
     */
    static final int MIN_SCALE_UP_WINDOW_SECONDS = LONGEST_MEASURED_NIGHTLY_SWEEP_SECONDS * 3 / 2;

    private static final Path BASE_HPA = Path.of("k8s/base/hpa.yaml");

    @Test
    @DisplayName("the backend HPA scales on CPU utilization and on nothing else")
    void theBackendScalesOnCpuAlone() throws IOException {
        List<String> declared = new ArrayList<>();
        for (Path manifest : manifests()) {
            for (Map<?, ?> spec : backendHpaSpecs(manifest)) {
                if (!(spec.get("metrics") instanceof List<?> metrics)) {
                    continue;
                }
                for (Object metric : metrics) {
                    declared.add(manifest + ": " + describe(metric));
                }
            }
        }
        assertThat(declared)
                .as("the HPA of %s must scale on CPU utilization alone. A JVM with a fixed heap does "
                        + "not give memory back, so a memory target measures how big the heap once "
                        + "grew, not load (#350)", BACKEND_DEPLOYMENT)
                .isNotEmpty()
                .allSatisfy(metric -> assertThat(metric).endsWith("Resource/cpu"));
    }

    @Test
    @DisplayName("the base HPA declares its CPU target, so the rule is not left to Kubernetes' default")
    void theBaseDeclaresTheCpuMetric() throws IOException {
        List<String> metrics = new ArrayList<>();
        for (Map<?, ?> spec : backendHpaSpecs(BASE_HPA)) {
            if (spec.get("metrics") instanceof List<?> declared) {
                declared.forEach(metric -> metrics.add(describe(metric)));
            }
        }
        assertThat(metrics).as("metrics of %s", BASE_HPA).containsExactly("Resource/cpu");
    }

    @Test
    @DisplayName("scale-up waits longer than the longest nightly checkpoint sweep")
    void scaleUpWaitsOutTheNightlySweep() throws IOException {
        List<String> windows = new ArrayList<>();
        boolean baseDeclares = false;
        for (Path manifest : manifests()) {
            for (Map<?, ?> spec : backendHpaSpecs(manifest)) {
                if (!(spec.get("behavior") instanceof Map<?, ?> behavior)) {
                    continue;
                }
                Object window = behavior.get("scaleUp") instanceof Map<?, ?> scaleUp
                        ? scaleUp.get("stabilizationWindowSeconds") : null;
                windows.add(manifest + ": " + window);
                baseDeclares |= manifest.equals(BASE_HPA) && window != null;
                assertThat(window)
                        .as("%s declares an HPA behavior for %s. Its scaleUp.stabilizationWindowSeconds "
                                        + "must be at least %d s: the nightly checkpoint sweep ran %d s on "
                                        + "25.09, and a replica started after 02:00 takes no part in it. "
                                        + "Leaving it out means Kubernetes' default of 0 (#350)",
                                manifest, BACKEND_DEPLOYMENT, MIN_SCALE_UP_WINDOW_SECONDS,
                                LONGEST_MEASURED_NIGHTLY_SWEEP_SECONDS)
                        .isInstanceOf(Number.class);
                assertThat(((Number) window).intValue())
                        .as("scaleUp.stabilizationWindowSeconds in %s", manifest)
                        .isGreaterThanOrEqualTo(MIN_SCALE_UP_WINDOW_SECONDS);
            }
        }
        assertThat(baseDeclares)
                .as("%s must declare scaleUp.stabilizationWindowSeconds itself; found %s", BASE_HPA, windows)
                .isTrue();
    }

    @Test
    @DisplayName("no manifest declares a PriorityClass or gives a pod one")
    void noPriorityClass() throws IOException {
        List<String> found = new ArrayList<>();
        for (Path manifest : manifests()) {
            for (Object document : documents(manifest)) {
                if (document instanceof Map<?, ?> root && "PriorityClass".equals(root.get("kind"))) {
                    found.add(manifest + ": kind PriorityClass");
                }
                collectKey(document, "priorityClassName", manifest, found);
            }
        }
        assertThat(found)
                .as("every forge preemption on dev (02.09–22.09) was by a GKE system pod at "
                        + "system-cluster-critical (2e9), above any user class (max 1e9). A class of "
                        + "ours prevents none of them and only lets forge preempt bitbi on the shared "
                        + "cluster. Bring new evidence and bitbi's agreement first (#350)")
                .isEmpty();
    }

    /** Every {@code spec} of a document in this manifest that is the backend's HPA. */
    private static List<Map<?, ?>> backendHpaSpecs(Path manifest) throws IOException {
        List<Map<?, ?>> specs = new ArrayList<>();
        for (Object document : documents(manifest)) {
            if (!(document instanceof Map<?, ?> root) || !"HorizontalPodAutoscaler".equals(root.get("kind"))) {
                continue;
            }
            if (!(root.get("spec") instanceof Map<?, ?> spec)) {
                continue;
            }
            boolean targetsBackend = spec.get("scaleTargetRef") instanceof Map<?, ?> target
                    ? BACKEND_DEPLOYMENT.equals(target.get("name"))
                    : root.get("metadata") instanceof Map<?, ?> metadata
                            && String.valueOf(metadata.get("name")).startsWith(BACKEND_DEPLOYMENT);
            if (targetsBackend) {
                specs.add(spec);
            }
        }
        return specs;
    }

    /** {@code type/resource-name} of one HPA metric, e.g. {@code Resource/cpu}. */
    private static String describe(Object metric) {
        if (!(metric instanceof Map<?, ?> map)) {
            return String.valueOf(metric);
        }
        Object name = map.get("resource") instanceof Map<?, ?> resource ? resource.get("name") : null;
        return map.get("type") + "/" + name;
    }

    private static void collectKey(Object node, String key, Path manifest, List<String> found) {
        if (node instanceof Map<?, ?> map) {
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (key.equals(entry.getKey())) {
                    found.add(manifest + ": " + key + " " + entry.getValue());
                }
                collectKey(entry.getValue(), key, manifest, found);
            }
        } else if (node instanceof List<?> list) {
            for (Object item : list) {
                collectKey(item, key, manifest, found);
            }
        }
    }

    private static List<Path> manifests() throws IOException {
        try (Stream<Path> tree = Files.walk(Path.of("k8s"))) {
            return tree.filter(path -> path.toString().endsWith(".yaml") || path.toString().endsWith(".yml"))
                    .sorted()
                    .toList();
        }
    }

    private static List<Object> documents(Path manifest) throws IOException {
        List<Object> documents = new ArrayList<>();
        new Yaml().loadAll(Files.readString(manifest)).forEach(documents::add);
        return documents;
    }
}
