package com.bitbi.dfm.documentation;

import com.bitbi.dfm.testsupport.RunOwnedScratch;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * No deployment surface provisions or configures a Redis the application does not have (issue #335).
 *
 * <p>#319 removed Redis from the application: the two Upload History caches were inert, Redis had no
 * other user, and {@code NoSpringCacheConventionTest} now refuses a Redis client in the build and a
 * {@code spring.data.redis} block in shipped configuration. What it could not reach is everything
 * that is not the application — the Terraform module that created a Memorystore instance in stage
 * and prod, the dormant AWS CloudFormation path with its Redis parameters and secret, and the
 * workflow that fed that secret. They are removed by #335, and this class keeps them removed: a
 * Memorystore block copied back from history, or a {@code SPRING_DATA_REDIS_*} key added to an
 * overlay, would provision and bill for a store nothing reads.</p>
 *
 * <h2>What is read</h2>
 *
 * <p>The tracked files ({@link ConflictMarkerLineTest#trackedFiles}, the index rather than a
 * directory walk — {@code infra/} holds a {@code .terraform/} provider cache full of the word) under
 * {@code infra/}, {@code deploy-script/}, {@code k8s/}, {@code .github/workflows/},
 * {@code .devcontainer/}, {@code .conductor/} and every {@code docker-compose*} file. Not
 * {@code docs/} or {@code specs/}: change requests and specs record the history in which Redis
 * existed, and rewriting them would erase the reason it is gone. Every tracked file is already an
 * input of {@code test} ({@code build.gradle.kts}, #342), so an infra-only commit re-runs this
 * class.</p>
 *
 * <p>The scan fails closed: it must read the files the removal touched, so a list that went empty —
 * a moved directory, a git that answered nothing — cannot read as a clean tree.</p>
 */
@DisplayName("No Redis in the deployment surfaces (issue #335)")
class NoRedisInfrastructureTest {

    private static final Pattern REDIS = Pattern.compile("(?i)redis|memorystore");

    private static final List<String> ROOTS = List.of(
            "infra/", "deploy-script/", "k8s/", ".github/workflows/", ".devcontainer/", ".conductor/");

    private static final List<String> MUST_READ = List.of(
            "infra/modules/forge-app/main.tf",
            "infra/modules/forge-app/variables.tf",
            "infra/modules/forge-app/outputs.tf",
            "infra/environments/stage/main.tf",
            "infra/environments/prod/main.tf",
            "infra/README.md",
            "deploy-script/template-1763397226530.yaml",
            ".github/workflows/ci-cd.yml",
            "k8s/base/configmap.yaml");

    @Test
    @DisplayName("no deployment file names Redis or Memorystore")
    void noDeploymentFileNamesRedis() throws IOException, InterruptedException {
        Path root = RunOwnedScratch.projectRoot();
        List<String> read = new ArrayList<>();
        List<String> findings = new ArrayList<>();
        for (String file : ConflictMarkerLineTest.trackedFiles(root)) {
            if (!inScope(file)) {
                continue;
            }
            Path path = root.resolve(file);
            if (!Files.isRegularFile(path)) {
                continue;
            }
            read.add(file);
            for (int line : mentions(Files.readString(path, StandardCharsets.UTF_8))) {
                findings.add(file + ":" + line);
            }
        }

        assertThat(read)
                .as("the scan must read the files the Redis removal touched, or it proves nothing")
                .containsAll(MUST_READ);
        assertThat(findings)
                .as("deployment files that still provision or configure Redis — the application has "
                        + "none since #319, so this is a store that is billed and never read (#335)")
                .isEmpty();
    }

    @Test
    @DisplayName("the scope is the deployment surfaces, not the history in docs/ and specs/")
    void scopeIsTheDeploymentSurfaces() {
        assertThat(inScope("infra/modules/forge-app/main.tf")).isTrue();
        assertThat(inScope(".github/workflows/ci-cd.yml")).isTrue();
        assertThat(inScope("docker-compose.prod.yml")).isTrue();
        assertThat(inScope(".devcontainer/docker-compose.yml")).isTrue();
        assertThat(inScope("docs/cr-gke-migration.md")).isFalse();
        assertThat(inScope("specs/008-upload-history-user/plan.md")).isFalse();
        assertThat(inScope(".github/ISSUE_TEMPLATE/task.yml")).isFalse();
    }

    @Test
    @DisplayName("a mention is found in any case and reported by line")
    void mentionIsFoundInAnyCaseByLine() {
        assertThat(mentions("""
                resource "google_storage_bucket" "uploads" {}
                resource "google_redis_instance" "cache" {
                  # Memorystore (stage/prod)
                }
                env:
                  REDIS_PASSWORD: x
                """)).containsExactly(2, 3, 6);
        assertThat(mentions("resource \"google_sql_database\" \"dfm\" {}\n")).isEmpty();
    }

    static boolean inScope(String file) {
        if (file.startsWith("docker-compose") || file.contains("/docker-compose")) {
            return true;
        }
        return ROOTS.stream().anyMatch(file::startsWith);
    }

    static List<Integer> mentions(String content) {
        List<Integer> lines = new ArrayList<>();
        String[] split = content.split("\n", -1);
        for (int i = 0; i < split.length; i++) {
            if (REDIS.matcher(split[i]).find()) {
                lines.add(i + 1);
            }
        }
        return lines;
    }
}
