# Research: Global Error Handling

**Feature**: 016-global-error-handling
**Date**: 2026-01-11

## Research Tasks Completed

### 1. Existing Error Domain Architecture

**Decision**: Extend existing `error_logs` table with `severity` and `is_read` columns

**Rationale**:
- Existing table already supports standalone errors (`batch_id = NULL`)
- Monthly partitioning by `occurred_at` already optimized for time-based queries
- Existing indexes (site_id, batch_id, type, occurred_at, metadata GIN) cover query patterns
- Reusing existing DTO patterns (records with `fromEntity()`)
- Existing validation (`@ValidMetadata`) handles metadata constraints

**Alternatives Considered**:
- Separate `global_errors` table: Rejected - duplicates infrastructure, complicates queries
- View over `error_logs`: Rejected - cannot add `is_read` field to view

### 2. Migration Strategy for Partitioned Table

**Decision**: Use `ALTER TABLE ... ADD COLUMN` with DEFAULT values

**Rationale**:
- PostgreSQL 11+ supports adding columns with DEFAULT without full table rewrite
- Partitioned table: migration applies to parent, inherited by all partitions
- `severity VARCHAR(20) DEFAULT 'ERROR'` - lightweight, string-based for flexibility
- `is_read BOOLEAN DEFAULT true` - existing errors marked as read (per clarification)

**Alternatives Considered**:
- Enum type for severity: Rejected - requires migration for new values, string sufficient
- Create new partitions only: Rejected - need backwards compatibility for existing data

### 3. Authorization Model for User-Facing API

**Decision**: Account-based access via Auth0 JWT claims

**Rationale**:
- Existing pattern: `https://api.dataforge.com/accountId` claim in JWT
- User API (`/api/v1/account/**`) uses OAuth2 (Auth0) not custom JWT
- Query errors where site belongs to user's account
- Consistent with plugin logs pattern (`/api/v1/account/plugins/{pluginId}/logs`)

**Query Pattern**:
```sql
SELECT e.* FROM error_logs e
JOIN sites s ON e.site_id = s.id
WHERE s.account_id = :accountId AND e.batch_id IS NULL
ORDER BY e.occurred_at DESC
```

**Alternatives Considered**:
- Site-level access: Rejected - user should see all their sites' errors
- Admin-only access: Rejected - spec requires user dashboard access

### 4. Bulk Mark-as-Read Performance

**Decision**: Single UPDATE query with IN clause

**Rationale**:
- 100 error IDs fits comfortably in single query
- Update by primary key (id, occurred_at) uses partition pruning
- Index on `is_read` not needed - rarely filtered by this column
- Transaction ensures atomicity

**SQL Pattern**:
```sql
UPDATE error_logs
SET is_read = true
WHERE id IN (:ids) AND occurred_at >= :cutoff
```

**Alternatives Considered**:
- Batch updates (10 at a time): Rejected - unnecessary complexity for 100 items
- Stored procedure: Rejected - overkill, standard JPA sufficient

### 5. Unread Count Query Optimization

**Decision**: COUNT query with partial index consideration

**Rationale**:
- Frequent query for badge: `SELECT COUNT(*) WHERE is_read = false AND batch_id IS NULL AND account_id = ?`
- Consider partial index: `CREATE INDEX idx_unread_errors ON error_logs (site_id) WHERE is_read = false AND batch_id IS NULL`
- Partition pruning helps but unread errors span all partitions

**Query via Sites Join**:
```sql
SELECT COUNT(*) FROM error_logs e
JOIN sites s ON e.site_id = s.id
WHERE s.account_id = :accountId
  AND e.batch_id IS NULL
  AND e.is_read = false
```

**Alternatives Considered**:
- Denormalized counter in accounts table: Rejected - adds complexity, stale data risk
- Cache count: Deferred - premature optimization, measure first

### 6. Frontend Widget Pattern

**Decision**: Follow existing Plugin Logs widget pattern

**Rationale**:
- Consistent with `MyPluginsWidget.tsx` which has tabs including Logs
- TanStack Query for data fetching with polling (30s interval for fresh data)
- shadcn/ui components (Card, Badge, Table, Dialog)
- Feature-sliced design: `features/global-errors/` + `widgets/global-errors/`

**Components**:
1. `GlobalErrorsWidget` - Dashboard card with badge count
2. `GlobalErrorList` - Paginated list with checkboxes
3. `GlobalErrorItem` - Single row with severity indicator
4. `GlobalErrorDetails` - Modal for full error details

**Alternatives Considered**:
- Real-time WebSocket: Out of scope per spec
- Separate page: Rejected - widget on Dashboard per spec

### 7. Severity Enum Storage

**Decision**: String column with application-level enum

**Rationale**:
- PostgreSQL `VARCHAR(20)` allows flexibility without DB migration for new values
- Java enum `ErrorSeverity` with `@Enumerated(EnumType.STRING)`
- Frontend TypeScript union type: `'CRITICAL' | 'ERROR' | 'WARNING' | 'INFO'`
- Default: `ERROR` (matches existing implicit severity)

**Values**:
| Severity | Color (UI) | Use Case |
|----------|------------|----------|
| CRITICAL | Red | System failure, data loss risk |
| ERROR | Orange | Operation failed |
| WARNING | Yellow | Degraded performance, recoverable |
| INFO | Blue | Informational, expected condition |

**Alternatives Considered**:
- PostgreSQL enum type: Rejected - requires migration to add values
- Integer severity (1-4): Rejected - less readable, error-prone

## Summary of Decisions

| Topic | Decision | Impact |
|-------|----------|--------|
| Storage | Extend error_logs | Low risk, high reuse |
| Migration | ADD COLUMN with DEFAULT | Zero downtime |
| Auth | Account-based via Auth0 | Consistent pattern |
| Bulk Update | Single IN query | Simple, performant |
| Count Query | JOIN sites table | Correct authorization |
| Frontend | Widget + polling | Consistent UX |
| Severity | String column + enum | Flexible |

## Open Items

None - all NEEDS CLARIFICATION resolved via spec clarification session.

## References

- Existing: `src/main/java/com/bitbi/dfm/error/domain/ErrorLog.java`
- Existing: `src/main/resources/db/migration/V5__create_error_logs_partitioned_table.sql`
- Pattern: `src/main/java/com/bitbi/dfm/plugin/presentation/PluginUserController.java`
- Pattern: `frontend/src/widgets/my-plugins/MyPluginsWidget.tsx`
