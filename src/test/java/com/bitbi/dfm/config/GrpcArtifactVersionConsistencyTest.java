package com.bitbi.dfm.config;

import com.bitbi.dfm.config.GrpcArtifactVersions.Family;
import com.bitbi.dfm.config.GrpcArtifactVersions.JarMetadata;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Every {@code io.grpc:*} artifact on the runtime classpath is one version, and so is every
 * {@code com.google.protobuf:protobuf-java*} artifact (issue #301).
 *
 * <p><strong>Why a test and not the build.</strong> Spring Boot 3.5 manages neither family, so the
 * versions declared in {@code build.gradle.kts} are the ones resolved. Spring Boot 4.1 manages both
 * ({@code grpc-bom}, {@code protobuf-bom}, through Spring gRPC), and {@code io.spring.dependency-management}
 * lets an explicit version win only on a <em>direct</em> dependency: the transitive
 * {@code grpc-api}, {@code grpc-core}, {@code grpc-util} and {@code grpc-protobuf-lite} follow the
 * BOM. Compilation passes on such a mix; the failure arrives at run time, inside the Delta ingestion
 * server on :9090 that the shipped Windows client talks to, and no other test in this suite would
 * notice it before something there broke.</p>
 *
 * <p><strong>Where the versions come from.</strong> The jars carry no {@code pom.properties} (checked
 * against gRPC 1.83.1 and protobuf 3.25.9), so each version is read from {@code MANIFEST.MF} —
 * {@code Implementation-Version} for gRPC, {@code Bundle-Version} for protobuf — and the artifact
 * name from the jar's own file. The scan goes through the class loader's
 * {@code META-INF/MANIFEST.MF} resources, which is the same under Gradle and an IDE. Membership also
 * requires the jar to carry the family's package, so {@code com.google.api.grpc:*} artifacts, which
 * version independently, are not mistaken for gRPC.</p>
 *
 * <p>No Spring context: this runs on the fast gate, and the property it holds is a fact about the
 * classpath, not about a wired application.</p>
 */
@DisplayName("gRPC and protobuf artifacts on the classpath share one version per family")
class GrpcArtifactVersionConsistencyTest {

    /**
     * The members this application cannot run without. Requiring them stops a scan that has gone
     * blind — a changed jar layout, a class loader that hides manifests — from reading as a
     * consistent classpath simply because it found nothing to compare.
     */
    private static final Set<String> REQUIRED_GRPC =
            Set.of("grpc-api", "grpc-core", "grpc-stub", "grpc-protobuf", "grpc-netty-shaded");
    private static final Set<String> REQUIRED_PROTOBUF = Set.of("protobuf-java");

    private static final List<JarMetadata> CLASSPATH = GrpcArtifactVersions.scanClasspath(
            GrpcArtifactVersionConsistencyTest.class.getClassLoader());

    @Test
    @DisplayName("the scan finds the gRPC and protobuf artifacts the application runs on")
    void findsTheArtifactsTheApplicationRunsOn() {
        assertThat(membersOf(Family.GRPC))
                .as("io.grpc artifacts found on the classpath")
                .containsAll(REQUIRED_GRPC);
        assertThat(membersOf(Family.PROTOBUF))
                .as("protobuf-java artifacts found on the classpath")
                .containsAll(REQUIRED_PROTOBUF);
    }

    @Test
    @DisplayName("each family resolves to exactly one version")
    void eachFamilyResolvesToOneVersion() {
        List<String> divergences = GrpcArtifactVersions.divergences(CLASSPATH);

        assertThat(divergences)
                .withFailMessage("""
                        Mixed gRPC or protobuf versions on the runtime classpath. Compilation passes on \
                        such a mix and the Delta ingestion server fails at run time. Under Spring Boot 4.1 \
                        the transitive artifacts follow the BOM while the direct ones keep build.gradle.kts's \
                        version — pin the family as a whole (#302):
                        %s""", String.join("\n", divergences))
                .isEmpty();
    }

    private static Set<String> membersOf(Family family) {
        return CLASSPATH.stream()
                .filter(jar -> GrpcArtifactVersions.familyOf(jar).equals(Optional.of(family)))
                .map(JarMetadata::artifactName)
                .collect(Collectors.toSet());
    }
}
