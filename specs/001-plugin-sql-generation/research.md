# Research: Plugin SQL Generation Extension

**Feature Branch**: `001-plugin-sql-generation`
**Date**: 2025-12-22
**Status**: Complete

## 1. CSV Diff Algorithm for Row-Level Comparison

### Decision: Row-based comparison with composite key detection

**Rationale**: Unlike the existing `DiffServiceImpl` which performs line-level text diff, SQL generation requires semantic understanding of CSV structure to identify added, modified, and deleted rows.

**Algorithm**:
1. Parse both CSV files into memory as `List<Map<String, String>>` (column name → value)
2. Detect row identity using ALL columns (no primary key assumption)
3. Compare rows by exact value match across all columns
4. Classify changes:
   - **ADDED**: Row exists in current batch, not in previous
   - **DELETED**: Row exists in previous batch, not in current
   - **MODIFIED**: Row exists in both, but some values differ

**Alternatives Considered**:
- **java-diff-utils (existing)**: Line-level diff, doesn't understand CSV semantics
- **CSV fingerprinting with hash**: Fast lookup but loses ability to detect which columns changed
- **Row-by-row streaming comparison**: Memory efficient but O(n²) complexity

**Implementation**: Use Apache Commons CSV for parsing (already in dependencies), in-memory HashMap for O(n) comparison.

---

## 2. DBF Type Information for NULL Handling

### Decision: Type metadata embedded in CSV headers or separate metadata file

**Rationale**: Spec requires different NULL handling based on field type:
- Character, Numeric, Logical, Date, Float, DateTime → empty string = NULL
- Integer, Currency → empty string = 0

**Research Finding**: The spec mentions "DBF type information" which suggests the source data comes from dBASE format. Two options for type metadata:

**Option A (Recommended)**: Header convention `column_name:TYPE`
- Example: `name:C`, `age:N`, `price:Y` where C=Character, N=Numeric, Y=Currency
- Parsed at CSV load time
- Simple, self-contained

**Option B**: Separate `.meta.json` file per CSV
- Example: `customers.csv` + `customers.meta.json` with type mappings
- More complex but allows rich metadata

**Decision**: Implement Option A with fallback to Option B if header doesn't contain types. Default to Character type (NULL for empty) if no type info available.

**DBF Type Mapping**:
| DBF Type | Code | Empty String Handling |
|----------|------|----------------------|
| Character | C | NULL |
| Numeric | N | NULL |
| Logical | L | NULL |
| Date | D | NULL |
| Float | F | NULL |
| DateTime | T | NULL |
| Integer | I | 0 |
| Currency | Y | 0 |

---

## 3. Plugin API Key Authentication

### Decision: Prefix-based API Key stored in account_plugins.plugin_data JSONB

**Rationale**: API Key must be:
1. Generated on plugin activation
2. Unique per account
3. Rotatable (re-activation generates new key)
4. Fast to validate (<50ms per SC-004)

**Format**: `plk_` + 32 alphanumeric characters = 36 characters total
- `plk_` prefix identifies as Plugin API Key
- 32 chars of Base62 provides ~190 bits of entropy

**Storage**: `account_plugins.plugin_data` JSONB field, alongside existing `tenantId`:
```json
{
  "tenantId": "bit-bi-tenant-123",
  "apiKey": "plk_a1b2c3d4e5f6g7h8i9j0k1l2m3n4o5p6"
}
```

**Validation**: Add GIN index on `plugin_data` JSONB for fast lookup by `apiKey`.

**Existing Index**: `idx_account_plugins_data ON account_plugins USING GIN(plugin_data)` already exists (V8 migration).

**Alternative Considered**: Separate `plugin_api_keys` table - rejected because:
- Adds complexity (new entity, migration, repository)
- No benefit over JSONB storage with existing index

---

## 4. S3 Path Structure for SQL Files

### Decision: `plugins/bit-bi/{accountId}/{siteName}/{source_datetime}--{comparison_datetime}.sql`

**Rationale**:
- Consistent with spec FR-009
- `siteName` is human-readable (domain), not UUID
- Double-dash `--` separates source and comparison timestamps
- First batch uses `{source_datetime}--first-batch.sql`

**Example Paths**:
- Regular: `plugins/bit-bi/550e8400-e29b-41d4-a716-446655440000/example.com/2025-12-22T14-30-00--2025-12-22T15-00-00.sql`
- First batch: `plugins/bit-bi/550e8400-e29b-41d4-a716-446655440000/example.com/2025-12-22T14-30-00--first-batch.sql`

**Timestamp Format**: ISO8601 with colons replaced by hyphens (`T14-30-00` instead of `T14:30:00`) for S3 key compatibility.

---

## 5. SQL Statement Formatting

### Decision: PostgreSQL-specific syntax with trailing comments

**Rationale**: Spec explicitly mentions PostgreSQL and requires comment format after each statement.

**INSERT Format**:
```sql
INSERT INTO tablename (col1, col2, col3) VALUES ('val1', 'val2', 123);
--- END OF COMMAND "filename.csv:42" ---
```

**UPDATE Format**:
```sql
UPDATE tablename SET col1 = 'new_val' WHERE col2 = 'unchanged1' AND col3 = 'unchanged2';
--- END OF COMMAND "filename.csv:42" ---
```

**DELETE Format**:
```sql
DELETE FROM tablename WHERE col1 = 'val1' AND col2 = 'val2' AND col3 = 'val3';
--- END OF COMMAND "filename.csv:42" ---
```

**Value Escaping**:
- Single quotes doubled: `O'Brien` → `'O''Brien'`
- NULL values: unquoted `NULL` keyword
- Integer/Currency zeros: unquoted `0`
- All other values: single-quoted strings

