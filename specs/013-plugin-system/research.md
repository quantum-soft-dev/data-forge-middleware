# Research: Plugin System & Bit BI OAuth Integration

**Date**: 2025-12-16
**Feature**: 013-plugin-system
**Purpose**: Resolve technical unknowns before Phase 1 design

## 1. Auth0 Machine-to-Machine (M2M) vs Authorization Code Flow

### Decision: **Hybrid Approach - Authorization Code for User Consent, Client Credentials for API Access**

### Rationale

The spec describes two distinct interactions:
1. **User-initiated connection** (US1): Bit BI user clicks "Connect to DFM" and authenticates via Auth0
2. **Backend API access** (US2-US6): Bit BI server activates plugins, receives events

| Interaction | Flow | Token Contains |
|-------------|------|----------------|
| User consent | Authorization Code + PKCE | `accountId` claim (user's DFM account) |
| API activation | Client Credentials OR User token | `clientId` (Bit BI) + `accountId` (from user flow) |
| Event dispatch | Server-to-plugin (internal) | N/A - direct method call |

### Implementation Decision

**For v1**: Use **Authorization Code + PKCE** for all plugin API calls. The user token contains both:
- `accountId` claim - identifies which account to activate the plugin for
- `azp` (authorized party) claim - identifies the calling application (Bit BI)

This simplifies implementation because:
1. Existing `AuthorizationHelper.getAuthenticatedAccountId()` already extracts accountId from JWT
2. No need for separate M2M token management in Bit BI
3. User consent is implicitly verified (they must have logged in)

**Future enhancement**: Add M2M Client Credentials support when plugins need to make server-to-server calls without user context (e.g., bulk event processing).

### Security Configuration Pattern

```java
// Add to SecurityConfiguration.java - Order 3 (before admin filter)
@Bean
@Order(3)
public SecurityFilterChain pluginApiFilterChain(HttpSecurity http) throws Exception {
    return http
        .securityMatcher("/api/v1/plugins/**")
        .authorizeHttpRequests(auth -> auth
            .requestMatchers("/api/v1/plugins/*/activate").authenticated()
            .requestMatchers("/api/v1/plugins/*/deactivate").authenticated()
            .anyRequest().authenticated()
        )
        .oauth2ResourceServer(oauth2 -> oauth2
            .jwt(jwt -> jwt.jwtAuthenticationConverter(jwtAuthenticationConverter()))
        )
        .build();
}
```

### Alternatives Considered

| Alternative | Rejected Because |
|-------------|-----------------|
| M2M-only | Requires separate token management, loses user identity context |
| API Key | Less secure, no Auth0 integration, manual rotation |
| Mutual TLS | Complex setup, overkill for this use case |

---

## 2. Async Plugin Execution with Timeout and Isolation

### Decision: **CompletableFuture.orTimeout() with Dedicated Thread Pool**

### Rationale

Per FR-008: "plugin.execute() calls MUST timeout after 30 seconds" and "plugin execution failures must not affect other plugins or core system."

### Implementation Pattern

```java
@Configuration
@EnableAsync
public class PluginAsyncConfiguration {

    @Bean(name = "pluginExecutor")
    public Executor pluginExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(5);
        executor.setMaxPoolSize(10);
        executor.setQueueCapacity(50);
        executor.setThreadNamePrefix("plugin-exec-");
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        executor.initialize();
        return executor;
    }
}

// In PluginEventDispatcher
private CompletableFuture<Void> executePluginAsync(Plugin plugin, PluginEvent event) {
    return CompletableFuture.runAsync(() -> plugin.execute(event), pluginExecutor)
        .orTimeout(30, TimeUnit.SECONDS)
        .exceptionally(ex -> {
            if (ex instanceof TimeoutException) {
                log.error("Plugin {} timed out for event {}", plugin.getId(), event.getId());
            } else {
                log.error("Plugin {} failed: {}", plugin.getId(), ex.getMessage());
            }
            return null; // Swallow exception - isolation achieved
        });
}
```

### Key Design Points

1. **`orTimeout()`** (Java 9+): Completes future exceptionally with `TimeoutException` after 30s
2. **`exceptionally()`**: Catches all errors and returns null - prevents propagation
3. **`CallerRunsPolicy`**: Provides backpressure when queue is full without throwing exceptions
4. **Separate thread pool**: Isolates plugin execution from main application threads

### Alternatives Considered

| Alternative | Rejected Because |
|-------------|-----------------|
| `@Async` + `Future.get(timeout)` | Blocking, doesn't provide clean error handling |
| Virtual threads (Project Loom) | Spring Boot 3.5.x support still maturing |
| Reactive (Project Reactor) | Overkill for simple fire-and-forget events |

---

## 3. Plugin Registration Pattern

### Decision: **Spring Component Scanning with Interface + @Conditional**

### Rationale

Per spec assumption: "compile-time plugins (no runtime dynamic loading)" - Spring's component scanning is the natural fit.

### Implementation Pattern

```java
// Plugin interface
public interface Plugin {
    String getId();
    String getName();
    String getVersion();
    Set<PluginEventType> getSupportedEvents();
    JsonSchema getDataSchema();

    void execute(PluginEvent event);
    void onActivate(AccountPlugin activation);
    void onDeactivate(AccountPlugin activation);
}

// Bit BI implementation
@Component
@ConditionalOnProperty(name = "plugins.bitbi.enabled", havingValue = "true", matchIfMissing = true)
public class BitBiPlugin implements Plugin {
    @Override
    public String getId() { return "bit-bi"; }
    // ...
}

// Registry collects all plugins via constructor injection
@Component
public class PluginRegistry {
    private final Map<String, Plugin> plugins;

    public PluginRegistry(List<Plugin> pluginBeans) {
        this.plugins = pluginBeans.stream()
            .collect(Collectors.toMap(Plugin::getId, Function.identity()));
    }
}
```

### Startup Validation (SC-005: <100ms)

```java
@Component
public class PluginStartupValidator implements ApplicationRunner {
    @Override
    public void run(ApplicationArguments args) {
        long start = System.currentTimeMillis();
        // Validate all registered plugins have database configs
        long elapsed = System.currentTimeMillis() - start;
        if (elapsed > 100) {
            log.warn("Plugin registration exceeded 100ms target: {}ms", elapsed);
        }
    }
}
```

### Alternatives Considered

| Alternative | Rejected Because |
|-------------|-----------------|
| Java SPI (ServiceLoader) | No Spring DI support, manual wiring needed |
| OSGi | Massive complexity for simple compile-time plugins |
| Manual bean registration | Boilerplate, no auto-discovery |

---

## 4. JSONB Schema Validation

### Decision: **networknt json-schema-validator at Application Layer**

### Rationale

Per FR-004: "System MUST validate plugin-specific data against the plugin's defined schema before storing."

### Library Choice

```kotlin
// build.gradle.kts
implementation("com.networknt:json-schema-validator:1.5.4")
```

### Implementation Pattern

```java
@Service
public class PluginDataValidator {

    private final Map<String, JsonSchema> schemaCache = new ConcurrentHashMap<>();

    public void validate(String pluginId, Map<String, Object> data, Plugin plugin) {
        JsonSchema schema = schemaCache.computeIfAbsent(
            pluginId,
            id -> loadSchema(plugin.getSchemaJson())
        );

        Set<ValidationMessage> errors = schema.validate(objectMapper.valueToTree(data));

        if (!errors.isEmpty()) {
            throw new PluginDataValidationException(pluginId, errors);
        }
    }
}

// Bit BI schema example
public class BitBiPlugin implements Plugin {
    private static final String SCHEMA_JSON = """
        {
          "$schema": "http://json-schema.org/draft-07/schema#",
          "type": "object",
          "required": ["tenantId"],
          "properties": {
            "tenantId": {
              "type": "string",
              "minLength": 1,
              "maxLength": 64,
              "pattern": "^[a-zA-Z0-9-_]+$"
            }
          },
          "additionalProperties": false
        }
        """;
}
```

### Alternatives Considered

| Alternative | Rejected Because |
|-------------|-----------------|
| Hibernate Validator | Not designed for arbitrary JSON Schema |
| PostgreSQL pg_jsonschema | Requires extension, less flexible |
| Manual Java validation | Not scalable per plugin |

---

## 5. Request Body Hashing for Audit Logs

### Decision: **SHA-256 via Servlet Filter with ContentCachingRequestWrapper**

### Rationale

Per FR-014: "System MUST store audit log request bodies as hashed values (not plaintext) for privacy."

### Implementation Pattern

```java
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class PluginAuditFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(HttpServletRequest request,
            HttpServletResponse response, FilterChain filterChain) {

        ContentCachingRequestWrapper wrappedRequest =
            new ContentCachingRequestWrapper(request);
        ContentCachingResponseWrapper wrappedResponse =
            new ContentCachingResponseWrapper(response);

        try {
            filterChain.doFilter(wrappedRequest, wrappedResponse);
        } finally {
            logAuditEntry(wrappedRequest, wrappedResponse);
            wrappedResponse.copyBodyToResponse();
        }
    }

    private String computeSha256Hash(byte[] data) {
        if (data == null || data.length == 0) return null;
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        return Base64.getEncoder().encodeToString(digest.digest(data));
    }
}
```

### Queryability Features

While body content is hashed, audit logs remain useful:

1. **Reproduce hash**: If you have a suspected request body, hash it and compare
2. **Query by metadata**: Filter by `plugin_id`, `account_id`, `action`, `response_status`, `occurred_at`
3. **Body size stored**: Helps identify large requests without storing content

### Alternatives Considered

| Alternative | Rejected Because |
|-------------|-----------------|
| Store encrypted body | Increases storage, key management complexity |
| Store nothing | Loses audit capability entirely |
| Truncated body | Still exposes partial data, inconsistent |

---

## Summary of Research Decisions

| Topic | Decision | Key Library/Pattern |
|-------|----------|---------------------|
| Auth for plugin API | Authorization Code + PKCE (v1) | Spring Security OAuth2 Resource Server |
| Async execution | `CompletableFuture.orTimeout()` | Dedicated `ThreadPoolTaskExecutor` |
| Plugin registration | Component scanning + interface | Inject `List<Plugin>` |
| Schema validation | Application-layer JSON Schema | `networknt:json-schema-validator` |
| Audit hashing | SHA-256 via filter | `ContentCachingRequestWrapper` |

---

## Dependencies to Add

```kotlin
// build.gradle.kts
dependencies {
    // JSON Schema validation
    implementation("com.networknt:json-schema-validator:1.5.4")
}
```

No new dependencies needed for async (Java 21 built-in) or hashing (Java standard library).
