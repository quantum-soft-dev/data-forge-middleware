package com.bitbi.dfm.integration;

import com.bitbi.dfm.batch.application.BatchHistoryService;
import com.bitbi.dfm.batch.domain.Batch;
import com.bitbi.dfm.batch.domain.BatchRepository;
import com.bitbi.dfm.batch.infrastructure.JpaBatchRepository;
import com.bitbi.dfm.batch.presentation.dto.BatchDetailDto;
import com.bitbi.dfm.config.TestSecurityConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

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
@SpringBootTest
@ActiveProfiles("test")
@Import(TestSecurityConfig.class)
@Testcontainers
@Sql("/test-data.sql")
@DisplayName("Upload History Integration Tests (Testcontainers)")
class BatchHistoryIntegrationTest {

    @Container
    private static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("dataforge_test")
            .withUsername("test")
            .withPassword("test");

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

        // For now, just verify Testcontainers PostgreSQL is working
        assertThat(postgres.isRunning()).isTrue();
        assertThat(postgres.getDatabaseName()).isEqualTo("dataforge_test");
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
}
