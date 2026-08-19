import org.springframework.boot.gradle.tasks.bundling.BootJar
import org.springframework.boot.gradle.tasks.run.BootRun

plugins {
    java
    id("org.springframework.boot") version "3.5.6"
    id("io.spring.dependency-management") version "1.1.7"
    jacoco
    id("com.google.protobuf") version "0.9.4"
}

group = "com.bitbi"
version = "2.0.0"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(25)
    }
}

configurations {
    compileOnly {
        extendsFrom(configurations.annotationProcessor.get())
    }
}

repositories {
    mavenCentral()
}

extra["awsSdkVersion"] = "2.28.11"
extra["grpcVersion"] = "1.68.1"
extra["protobufVersion"] = "3.25.5"
extra["parquetVersion"] = "1.15.2"
extra["hadoopVersion"] = "3.4.1"

dependencies {
    // Spring Boot Starters (versions managed by Spring Boot BOM)
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")
    implementation("org.springframework.boot:spring-boot-starter-security")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("org.springframework.boot:spring-boot-starter-actuator")

    // Database
    runtimeOnly("org.postgresql:postgresql")
    implementation("org.flywaydb:flyway-core")
    runtimeOnly("org.flywaydb:flyway-database-postgresql")

    // AWS S3
    implementation(platform("software.amazon.awssdk:bom:${property("awsSdkVersion")}"))
    implementation("software.amazon.awssdk:s3")
    implementation("software.amazon.awssdk:s3-transfer-manager")

    // OAuth2 Resource Server (for Auth0 integration)
    implementation("org.springframework.boot:spring-boot-starter-oauth2-resource-server")

    // Auth0 Integration
    implementation("com.auth0:auth0:2.26.0")
    implementation("com.auth0:java-jwt:4.4.0")

    // Spring Retry (for Auth0 API resilience)
    implementation("org.springframework.retry:spring-retry")

    // JWT
    implementation("io.jsonwebtoken:jjwt-api:0.12.6")
    runtimeOnly("io.jsonwebtoken:jjwt-impl:0.12.6")
    runtimeOnly("io.jsonwebtoken:jjwt-jackson:0.12.6")

    // OpenAPI/Swagger
    implementation("org.springdoc:springdoc-openapi-starter-webmvc-ui:2.8.3")

    // Metrics (managed by Spring Boot)
    runtimeOnly("io.micrometer:micrometer-registry-prometheus")

    // Logging
    implementation("net.logstash.logback:logstash-logback-encoder:8.0")

    // Upload History Feature Dependencies
    // Excel generation
    implementation("org.apache.poi:poi-ooxml:5.3.0")
    // CSV parsing
    implementation("org.apache.commons:commons-csv:1.12.0")
    // Compression (ZIP + Gzip)
    implementation("org.apache.commons:commons-compress:1.28.0")
    // Encoding detection
    implementation("com.ibm.icu:icu4j:76.1")
    // Redis caching
    implementation("org.springframework.boot:spring-boot-starter-data-redis")
    implementation("org.springframework.boot:spring-boot-starter-cache")

    // File Comparison Feature Dependencies
    // Diff library for file comparison
    implementation("io.github.java-diff-utils:java-diff-utils:4.12")
    // Hypersistence Utils for JSONB support
    implementation("io.hypersistence:hypersistence-utils-hibernate-63:3.9.0")

    // Plugin System Dependencies
    // JSON Schema validation for plugin data
    implementation("com.networknt:json-schema-validator:1.5.4")
    // Rate limiting for Plugin API
    implementation("com.bucket4j:bucket4j-core:8.10.1")
    // Caffeine cache for rate limiter (prevents memory leak)
    implementation("com.github.ben-manes.caffeine:caffeine:3.1.8")

    // gRPC + Protobuf (Delta Client v2 ingestion — 022)
    implementation("io.grpc:grpc-stub:${property("grpcVersion")}")
    implementation("io.grpc:grpc-protobuf:${property("grpcVersion")}")
    runtimeOnly("io.grpc:grpc-netty-shaded:${property("grpcVersion")}")
    implementation("com.google.protobuf:protobuf-java:${property("protobufVersion")}")
    // javax.annotation.Generated, referenced by generated gRPC stubs on JDK 9+
    compileOnly("org.apache.tomcat:annotations-api:6.0.53")

    // Parquet egress for Power BI (Delta Client v2 — 022, Task 4).
    // We write/read via Parquet's OutputFile/InputFile + PlainParquetConfiguration (no Hadoop FS), but
    // parquet-hadoop references org.apache.hadoop.{fs.Path,conf.Configuration} in its API signatures, so
    // the Hadoop client classes must be on the classpath. Use the shaded thin-client artifacts: they
    // relocate their own guava/protobuf/jackson and so do NOT conflict with Spring Boot or our protobuf.
    implementation("org.apache.parquet:parquet-avro:${property("parquetVersion")}")
    implementation("org.apache.hadoop:hadoop-client-api:${property("hadoopVersion")}")
    runtimeOnly("org.apache.hadoop:hadoop-client-runtime:${property("hadoopVersion")}")

    // Lombok
    compileOnly("org.projectlombok:lombok")
    annotationProcessor("org.projectlombok:lombok")

    // Test Dependencies
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.security:spring-security-test")
    // In-process gRPC transport for Delta v2 contract tests (022)
    testImplementation("io.grpc:grpc-inprocess:${property("grpcVersion")}")
    // Testcontainers 2.0.3 for Docker Desktop 29.x compatibility
    testImplementation("org.testcontainers:testcontainers:2.0.3")
    testImplementation("org.testcontainers:testcontainers-junit-jupiter:2.0.3")
    testImplementation("org.testcontainers:testcontainers-postgresql:2.0.3")
    testImplementation("org.testcontainers:testcontainers-localstack:2.0.3")
    testImplementation("org.awaitility:awaitility:4.2.2")
    // Docker 29.x compatibility handled by Testcontainers 2.0.2+
}


