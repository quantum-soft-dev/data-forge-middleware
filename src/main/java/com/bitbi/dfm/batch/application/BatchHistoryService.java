package com.bitbi.dfm.batch.application;

import com.bitbi.dfm.batch.domain.Batch;
import com.bitbi.dfm.batch.domain.exception.BatchNotFoundException;
import com.bitbi.dfm.batch.domain.exception.UnauthorizedBatchAccessException;
import com.bitbi.dfm.batch.infrastructure.BatchWithFileCountProjection;
import com.bitbi.dfm.batch.infrastructure.JpaBatchRepository;
import com.bitbi.dfm.batch.presentation.dto.BatchDetailDto;
import com.bitbi.dfm.batch.presentation.dto.BatchSummaryDto;
import com.bitbi.dfm.batch.presentation.dto.CursorPageResponseDto;
import com.bitbi.dfm.site.domain.Site;
import com.bitbi.dfm.site.infrastructure.JpaSiteRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
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

    public BatchHistoryService(JpaBatchRepository batchRepository, JpaSiteRepository siteRepository) {
        this.batchRepository = batchRepository;
        this.siteRepository = siteRepository;
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

        // Convert projections to DTOs
        List<BatchSummaryDto> dtos = pageItems.stream()
                .map(BatchSummaryDto::fromProjection)
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
     * T027: Fetch first page of batches (cacheable).
     * <p>
     * Cached with 5-minute TTL in Redis for performance.
     * Cache key: "batch-first-page:{accountId}"
     * </p>
     *
     * @param siteIds Site IDs to filter
     * @param limit   Fetch limit
     * @return Batch projections
     */
    @Cacheable(value = "batch-first-page", key = "#siteIds.toString()")
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
     */
    protected List<BatchWithFileCountProjection> fetchWithCursor(List<UUID> siteIds, String cursor, int limit) {
        logger.debug("Fetching with cursor={}, siteIds={}, limit={}", cursor, siteIds.size(), limit);

        // Parse cursor: "2025-11-01T10:30:00_550e8400-e29b-41d4-a716-446655440000"
        String[] parts = cursor.split(CURSOR_DELIMITER, 2);
        if (parts.length != 2) {
            throw new IllegalArgumentException("Invalid cursor format. Expected: startedAt_id");
        }

        LocalDateTime cursorStartedAt = LocalDateTime.parse(parts[0]);
        UUID cursorId = UUID.fromString(parts[1]);

        return batchRepository.findBySiteIdsWithCursor(siteIds, cursorStartedAt, cursorId, limit);
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

        return BatchDetailDto.fromEntityAndFiles(batch, batch.getUploadedFiles());
    }
}
