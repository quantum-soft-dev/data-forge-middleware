# Research: Plugin History Management

**Date**: 2026-01-01
**Feature**: 014-plugin-history

## Research Topics

### 1. Entity Extension Strategy

**Decision**: Add `superseded` boolean and `supersededBy` UUID to PluginSqlGeneration entity

**Rationale**:
- Current entity has no versioning fields - clean addition
- Self-referential foreign key allows tracking regeneration chain
- Boolean flag enables simple filtering of active vs superseded records

**Alternatives Considered**:
- Separate version table: Rejected - adds complexity, current entity is small
- Soft delete flag: Rejected - doesn't capture regeneration relationship
- Status enum: Rejected - only two states needed (active/superseded)

---

### 2. Pagination Approach for SQL Content

**Decision**: Use offset-based pagination with 100 statements per page

**Rationale**:
- Consistent with existing admin endpoints (PluginAdminController uses PageRequest)
- SQL statements are delimiter-separated (`--- END OF COMMAND ---`), easy to split and paginate
- 100 statements provides good balance of content visibility vs page load

**Alternatives Considered**:
- Cursor-based pagination: Rejected - not needed for admin tooling, adds complexity
- Infinite scroll: Rejected - less suitable for administrative review workflows

---

### 3. Bulk S3 Deletion Strategy

**Decision**: Sequential deletion with failure collection, best-effort approach

**Rationale**:
- S3SqlFileStorageService.deleteFile() already exists for single file deletion
- AWS SDK doesn't provide atomic multi-delete guarantees anyway
- Collecting failures and logging allows manual cleanup if needed
- Matches assumption in spec: "partial failures are logged but don't block"

**Alternatives Considered**:
- S3 DeleteObjects batch API: Could add for performance, but adds complexity
- Transaction-like rollback: Rejected - S3 is eventually consistent, not transactional

---

### 4. New Audit Action Types

**Decision**: Add three new action types to PluginActionType enum

| Action Type | Description |
|-------------|-------------|
| `PLUGIN_HISTORY_CLEARED` | Admin cleared all history for account-plugin |
| `SQL_REGENERATION_STARTED` | Regeneration initiated for batch |
| `SQL_REGENERATION_COMPLETED` | Regeneration finished successfully |
| `SQL_REGENERATION_FAILED` | Regeneration failed |

**Rationale**:
- Extends existing enum pattern
- Separate start/complete/fail for regeneration (matches SQL_GENERATION_* pattern)
- PLUGIN_HISTORY_CLEARED is single action (clear is synchronous, doesn't need start/complete)

---

### 5. Confirmation Mechanism for Clear Operation

**Decision**: Two-step process with GET summary + DELETE confirmation

**Rationale**:
- GET `/api/v1/admin/plugins/{pluginId}/accounts/{accountId}/history/summary` returns what will be deleted
- DELETE `/api/v1/admin/plugins/{pluginId}/accounts/{accountId}/history` requires `?confirm=true` query param
- Frontend shows confirmation dialog with summary before calling DELETE
- Simpler than token-based confirmation, sufficient for admin-only operation

**Alternatives Considered**:
- Confirmation token with TTL: Rejected - overkill for internal admin tool
- POST with confirmation body: Rejected - DELETE is more RESTful for this operation

---

### 6. SQL Content Parsing for Pagination

**Decision**: Parse SQL file by `--- END OF COMMAND ---` delimiter

**Rationale**:
- SqlStatementGenerator already uses this delimiter consistently
- Easy to split and count statements
- Each "statement" maps to one CSV row operation (INSERT/UPDATE/DELETE)

**Implementation**:
```java
String[] statements = sqlContent.split("--- END OF COMMAND[^-]*---");
int totalPages = (int) Math.ceil((double) statements.length / pageSize);
List<String> pageContent = Arrays.asList(statements).subList(startIndex, endIndex);
```

---

### 7. Syntax Highlighting Approach

**Decision**: Return plain SQL text; frontend applies highlighting

**Rationale**:
- Backend returns raw SQL with `Content-Type: text/plain` or structured JSON
- Frontend uses existing syntax highlighting library (e.g., Prism.js, Shiki)
- Separation of concerns - backend doesn't need to know about UI rendering
- Smaller response payload

**Alternatives Considered**:
- Backend HTML generation: Rejected - couples backend to presentation layer
- Pre-highlighted storage: Rejected - doubles storage, inflexible

---

## Existing Code References

| Component | Path | Notes |
|-----------|------|-------|
| PluginSqlGeneration | `src/main/java/.../plugin/domain/PluginSqlGeneration.java` | Add superseded fields |
| PluginAdminController | `src/main/java/.../plugin/presentation/PluginAdminController.java` | Extend with new endpoints |
| S3SqlFileStorageService | `src/main/java/.../plugin/infrastructure/storage/S3SqlFileStorageService.java` | Has deleteFile() |
| PluginActionType | `src/main/java/.../plugin/domain/PluginActionType.java` | Add new action types |
| SqlStatementGenerator | `src/main/java/.../plugin/application/SqlStatementGenerator.java` | Uses `--- END OF COMMAND ---` |
| Latest migration | `V13__add_sql_generation_action_types.sql` | Next: V14 |

## Dependencies

No new external dependencies required. All functionality uses existing:
- Spring Data JPA (pagination)
- AWS SDK v2 (S3 operations)
- Existing plugin infrastructure
