package com.bitbi.dfm.batch.application;

import com.bitbi.dfm.batch.domain.Batch;
import com.bitbi.dfm.batch.domain.exception.BatchNotFoundException;
import com.bitbi.dfm.batch.domain.exception.UnauthorizedBatchAccessException;
import com.bitbi.dfm.batch.infrastructure.BatchWithFileCountProjection;
import com.bitbi.dfm.batch.infrastructure.JpaBatchRepository;
import com.bitbi.dfm.batch.presentation.dto.BatchDetailDto;
import com.bitbi.dfm.batch.presentation.dto.BatchSummaryDto;
import com.bitbi.dfm.batch.presentation.dto.CursorPageResponseDto;
import com.bitbi.dfm.batch.presentation.dto.DeltaSeqRangeDto;
import com.bitbi.dfm.batch.presentation.dto.DeltaTableStatsDto;
import com.bitbi.dfm.delta.domain.ChangelogSegment;
import com.bitbi.dfm.delta.domain.ChangelogSegmentRepository;
import com.bitbi.dfm.delta.domain.SegmentBatchAggregate;
import com.bitbi.dfm.delta.domain.TableChangeStats;
import com.bitbi.dfm.site.domain.Site;
import com.bitbi.dfm.site.infrastructure.JpaSiteRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * T026: Upload History Service - Business logic for viewing batch history.
 * <p>
 * Provides cursor-based paginated access to user's upload history.
 * Implements authorization by filtering batches to user's sites only.
 * </p>
 *
 * <p><strong>Features:</strong></p>
 * <ul>
 *   <li>Cursor-based pagination (no OFFSET performance issues)</li>
 *   <li>Redis caching for first page (5-minute TTL)</li>
 *   <li>Authorization via accountId → sites → batches chain</li>
 *   <li>DTO projection to avoid N+1 queries</li>
 * </ul>
 *
 * @author Data Forge Team (Feature: 008-upload-history-user)
 * @version 1.0.0
 * @see com.bitbi.dfm.batch.presentation.BatchHistoryController
 */
@Service
@Transactional(readOnly = true)
public class BatchHistoryService {

    private static final Logger logger = LoggerFactory.getLogger(BatchHistoryService.class);
    private static final int DEFAULT_PAGE_SIZE = 20;
    private static final String CURSOR_DELIMITER = "_";

    private final JpaBatchRepository batchRepository;
    private final JpaSiteRepository siteRepository;
    private final ChangelogSegmentRepository changelogSegmentRepository;

    public BatchHistoryService(JpaBatchRepository batchRepository, JpaSiteRepository siteRepository,
                               ChangelogSegmentRepository changelogSegmentRepository) {
        this.batchRepository = batchRepository;
        this.siteRepository = siteRepository;
        this.changelogSegmentRepository = changelogSegmentRepository;
    }

    /**
     * T026: List batch history with cursor-based pagination.
     * <p>
     * Authorization: Only returns batches for sites owned by accountId.
     * </p>
     *
     * @param accountId User's account ID (from JWT)
     * @param cursor    Cursor for next page (null for first page)
     * @param limit     Maximum items per page (null = default 20)
     * @return Paginated batch list
     */
    public CursorPageResponseDto<BatchSummaryDto> listBatchHistory(UUID accountId, String cursor, Integer limit) {
        logger.info("Listing batch history for accountId={}, cursor={}, limit={}", accountId, cursor, limit);

        // Get user's site IDs for authorization
        List<UUID> siteIds = siteRepository.findByAccountId(accountId).stream()
                .map(Site::getId)
                .collect(Collectors.toList());

        if (siteIds.isEmpty()) {
            logger.warn("No sites found for accountId={}", accountId);
            return CursorPageResponseDto.empty();
        }

        int pageSize = limit != null && limit > 0 && limit <= 100 ? limit : DEFAULT_PAGE_SIZE;

        // Fetch one extra item to determine if there's a next page
        int fetchLimit = pageSize + 1;

        List<BatchWithFileCountProjection> projections;

        if (cursor == null) {
            // First page (cacheable)
            projections = fetchFirstPage(siteIds, fetchLimit);
        } else {
            // Subsequent page with cursor
            projections = fetchWithCursor(siteIds, cursor, fetchLimit);
        }

        // Check if there are more items (hasNext)
        boolean hasNext = projections.size() > pageSize;

        // Trim to actual page size
        List<BatchWithFileCountProjection> pageItems = hasNext
                ? projections.subList(0, pageSize)
                : projections;

        // Per-batch delta totals for the page, aggregated SQL-side (029): a session batch owns N
        // segments, so the list shows SUM(record_count) and the distinct-table count — one grouped
        // query for the whole page, never the raw segment rows.
        Map<UUID, SegmentBatchAggregate> aggregatesByBatchId = pageItems.isEmpty()
                ? Map.of()
                : changelogSegmentRepository
                        .aggregateByBatchIds(pageItems.stream().map(BatchWithFileCountProjection::getId).toList())
                        .stream()
                        .collect(Collectors.toMap(SegmentBatchAggregate::getBatchId, a -> a));

        // Convert projections to DTOs
        List<BatchSummaryDto> dtos = pageItems.stream()
                .map(p -> BatchSummaryDto.fromProjection(p, aggregatesByBatchId.get(p.getId())))
                .collect(Collectors.toList());

        // Generate cursor for next page
        String nextCursor = null;
        if (hasNext && !pageItems.isEmpty()) {
            BatchWithFileCountProjection lastItem = pageItems.get(pageItems.size() - 1);
            nextCursor = encodeCursor(lastItem.getStartedAt(), lastItem.getId());
        }

        logger.info("Returning {} batches (hasNext={}) for accountId={}", dtos.size(), hasNext, accountId);

        return CursorPageResponseDto.of(dtos, nextCursor, hasNext);
    }

