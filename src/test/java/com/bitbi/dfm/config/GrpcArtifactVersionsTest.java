package com.bitbi.dfm.config;

import com.bitbi.dfm.config.GrpcArtifactVersions.Family;
import com.bitbi.dfm.config.GrpcArtifactVersions.JarMetadata;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The reader behind {@link GrpcArtifactVersionConsistencyTest}, over synthetic jars (issue #301).
 *
 * <p>A guard that misreads the classpath is worse than none: it reports a mixed gRPC as one version,
 * or a blind scan as a clean one. So the reading rules are pinned here, where each can fail on its
 * own, rather than only through the one real classpath that happens to be consistent today.</p>
 */
@DisplayName("Reading gRPC and protobuf artifact versions from jar metadata")
class GrpcArtifactVersionsTest {

    @Test
    @DisplayName("names the artifact from the jar file when the jar carries no pom.properties")
    void namesTheArtifactFromTheJarFileName() {
        assertThat(GrpcArtifactVersions.artifactNameOf("grpc-netty-shaded-1.83.1.jar"))
                .isEqualTo("grpc-netty-shaded");
        assertThat(GrpcArtifactVersions.artifactNameOf("protobuf-java-4.35.1.jar"))
                .isEqualTo("protobuf-java");
        assertThat(GrpcArtifactVersions.artifactNameOf("grpc-core.jar")).isEqualTo("grpc-core");
    }

    @Test
    @DisplayName("prefers pom.properties, then Implementation-Version, then Bundle-Version")
    void resolvesTheVersionInOrderOfPrecision() {
        assertThat(jar("grpc-core-1.83.1.jar", "grpc-core", "1.83.1", "9.9.9", "8.8.8").version())
                .contains("1.83.1");
        assertThat(jar("grpc-core-1.83.1.jar", null, null, "1.83.1", "8.8.8").version())
                .contains("1.83.1");
        assertThat(jar("protobuf-java-3.25.9.jar", null, null, null, "3.25.9").version())
                .contains("3.25.9");
        assertThat(jar("grpc-core-1.83.1.jar", null, null, null, null).version()).isEmpty();
    }

    @Test
    @DisplayName("assigns a jar to a family by its name and by the package it actually carries")
    void assignsFamiliesByNameAndPackage() {
        assertThat(GrpcArtifactVersions.familyOf(jar("grpc-api-1.83.1.jar", null, null, "1.83.1", null)))
                .contains(Family.GRPC);
        assertThat(GrpcArtifactVersions.familyOf(protobuf("protobuf-java-3.25.9.jar", "3.25.9")))
                .contains(Family.PROTOBUF);
        // com.google.api.grpc:grpc-google-* has the prefix but no io/grpc classes of its own.
        assertThat(GrpcArtifactVersions.familyOf(new JarMetadata("grpc-google-common-protos-2.1.jar",
                "grpc-google-common-protos", Optional.of("2.1"), false, false)))
                .isEmpty();
        assertThat(GrpcArtifactVersions.familyOf(new JarMetadata("proto-google-common-protos-2.64.1.jar",
                "proto-google-common-protos", Optional.of("2.64.1"), false, true)))
                .isEmpty();
    }

    @Test
    @DisplayName("reports nothing when every member of each family shares one version")
    void reportsNothingForAConsistentClasspath() {
        List<JarMetadata> jars = List.of(
                jar("grpc-api-1.83.1.jar", null, null, "1.83.1", null),
                jar("grpc-core-1.83.1.jar", null, null, "1.83.1", null),
                protobuf("protobuf-java-3.25.9.jar", "3.25.9"),
                protobuf("protobuf-java-util-3.25.9.jar", "3.25.9"));

        assertThat(GrpcArtifactVersions.divergences(jars)).isEmpty();
    }

    @Test
    @DisplayName("names every diverging artifact with its version — the Boot 4.1 BOM shape")
    void namesDivergingArtifactsWithTheirVersions() {
        List<JarMetadata> jars = List.of(
                jar("grpc-stub-1.68.1.jar", null, null, "1.68.1", null),
                jar("grpc-api-1.83.1.jar", null, null, "1.83.1", null),
                jar("grpc-core-1.83.1.jar", null, null, "1.83.1", null),
                protobuf("protobuf-java-3.25.9.jar", "3.25.9"));

        List<String> divergences = GrpcArtifactVersions.divergences(jars);

        assertThat(divergences).hasSize(1);
        assertThat(divergences.getFirst())
                .contains("io.grpc")
                .contains("grpc-stub 1.68.1")
                .contains("grpc-api 1.83.1")
                .contains("grpc-core 1.83.1");
    }

    @Test
    @DisplayName("fails a family member whose metadata carries no version instead of skipping it")
    void failsAMemberWithNoVersion() {
        List<JarMetadata> jars = List.of(
                jar("grpc-api-1.83.1.jar", null, null, "1.83.1", null),
                jar("grpc-util-1.83.1.jar", null, null, null, null));

        assertThat(GrpcArtifactVersions.divergences(jars))
                .singleElement(org.assertj.core.api.InstanceOfAssertFactories.STRING)
                .contains("grpc-util")
                .contains("no version");
    }

    @Test
    @DisplayName("counts the same artifact twice on the classpath as a divergence when versions differ")
    void countsTheSameArtifactTwiceWithDifferentVersions() {
        List<JarMetadata> jars = List.of(
                protobuf("protobuf-java-3.25.9.jar", "3.25.9"),
                protobuf("protobuf-java-4.35.1.jar", "4.35.1"));

        assertThat(GrpcArtifactVersions.divergences(jars))
                .singleElement(org.assertj.core.api.InstanceOfAssertFactories.STRING)
                .contains("protobuf-java 3.25.9")
                .contains("protobuf-java 4.35.1");
    }

    private static JarMetadata jar(String fileName, String pomArtifactId, String pomVersion,
                                   String implementationVersion, String bundleVersion) {
        return GrpcArtifactVersions.metadataOf(fileName, Optional.ofNullable(pomArtifactId),
                Optional.ofNullable(pomVersion), Optional.ofNullable(implementationVersion),
                Optional.ofNullable(bundleVersion), true, false);
    }

    private static JarMetadata protobuf(String fileName, String bundleVersion) {
        return GrpcArtifactVersions.metadataOf(fileName, Optional.empty(), Optional.empty(),
                Optional.empty(), Optional.of(bundleVersion), false, true);
    }
}
