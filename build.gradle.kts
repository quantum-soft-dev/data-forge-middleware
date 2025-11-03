import org.springframework.boot.gradle.tasks.bundling.BootJar

plugins {
    java
    id("org.springframework.boot") version "3.5.6"
    id("io.spring.dependency-management") version "1.1.7"
    jacoco
}

group = "com.bitbi"
version = "0.0.1-SNAPSHOT"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
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

extra["testcontainersVersion"] = "1.20.2"
extra["awsSdkVersion"] = "2.28.11"

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

    // OAuth2 Resource Server (for Keycloak integration)
    implementation("org.springframework.boot:spring-boot-starter-oauth2-resource-server")

    // Keycloak Admin Client
    implementation("org.keycloak:keycloak-admin-client:26.0.7")

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

    // Lombok
    compileOnly("org.projectlombok:lombok")
    annotationProcessor("org.projectlombok:lombok")

    // Test Dependencies
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.security:spring-security-test")
    testImplementation(platform("org.testcontainers:testcontainers-bom:${property("testcontainersVersion")}"))
    testImplementation("org.testcontainers:testcontainers")
    testImplementation("org.testcontainers:junit-jupiter")
    testImplementation("org.testcontainers:postgresql")
    testImplementation("org.testcontainers:localstack")
    testImplementation("org.awaitility:awaitility:4.2.2")
}


tasks.withType<Test> {
    useJUnitPlatform()
    finalizedBy(tasks.jacocoTestReport)
}

tasks.jacocoTestReport {
    dependsOn(tasks.test)
    reports {
        xml.required = true
        html.required = true
    }
}

jacoco {
    toolVersion = "0.8.12"
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

tasks.named<BootJar>("bootJar") {
    archiveFileName = "${project.name}.jar"
}