    /**
     * Generate cache key from site IDs list.
     * <p>
     * Sorts IDs before generating key to ensure consistent cache keys regardless of input order.
     * Example: [UUID2, UUID1] and [UUID1, UUID2] generate the same key.
     * </p>
     * <p>
     * <strong>Note:</strong> Must be public for Spring Cache SpEL expression access.
     * </p>
     *
     * @param siteIds List of site IDs
     * @return Sorted, comma-separated string of site IDs
     */
    public String generateCacheKey(List<UUID> siteIds) {
        return siteIds.stream()
                .sorted()
                .map(UUID::toString)
                .collect(Collectors.joining(","));
    }

    /**
     * T027: Fetch first page of batches (cacheable).
     * <p>
     * Cached with 5-minute TTL in Redis for performance.
     * Cache key: "batch-first-page:{sorted-siteIds}" (sorted to ensure consistent key)
     * </p>
     * <p>
     * <strong>Cache Key Consistency:</strong>
     * siteIds are sorted before creating cache key to ensure [UUID1, UUID2] and [UUID2, UUID1]
     * generate the same cache key and can share cached results.
     * </p>
     *
     * @param siteIds Site IDs to filter (will be sorted for cache key)
     * @param limit   Fetch limit
     * @return Batch projections
     */
    @Cacheable(value = "batch-first-page", key = "#root.target.generateCacheKey(#siteIds)")
    protected List<BatchWithFileCountProjection> fetchFirstPage(List<UUID> siteIds, int limit) {
        logger.debug("Fetching first page for siteIds={}, limit={}", siteIds.size(), limit);
        return batchRepository.findBySiteIdsFirstPage(siteIds, limit);
    }

    /**
     * T026: Fetch batches with cursor.
     * <p>
     * NOT cached - cursors are unique per request.
     * </p>
     *
     * @param siteIds Site IDs to filter
     * @param cursor  Cursor string (startedAt_id format)
     * @param limit   Fetch limit
     * @return Batch projections
     * @throws IllegalArgumentException if cursor format is invalid or cannot be parsed
     */
    protected List<BatchWithFileCountProjection> fetchWithCursor(List<UUID> siteIds, String cursor, int limit) {
        logger.debug("Fetching with cursor={}, siteIds={}, limit={}", cursor, siteIds.size(), limit);

        // Parse cursor: "2025-11-01T10:30:00_550e8400-e29b-41d4-a716-446655440000"
        String[] parts = cursor.split(CURSOR_DELIMITER, 2);
        if (parts.length != 2) {
            throw new IllegalArgumentException("Invalid cursor format. Expected: startedAt_id");
        }

        try {
            LocalDateTime cursorStartedAt = LocalDateTime.parse(parts[0]);
            UUID cursorId = UUID.fromString(parts[1]);

            return batchRepository.findBySiteIdsWithCursor(siteIds, cursorStartedAt, cursorId, limit);
        } catch (DateTimeParseException e) {
            logger.warn("Invalid cursor timestamp format: {}", parts[0], e);
            throw new IllegalArgumentException("Invalid cursor: malformed timestamp", e);
        } catch (IllegalArgumentException e) {
            logger.warn("Invalid cursor UUID format: {}", parts[1], e);
            throw new IllegalArgumentException("Invalid cursor: malformed UUID", e);
        }
    }

