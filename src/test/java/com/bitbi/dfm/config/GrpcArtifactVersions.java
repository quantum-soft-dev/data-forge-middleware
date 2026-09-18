package com.bitbi.dfm.config;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.JarURLConnection;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.Set;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.Manifest;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Reads the versions of the gRPC and protobuf artifacts on a class loader's classpath, for
 * {@link GrpcArtifactVersionConsistencyTest} (issue #301).
 *
 * <p>The scan is separated from the judgement so that every reading rule is exercised over synthetic
 * jars in {@link GrpcArtifactVersionsTest}; only {@link #scanClasspath} touches the file system.</p>
 */
final class GrpcArtifactVersions {

    /** The families whose members must agree on one version. */
    enum Family {
        GRPC("io.grpc", "grpc-", "io/grpc/"),
        PROTOBUF("com.google.protobuf:protobuf-java*", "protobuf-java", "com/google/protobuf/");

        private final String label;
        private final String namePrefix;
        private final String packagePrefix;

        Family(String label, String namePrefix, String packagePrefix) {
            this.label = label;
            this.namePrefix = namePrefix;
            this.packagePrefix = packagePrefix;
        }
    }

    /**
     * One jar, as far as this guard cares about it.
     *
     * @param fileName            the jar's file name, kept for the failure message
     * @param artifactName        the Maven artifact id, or the file name without its version
     * @param version             the version its metadata declares, if any
     * @param carriesGrpcPackage  whether it has entries under {@code io/grpc/}
     * @param carriesProtobufPackage whether it has entries under {@code com/google/protobuf/}
     */
    record JarMetadata(String fileName, String artifactName, Optional<String> version,
                       boolean carriesGrpcPackage, boolean carriesProtobufPackage) {
    }

    /** {@code name-1.2.3.jar} → {@code name}; the version starts at the first dash followed by a digit. */
    private static final Pattern VERSIONED_JAR = Pattern.compile("^(.+?)-\\d.*\\.jar$");

    private GrpcArtifactVersions() {
    }

    static String artifactNameOf(String jarFileName) {
        Matcher matcher = VERSIONED_JAR.matcher(jarFileName);
        if (matcher.matches()) {
            return matcher.group(1);
        }
        return jarFileName.endsWith(".jar")
                ? jarFileName.substring(0, jarFileName.length() - ".jar".length())
                : jarFileName;
    }

    /**
     * Builds a jar's metadata. The version is taken from the most precise source present:
     * {@code pom.properties} names the Maven coordinate exactly, {@code Implementation-Version} is
     * what gRPC writes, {@code Bundle-Version} is what protobuf writes.
     */
    static JarMetadata metadataOf(String fileName, Optional<String> pomArtifactId, Optional<String> pomVersion,
                                  Optional<String> implementationVersion, Optional<String> bundleVersion,
                                  boolean carriesGrpcPackage, boolean carriesProtobufPackage) {
        Optional<String> version = pomVersion.or(() -> implementationVersion).or(() -> bundleVersion);
        String artifactName = pomArtifactId.orElseGet(() -> artifactNameOf(fileName));
        return new JarMetadata(fileName, artifactName, version, carriesGrpcPackage, carriesProtobufPackage);
    }

    /**
     * The family a jar belongs to. The name alone is not enough — {@code com.google.api.grpc} publishes
     * {@code grpc-google-*} artifacts on their own version line — so the jar must also carry the
     * family's package.
     */
    static Optional<Family> familyOf(JarMetadata jar) {
        if (jar.artifactName().startsWith(Family.GRPC.namePrefix) && jar.carriesGrpcPackage()) {
            return Optional.of(Family.GRPC);
        }
        if (jar.artifactName().startsWith(Family.PROTOBUF.namePrefix) && jar.carriesProtobufPackage()) {
            return Optional.of(Family.PROTOBUF);
        }
        return Optional.empty();
    }

    /**
     * One message per family that does not resolve to a single version, naming every member with its
     * version. A member whose metadata declares no version is itself a divergence: skipping it would
     * let exactly the jar nobody can vouch for pass unseen.
     */
    static List<String> divergences(List<JarMetadata> jars) {
        Map<Family, List<JarMetadata>> byFamily = new LinkedHashMap<>();
        for (JarMetadata jar : jars) {
            familyOf(jar).ifPresent(family -> byFamily.computeIfAbsent(family, f -> new ArrayList<>()).add(jar));
        }

        List<String> divergences = new ArrayList<>();
        for (Family family : Family.values()) {
            List<JarMetadata> members = byFamily.getOrDefault(family, List.of());
            Set<Optional<String>> versions = members.stream()
                    .map(JarMetadata::version)
                    .collect(Collectors.toSet());
            boolean unversioned = versions.contains(Optional.<String>empty());
            if (versions.size() > 1 || unversioned) {
                String listing = members.stream()
                        .sorted(Comparator.comparing(JarMetadata::artifactName)
                                .thenComparing(jar -> jar.version().orElse("")))
                        .map(jar -> "  " + jar.artifactName() + " "
                                + jar.version().orElse("(no version in its metadata: " + jar.fileName() + ")"))
                        .collect(Collectors.joining("\n"));
                divergences.add(family.label + " resolves to more than one version:\n" + listing);
            }
        }
        return divergences;
    }

    /** Every jar on the class loader's classpath, read through its {@code META-INF/MANIFEST.MF}. */
    static List<JarMetadata> scanClasspath(ClassLoader classLoader) {
        Set<Path> jarPaths = new LinkedHashSet<>();
        try {
            Enumeration<URL> manifests = classLoader.getResources(JarFile.MANIFEST_NAME);
            while (manifests.hasMoreElements()) {
                URL url = manifests.nextElement();
                if ("jar".equals(url.getProtocol())
                        && url.openConnection() instanceof JarURLConnection connection) {
                    jarPaths.add(Path.of(connection.getJarFileURL().toURI()));
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Could not enumerate the classpath's manifests", e);
        } catch (URISyntaxException e) {
            throw new IllegalStateException("A classpath jar has an unreadable location", e);
        }

        List<JarMetadata> jars = new ArrayList<>();
        for (Path jarPath : jarPaths) {
            jars.add(read(jarPath));
        }
        return jars;
    }

    private static JarMetadata read(Path jarPath) {
        try (JarFile jar = new JarFile(jarPath.toFile())) {
            Manifest manifest = jar.getManifest();
            Attributes attributes = manifest == null ? new Attributes() : manifest.getMainAttributes();

            Optional<String> pomArtifactId = Optional.empty();
            Optional<String> pomVersion = Optional.empty();
            boolean grpcPackage = false;
            boolean protobufPackage = false;
            Enumeration<JarEntry> entries = jar.entries();
            while (entries.hasMoreElements()) {
                JarEntry entry = entries.nextElement();
                String name = entry.getName();
                grpcPackage |= name.startsWith(Family.GRPC.packagePrefix);
                protobufPackage |= name.startsWith(Family.PROTOBUF.packagePrefix);
                if (pomVersion.isEmpty() && name.startsWith("META-INF/maven/") && name.endsWith("/pom.properties")) {
                    Properties properties = new Properties();
                    try (InputStream in = jar.getInputStream(entry)) {
                        properties.load(in);
                    }
                    // A shaded jar carries its dependencies' pom.properties too; only its own counts.
                    String artifactId = properties.getProperty("artifactId");
                    if (artifactNameOf(jarPath.getFileName().toString()).equals(artifactId)) {
                        pomArtifactId = Optional.of(artifactId);
                        pomVersion = Optional.ofNullable(properties.getProperty("version"));
                    }
                }
            }
            return metadataOf(jarPath.getFileName().toString(), pomArtifactId, pomVersion,
                    Optional.ofNullable(attributes.getValue(Attributes.Name.IMPLEMENTATION_VERSION)),
                    Optional.ofNullable(attributes.getValue("Bundle-Version")),
                    grpcPackage, protobufPackage);
        } catch (IOException e) {
            throw new UncheckedIOException("Could not read " + jarPath, e);
        }
    }
}