tasks.withType<Test> {
    useJUnitPlatform()
    finalizedBy(tasks.jacocoTestReport)

    // Where the test profile puts the file-backed Parquet scratch (issue #187). Undeclared, both
    // temp-dir keys fall back to ${java.io.tmpdir} and every cached Spring context boots a
    // ParquetScratchOrphanSweeper over the host's temp directory, deleting checkpoint-* /
    // batch-parquet-* files this run does not own — another worktree's suite among them. An
    // absolute path from Gradle rather than the relative default in application-test.yml, so it is
    // this build's directory whatever working directory the JVM is given, and `clean` removes
    // whatever a run leaves behind.
    systemProperty(
        "dfm.test.parquet-scratch-root",
        layout.buildDirectory.dir("test-scratch/parquet").get().asFile.absolutePath,
    )

    // The heap the suite runs on (issue #207). Gradle's default is 512 MB, and CI runs everything
    // in one such JVM — ~2470 tests, 444 classes, 24 cached Spring contexts held for the length of
    // the run — so the margin was whatever the runner happened to leave. When it ran out, the
    // OutOfMemoryError surfaced wherever the allocation was: inside Spring's ConstructorResolver,
    // reported as a BeanCreationException of a contract test that allocates nothing.
    //
    // 2 GiB is measured, not guessed. `./gradlew test -PtestHeapLog` at a deliberately generous
    // 3 GiB never let G1 expand past 1014 MB; the highest occupancy after a collection was 801 MB
    // and the highest before one 965 MB. So 1 GiB sits on the cliff, and the shipped default was
    // under it. TestJvmHeapCeilingTest holds this value inside an agreed range and fails if a
    // second, narrower declaration appears — one on `test` or `integrationTest` overrides this
    // ceiling for that task alone, so the value the guard checked is not the value that task runs
    // on. It also refuses a -Xmx passed through jvmArgs, which is the same override in a form no
    // guard can read; and its twin under src/test/java/com/bitbi/dfm/integration/ checks the JVM
    // the Testcontainers task is actually given, which nothing under config/ can observe.
    maxHeapSize = "2g"

    // ONE JVM, deliberately: no forkEvery and no maxParallelForks.
    //
    // forkEvery would bound the context accumulation by throwing the Spring TestContext cache away
    // — and that cache is exactly what makes 444 classes affordable, since a fresh JVM rebuilds
    // every context it needs (Flyway migration included) and restarts the Testcontainers
    // singletons. It is the right tool for accumulation that has no ceiling; this accumulation
    // does. It is one context per distinct configuration, a property of the test classes and not
    // of the test count, which is what the measurement above shows levelling off well under 1 GiB.
    //
    // maxParallelForks is excluded for a different reason: the suite deliberately shares one
    // PostgreSQL database across every context — `test-data.sql` deletes by `%.example.com`, the
    // delta-SQL queue is global (#175), and #197 had to bound lock waits because sibling contexts
    // already contend on the same rows. Parallel JVMs would make that contention the normal case.

    jvmArgs(
        // End the JVM on the first allocation failure instead of letting the error unwind into
        // whichever caller is on the stack, be caught and re-reported as something else. This is
        // what makes an OOM name itself; TestJvmOutOfMemoryExitTest proves both directions against
        // real child JVMs, including that an `OutOfMemoryError` a Mockito stub throws stays
        // catchable (BatchParquetFinalizationIntegrationTest asserts exactly that).
        "-XX:+ExitOnOutOfMemoryError",
        // It fires for every OutOfMemoryError the VM itself raises, not only "Java heap space":
        // "unable to create native thread" and "Metaspace" end the worker the same way, and every
        // remaining test result is lost with it. That is still better than the swallow — the build
        // says which error it was — but the remedy differs, and raising maxHeapSize makes native
        // thread exhaustion likelier rather than less likely, so read the message before acting.
        // An OutOfMemoryError a Mockito stub throws is not affected: it never reaches this path.
        //
        // The JVM is about to disappear, so the dump is the only evidence left for re-sizing.
        "-XX:+HeapDumpOnOutOfMemoryError",
        "-XX:HeapDumpPath=${layout.buildDirectory.dir("reports/test-oom").get().asFile.absolutePath}",
    )

    // Re-measure before moving the ceiling: `./gradlew test -PtestHeapLog` writes one GC log per
    // Test task under build/reports/test-heap/. Read the occupancy after a `Pause Cleanup` or a
    // `Pause Young (Mixed)` — that is the live set; the value before a collection is live set plus
    // whatever the run had allocated since the last one.
    if (project.hasProperty("testHeapLog")) {
        val gcLog = layout.buildDirectory.file("reports/test-heap/gc-$name.log").get().asFile
        jvmArgs("-Xlog:gc:file=${gcLog.absolutePath}:time,uptime")
    }

    // -XX:HeapDumpPath and -Xlog:file both need the directory to exist; the JVM will not create it,
    // and a dump that could not be written is the evidence lost at the one moment it is needed.
    // Resolved here rather than inside the action so nothing reaches for the project at execution.
    val oomDir = layout.buildDirectory.dir("reports/test-oom").get().asFile
    val heapLogDir = layout.buildDirectory.dir("reports/test-heap").get().asFile
    doFirst {
        oomDir.mkdirs()
        heapLogDir.mkdirs()
    }
}

