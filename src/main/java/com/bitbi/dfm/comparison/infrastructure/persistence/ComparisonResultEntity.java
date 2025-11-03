package com.bitbi.dfm.comparison.infrastructure.persistence;

import com.bitbi.dfm.comparison.domain.ChangeType;
import com.bitbi.dfm.comparison.domain.ComparisonResult;
import io.hypersistence.utils.hibernate.type.json.JsonBinaryType;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.Type;

import java.time.Instant;

/**
 * JPA entity for persisting {@link ComparisonResult} domain entities.
 *
 * <p>This entity is part of the infrastructure layer and handles the persistence
 * of individual file comparison results within a FileComparison aggregate.
 *
 * <p>The unifiedDiff field is stored as JSONB in PostgreSQL for queryability and efficiency.
 */
@Entity
@Table(name = "comparison_results")
@Getter
@NoArgsConstructor
public class ComparisonResultEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "comparison_id", nullable = false)
    private Long comparisonId;

    @Column(name = "file_id", nullable = false)
    private Long fileId;

    @Column(name = "target_file_id")
    private Long targetFileId;

    @Enumerated(EnumType.STRING)
    @Column(name = "change_type", nullable = false, length = 20)
    private ChangeType changeType;

    @Type(JsonBinaryType.class)
    @Column(name = "unified_diff", columnDefinition = "jsonb")
    private String unifiedDiff;

    @Column(name = "line_additions", nullable = false)
    private Integer lineAdditions = 0;

    @Column(name = "line_deletions", nullable = false)
    private Integer lineDeletions = 0;

    @Column(name = "change_size", nullable = false)
    private Long changeSize = 0L;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

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
     * Converts a domain ComparisonResult to a JPA entity.
     *
     * @param domain the domain object
     * @return the corresponding entity
     */
    public static ComparisonResultEntity fromDomain(ComparisonResult domain) {
        if (domain == null) {
            return null;
        }

        ComparisonResultEntity entity = new ComparisonResultEntity();
        entity.id = domain.getId();
        entity.comparisonId = domain.getComparisonId();
        entity.fileId = domain.getFileId();
        entity.targetFileId = domain.getTargetFileId();
        entity.changeType = domain.getChangeType();
        entity.unifiedDiff = domain.getUnifiedDiff();
        entity.lineAdditions = domain.getLineAdditions();
        entity.lineDeletions = domain.getLineDeletions();
        entity.changeSize = domain.getChangeSize();
        entity.createdAt = domain.getCreatedAt();

        return entity;
    }

    /**
     * Converts this JPA entity to a domain ComparisonResult.
     *
     * @return the corresponding domain object
     */
    public ComparisonResult toDomain() {
        ComparisonResult domain = new ComparisonResult(
            comparisonId,
            fileId,
            targetFileId,
            changeType,
            unifiedDiff,
            lineAdditions,
            lineDeletions,
            changeSize
        );

        // Set ID and createdAt using reflection
        try {
            var idField = ComparisonResult.class.getDeclaredField("id");
            idField.setAccessible(true);
            idField.set(domain, this.id);

            var createdAtField = ComparisonResult.class.getDeclaredField("createdAt");
            createdAtField.setAccessible(true);
            createdAtField.set(domain, this.createdAt);

        } catch (ReflectiveOperationException e) {
            throw new RuntimeException("Failed to convert entity to domain", e);
        }

        return domain;
    }
}
