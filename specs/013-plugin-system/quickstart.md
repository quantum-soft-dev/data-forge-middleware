# Quickstart: Plugin System & Bit BI OAuth Integration

**Date**: 2025-12-16
**Feature**: 013-plugin-system
**Audience**: Developers implementing the plugin system

## Prerequisites

- Java 21 JDK installed
- Docker and Docker Compose for local services
- Auth0 account configured (see existing setup in CLAUDE.md)
- Understanding of existing codebase patterns (DDD, Spring Boot)

## Getting Started

### 1. Run Database Migrations

After implementing V8 and V9 migrations, apply them:

```bash
./gradlew flywayMigrate
```

### 2. Configure Bit BI Plugin

Add to `application.yml`:

```yaml
plugins:
  bitbi:
    enabled: true
    client-id: ${BITBI_CLIENT_ID}  # From Auth0 M2M application
```

### 3. Seed Plugin Configuration

The V8 migration includes seed data:

```sql
INSERT INTO plugin_configs (plugin_id, client_id, display_name, is_enabled, config)
VALUES ('bit-bi', '${BITBI_CLIENT_ID}', 'Bit BI', true, '{}');
```

### 4. Test Plugin Activation

```bash
# Get OAuth token (user token with accountId claim)
TOKEN=$(curl -s -X POST "https://${AUTH0_DOMAIN}/oauth/token" \
  -H "Content-Type: application/json" \
  -d '{
    "client_id": "...",
    "client_secret": "...",
    "audience": "https://api.dataforge.com",
    "grant_type": "client_credentials"
  }' | jq -r '.access_token')

# Activate plugin
curl -X POST "http://localhost:8080/api/v1/plugins/bit-bi/activate" \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{
    "pluginData": {
      "tenantId": "test-tenant-123"
    }
  }'
```

Expected response (201 Created):
```json
{
  "pluginId": "bit-bi",
  "pluginName": "Bit BI",
  "accountId": "550e8400-e29b-41d4-a716-446655440000",
  "isActive": true,
  "activatedAt": "2025-12-16T10:30:00Z",
  "lastUsedAt": null
}
```

## Key Implementation Files

### Domain Layer

| File | Purpose |
|------|---------|
| `plugin/domain/Plugin.java` | Plugin interface - implement for each plugin |
| `plugin/domain/PluginConfig.java` | Database entity for plugin configuration |
| `plugin/domain/AccountPlugin.java` | Entity linking accounts to plugins |
| `plugin/domain/PluginAuditLog.java` | Audit trail entity |
| `plugin/domain/PluginEvent.java` | Value object for events dispatched to plugins |

### Application Layer

| File | Purpose |
|------|---------|
| `plugin/application/PluginActivationService.java` | Activation/deactivation logic |
| `plugin/application/PluginEventDispatcher.java` | Async event dispatch to plugins |
| `plugin/application/PluginDataValidator.java` | JSON Schema validation |
| `plugin/application/BitBiPlugin.java` | Bit BI plugin implementation |

### Infrastructure Layer

| File | Purpose |
|------|---------|
| `plugin/infrastructure/persistence/JpaAccountPluginRepository.java` | JPA repository |
| `plugin/infrastructure/events/BatchEventListener.java` | Spring event listener |
| `plugin/infrastructure/PluginAuditFilter.java` | Servlet filter for audit logging |

### Presentation Layer

| File | Purpose |
|------|---------|
| `plugin/presentation/PluginController.java` | REST endpoints |
| `plugin/presentation/dto/*` | Request/response DTOs |

## Creating a New Plugin

### Step 1: Implement Plugin Interface

```java
@Component
@ConditionalOnProperty(name = "plugins.my-plugin.enabled", havingValue = "true")
public class MyPlugin implements Plugin {

    private static final String ID = "my-plugin";
    private static final String SCHEMA_JSON = """
        {
          "$schema": "http://json-schema.org/draft-07/schema#",
          "type": "object",
          "required": ["apiKey"],
          "properties": {
            "apiKey": { "type": "string", "minLength": 10 }
          }
        }
        """;

    @Override
    public String getId() { return ID; }

    @Override
    public String getName() { return "My Plugin"; }

    @Override
    public String getVersion() { return "1.0.0"; }

    @Override
    public Set<PluginEventType> getSupportedEvents() {
        return Set.of(PluginEventType.BATCH_COMPLETED);
    }

    @Override
    public String getSchemaJson() { return SCHEMA_JSON; }

    @Override
    public void execute(PluginEvent event) {
        log.info("MyPlugin received event: {}", event);
        // Handle event
    }

    @Override
    public void onActivate(AccountPlugin activation) {
        log.info("MyPlugin activated for account: {}", activation.getAccountId());
    }

    @Override
    public void onDeactivate(AccountPlugin activation) {
        log.info("MyPlugin deactivated for account: {}", activation.getAccountId());
    }
}
```