// Per-task gate (pre-commit hook): `./gradlew test -PexcludeIntegration` runs unit + contract
// tests only, skipping the Testcontainers integration suite (fast, no Docker required).
// Default `./gradlew test` (CI) still runs everything.
tasks.named<Test>("test") {
    inputs.files("AGENTS.md", "CLAUDE.md")
        .withPathSensitivity(PathSensitivity.RELATIVE)
    inputs.dir("src/main/resources/db/migration")
        .withPathSensitivity(PathSensitivity.RELATIVE)
    // ParquetScratchOrphanSweeperTest asserts that the manifests declare the scratch pod-private
    // exactly while they mount it on the emptyDir (#141) — the configmap keys, the deployment's
    // volume, and no overlay quietly overriding either. Without this input a commit that touches
    // only k8s/ leaves `test` UP-TO-DATE and the guard never runs.
    inputs.dir("k8s")
        .withPathSensitivity(PathSensitivity.RELATIVE)

    if (project.hasProperty("excludeIntegration")) {
        exclude("**/integration/**")
    }
}

// Before-PR gate: only the Testcontainers integration suite.
tasks.register<Test>("integrationTest") {
    description = "Runs Testcontainers integration tests (src/test/java/**/integration/**)."
    group = "verification"
    testClassesDirs = sourceSets["test"].output.classesDirs
    classpath = sourceSets["test"].runtimeClasspath
    include("**/integration/**")
    shouldRunAfter(tasks.test)
}

tasks.jacocoTestReport {
    dependsOn(tasks.test)
    reports {
        xml.required = true
        html.required = true
    }
}

jacoco {
    toolVersion = "0.8.13"
}

tasks.jacocoTestCoverageVerification {
    violationRules {
        rule {
            limit {
                minimum = "0.80".toBigDecimal()
            }
        }
    }
}

protobuf {
    protoc {
        artifact = "com.google.protobuf:protoc:${property("protobufVersion")}"
    }
    plugins {
        create("grpc") {
            artifact = "io.grpc:protoc-gen-grpc-java:${property("grpcVersion")}"
        }
    }
    generateProtoTasks {
        all().forEach { task ->
            task.plugins {
                create("grpc")
            }
        }
    }
}

tasks.named<BootJar>("bootJar") {
    archiveFileName = "${project.name}.jar"
}

tasks.named<BootRun>("bootRun") {
    if(project.hasProperty("dev")) {
        systemProperty("spring.profiles.active", "dev")
        environment("AWS_S3_BUCKET_NAME", "dfm-uploads")
    }
}
