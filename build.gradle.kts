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
    // exactly while they mount it on the emptyDir (#141). Without these inputs a commit that
    // touches only k8s/ leaves `test` UP-TO-DATE and the guard never runs.
    inputs.files("k8s/base/configmap.yaml")
        .withPathSensitivity(PathSensitivity.RELATIVE)
    inputs.dir("k8s/overlays")
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