### Step 2: Add Database Configuration

```sql
-- Add to migration or seed data
INSERT INTO plugin_configs (plugin_id, client_id, display_name, is_enabled)
VALUES ('my-plugin', 'my-plugin-client-id', 'My Plugin', true);
```

### Step 3: Configure Application Properties

```yaml
plugins:
  my-plugin:
    enabled: true
    client-id: ${MY_PLUGIN_CLIENT_ID}
```

### Step 4: Register in Auth0

1. Create M2M application in Auth0 for the plugin
2. Authorize it to call DFM API with `plugins:write` scope
3. Configure client_id in DFM

## Testing

### Contract Tests

```java
@WebMvcTest(PluginController.class)
class PluginContractTest {

    @Test
    void activatePlugin_validData_returns201() {
        // Given
        var request = Map.of("pluginData", Map.of("tenantId", "test-123"));

        // When/Then
        mockMvc.perform(post("/api/v1/plugins/bit-bi/activate")
                .with(jwt().jwt(jwt -> jwt.claim("accountId", ACCOUNT_ID)))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.pluginId").value("bit-bi"))
            .andExpect(jsonPath("$.isActive").value(true));
    }
}
```

### Integration Tests

```java
@SpringBootTest
@Testcontainers
class PluginIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16");

    @Test
    void batchCompleted_dispatchchesToActivePlugins() {
        // Given: Plugin activated for account
        activationService.activate(accountId, "bit-bi", Map.of("tenantId", "t123"));

        // When: Batch completes
        eventPublisher.publishEvent(new BatchCompletedEvent(batchId, 10, 1024L));

        // Then: Plugin receives event
        verify(bitBiPlugin, timeout(5000)).execute(any(PluginEvent.class));
    }
}
```

## Event Flow

```
1. BatchLifecycleService.completeBatch()
   ↓ publishes
2. BatchCompletedEvent
   ↓ caught by
3. BatchEventListener.onBatchCompleted()
   ↓ creates
4. PluginEvent
   ↓ dispatched by
5. PluginEventDispatcher.dispatchEvent()
   ↓ async calls
6. Plugin.execute() with 30s timeout
   ↓ logged to
7. PluginAuditLog (EVENT_DISPATCHED or EVENT_FAILED)
```

## Security Configuration

Add to `SecurityConfiguration.java`:

```java
@Bean
@Order(3) // Before adminApiFilterChain (Order 3)
public SecurityFilterChain pluginApiFilterChain(HttpSecurity http) throws Exception {
    return http
        .securityMatcher("/api/v1/plugins/**")
        .csrf(csrf -> csrf.disable())
        .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
        .authorizeHttpRequests(auth -> auth
            .anyRequest().authenticated()
        )
        .oauth2ResourceServer(oauth2 -> oauth2
            .jwt(jwt -> jwt.jwtAuthenticationConverter(jwtAuthenticationConverter()))
        )
        .build();
}
```

## Troubleshooting

### Plugin Not Found (404)

1. Check `plugin_configs` table has entry for plugin ID
2. Verify `is_enabled = true`
3. Ensure Plugin bean is registered (check logs for "Registered plugins: [...]")

### Validation Error (400)

1. Compare request body against plugin's JSON Schema
2. Check `getSchemaJson()` returns valid JSON Schema draft-07

### Account Not Found (403)

1. Verify JWT contains `accountId` claim (check Auth0 Action configuration)
2. Ensure account exists in `accounts` table
3. Check `AuthorizationHelper.getAuthenticatedAccountId()` logic

### Events Not Dispatched

1. Verify `account_plugins.is_active = true`
2. Check plugin supports the event type (`getSupportedEvents()`)
3. Look for errors in `plugin_audit_logs` table
4. Check async executor logs for timeout/exception

## Performance Tuning

### Thread Pool Configuration

```yaml
# application.yml
spring:
  task:
    execution:
      pool:
        core-size: 5
        max-size: 10
        queue-capacity: 50
      thread-name-prefix: plugin-exec-
```

### Database Indexes

Ensure these indexes exist for optimal performance:

```sql
-- Fast lookup for event dispatch
CREATE INDEX idx_account_plugins_active ON account_plugins(plugin_id)
  WHERE is_active = true;

-- Fast audit log queries
CREATE INDEX idx_plugin_audit_occurred ON plugin_audit_logs(occurred_at DESC);
```
