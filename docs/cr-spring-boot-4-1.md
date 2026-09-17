# CR: Spring Boot 4.1.1 (Framework 7, Security 7, Hibernate 7.4, Jackson 3)

**Status:** implemented in `migration/spring-boot-4.1` · **Date:** 2026-09-17 · **Issue:** #302
(migration umbrella #298; prepared by #299, #300, #301, #313; follow-ups #303, #304, landing #305)

## Why

Free support for Spring Boot 3.5 ended on 2026-06-30. 4.0.x ends on 2026-12-31, 4.1.x on
2027-07-31, so the target is **4.1.1**: Framework 7.0.9, Security 7.1.1, Data 2026.0.1, Hibernate
7.4.5, Jackson 3.1.5, Flyway 12.4, Micrometer 1.17.1, JUnit 6.0.3, Tomcat 11, HikariCP 7.0.2,
Testcontainers 2.0.5.

The step is **atomic**: no intermediate state compiles and passes. Everything that could be done
beforehand was — Boot 3.5.16 with no deprecated API (#299), characterization tests of every JSON
surface Jackson 3 can move (#300), a single-version guard for gRPC/protobuf (#301), gRPC 1.83.1 (#313).

## What changed

### Build (`build.gradle.kts`)

| Before | After |
|---|---|
| `spring-boot-starter-web` | `spring-boot-starter-webmvc` |
| `spring-boot-starter-oauth2-resource-server` | `spring-boot-starter-security-oauth2-resource-server` |
| `flyway-core` | `spring-boot-starter-flyway` — **without the starter Boot 4 does not run migrations at all** |
| `spring-boot-starter-test` + `spring-security-test` | `-webmvc-test`, `-security-test`, `-data-jpa-test`, `-micrometer-metrics-test` |
| caffeine 3.1.8, testcontainers 2.0.3, awaitility 4.2.2 | versions from the Boot BOM |
| `org.springframework.retry:spring-retry` | removed — Spring Framework's own retry |
| springdoc 2.8.3 | 3.1.1 |
| `hypersistence-utils-hibernate-63` 3.9.0 | `hypersistence-utils-hibernate-73` 3.15.5 |
| `org.apache.tomcat:annotations-api` (compileOnly) | removed — generated stubs no longer reference `javax.annotation.Generated` |

**protobuf stays 3.25.9** through the BOM property `extra["protobuf-java.version"]`, not only the
direct dependency: Boot 4.1 manages protobuf 4.35.1 (through Spring gRPC) and
`io.spring.dependency-management` lets an explicit version win only on a direct dependency, so a
transitive `protobuf-java*` would otherwise follow the BOM. `grpc-protobuf` 1.83.1 needs 3.25.x;
`GrpcArtifactVersionConsistencyTest` (#301) stays green. Protobuf 4 is #304. gRPC 1.83.1 already equals
the BOM (#313).

**The protobuf Gradle plugin is now configured by Boot.** Boot 4.1's Gradle plugin reacts to
`com.google.protobuf`: it gives `protoc` a version-less artifact and, when a `grpc` plugin is
declared, gives it a version-less `protoc-gen-grpc-java` and adds it to every generate task with
`@generated=omit`. Two consequences for our block:

- the `generateProtoTasks { all() { plugins { create("grpc") } } }` we carried is gone — a second
  registration fails configuration with *"Cannot add a PluginOptions with name 'grpc' as a
  PluginOptions with that name already exists"*;
- the version-less coordinates are meant to align with the resolved `protobuf-java`/`grpc-util`, but
  with `protobuf-java` a direct dependency they resolved to no version (*"Could not find
  com.google.protobuf:protoc:."*), so `protoc` and the `grpc` plugin stay pinned to the same
  properties as the runtime artifacts. The plugin's artifact is set in a `configureEach` registered
  after Boot's, so it wins.

`spring-boot-properties-migrator` was added for the upgrade and **removed before merge**. Its report
was read for every profile (`default`, `dev`, `prod`, `test`) with an empty application context:
no renamed key. A positive control (`spring.redis.host`) was reported in each, so the silence is a
result, not a migrator that did not run.

### Package moves

- `Health`/`HealthIndicator` → `org.springframework.boot.health.contributor` (`S3HealthIndicator`);
- `@AutoConfigureMockMvc` → `org.springframework.boot.webmvc.test.autoconfigure` (`BaseIntegrationTest`);
- `@AutoConfigureObservability` → `@AutoConfigureMetrics`
  (`org.springframework.boot.micrometer.metrics.test.autoconfigure`, `MetricsScrapeContractTest`);
- `DefaultBootstrapContext` → `org.springframework.boot.bootstrap` (`IsolatedEnvironments`);
- `NoResourceFoundException` takes a third argument, the resource path (`GlobalExceptionHandlerTest`).

`WebMvcEndpointHandlerMapping` needed nothing: #299 deleted `ActuatorConfiguration`.

### Retry

Spring Retry is not managed by Boot 4 and its repository is archived. `Auth0Configuration` carries
`@EnableResilientMethods` and `AccountSyncService.createAccount` uses
`org.springframework.resilience.annotation.@Retryable(includes = Auth0ServiceUnavailableException,
maxRetries = 2, delay = 1000, multiplier = 2.0)`. The spelling differs — `maxAttempts = 3` counted the
first call, `maxRetries` does not — so the behaviour is pinned rather than the attributes:
`AccountSyncServiceRetryTest` drives the real proxy and requires three calls 1 s then 2 s apart, and a
single call for any other failure. Mutations: removing `@EnableResilientMethods`, or `maxRetries = 1`,
each turn it red. The advisor order is unchanged: both `@EnableRetry` and `@EnableResilientMethods`
default to `LOWEST_PRECEDENCE - 1`, so the retry still wraps the transaction and each attempt is a
transaction of its own.

### Jackson 3

- `com.fasterxml.jackson.{databind,core}` → `tools.jackson.*` in 10 main and 18 test files;
  `com.fasterxml.jackson.annotation.*` stays. `JsonProcessingException` → `JacksonException`
  (unchecked). Every production call site already caught `Exception` or its Jackson exception, so no
  `catch (IOException)` silently stopped catching a parse failure.
- **HTTP API unchanged:** `spring.jackson.use-jackson2-defaults: true`. Every #300 wire-contract and
  request-acceptance test is green **without a changed expectation** — including the derived `"empty"`
  member of the Bit BI list DTOs, `changePercentage` of `ComparisonSummaryDto`, explicit `null`s and
  the `null`-for-primitive acceptance of `ManualSqlGenerationRequestDto.forceFullGeneration`. Dropping
  the Jackson 2 defaults is #303.
- `JacksonConfiguration` no longer declares a Jackson 2 `ObjectMapper` (Boot 4 would not use it for
  HTTP); it contributes a `JsonMapperBuilderCustomizer` with the same `Include.ALWAYS`.
- **`PluginDataValidator` stays on Jackson 2**, with a mapper of its own instead of an injected one:
  json-schema-validator 1.5.x validates Jackson 2 `JsonNode`s and there is no Jackson 2 mapper in the
  context any more. Jackson 2 stays on the classpath anyway (swagger-core, Avro, json-schema-validator,
  Auth0, jjwt).

### JSONB (hypersistence-utils 3.15 on Jackson 3)

Two library defaults changed, and both had to be set back explicitly:

1. **The mapper.** Left alone, hypersistence-utils-hibernate-73 builds
   `JsonMapper.builder().findAndAddModules()` — Jackson 3 defaults, under which an `Instant` in a
   free-form JSONB map would be stored as an ISO string next to rows holding epoch seconds.
   `HypersistenceJsonMapperSupplier` builds `JsonMapper.builderWithJackson2Defaults().findAndAddModules()`
   and is registered in `src/main/resources/hypersistence-utils.properties`, where the library looks
   for it. `JsonbColumnCharacterizationIntegrationTest` (#300) holds all seven columns both ways,
   unchanged — stored form, historical documents, the unknown member that fails a `stats` load, and
   the missing/`null` member read as `0`.
2. **Dirty-checking snapshots.** 3.15's default `JsonSerializer` copies an attribute by Java
   serialization and throws `NonSerializableObjectException` for anything else, where 3.9 fell back
   to a JSON copy. `changelog_segments.stats` is `Map<String, TableChangeStats>`, so **every load of a
   segment with stats failed** — most of the 71 integration failures of the first Boot 4 run.
   `TableChangeStats` implements `Serializable`. The other six columns hold JDK types only.

Rejected: a custom `JsonSerializer` doing the old JSON copy — it needs the attribute's generic type to
copy a `Map<String, TableChangeStats>` into the same types, otherwise the snapshot never equals the
entity and every flush writes the row.

### Redis cache

`GenericJackson2JsonRedisSerializer` → `GenericJacksonJsonRedisSerializer` with default typing and
Spring's cache null marker, as before, and **every key prefixed with `jackson3:`**
(`CacheConfiguration.KEY_PREFIX`) so old and new pods sharing Redis during a rolling deployment never
read each other's entries. Default typing admits `com.bitbi.dfm.*` and `java.*` subtypes only, where
the Jackson 2 serializer accepted anything.

Two #300 expectations changed, deliberately: keys carry the prefix, and `BatchDetailDto` — which the
Jackson 2 serializer could not write (no JSR-310) — now round-trips. The stored form of the other
values is byte-for-byte what it was. What production caches is unchanged: both caches remain inert for
the reasons #319 records. Two tests were added: an entry in the old key space is not read (mutation:
dropping `computePrefixWith` reddens four tests), and the reads now wait for the value, because with
Spring Data Redis 4 `Cache#put` can return before the value is readable on another connection.

### Spring Security 7

`JwtAuthenticationConverter` adds a `FACTOR_BEARER` authority to every bearer-token authentication.
Nothing in production reads the authority set (roles are checked with `hasRole`), and
`TestSecurityConfigTest` now pins exactly `{ROLE_x, FACTOR_BEARER}` instead of "one authority".

### OpenAPI (springdoc 3)

`OpenApiDocsContractTest` is new: `/api-docs` serves an OpenAPI 3.1 document describing the
application's routes, `/swagger-ui.html` redirects and the UI is served — nothing requested either
before. The test profile's security now permits `/swagger-ui.html`, as production already did. The
document was dumped on Boot 3.5.16 and on 4.1.1 and compared after key sorting: **paths, operations
and schema names are identical**, and so is everything outside `components`. Inside schemas
springdoc 3 / swagger-core 2.2.55 describe more precisely: nullable types as `["string", "null"]`,
`example` values in their declared type (the cron-schedule example read `0` and now reads
`"0 0 2 * * *"`), `minLength: 1` and `format: email` from Bean Validation, `additionalProperties: {}`
for `Object`, a typed boolean `default`. The frontend's Zod schemas are hand-written, so nothing is
generated from the document.

## Guard tests named by the ticket

`ScheduledTaskIsolationTest`, `AsyncExecutorQualifierTest`, `BackgroundConnectionDemandTest`,
`MetricsScrapeContractTest`, `LockWaitBound*` and `GrpcArtifactVersionConsistencyTest` are green
without a change: the auto-configured scheduler, the `applicationTaskExecutor` back-off, the metric
names and HikariCP's init SQL behave as they did.

## Verified

- `./gradlew test -PexcludeIntegration` and `./gradlew integrationTest` green.
- `integrationTest` green with `TZ=UTC` and with `TZ=Asia/Jerusalem` (#280/#282/#286 on Hibernate 7.4).
- Flyway applies every migration to a clean database: the integration suite boots.
- Delta gRPC contract tests green; `/actuator/prometheus` serves the delta counters.

## Rejected

- **`spring-boot-jackson2` as a permanent answer** — deprecated, removed in Boot 4.3.
- **`hypersistence-utils-hibernate-71`** (Jackson 2) — supports Hibernate 7.1/7.2; Boot 4.1 ships 7.4.
- **Stopping at Boot 4.0** — support ends 2026-12-31.
- **Spring gRPC's starter** — its default port is 9090, the port of our own server.

No REST route, gRPC contract, `delta-ingestion.proto`, DTO, Flyway migration (**V58 stays free**),
configuration key, metric name, S3 key or frontend change.
