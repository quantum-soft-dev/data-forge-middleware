# Implementation Plan: Plugin System & Bit BI OAuth Integration

**Branch**: `013-plugin-system` | **Date**: 2025-12-16 | **Spec**: [spec.md](./spec.md)
**Input**: Feature specification from `/specs/013-plugin-system/spec.md`

## Summary

Implement an extensible plugin system for Data Forge Middleware that enables third-party applications (starting with Bit BI) to integrate via OAuth2 and receive domain events like batch completion notifications. The plugin system uses compile-time registration with runtime activation per account, storing plugin-specific data in JSONB columns, and dispatching events asynchronously to subscribed plugins with isolation guarantees.

## Technical Context

**Language/Version**: Java 21 (LTS) with Spring Boot 3.5.6
**Primary Dependencies**: Spring Security 6 (OAuth2 Resource Server + Auth0), Spring Data JPA, Spring Events (`ApplicationEventPublisher`), Hypersistence Utils (JSONB types)
**Storage**: PostgreSQL 16 (new tables: `plugin_configs`, `account_plugins`, `plugin_audit_logs` - partitioned)
**Testing**: JUnit 5 + Mockito (unit), Testcontainers PostgreSQL (integration), MockMvc (contract)
**Target Platform**: Linux server (Docker/ECS), Java 21 runtime
**Project Type**: Web (existing backend-only architecture, no frontend for v1)
**Performance Goals**:
- Plugin activation: <200ms (95th percentile) per SC-002
- Event dispatch: <500ms (95th percentile) per SC-003
- Startup plugin discovery: <100ms per SC-005
**Constraints**:
- Plugin execution timeout: 30 seconds per FR-008
- Isolated failures: Plugin errors must not propagate to core system per FR-008
- Audit log hashing: Request bodies stored as SHA-256 hashes per FR-014
**Scale/Scope**: Single plugin initially (Bit BI), designed for 10+ plugins future expansion

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

**Note**: The project constitution is not yet defined (template placeholders in constitution.md). Proceeding with standard DDD and Spring Boot best practices established in the existing codebase:

| Principle | Status | Implementation Approach |
|-----------|--------|------------------------|
| Domain-Driven Design | PASS | Plugin as new aggregate with PluginConfig, AccountPlugin, PluginAuditLog entities |
| Package-by-Layered-Feature | PASS | New `/plugin/` package with domain/application/infrastructure/presentation layers |
| Event-Driven Architecture | PASS | Subscribe to existing BatchCompletedEvent, dispatch to plugins via Spring async |
| Auth0 Integration | PASS | Leverage existing OAuth2 Resource Server, extend for M2M client credentials |
| Test-First Development | PASS | Contract tests → Integration tests → Unit tests |
| Structured Logging | PASS | MDC context with pluginId, accountId for plugin operations |

## Project Structure

### Documentation (this feature)

```text
specs/013-plugin-system/
├── plan.md              # This file (/speckit.plan command output)
├── research.md          # Phase 0 output (/speckit.plan command)
├── data-model.md        # Phase 1 output (/speckit.plan command)
├── quickstart.md        # Phase 1 output (/speckit.plan command)
├── contracts/           # Phase 1 output (/speckit.plan command)
└── tasks.md             # Phase 2 output (/speckit.tasks command)
```

### Source Code (repository root)

```text
src/main/java/com/bitbi/dfm/
├── plugin/                        # NEW: Plugin aggregate
│   ├── domain/
│   │   ├── Plugin.java            # Plugin interface (compile-time registration)
│   │   ├── PluginRegistry.java    # In-memory registry of discovered plugins
│   │   ├── PluginConfig.java      # Entity: Database-stored plugin configuration
│   │   ├── AccountPlugin.java     # Entity: Account-plugin activation record
│   │   ├── PluginAuditLog.java    # Entity: Audit trail for plugin operations
│   │   ├── PluginEvent.java       # Value object: Event dispatched to plugins
│   │   ├── PluginEventType.java   # Enum: BATCH_COMPLETED, BATCH_FAILED, etc.
│   │   ├── PluginConfigRepository.java
│   │   ├── AccountPluginRepository.java
│   │   └── PluginAuditLogRepository.java
│   ├── application/
│   │   ├── PluginActivationService.java     # Activation/deactivation logic
│   │   ├── PluginEventDispatcher.java       # Async event dispatch to plugins
│   │   ├── PluginAuditService.java          # Audit logging with hashing
│   │   └── BitBiPlugin.java                 # Bit BI plugin implementation
│   ├── infrastructure/
│   │   ├── persistence/
│   │   │   ├── JpaPluginConfigRepository.java
│   │   │   ├── JpaAccountPluginRepository.java
│   │   │   └── JpaPluginAuditLogRepository.java
│   │   └── events/
│   │       └── BatchEventListener.java      # Spring @EventListener for batch events
│   └── presentation/
│       ├── PluginController.java            # POST /activate, DELETE /deactivate
│       ├── AccountPluginsController.java    # GET /account/plugins
│       └── dto/
│           ├── ActivatePluginRequestDto.java
│           ├── PluginActivationResponseDto.java
│           └── AccountPluginListResponseDto.java
├── shared/
│   ├── domain/events/
│   │   └── BatchCompletedEvent.java        # EXISTING: Extended with accountId
│   └── config/
│       └── SecurityConfiguration.java       # MODIFY: Add /api/v1/plugins/** routes

src/main/resources/
├── db/migration/
│   ├── V8__create_plugin_tables.sql        # plugin_configs, account_plugins
│   └── V9__create_plugin_audit_logs_partitioned.sql  # Partitioned audit table

src/test/java/com/bitbi/dfm/plugin/
├── contract/
│   └── PluginContractTest.java
├── integration/
│   └── PluginIntegrationTest.java
└── unit/
    ├── PluginActivationServiceTest.java
    └── PluginEventDispatcherTest.java
```

**Structure Decision**: Follows existing Package-by-Layered-Feature pattern established in `/account/`, `/batch/`, `/comparison/` aggregates. The plugin system is a new aggregate with its own domain, application, infrastructure, and presentation layers.

## Complexity Tracking

> No constitution violations identified. The implementation follows established patterns.

| Decision | Rationale |
|----------|-----------|
| Compile-time plugins only | Simpler than OSGi/dynamic loading; sufficient for initial requirements; can evolve later |
| JSONB for plugin data | Flexible schema per plugin; PostgreSQL native; queryable via GIN index; existing pattern in comparison_results |
| Partitioned audit logs | Aligned with existing error_logs pattern; efficient for time-based queries and retention |
| Async event dispatch | Non-blocking; isolated failures; 30s timeout per plugin |
