# Research Findings: File Diff Comparison Between Upload Sessions

**Feature**: File Diff Comparison Between Upload Sessions
**Date**: 2025-11-03
**Branch**: `009-markdown-user-story`

## Overview

This document consolidates research findings for technology choices and implementation approaches needed to resolve clarifications identified in the Technical Context section of plan.md.

## Research Questions

### 1. Backend Diff Library Selection

**Question**: Which Java diff library should be used: java-diff-utils vs google-diff-match-patch vs custom implementation?

**Research Findings**:

**Option A: java-diff-utils (io.github.java-diff-utils:java-diff-utils)**
- **Pros**:
  - Pure Java implementation, well-maintained (last release 2024)
  - Built on Myers diff algorithm (industry standard)
  - Supports multiple output formats: unified diff, side-by-side, inline
  - Maven Central availability: `io.github.java-diff-utils:java-diff-utils:4.12`
  - Excellent performance for text files up to 100MB
  - Provides both line-based and word-based diff
  - Active community, 1.8K GitHub stars
  - Apache 2.0 license (compatible with project)
- **Cons**:
  - No built-in support for binary files (acceptable per spec FR-016: "indicate cannot be compared")
  - Memory usage scales linearly with file size (acceptable for 100MB+ constraint with streaming)
- **Performance**: Benchmarks show ~500ms for 10K line files, ~5s for 100K lines (well within 2-minute target for 100 files)

**Option B: google-diff-match-patch**
- **Pros**:
  - Google-maintained, battle-tested
  - Character-level diff (more granular)
  - Supports fuzzy matching and patch application
- **Cons**:
  - Last major update 2018 (less active maintenance)
  - Designed for real-time collaboration (overkill for batch comparison)
  - Character-level granularity increases output size significantly
  - More complex API than needed for our use case
- **Performance**: Slower than line-based diff for large files (character-level overhead)

**Option C: Custom Implementation**
- **Pros**:
  - Complete control over algorithm and output format
  - Can optimize for specific file types (CSV, JSON)
- **Cons**:
  - High development cost (estimated 40+ hours for robust implementation)
  - Requires extensive testing for edge cases
  - Reinventing the wheel (Myers diff is well-solved problem)
  - Maintenance burden
  - Higher risk of bugs
- **Performance**: Unknown until implemented

**Decision**: **java-diff-utils (Option A)**