**Table Name Derivation**: CSV filename without extension (e.g., `customers.csv` → `customers`)

---

## 6. Batch Comparison Strategy

### Decision: Compare files by filename match between batches

**Rationale**: Spec requires comparing "current batch files against previous batch files for the same site".

**Matching Logic**:
1. Get list of files from current batch (from `uploaded_files` table)
2. Get list of files from previous batch for same site (most recent COMPLETED batch)
3. Match files by `original_file_name` (exact match, case-sensitive)
4. For matched files: generate UPDATE/DELETE/INSERT based on row diff
5. For new files (in current, not in previous): generate INSERT for all rows
6. For deleted files (in previous, not in current): generate DELETE for all rows

**Edge Cases**:
- First batch: No previous batch exists → INSERT all rows from all files
- File renamed: Treated as delete + add (no rename detection)

---

## 7. Plugin Event Integration

### Decision: Extend existing BitBiPlugin.execute() method

**Rationale**: The plugin infrastructure already dispatches `BATCH_COMPLETED` events to BitBiPlugin. SQL generation should be triggered within this existing flow.

**Current Flow**:
1. `BatchService.completeBatch()` publishes `BatchCompletedEvent`
2. `BatchEventListener.onBatchCompleted()` creates `PluginEvent` and calls `PluginEventDispatcher.dispatch()`
3. `PluginEventDispatcher` finds active `AccountPlugin` records and calls `plugin.execute()`
4. `BitBiPlugin.execute()` currently logs (no-op)

**Extended Flow** (this feature):
1-3. Same as above
4. `BitBiPlugin.execute()` calls `SqlGenerationService.generateForBatch(batchId, accountPlugin)`
5. `SqlGenerationService` orchestrates:
   - Find previous batch for same site
   - For each matched file pair, call `CsvDiffService.diff()`
   - For each diff result, call `SqlStatementGenerator.generate()`
   - Concatenate all SQL into single file
   - Upload to S3 via `S3SqlFileStorageService`
   - Save metadata to `plugin_sql_generations` table

**Async Consideration**: Plugin execution already runs on `pluginExecutionExecutor` (10 core threads, 20 max). SQL generation can run synchronously within the 30-second timeout. For batches with many large files, may need to extend timeout or use dedicated executor.

---

## 8. API Key Validation Performance

### Decision: Database lookup with JSONB containment query

**Rationale**: Need to validate API Key and retrieve associated accountId in <50ms.

**Query**:
```sql
SELECT account_id FROM account_plugins
WHERE plugin_id = 'bit-bi'
  AND is_active = true
  AND plugin_data @> '{"apiKey": "plk_xxx..."}'::jsonb
```

**Performance**: GIN index on `plugin_data` supports `@>` operator. Expected query time <10ms with index.

**Alternative Considered**: In-memory cache with TTL - rejected for v1 due to:
- Added complexity (cache invalidation on key rotation)
- Database query fast enough to meet SLA
- Can add caching later if needed

---

## 9. Existing Infrastructure Reuse

### Can Reuse:
- `S3FileContentService`: Fetch CSV content from S3 (supports gzip decompression)
- `CsvDecompressionService`: Handle .csv.gz files
- Apache Commons CSV (1.12.0): CSV parsing
- `PluginEventDispatcher`: Event delivery infrastructure
- `AccountPluginRepository`: API Key storage
- `PluginAuditLogRepository`: Audit trail

### Must Create New:
- `PluginSqlGeneration`: Entity for tracking SQL file generation
- `CsvDiffService`: Row-level CSV comparison (semantic diff, not text diff)
- `SqlStatementGenerator`: PostgreSQL statement formatting
- `S3SqlFileStorageService`: Upload SQL files to plugin-specific S3 path
- `PluginApiKeyService`: Key generation and validation
- `BitBiPluginApiController`: REST endpoints for Plugin API

---

## 10. TDD Implementation Order

**MANDATORY**: All development follows TDD cycle (Red → Green → Refactor).

### Phase 1: Contract Tests First
1. Write `BitBiPluginApiContractTest` with MockMvc
   - `GET /api/v1/plugins/bit-bi/sql-changes` - 401, 403, 200 scenarios
   - `GET /api/v1/plugins/bit-bi/sites` - 401, 200 scenarios
2. Tests MUST fail initially (Red phase)

### Phase 2: Domain & Application Layer
1. Write unit tests for `CsvDiffServiceTest`
   - Test row comparison logic
   - Test DBF type handling
2. Write unit tests for `SqlStatementGeneratorTest`
   - Test INSERT, UPDATE, DELETE formatting
   - Test NULL handling by type
   - Test special character escaping
3. Implement services to pass tests (Green phase)

### Phase 3: Integration Tests
1. Write `SqlGenerationIntegrationTest` with Testcontainers
   - End-to-end batch completion → SQL file generation
2. Write `PluginApiKeyIntegrationTest`
   - Key generation on activation
   - Key validation performance

### Phase 4: API Implementation
1. Implement `BitBiPluginApiController` to pass contract tests
2. Verify all tests pass

---

## Summary of Decisions

| Topic | Decision |
|-------|----------|
| CSV Diff | Row-based with HashMap for O(n) comparison |
| DBF Types | Header convention `column:TYPE` with fallback |
| API Key | `plk_` + 32 alphanumeric in plugin_data JSONB |
| S3 Path | `plugins/bit-bi/{accountId}/{siteName}/{timestamps}.sql` |
| SQL Format | PostgreSQL with `--- END OF COMMAND "file:line" ---` |
| Batch Matching | By filename, previous = most recent COMPLETED for site |
| Event Integration | Extend BitBiPlugin.execute() |
| Key Validation | JSONB containment query with GIN index |
| TDD Order | Contract → Unit → Integration → Implementation |
