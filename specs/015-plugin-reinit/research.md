# Research: BitBi Plugin Reinit Option

**Feature**: 015-plugin-reinit
**Date**: 2026-01-04

## Research Tasks

### 1. Finding the Latest Completed Batch

**Question**: How to find the most recent completed batch for an account?

**Decision**: Add new repository method `findLatestCompletedByAccountId(UUID accountId)`

**Rationale**:
- Existing `findPreviousBatchForSite()` only works for a specific site and excludes a batch
- Need account-level query to find any completed batch across all sites
- Query should order by `completedAt DESC` and limit to 1

**Implementation Pattern**:
```java
// BatchRepository.java
Optional<Batch> findLatestCompletedByAccountId(UUID accountId);

// JpaBatchRepository.java
@Query("""
    SELECT b FROM Batch b
    WHERE b.accountId = :accountId
    AND b.status = 'COMPLETED'
    ORDER BY b.completedAt DESC
    LIMIT 1
    """)
Optional<Batch> findLatestCompletedByAccountId(@Param("accountId") UUID accountId);
```

**Alternatives Considered**:
- Query all sites then find latest per site - rejected (N+1, complex)
- Use existing `findPreviousBatchForSiteWithFiles` - rejected (site-scoped, not account-scoped)

---

### 2. Async SQL Generation on Activation

**Question**: How to trigger SQL generation asynchronously during activation?

**Decision**: Use Spring's `@Async` annotation with existing `TaskExecutor`

**Rationale**:
- `onActivate()` hook is called synchronously from `PluginActivationService`
- SQL generation can take several seconds (S3 reads, CSV diff, S3 writes)
- API response should return immediately with API key
- Existing `BatchEventListener` uses similar async pattern

**Implementation Pattern**:
```java
// BitBiPlugin.java
@Async
public void initializeSqlFromLatestBatch(AccountPlugin accountPlugin, boolean isNewActivation) {
    if (!isNewActivation) return; // Skip for config updates

    Optional<Batch> latestBatch = batchRepository.findLatestCompletedByAccountId(
        accountPlugin.getAccountId());

    if (latestBatch.isPresent()) {
        sqlGenerationService.generateSqlForBatch(
            latestBatch.get().getId(),
            accountPlugin.getId());
    }
}
```

**Alternatives Considered**:
- Synchronous generation - rejected (blocks API response, poor UX)
- Event-driven (publish `PluginActivatedEvent`) - rejected (over-engineering for simple use case)
- CompletableFuture - rejected (`@Async` is simpler and sufficient)

---

### 3. Distinguishing New Activation vs Config Update

**Question**: How does `onActivate()` know if it's a new activation or just a config update?

**Decision**: Add `isNewActivation` parameter to `Plugin.onActivate()` interface

**Rationale**:
- Current `onActivate()` signature: `String onActivate(AccountPlugin accountPlugin)`
- Cannot distinguish new activation from config update
- Need to skip SQL initialization on config updates (FR-003)
- API key is only generated on new activation/reactivation

**Implementation Pattern**:
```java
// Plugin.java interface - BREAKING CHANGE
String onActivate(AccountPlugin accountPlugin, boolean isNewActivation);

// Or: Add separate method (non-breaking)
default void onNewActivation(AccountPlugin accountPlugin) {}
```

**Decision**: Use existing `ActivationResult.isNewActivation()` to call a separate initialization method after `onActivate()` completes.

**Alternatives Considered**:
- Modify `Plugin` interface - rejected (breaking change to interface)
- Check `AccountPlugin.getActivatedAt()` timestamp - rejected (unreliable timing)

---

### 4. Reinit Operation Flow

**Question**: What is the exact sequence for the reinit operation?

**Decision**: Implement as transactional delete + async regeneration

**Rationale**:
- Delete must be atomic (all-or-nothing for DB records)
- S3 deletion is best-effort (matches existing `clearHistory()` pattern)
- SQL generation should be async to avoid long-running transactions
- Plugin remains active throughout (unlike `clearHistory()` which deactivates)

**Implementation Flow**:
```
1. Validate plugin is active (FR-009)
2. Log REINIT_STARTED audit entry
3. Begin transaction:
   a. Get all S3 keys for account-plugin
   b. Delete S3 files (best-effort, collect failures)
   c. Delete all PluginSqlGeneration records
4. Commit transaction
5. Find latest completed batch
6. Trigger async SQL generation (if batch exists)
7. Log REINIT_COMPLETED audit entry
8. Return success with batch info
```

**Alternatives Considered**:
- Synchronous regeneration - rejected (can take >60s for large batches)
- Soft-delete old records - rejected (spec requires complete removal)
- Background job queue - rejected (over-engineering, Spring `@Async` sufficient)

---

### 5. New Audit Action Type

**Question**: What audit action type for reinit operations?

**Decision**: Add `REINIT` to `PluginActionType` enum

**Rationale**:
- Distinct from `PLUGIN_HISTORY_CLEARED` (which deactivates plugin)
- Should be visible in user-facing plugin logs
- Metadata should include: success/failure, SQL generation triggered, batch ID

**Implementation**:
```java
// PluginActionType.java
/** Plugin SQL state reinitialized (history cleared + regenerated) */
REINIT
```

---

### 6. Endpoint Design

**Question**: What HTTP method and path for reinit endpoint?

**Decision**: `POST /api/v1/account/plugins/{pluginId}/reinit`

**Rationale**:
- POST because it's not idempotent (generates new SQL each time)
- Under `/account/plugins` path (user-facing, OAuth2 auth)
- Consistent with existing endpoint patterns

**Alternatives Considered**:
- `DELETE /api/v1/account/plugins/{pluginId}/sql` + `POST /generate` - rejected (two calls, complex)
- `PUT /api/v1/account/plugins/{pluginId}` with body flag - rejected (mixing concerns)
- `POST /api/v1/plugins/{pluginId}/reinit` - rejected (inconsistent with `/account/plugins` pattern)

---

### 7. Error Handling

**Question**: What happens if SQL generation fails during reinit?

**Decision**: Return success for deletion, log warning for generation failure

**Rationale**:
- Primary goal of reinit is to clear history (achieved)
- SQL generation can fail for transient reasons (S3 timeout, etc.)
- User can call reinit again or wait for next batch
- Matches existing behavior where SQL generation failures don't fail activation

**Response Pattern**:
```json
{
  "success": true,
  "deletedGenerations": 10,
  "deletedS3Files": 10,
  "sqlGenerationTriggered": true,
  "batchId": "uuid",
  "warnings": ["SQL generation is running asynchronously"]
}
```

---

## Key Decisions Summary

| Decision | Choice | Key Reason |
|----------|--------|------------|
| Latest batch query | Account-level repo method | Simple, efficient |
| Async generation | Spring `@Async` | Non-blocking, existing pattern |
| Activation distinction | Separate init method call | Non-breaking interface |
| Reinit transaction | Delete sync, generate async | Atomic cleanup, responsive |
| Audit type | New `REINIT` enum value | Clear semantics |
| Endpoint | `POST .../reinit` | RESTful, non-idempotent |
| Error handling | Best-effort generation | Graceful degradation |