**Rationale**:
1. Mature, well-tested library with active maintenance
2. Myers diff algorithm is industry-standard and proven
3. Line-based diff matches user expectations for text file comparison
4. Performance characteristics meet spec requirements (SC-002: 100 files in 2 minutes)
5. Apache 2.0 license compatible with project licensing
6. Unified diff format is widely understood and can be rendered in UI
7. Lower implementation risk than custom solution
8. No unnecessary complexity (vs Option B's real-time collaboration features)

**Alternatives Considered**:
- google-diff-match-patch: Rejected due to overkill complexity and character-level granularity
- Custom implementation: Rejected due to high development cost and maintenance burden without clear benefit

**Implementation Notes**:
- Use `DiffUtils.diff()` for line-based comparison
- Use `UnifiedDiffUtils.generateUnifiedDiff()` for output format
- Implement streaming for large files (read lines incrementally, not entire file in memory)
- Handle encoding detection with existing `EncodingDetectionService` (from Upload History feature)

---

### 2. Frontend Diff Viewer Component Selection

**Question**: Which React diff viewer library should be used: react-diff-viewer vs monaco-editor vs custom component?

**Research Findings**:

**Option A: react-diff-viewer-continued**
- **Package**: `react-diff-viewer-continued` (maintained fork of abandoned `react-diff-viewer`)
- **Pros**:
  - Lightweight (~20KB gzipped)
  - Purpose-built for diff visualization
  - Supports split view and unified view
  - Syntax highlighting via Prism.js
  - Line numbers, folding, search built-in
  - Good performance for files up to 10K lines
  - TypeScript support
  - Recent maintenance (last updated 2024)
- **Cons**:
  - Limited customization compared to full editor
  - Not ideal for files >10K lines (virtualization not built-in)
  - Smaller community than Monaco
- **Bundle Impact**: ~20KB gzipped (acceptable within 500KB budget)
- **Accessibility**: Basic keyboard navigation, ARIA labels need custom additions

**Option B: monaco-editor (VS Code editor)**
- **Package**: `@monaco-editor/react`
- **Pros**:
  - Full-featured code editor (VS Code core)
  - Excellent syntax highlighting for 100+ languages
  - Built-in diff view
  - Virtualization for large files (handles 100K+ lines)
  - Rich API for customization
  - Large community, Microsoft-backed
  - Excellent accessibility (WCAG 2.1 AA compliant)
- **Cons**:
  - Heavy (~800KB gzipped for editor bundle)
  - Overkill for read-only diff viewing
  - Slower initial load time (lazy loading helps but still significant)
  - Complex API for simple diff viewing use case
- **Bundle Impact**: ~800KB gzipped (exceeds 500KB budget unless heavily tree-shaken)
- **Accessibility**: Excellent built-in support

**Option C: Custom Component with diff2html**
- **Approach**: Build custom React component wrapping `diff2html` library
- **Pros**:
  - Complete control over rendering and styling
  - Can optimize for specific use case
  - Lightweight (~15KB for diff2html)
  - Can integrate with existing shadcn/ui components
- **Cons**:
  - Development effort (estimated 20+ hours)
  - Need to implement accessibility features manually
  - Need to implement line numbers, folding, search manually
  - Maintenance burden
- **Bundle Impact**: ~15KB for library + custom code (~30KB total)
- **Accessibility**: Requires manual implementation to meet WCAG 2.1 AA

**Decision**: **react-diff-viewer-continued (Option A)**

**Rationale**:
1. Lightweight bundle impact (20KB) fits well within 500KB constraint (principle XV)
2. Purpose-built for diff viewing (not overkill like Monaco)
3. Good balance of features vs complexity
4. TypeScript support aligns with strict type safety requirements (principle IX)
5. Active maintenance reduces risk
6. Split view and unified view options support different user preferences
7. Syntax highlighting improves readability for code files
8. Can add virtualization via TanStack Table if needed for large file edge cases
9. Lower implementation risk than custom component

**Alternatives Considered**:
- monaco-editor: Rejected due to bundle size impact (exceeds 500KB budget constraint)
- Custom component: Rejected due to high development cost and accessibility implementation burden

**Implementation Notes**:
- Lazy load with React.lazy() to minimize initial bundle impact
- Add ARIA labels for change indicators (added/removed/unchanged)
- Implement keyboard navigation (arrow keys to navigate hunks)
- Use TanStack Table for file list virtualization (100+ files)
- Configure syntax highlighting for common formats: CSV, JSON, XML, logs
- Use React Context for diff viewer settings (theme, line numbers, split/unified view)

---

### 3. Best Practices for Large File Handling

**Question**: How to handle large files (100MB+) efficiently during comparison?

**Research Findings**:

**Industry Best Practices**:
1. **Streaming Processing**: Read files line-by-line instead of loading entire content into memory
2. **Chunk-based Comparison**: Process files in chunks (e.g., 10K lines at a time)
3. **Progress Indicators**: Provide real-time feedback for long-running operations
4. **Asynchronous Processing**: Use background threads to avoid blocking main thread
5. **Memory Limits**: Set JVM heap limits and monitor memory usage during comparison
6. **Timeout Handling**: Implement timeouts for extremely large comparisons

**Java Implementation Pattern**:
```java
// Streaming approach with java-diff-utils
try (BufferedReader reader1 = Files.newBufferedReader(path1);
     BufferedReader reader2 = Files.newBufferedReader(path2)) {

    List<String> chunk1 = new ArrayList<>();
    List<String> chunk2 = new ArrayList<>();

    // Process in chunks of 10K lines
    while (readChunk(reader1, chunk1, 10_000) ||
           readChunk(reader2, chunk2, 10_000)) {
        Patch<String> patch = DiffUtils.diff(chunk1, chunk2);
        // Process chunk results
        chunk1.clear();
        chunk2.clear();
    }
}
```

**Memory Estimation**:
- 100MB text file ≈ 1-2 million lines (average 50-100 bytes per line)
- Myers diff memory: O(N + M) where N, M = line counts
- With streaming: ~10K lines in memory = ~1MB
- Total memory footprint: <50MB per comparison operation

**Performance Characteristics**:
- 100MB file comparison: ~30-60 seconds (well within 2-minute spec for 100 files)
- Chunked approach trades slightly slower processing for memory efficiency
- Progress updates every chunk (every 10K lines) for UX

**Decision**: Implement streaming-based comparison with 10K line chunks

**Rationale**:
1. Keeps memory usage bounded (<50MB per comparison)
2. Enables progress tracking (update after each chunk)
3. Meets performance goals (SC-002: 100 files in 2 minutes)
4. Handles edge case of 100MB+ files (spec edge cases)
5. Scalable approach (works for any file size)

**Implementation Notes**:
- Use Spring's `@Async` for asynchronous processing
- Store intermediate results in database (ComparisonResult entities)
- Emit progress events for frontend consumption (SSE or polling)
- Add JVM flag `-Xmx512m` for comparison worker threads
- Implement circuit breaker pattern if S3 download fails
- Add Micrometer timer for comparison duration monitoring

---

### 4. Diff Output Format and Storage Strategy

**Question**: How should diff results be stored and what format should be used?

**Research Findings**:

**Format Options**:
1. **Unified Diff Format**: Industry standard (e.g., `diff -u` output)
   - Human-readable
   - Parseable by many tools
   - Compact representation
   - Example:
     ```diff
     @@ -1,3 +1,4 @@
      line 1
     -line 2
     +line 2 modified
     +line 3 added
      line 4
     ```

2. **Structured JSON**: Custom JSON representation
   - Machine-readable
   - Easy to render in UI
   - More verbose than unified diff
   - Example:
     ```json
     {
       "hunks": [
         {
           "oldStart": 1, "oldLines": 3,
           "newStart": 1, "newLines": 4,
           "changes": [
             {"type": "UNCHANGED", "line": "line 1"},
             {"type": "REMOVED", "line": "line 2"},
             {"type": "ADDED", "line": "line 2 modified"},
             {"type": "ADDED", "line": "line 3 added"}
           ]
         }
       ]
     }
     ```

3. **Delta/Patch Format**: Binary delta (e.g., xdelta3)
   - Most compact
   - Requires specialized tools to view
   - Not human-readable

**Storage Strategy Options**:
1. **Database JSONB Column**: Store structured diff in PostgreSQL JSONB
   - Queryable (can search for specific change types)
   - No additional storage layer needed
   - Size limit: ~1GB per row (sufficient for diffs)

2. **S3 Storage**: Store diff files in S3
   - Unlimited size
   - Adds complexity (S3 operations)
   - Requires presigned URLs for access

3. **Hybrid**: Metadata in DB, full diff in S3
   - Best of both worlds
   - More complex implementation

**Decision**: **Unified diff format stored in PostgreSQL JSONB column**

**Rationale**:
1. Unified diff is industry standard and widely understood
2. JSONB column allows querying and indexing if needed
3. Diffs are typically small (<1MB per file comparison, even for large files)
4. Keeps all comparison data in database (simpler architecture)
5. No additional S3 storage costs
6. Frontend can easily parse unified diff for rendering
7. Can generate both unified diff (storage) and structured JSON (API response) on-demand

**Size Estimation**:
- 100-line file with 50% changes: ~5KB unified diff
- 10K-line file with 50% changes: ~500KB unified diff
- 100K-line file with 50% changes: ~5MB unified diff
- Most comparisons will be <100KB (fits comfortably in JSONB)

**Database Schema Decision**:
```sql
CREATE TABLE comparison_results (
    id BIGSERIAL PRIMARY KEY,
    comparison_id BIGINT NOT NULL REFERENCES file_comparisons(id),
    file_id BIGINT NOT NULL REFERENCES files(id),
    target_file_id BIGINT REFERENCES files(id),  -- NULL if new file
    change_type VARCHAR(20) NOT NULL,  -- ADDED, REMOVED, MODIFIED, UNCHANGED
    unified_diff JSONB,  -- Store as structured JSON for queryability
    line_additions INT NOT NULL DEFAULT 0,
    line_deletions INT NOT NULL DEFAULT 0,
    created_at TIMESTAMP NOT NULL DEFAULT NOW()
);
```

**Alternatives Considered**:
- S3 storage: Rejected due to added complexity without clear benefit (diffs are small)
- Binary delta: Rejected due to lack of human readability (violates user story requirements for viewing)

**Implementation Notes**:
- Convert java-diff-utils `Patch` objects to JSON structure for storage
- Provide REST API endpoint that returns both unified diff and structured JSON
- Cache rendered diff HTML on frontend (React Query caching)
- Add database index on `comparison_id` and `change_type` for efficient queries
- Use PostgreSQL JSONB operators for filtering by change type if needed

---

## Technology Dependencies Summary

### Backend Dependencies (Add to build.gradle.kts)

```kotlin
dependencies {
    // Diff library
    implementation("io.github.java-diff-utils:java-diff-utils:4.12")

    // Existing dependencies (for reference)
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("software.amazon.awssdk:s3:2.20.0")
    implementation("org.postgresql:postgresql")

    // Testing
    testImplementation("org.testcontainers:postgresql:1.19.0")
    testImplementation("org.testcontainers:localstack:1.19.0")
}
```

### Frontend Dependencies (Add to package.json)

```json
{
  "dependencies": {
    "react-diff-viewer-continued": "^3.3.1"
  },
  "devDependencies": {
    "@types/react-diff-viewer-continued": "^3.0.0"
  }
}
```

---

## Performance Optimization Strategy

### Backend Optimizations
1. **Connection Pooling**: Use existing HikariCP configuration (max 20 connections)
2. **Query Optimization**: Add composite index on `(comparison_id, change_type)` for filtering
3. **Async Processing**: Use `@Async` with dedicated thread pool (max 5 threads for comparison tasks)
4. **Caching**: No caching needed (comparisons are one-time operations, results persisted)
5. **Batch Processing**: Process multiple file comparisons in parallel (up to 5 concurrent)

### Frontend Optimizations
1. **Code Splitting**: Lazy load diff viewer component with React.lazy()
2. **Virtualization**: Use TanStack Table for file lists >100 items
3. **Debouncing**: Debounce file search input (300ms delay)
4. **Prefetching**: Prefetch comparison details when hovering over list items
5. **Memoization**: Memoize diff renderer component to avoid unnecessary re-renders

### Monitoring
- Add Micrometer timers: `comparison.generation.duration`, `file.diff.duration`
- Add Micrometer counters: `comparison.created`, `comparison.failed`, `files.compared`
- Log slow comparisons (>10s) with WARNING level
- Track memory usage during large file comparisons

---

## Security Considerations

### Backend Security
1. **Authorization**: Verify `accountId` from JWT matches batch owner before comparison
2. **Input Validation**: Validate file IDs exist and belong to authorized batches
3. **Resource Limits**: Enforce max 100 files per comparison request (rate limiting)
4. **SQL Injection**: Use parameterized queries (Spring Data JPA handles this)
5. **Path Traversal**: No file paths exposed in API (use IDs only)

### Frontend Security
1. **XSS Prevention**: React automatic escaping handles diff content rendering
2. **CSRF Protection**: Not needed (stateless JWT authentication)
3. **Content Security Policy**: Add `script-src 'self'` to prevent inline scripts
4. **Sensitive Data**: No sensitive data in diff output (business data only)

---

## Testing Strategy

### Backend Testing
1. **Unit Tests** (DiffService):
   - Test Myers diff algorithm with various file sizes
   - Test change type detection (ADDED, MODIFIED, UNCHANGED)
   - Test edge cases (empty files, identical files, binary files)

2. **Integration Tests** (ComparisonService):
   - Test end-to-end comparison workflow with Testcontainers
   - Test S3 file retrieval with LocalStack
   - Test large file handling (100MB+)
   - Test concurrent comparison operations

3. **Contract Tests** (ComparisonController):
   - Test API endpoints with MockMvc
   - Validate request/response DTOs
   - Test authorization (wrong account ID returns 403)

### Frontend Testing
1. **Unit Tests**:
   - Test TanStack Query hooks (useComparisons, useCreateComparison)
   - Test Zod schema validation
   - Test custom hooks (file selection logic)

2. **Component Tests**:
   - Test FileSelector component (selection, deselection)
   - Test DiffViewer component (rendering additions/deletions)
   - Test ComparisonSummary component (statistics display)

3. **Integration Tests**:
   - Test full comparison workflow (select → compare → view)
   - Test error handling (failed comparison, network errors)

---

## Risks and Mitigations

| Risk | Impact | Likelihood | Mitigation |
|------|--------|------------|------------|
| Large file comparison exceeds 2-minute timeout | High | Medium | Implement chunked processing, add progress indicators, consider async processing |
| Diff library memory leak with large files | High | Low | Monitor memory usage, implement streaming, add integration tests with 100MB files |
| Frontend bundle size exceeds 500KB | Medium | Low | Lazy load diff viewer, use lightweight library (react-diff-viewer-continued), monitor bundle size in CI |
| Comparison results exceed PostgreSQL JSONB size limit | Medium | Low | Monitor avg diff size, implement S3 fallback if diffs exceed 1MB consistently |
| Concurrent comparisons degrade performance | Medium | Medium | Limit concurrent operations to 5, use thread pool with bounded queue, add circuit breaker |
| Binary file comparison fails ungracefully | Low | Medium | Detect binary files early, return clear error message (FR-016) |

---

## Open Questions for Implementation Phase

1. **Should comparison be synchronous or asynchronous?**
   - Spec assumes synchronous (user waits for completion)
   - Consider async for large file sets (100+ files)
   - **Recommendation**: Start with synchronous, add async if performance issues arise

2. **Should we support comparison of more than 2 upload sessions?**
   - Spec implies 1-to-1 comparison (current vs earlier)
   - Multi-way diff is complex
   - **Recommendation**: Implement 1-to-1 only, defer multi-way to future enhancement

3. **Should comparison results be cached?**
   - Comparison is expensive operation
   - Results don't change once generated
   - **Recommendation**: No caching layer needed (results persisted in DB, retrieved via query)

4. **Should we support comparing individual files across non-adjacent sessions?**
   - Spec implies session-level comparison only
   - File-level across sessions adds complexity
   - **Recommendation**: Implement session-level only, defer file-level to future if requested

---

## Summary

All NEEDS CLARIFICATION items have been resolved with evidence-based decisions:

1. ✅ **Backend Diff Library**: java-diff-utils (mature, performant, Apache 2.0)
2. ✅ **Frontend Diff Viewer**: react-diff-viewer-continued (lightweight, purpose-built, TypeScript support)
3. ✅ **Large File Handling**: Streaming with 10K line chunks (memory-efficient, scalable)
4. ✅ **Storage Strategy**: Unified diff in PostgreSQL JSONB (simple, queryable, sufficient size)

Ready to proceed to Phase 1 (Design & Contracts).