    /**
     * T026: Encode cursor from batch projection.
     * <p>
     * Format: "{startedAt}_{id}" (e.g., "2025-11-01T10:30:00_550e8400-e29b-41d4-a716-446655440000")
     * </p>
     *
     * @param startedAt Batch startedAt timestamp
     * @param id        Batch ID
     * @return Cursor string
     */
    private String encodeCursor(LocalDateTime startedAt, UUID id) {
        return startedAt.format(DateTimeFormatter.ISO_LOCAL_DATE_TIME) + CURSOR_DELIMITER + id.toString();
    }

    /**
     * T046/T047: Get batch details with file list.
     * <p>
     * Loads batch with all uploaded files eagerly (JOIN FETCH) to avoid N+1 queries.
     * Includes authorization check to ensure user owns the batch.
     * Redis caching with 30-minute TTL for COMPLETED batches only (immutable data).
     * </p>
     *
     * @param batchId   Batch identifier
     * @param accountId User's account ID (from JWT)
     * @return Batch details with file list
     * @throws BatchNotFoundException              if batch doesn't exist
     * @throws UnauthorizedBatchAccessException    if batch doesn't belong to user
     */
    @Cacheable(
        value = "batch-details",
        key = "#batchId",
        condition = "#result != null && #result.status() == 'COMPLETED'"
    )
    public BatchDetailDto getBatchDetails(UUID batchId, UUID accountId) {
        logger.info("Getting batch details for batchId={}, accountId={}", batchId, accountId);

        // Load batch with files using JOIN FETCH
        Batch batch = batchRepository.findByIdWithFiles(batchId)
                .orElseThrow(() -> new BatchNotFoundException(batchId));

        // Authorization check: verify batch belongs to user
        if (!batch.getAccountId().equals(accountId)) {
            logger.warn("Unauthorized batch access attempt: batchId={}, accountId={}, batchAccountId={}",
                    batchId, accountId, batch.getAccountId());
            throw new UnauthorizedBatchAccessException(batchId, accountId);
        }

        logger.info("Returning batch details for batchId={} with {} files",
                batchId, batch.getUploadedFiles().size());

        List<ChangelogSegment> segments = changelogSegmentRepository.findByBatchId(batchId);
        return BatchDetailDto.fromEntityAndFiles(batch, batch.getUploadedFiles(),
                resolveDeltaStats(segments), resolveMode(segments), resolveSeqRange(segments));
    }

    /**
     * Per-table insert/update/delete counts for a Delta v2 batch, sorted by table name for a
     * stable display order. Empty for v1 file-based batches (no changelog segment).
     */
    private static List<DeltaTableStatsDto> resolveDeltaStats(List<ChangelogSegment> segments) {
        Map<String, TableChangeStats> byTable = new TreeMap<>();
        segments.stream()
                .map(ChangelogSegment::getStats)
                .filter(Objects::nonNull)
                .flatMap(stats -> stats.entrySet().stream())
                .forEach(entry -> byTable.merge(entry.getKey(), entry.getValue(), (left, right) ->
                        new TableChangeStats(
                                left.inserts() + right.inserts(),
                                left.updates() + right.updates(),
                                left.deletes() + right.deletes())));
        return byTable.entrySet().stream()
                .map(entry -> DeltaTableStatsDto.of(entry.getKey(), entry.getValue()))
                .toList();
    }

    /** Session mode of a Delta v2 batch — all segments of one session share it. Null for v1 batches. */
    private static String resolveMode(List<ChangelogSegment> segments) {
        return segments.isEmpty() ? null : segments.get(0).getMode();
    }

    /**
     * Sequence range covered by a Delta v2 batch: min first_seq / max last_seq over the session's
     * segments (B9). Null for v1 batches.
     */
    private static DeltaSeqRangeDto resolveSeqRange(List<ChangelogSegment> segments) {
        if (segments.isEmpty()) {
            return null;
        }
        long first = segments.stream().mapToLong(ChangelogSegment::getFirstSeq).min().orElseThrow();
        long last = segments.stream().mapToLong(ChangelogSegment::getLastSeq).max().orElseThrow();
        return new DeltaSeqRangeDto(first, last);
    }
}
