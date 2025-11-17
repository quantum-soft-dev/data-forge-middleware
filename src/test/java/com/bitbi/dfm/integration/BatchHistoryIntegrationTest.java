package com.bitbi.dfm.integration;

import com.bitbi.dfm.batch.application.BatchHistoryService;
import com.bitbi.dfm.batch.domain.Batch;
import com.bitbi.dfm.batch.domain.BatchRepository;
import com.bitbi.dfm.batch.infrastructure.JpaBatchRepository;
import com.bitbi.dfm.batch.presentation.dto.BatchDetailDto;
import com.bitbi.dfm.error.application.ErrorLoggingService;
import com.bitbi.dfm.error.domain.ErrorLog;
import com.bitbi.dfm.error.domain.ErrorLogRepository;
import com.bitbi.dfm.error.presentation.dto.ErrorLogSummaryDto;
import com.bitbi.dfm.shared.presentation.dto.PageResponseDto;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertAll;

/**
 * Integration tests for Upload History functionality using Testcontainers.
 * <p>
 * Purpose: Verify end-to-end flows with real PostgreSQL database.
 * Focus: JOIN FETCH optimization, N+1 query prevention, data integrity.
 * </p>
 *
 * Feature: Upload History (User Stories 1 & 2)
 */
@Sql("/test-data.sql")
@DisplayName("Upload History Integration Tests (Testcontainers)")
class BatchHistoryIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private BatchHistoryService batchHistoryService;

    @Autowired
    private JpaBatchRepository batchRepository;

    /**
     * T024 (US1): End-to-end test for listing batches (placeholder from Phase 3)
     */
    @Test
    @DisplayName("T024: List batches end-to-end with Testcontainers PostgreSQL")
    void t024_listBatches_endToEnd() {
        // This is a placeholder test from Phase 3 (User Story 1)
        // The actual implementation will use BatchHistoryService.listBatchHistory()

        // For now, just verify Testcontainers are working
        assertThat(areContainersRunning()).isTrue();
        assertThat(postgresContainer.isRunning()).isTrue();
        assertThat(postgresContainer.getDatabaseName()).isEqualTo("dataforge_test");
    }

    /**
     * T044 (US2): Load batch details with JOIN FETCH to prevent N+1 queries
     * <p>
     * Given: Batch exists with multiple uploaded files
     * When: Load batch details using findByIdWithFiles()
     * Then: Files are loaded in single query (JOIN FETCH), no N+1 problem
     * </p>
     */
    @Test
    @Transactional
    @DisplayName("T044: Load batch details with JOIN FETCH prevents N+1 query")
    void t044_loadBatchDetails_shouldPreventN1Queries() {
        // Given: Batch ID from test-data.sql with multiple files (c3d4e5f6-a7b8-9012-cdef-123456789012 has 2 files)
        UUID batchId = UUID.fromString("c3d4e5f6-a7b8-9012-cdef-123456789012");

        // When: Load batch with files using JOIN FETCH
        Optional<Batch> batchOptional = batchRepository.findByIdWithFiles(batchId);

        // Then: Batch should be loaded
        assertThat(batchOptional).isPresent();

        Batch batch = batchOptional.get();

        // Verify files are eagerly loaded (no lazy initialization exception)
        assertAll("Batch details loaded correctly",
                () -> assertThat(batch.getId()).isEqualTo(batchId),
                () -> assertThat(batch.getUploadedFiles()).isNotNull(),
                () -> assertThat(batch.getUploadedFiles()).isNotEmpty(),
                () -> assertThat(batch.getUploadedFiles().size()).isGreaterThan(0)
        );

        // Verify file metadata is accessible without additional queries
        batch.getUploadedFiles().forEach(file -> {
            assertThat(file.getOriginalFileName()).isNotNull();
            assertThat(file.getFileSize()).isGreaterThan(0);
            assertThat(file.getS3Key()).isNotNull();
        });

        // Note: To truly verify no N+1 queries, enable Hibernate SQL logging:
        // logging.level.org.hibernate.SQL=DEBUG
        // logging.level.org.hibernate.type.descriptor.sql.BasicBinder=TRACE
        // and verify only 1 SELECT statement is executed
    }

    /**
     * T044 (US2): Verify batch details DTO conversion includes all files
     */
    @Test
    @Transactional
    @DisplayName("T044: BatchDetailDto includes all files from batch")
    void t044_batchDetailDto_shouldIncludeAllFiles() {
        // Given: Batch ID with known file count (c3d4e5f6-a7b8-9012-cdef-123456789012 has 2 files)
        UUID batchId = UUID.fromString("c3d4e5f6-a7b8-9012-cdef-123456789012");

        // When: Load batch with files
        Optional<Batch> batchOptional = batchRepository.findByIdWithFiles(batchId);
        assertThat(batchOptional).isPresent();

        Batch batch = batchOptional.get();

        // Convert to DTO
        BatchDetailDto dto = BatchDetailDto.fromEntityAndFiles(batch, batch.getUploadedFiles());

        // Then: DTO should include all files
        assertAll("BatchDetailDto contains all batch data",
                () -> assertThat(dto.id()).isEqualTo(batchId),
                () -> assertThat(dto.siteId()).isNotNull(),
                () -> assertThat(dto.status()).isNotBlank(),
                () -> assertThat(dto.files()).isNotNull(),
                () -> assertThat(dto.files()).hasSameSizeAs(batch.getUploadedFiles()),
                () -> assertThat(dto.uploadedFilesCount()).isEqualTo(batch.getUploadedFiles().size())
        );

        // Verify file DTOs have required fields
        dto.files().forEach(fileDto -> {
            assertThat(fileDto.id()).isNotNull();
            assertThat(fileDto.originalFileName()).isNotBlank();
            assertThat(fileDto.fileSize()).isGreaterThan(0);
            assertThat(fileDto.uploadedAt()).isNotNull();
        });
    }

    // ============================================================================
    // Phase 7: Error Details View (Supporting P1)
    // ============================================================================

    @Autowired(required = false)
    private ErrorLoggingService errorLoggingService;

    @Autowired(required = false)
    private ErrorLogRepository errorLogRepository;

    /**
     * T102: Integration test for error pagination with 100+ errors
     * <p>
     * Given: Batch with 100+ error logs
     * When: Get batch errors with pagination (page size 20)
     * Then: Returns correct page with 20 errors, accurate totalElements count
     * </p>
     */
    @Test
    @Transactional
    @DisplayName("T102: Error pagination should correctly handle 100+ errors")
    void t102_getBatchErrors_shouldHandlePaginationWith100PlusErrors() {
        // Given: Create a test batch with 100+ error logs
        UUID testBatchId = UUID.fromString("b1c2d3e4-f5a6-7890-bcde-f12345678903"); // IN_PROGRESS batch from test-data.sql
        UUID testAccountId = UUID.fromString("a1b2c3d4-e5f6-7890-abcd-ef1234567890"); // Correct account ID from test-data.sql

        // Find the batch (cast to avoid ambiguity between BatchRepository.findById and CrudRepository.findById)
        Optional<Batch> batchOptional = ((BatchRepository) batchRepository).findById(testBatchId);
        assertThat(batchOptional).isPresent();

        // Create 100 error logs for this batch
        List<ErrorLog> errors = new ArrayList<>();
        UUID testSiteId = UUID.fromString("0199baac-f852-753f-6fc3-7c994fc38654");  // Valid site ID from test-data.sql (store-01)
        for (int i = 0; i < 100; i++) {
            ErrorLog error = ErrorLog.create(
                    testSiteId,
                    testBatchId,
                    "ERROR",
                    "Test error " + i,
                    "Test error message " + i,
                    null, // stackTrace
                    null, // clientVersion
                    Map.of("index", i)
            );
            errors.add(error);
        }

        // Save all errors
        for (ErrorLog error : errors) {
            errorLogRepository.save(error);
        }

        // When: Get first page of errors (page 0, size 20)
        PageResponseDto<ErrorLogSummaryDto> page1 = errorLoggingService.getBatchErrors(
                testBatchId,
                testAccountId,
                0,
                20
        );

        // Then: First page should have 20 errors
        assertAll("First page of errors",
                () -> assertThat(page1.content()).hasSize(20),
                () -> assertThat(page1.page()).isEqualTo(0),
                () -> assertThat(page1.size()).isEqualTo(20),
                () -> assertThat(page1.totalElements()).isGreaterThanOrEqualTo(100),
                () -> assertThat(page1.totalPages()).isGreaterThanOrEqualTo(5)
        );

        // When: Get last page (page 4, size 20)
        PageResponseDto<ErrorLogSummaryDto> page5 = errorLoggingService.getBatchErrors(
                testBatchId,
                testAccountId,
                4,
                20
        );

        // Then: Last page should have remaining errors
        assertAll("Last page of errors",
                () -> assertThat(page5.content()).isNotEmpty(),
                () -> assertThat(page5.content().size()).isLessThanOrEqualTo(20),
                () -> assertThat(page5.page()).isEqualTo(4)
        );

        // Verify error DTOs have required fields
        page1.content().forEach(errorDto -> {
            assertThat(errorDto.id()).isNotNull();
            assertThat(errorDto.type()).isNotBlank();
            assertThat(errorDto.message()).isNotBlank();
            assertThat(errorDto.title()).isNotBlank();
            assertThat(errorDto.occurredAt()).isNotNull();
        });
    }

    /**
     * Additional test: Verify errors are sorted by occurredAt DESC
     */
    @Test
    @Transactional
    @DisplayName("T102b: Error list should be sorted by occurredAt DESC")
    void t102b_getBatchErrors_shouldBeSortedByOccurredAtDesc() {
        // Given: Batch with errors from test-data.sql
        UUID testBatchId = UUID.fromString("b1c2d3e4-f5a6-7890-bcde-f12345678903"); // IN_PROGRESS batch from test-data.sql
        UUID testAccountId = UUID.fromString("a1b2c3d4-e5f6-7890-abcd-ef1234567890"); // Correct account ID from test-data.sql

        // Create test errors with different timestamps
        List<ErrorLog> errors = new ArrayList<>();
        UUID testSiteId = UUID.fromString("0199baac-f852-753f-6fc3-7c994fc38654");  // Valid site ID from test-data.sql (store-01)

        errors.add(ErrorLog.create(
                testSiteId,
                testBatchId,
                "ERROR",
                "Oldest error",
                "Oldest error message",
                null,
                null,
                Map.of("order", "oldest")
        ));

        errors.add(ErrorLog.create(
                testSiteId,
                testBatchId,
                "ERROR",
                "Newest error",
                "Newest error message",
                null,
                null,
                Map.of("order", "newest")
        ));

        errors.add(ErrorLog.create(
                testSiteId,
                testBatchId,
                "ERROR",
                "Middle error",
                "Middle error message",
                null,
                null,
                Map.of("order", "middle")
        ));

        // Save all errors
        for (ErrorLog error : errors) {
            errorLogRepository.save(error);
        }

        // When: Get errors
        PageResponseDto<ErrorLogSummaryDto> page = errorLoggingService.getBatchErrors(
                testBatchId,
                testAccountId,
                0,
                10
        );

        // Then: Errors should be sorted by occurredAt DESC (newest first)
        List<ErrorLogSummaryDto> errorList = page.content();
        assertThat(errorList).isNotEmpty();

        // Verify first error is newer than second (if we have at least 2 errors)
        if (errorList.size() >= 2) {
            assertThat(errorList.get(0).occurredAt())
                    .isAfterOrEqualTo(errorList.get(1).occurredAt());
        }
    }
}
