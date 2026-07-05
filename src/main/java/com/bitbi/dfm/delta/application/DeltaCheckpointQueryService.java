package com.bitbi.dfm.delta.application;

import com.bitbi.dfm.batch.infrastructure.S3PresignedUrlService;
import com.bitbi.dfm.batch.infrastructure.S3PresignedUrlService.PresignedUrlResult;
import com.bitbi.dfm.delta.domain.Checkpoint;
import com.bitbi.dfm.delta.domain.CheckpointRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Read-side application service for a site's checkpoints (feature 023 — Delta Sync UI, B5).
 *
 * <p>Lists the per-table checkpoint rows for the UI and issues short-lived presigned download
 * URLs for the materialized snapshot files. URLs are generated per click — never in the list
 * response — so polling the list does not mint URLs.</p>
 *
 * @author Data Forge Team
 * @version 1.0.0
 */
@Service
public class DeltaCheckpointQueryService {

    private final CheckpointRepository checkpointRepository;
    private final S3PresignedUrlService presignedUrlService;

    public DeltaCheckpointQueryService(CheckpointRepository checkpointRepository,
                                       S3PresignedUrlService presignedUrlService) {
        this.checkpointRepository = checkpointRepository;
        this.presignedUrlService = presignedUrlService;
    }

    /**
     * List a site's checkpoints sorted by table name.
     *
     * @param siteId site identifier
     * @return checkpoints ordered by table name
     */
    @Transactional(readOnly = true)
    public List<Checkpoint> listCheckpoints(UUID siteId) {
        return checkpointRepository.findBySiteId(siteId).stream()
                .sorted(Comparator.comparing(Checkpoint::getTableName))
                .toList();
    }

    /**
     * Issue a fresh presigned download URL (15 min) for one checkpoint file.
     *
     * @param siteId    site identifier
     * @param tableName checkpoint table name
     * @param format    requested file format: {@code csv} (legacy gzipped CSV) or {@code parquet}
     * @return presigned download, or empty when the checkpoint or the requested file does not exist
     * @throws IllegalArgumentException for an unsupported format (→ 400)
     */
    @Transactional(readOnly = true)
    public Optional<PresignedDownload> presignDownload(UUID siteId, String tableName, String format) {
        Optional<Checkpoint> found = checkpointRepository.findBySiteIdAndTableName(siteId, tableName);
        if (found.isEmpty()) {
            return Optional.empty();
        }
        Checkpoint checkpoint = found.get();

        String s3Key;
        String fileName;
        switch (format) {
            case "csv" -> {
                s3Key = checkpoint.getS3KeyCsv();
                fileName = "%s_seq%d.csv.gz".formatted(checkpoint.getTableName(), checkpoint.getSeq());
            }
            case "parquet" -> {
                s3Key = checkpoint.getS3KeyParquet();
                fileName = "%s_seq%d.parquet".formatted(checkpoint.getTableName(), checkpoint.getSeq());
            }
            default -> throw new IllegalArgumentException("Unsupported checkpoint format: " + format);
        }
        if (s3Key == null) {
            return Optional.empty(); // requested format not materialized yet (e.g. Parquet pending)
        }

        PresignedUrlResult result = presignedUrlService.generatePresignedUrl(s3Key, fileName);
        return Optional.of(new PresignedDownload(result.url(), fileName, result.expiresAt()));
    }

    /**
     * A freshly-issued presigned checkpoint download.
     *
     * @param downloadUrl presigned S3 URL (15-minute expiry)
     * @param fileName    suggested filename for the browser
     * @param expiresAt   URL expiration timestamp
     */
    public record PresignedDownload(String downloadUrl, String fileName, Instant expiresAt) {
    }
}
