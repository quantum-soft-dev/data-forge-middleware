package com.bitbi.dfm.comparison.infrastructure.persistence;

import com.bitbi.dfm.comparison.domain.ComparisonResult;
import com.bitbi.dfm.comparison.domain.ComparisonStatus;
import com.bitbi.dfm.comparison.domain.FileComparison;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * JPA entity for persisting {@link FileComparison} domain aggregates.
 *
 * <p>This entity is part of the infrastructure layer and is responsible for
 * mapping the FileComparison domain model to database tables.
 *
 * <p>Conversion between domain and entity is handled by static factory methods
 * and instance methods (fromDomain/toDomain pattern).
 */
@Entity
@Table(name = "file_comparisons")
@Getter
@NoArgsConstructor
public class FileComparisonEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "current_batch_id", nullable = false)
    private UUID currentBatchId;

    @Column(name = "target_batch_id", nullable = false)
    private UUID targetBatchId;

    @Column(name = "account_id", nullable = false)
    private UUID accountId;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private ComparisonStatus status;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "started_at")
    private Instant startedAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    @Column(name = "total_files_compared", nullable = false)
    private Integer totalFilesCompared = 0;

    @Column(name = "files_changed", nullable = false)
    private Integer filesChanged = 0;

    @Column(name = "files_added", nullable = false)
    private Integer filesAdded = 0;

    @Column(name = "files_unchanged", nullable = false)
    private Integer filesUnchanged = 0;

    @Column(name = "total_change_size", nullable = false)
    private Long totalChangeSize = 0L;

    @Column(name = "error_message", length = 1000)
    private String errorMessage;

    @OneToMany(mappedBy = "comparisonId", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    private List<ComparisonResultEntity> results = new ArrayList<>();

    /**
     * Lifecycle callback to set created timestamp before persist.
     */
    @PrePersist
    protected void onCreate() {
        if (createdAt == null) {
            createdAt = Instant.now();
        }
    }

    /**
     * Converts a domain FileComparison to a JPA entity.
     *
     * @param domain the domain object
     * @return the corresponding entity
     */
    public static FileComparisonEntity fromDomain(FileComparison domain) {
        if (domain == null) {
            return null;
        }

        FileComparisonEntity entity = new FileComparisonEntity();
        entity.id = domain.getId();
        entity.currentBatchId = domain.getCurrentBatchId();
        entity.targetBatchId = domain.getTargetBatchId();
        entity.accountId = domain.getAccountId();
        entity.status = domain.getStatus();
        entity.createdAt = domain.getCreatedAt();
        entity.startedAt = domain.getStartedAt();
        entity.completedAt = domain.getCompletedAt();
        entity.totalFilesCompared = domain.getTotalFilesCompared();
        entity.filesChanged = domain.getFilesChanged();
        entity.filesAdded = domain.getFilesAdded();
        entity.filesUnchanged = domain.getFilesUnchanged();
        entity.totalChangeSize = domain.getTotalChangeSize();
        entity.errorMessage = domain.getErrorMessage();

        // Convert results
        if (domain.getResults() != null && !domain.getResults().isEmpty()) {
            entity.results = domain.getResults().stream()
                .map(ComparisonResultEntity::fromDomain)
                .collect(Collectors.toList());
        }

        return entity;
    }

    /**
     * Converts this JPA entity to a domain FileComparison.
     *
     * @return the corresponding domain object
     */
    public FileComparison toDomain() {
        FileComparison domain = new FileComparison(currentBatchId, targetBatchId, accountId);

        // Use reflection or protected setters to set read-only fields
        try {
            var idField = FileComparison.class.getDeclaredField("id");
            idField.setAccessible(true);
            idField.set(domain, this.id);

            var statusField = FileComparison.class.getDeclaredField("status");
            statusField.setAccessible(true);
            statusField.set(domain, this.status);

            var createdAtField = FileComparison.class.getDeclaredField("createdAt");
            createdAtField.setAccessible(true);
            createdAtField.set(domain, this.createdAt);

            var startedAtField = FileComparison.class.getDeclaredField("startedAt");
            startedAtField.setAccessible(true);
            startedAtField.set(domain, this.startedAt);

            var completedAtField = FileComparison.class.getDeclaredField("completedAt");
            completedAtField.setAccessible(true);
            completedAtField.set(domain, this.completedAt);

            var totalField = FileComparison.class.getDeclaredField("totalFilesCompared");
            totalField.setAccessible(true);
            totalField.set(domain, this.totalFilesCompared);

            var changedField = FileComparison.class.getDeclaredField("filesChanged");
            changedField.setAccessible(true);
            changedField.set(domain, this.filesChanged);

            var addedField = FileComparison.class.getDeclaredField("filesAdded");
            addedField.setAccessible(true);
            addedField.set(domain, this.filesAdded);

            var unchangedField = FileComparison.class.getDeclaredField("filesUnchanged");
            unchangedField.setAccessible(true);
            unchangedField.set(domain, this.filesUnchanged);

            var sizeField = FileComparison.class.getDeclaredField("totalChangeSize");
            sizeField.setAccessible(true);
            sizeField.set(domain, this.totalChangeSize);

            var errorField = FileComparison.class.getDeclaredField("errorMessage");
            errorField.setAccessible(true);
            errorField.set(domain, this.errorMessage);

            // Convert results
            if (results != null && !results.isEmpty()) {
                List<ComparisonResult> domainResults = results.stream()
                    .map(ComparisonResultEntity::toDomain)
                    .collect(Collectors.toList());
                var resultsField = FileComparison.class.getDeclaredField("results");
                resultsField.setAccessible(true);
                resultsField.set(domain, domainResults);
            }

        } catch (ReflectiveOperationException e) {
            throw new RuntimeException("Failed to convert entity to domain", e);
        }

        return domain;
    }
}
