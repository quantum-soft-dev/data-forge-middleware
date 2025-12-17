# Implementation Plan: Plugin System & Bit BI OAuth Integration

**Branch**: `013-plugin-system` | **Date**: 2025-12-17 | **Spec**: [spec.md](spec.md)
**Input**: Feature specification from `/specs/013-plugin-system/spec.md`

## Summary

Implement a plugin system enabling third-party applications to integrate with Data Forge Middleware via OAuth 2.0. The initial implementation supports Bit BI integration for batch completion notifications. The system uses compile-time registered plugins (Spring components) with runtime configuration stored in PostgreSQL, async event dispatch with 30-second timeouts, and comprehensive audit logging for admin visibility.

## Technical Context

**Language/Version**: Java 21 (LTS) with Spring Boot 3.5.6
**Primary Dependencies**: Spring Security 6 (OAuth2 Resource Server + Auth0), Spring Data JPA, Spring Events (`ApplicationEventPublisher`), Hypersistence Utils (JSONB types), json-schema-validator (1.5.4)
**Storage**: PostgreSQL 16 (new tables: `plugin_configs`, `account_plugins`, `plugin_audit_logs` - partitioned)
**Testing**: JUnit 5 + Mockito (unit), Testcontainers + MockMvc (integration/contract), WireMock (Auth0 mocking)
**Target Platform**: Linux server (AWS ECS)
**Project Type**: Single project (Spring Boot backend, existing DDD structure)
**Performance Goals**: <200ms activation (SC-002), <500ms event dispatch (SC-003), <100ms startup validation (SC-005)
**Constraints**: 30-second plugin execution timeout (FR-008), isolated plugin failures
**Scale/Scope**: ~1000 accounts, ~5 plugins initially, ~10K events/day

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

| Principle | Status | Evidence |
|-----------|--------|----------|
| Test-First | ✓ PASS | Test tasks added for each user story |
| Integration Testing | ✓ PASS | Contract tests, integration tests with Testcontainers |
| Simplicity | ✓ PASS | Compile-time plugins avoid dynamic loading complexity |

## Project Structure

### Documentation (this feature)

```text
specs/013-plugin-system/
├── plan.md              # This file
├── research.md          # Technical decisions and patterns
├── data-model.md        # Entity definitions and relationships
├── quickstart.md        # Integration scenarios
├── contracts/           # OpenAPI specification
│   └── plugin-api.yaml
├── checklists/          # Verification checklists
│   └── requirements.md
└── tasks.md             # Implementation tasks
```

### Source Code (repository root)

```text
src/main/java/com/bitbi/dfm/plugin/
├── domain/              # Entities, value objects, interfaces
│   ├── Plugin.java
│   ├── PluginConfig.java
│   ├── AccountPlugin.java
│   ├── PluginAuditLog.java
│   ├── PluginEvent.java
│   ├── PluginEventType.java
│   ├── PluginActionType.java
│   ├── PluginRegistry.java
│   ├── PluginConfigRepository.java
│   ├── AccountPluginRepository.java
│   ├── PluginAuditLogRepository.java
│   └── exception/
│       ├── PluginNotFoundException.java
│       ├── PluginNotEnabledException.java
│       ├── PluginNotActivatedException.java
│       └── PluginDataValidationException.java
├── application/         # Application services
│   ├── PluginActivationService.java
│   ├── PluginDataValidator.java
│   ├── PluginQueryService.java
│   ├── PluginEventDispatcher.java
│   ├── PluginAuditService.java
│   ├── PluginAdminQueryService.java
│   └── BitBiPlugin.java
├── infrastructure/      # JPA repositories, config, filters
│   ├── persistence/
│   │   ├── JpaPluginConfigRepository.java
│   │   ├── JpaAccountPluginRepository.java
│   │   └── JpaPluginAuditLogRepository.java
│   ├── events/
│   │   └── BatchEventListener.java
│   ├── PluginStartupValidator.java
│   ├── PluginAsyncConfiguration.java
│   └── PluginAuditFilter.java
└── presentation/        # Controllers and DTOs
    ├── PluginController.java
    ├── AccountPluginsController.java
    ├── PluginAdminController.java
    └── dto/
        ├── ActivatePluginRequestDto.java
        ├── PluginActivationResponseDto.java
        ├── AccountPluginSummaryDto.java
        ├── AccountPluginListResponseDto.java
        ├── PluginAuditLogEntryDto.java
        ├── PluginAuditLogPageResponseDto.java
        └── PluginConfigResponseDto.java

src/test/java/com/bitbi/dfm/plugin/
├── contract/            # API contract tests (MockMvc)
│   ├── PluginActivationContractTest.java
│   ├── PluginDeactivationContractTest.java
│   ├── AccountPluginsContractTest.java
│   └── PluginAdminContractTest.java
├── integration/         # Integration tests (Testcontainers)
│   ├── PluginActivationIntegrationTest.java
│   ├── PluginEventDispatchIntegrationTest.java
│   └── PluginAuditIntegrationTest.java
└── unit/                # Unit tests
    ├── PluginDataValidatorTest.java
    ├── PluginActivationServiceTest.java
    ├── PluginEventDispatcherTest.java
    ├── AccountPluginTest.java
    └── BitBiPluginTest.java

src/main/resources/db/migration/
├── V8__create_plugin_tables.sql
└── V9__create_plugin_audit_logs_partitioned.sql
```

