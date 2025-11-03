package com.bitbi.dfm.comparison.infrastructure;

import com.bitbi.dfm.comparison.domain.ComparisonRepository;
import com.bitbi.dfm.comparison.domain.ComparisonStatus;
import com.bitbi.dfm.comparison.domain.FileComparison;
import com.bitbi.dfm.comparison.infrastructure.persistence.FileComparisonEntity;
import com.bitbi.dfm.comparison.infrastructure.persistence.SpringDataComparisonRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * JPA adapter implementation of {@link ComparisonRepository} domain interface.
 *
 * <p>This adapter bridges the gap between the domain layer and the infrastructure layer,
 * translating between domain objects and JPA entities.
 *
 * <p>It delegates actual persistence operations to {@link SpringDataComparisonRepository}
 * and performs entity-to-domain conversions.
 */
@Component
public class JpaComparisonRepository implements ComparisonRepository {

    private final SpringDataComparisonRepository springDataRepository;

    public JpaComparisonRepository(SpringDataComparisonRepository springDataRepository) {
        this.springDataRepository = springDataRepository;
    }

    @Override
    public FileComparison save(FileComparison comparison) {
        if (comparison == null) {
            throw new IllegalArgumentException("FileComparison cannot be null");
        }

        FileComparisonEntity entity = FileComparisonEntity.fromDomain(comparison);
        FileComparisonEntity savedEntity = springDataRepository.save(entity);
        return savedEntity.toDomain();
    }

    @Override
    public Optional<FileComparison> findById(Long id) {
        return springDataRepository.findById(id)
            .map(FileComparisonEntity::toDomain);
    }

    @Override
    public Optional<FileComparison> findByIdWithResults(Long id) {
        return springDataRepository.findByIdWithResults(id)
            .map(FileComparisonEntity::toDomain);
    }

    @Override
    public List<FileComparison> findByAccountId(UUID accountId) {
        return springDataRepository.findByAccountId(accountId).stream()
            .map(FileComparisonEntity::toDomain)
            .collect(Collectors.toList());
    }

    @Override
    public Page<FileComparison> findByAccountIdOrderByCreatedAtDesc(UUID accountId, Pageable pageable) {
        return springDataRepository.findByAccountIdOrderByCreatedAtDesc(accountId, pageable)
            .map(FileComparisonEntity::toDomain);
    }

    @Override
    public List<FileComparison> findByCurrentBatchId(UUID batchId) {
        return springDataRepository.findByCurrentBatchId(batchId).stream()
            .map(FileComparisonEntity::toDomain)
            .collect(Collectors.toList());
    }

    @Override
    public List<FileComparison> findByTargetBatchId(UUID batchId) {
        return springDataRepository.findByTargetBatchId(batchId).stream()
            .map(FileComparisonEntity::toDomain)
            .collect(Collectors.toList());
    }

    @Override
    public boolean existsByCurrentBatchIdAndTargetBatchIdAndStatus(
        UUID currentBatchId,
        UUID targetBatchId,
        ComparisonStatus status
    ) {
        return springDataRepository.existsByCurrentBatchIdAndTargetBatchIdAndStatus(
            currentBatchId, targetBatchId, status
        );
    }

    @Override
    public List<FileComparison> findByStatus(ComparisonStatus status) {
        return springDataRepository.findByStatus(status).stream()
            .map(FileComparisonEntity::toDomain)
            .collect(Collectors.toList());
    }

    @Override
    public long countByAccountId(UUID accountId) {
        return springDataRepository.countByAccountId(accountId);
    }

    @Override
    public void delete(FileComparison comparison) {
        if (comparison == null) {
            throw new IllegalArgumentException("FileComparison cannot be null");
        }
        if (!comparison.canDelete()) {
            throw new IllegalStateException(
                "Cannot delete comparison with status " + comparison.getStatus() +
                " (IN_PROGRESS comparisons cannot be deleted)"
            );
        }

        FileComparisonEntity entity = FileComparisonEntity.fromDomain(comparison);
        springDataRepository.delete(entity);
    }

    @Override
    public void deleteById(Long id) {
        springDataRepository.deleteById(id);
    }

    @Override
    public long deleteByAccountId(UUID accountId) {
        return springDataRepository.deleteByAccountId(accountId);
    }
}