**Structure Decision**: Single project with existing DDD package-by-layered-feature pattern. Plugin module follows same conventions as existing account, site, batch modules.

## Testing Strategy

### Test Pyramid

| Layer | Technology | Coverage Target | Purpose |
|-------|------------|-----------------|---------|
| Unit | JUnit 5 + Mockito | 80% line coverage | Business logic, validation, domain rules |
| Contract | MockMvc + @WebMvcTest | All API endpoints | Request/response format, status codes, error handling |
| Integration | Testcontainers (PostgreSQL) | Critical paths | Database operations, repository queries, transactions |

### Contract Tests (MockMvc)

Contract tests verify API contracts without full Spring context:

1. **PluginActivationContractTest**
   - `POST /api/v1/plugins/{pluginId}/activate` → 201 (new activation)
   - `POST /api/v1/plugins/{pluginId}/activate` → 200 (update existing)
   - `POST /api/v1/plugins/{pluginId}/activate` → 400 (invalid schema)
   - `POST /api/v1/plugins/{pluginId}/activate` → 404 (plugin not found)
   - `POST /api/v1/plugins/{pluginId}/activate` → 404 (plugin disabled)

2. **PluginDeactivationContractTest**
   - `DELETE /api/v1/plugins/{pluginId}/deactivate` → 204 (success)
   - `DELETE /api/v1/plugins/{pluginId}/deactivate` → 403 (not activated)
   - `DELETE /api/v1/plugins/{pluginId}/deactivate` → 404 (plugin not found)

3. **AccountPluginsContractTest**
   - `GET /api/v1/account/plugins` → 200 (list active)
   - `GET /api/v1/account/plugins?includeInactive=true` → 200 (list all)
   - `GET /api/v1/account/plugins` → empty list when no activations

4. **PluginAdminContractTest**
   - `GET /api/v1/admin/plugins` → 200 (list registered plugins)
   - `GET /api/v1/admin/plugins/audit` → 200 (paginated audit logs)
   - `GET /api/v1/admin/plugins/audit?pluginId=bit-bi` → 200 (filtered)
   - Unauthorized access → 403

### Integration Tests (Testcontainers)

Integration tests verify database operations with real PostgreSQL:

1. **PluginActivationIntegrationTest**
   - New activation creates AccountPlugin record
   - Update activation modifies existing record
   - Reactivation sets is_active=true, clears deactivated_at
   - JSON Schema validation rejects invalid pluginData
   - Concurrent activation requests handled correctly

2. **PluginEventDispatchIntegrationTest**
   - BatchCompletedEvent triggers plugin execution
   - Only active plugins receive events
   - Deactivated plugins do not receive events
   - Plugin timeout (30s) is enforced
   - Plugin failures are isolated (other plugins still execute)
   - last_used_at is updated on successful dispatch

3. **PluginAuditIntegrationTest**
   - Activation creates audit log entry
   - Deactivation creates audit log entry
   - Event dispatch creates audit log entry
   - Audit log query with filters works correctly
   - Partition pruning works for date-range queries

### Unit Tests

Unit tests verify business logic in isolation:

1. **PluginDataValidatorTest**
   - Valid tenantId passes validation
   - Missing tenantId fails validation
   - Invalid tenantId format fails validation
   - Schema caching works correctly

2. **PluginActivationServiceTest**
   - Activate calls plugin.onActivate()
   - Deactivate calls plugin.onDeactivate()
   - Plugin not found throws PluginNotFoundException
   - Plugin disabled throws PluginNotEnabledException
   - Already deactivated throws PluginNotActivatedException

3. **PluginEventDispatcherTest**
   - Dispatches to all subscribed plugins
   - Respects plugin event type subscriptions
   - Handles plugin exceptions gracefully
   - Enforces 30-second timeout

4. **AccountPluginTest**
   - activate() sets correct timestamps
   - deactivate() sets deactivated_at
   - reactivate() clears deactivated_at
   - updatePluginData() updates data and timestamp
   - recordUsage() updates last_used_at

5. **BitBiPluginTest**
   - Returns correct plugin ID
   - Returns correct supported events
   - Schema validates valid tenantId
   - onActivate logs activation
   - execute handles BATCH_COMPLETED event

## Performance Validation

Per SC-002, SC-003, SC-005:

| Criterion | Target | Test Method |
|-----------|--------|-------------|
| SC-002: Activation | <200ms p95 | JMH microbenchmark or timed integration test |
| SC-003: Event dispatch | <500ms | Timed integration test with 5 active plugins |
| SC-005: Startup validation | <100ms per plugin | Timed ApplicationRunner test |

## Complexity Tracking

| Item | Justification | Simpler Alternative Rejected |
|------|---------------|------------------------------|
| JSONB for pluginData | Schema-validated, queryable | Plain VARCHAR loses type safety |
| Partitioned audit logs | Required for query performance at scale | Single table would slow down over time |
| Compile-time plugins | Safer than dynamic loading, easier testing | Runtime loading adds security risks |
