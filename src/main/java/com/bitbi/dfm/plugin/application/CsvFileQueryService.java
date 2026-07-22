package com.bitbi.dfm.plugin.application;

import com.bitbi.dfm.delta.domain.Checkpoint;
import com.bitbi.dfm.delta.domain.CheckpointRepository;
import com.bitbi.dfm.delta.infrastructure.S3CheckpointStorage;
import com.bitbi.dfm.plugin.presentation.dto.FileDto;
import com.bitbi.dfm.site.domain.Site;
import com.bitbi.dfm.site.domain.SiteRepository;
import com.bitbi.dfm.upload.domain.UploadedFileRepository;
import com.bitbi.dfm.upload.domain.UploadedFileRepository.LatestFileInfoWithS3Key;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.S3Exception;

import java.io.InputStream;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

/**
 * Service for querying and retrieving CSV files for the Bit BI Plugin API.
 *
 * <p>Provides functionality to:
 * <ul>
 *   <li>List CSV files for a site (latest version of each file)</li>
 *   <li>Download a specific CSV file by name</li>
 *   <li>Validate site ownership before operations</li>
 * </ul>
 *
 * <p>Used for plugin initialization - clients download CSV files directly
 * instead of receiving SQL statements for the baseline batch.</p>
 *
 * @see com.bitbi.dfm.plugin.presentation.BitBiPluginApiController
 */
@Service
public class CsvFileQueryService {

    private static final Logger log = LoggerFactory.getLogger(CsvFileQueryService.class);

    private final UploadedFileRepository uploadedFileRepository;
    private final SiteRepository siteRepository;
    private final CheckpointRepository checkpointRepository;
    private final S3CheckpointStorage checkpointStorage;
    private final S3Client s3Client;
    private final String bucketName;
    private final MeterRegistry meterRegistry;

    public CsvFileQueryService(
            UploadedFileRepository uploadedFileRepository,
            SiteRepository siteRepository,
            CheckpointRepository checkpointRepository,
            S3CheckpointStorage checkpointStorage,
            S3Client s3Client,
            @Value("${s3.bucket.name}") String bucketName,
            MeterRegistry meterRegistry) {
        this.uploadedFileRepository = uploadedFileRepository;
        this.siteRepository = siteRepository;
        this.checkpointRepository = checkpointRepository;
        this.checkpointStorage = checkpointStorage;
        this.s3Client = s3Client;
        this.bucketName = bucketName;
        this.meterRegistry = meterRegistry;
    }

    /** Suffix used to expose a reconstructed-table checkpoint as a downloadable file. */
    private static final String CHECKPOINT_FILE_SUFFIX = ".csv.gz";

    /**
     * Lists all CSV files for a site.
     *
     * <p>Returns the latest version of each unique file name.
     * Validates that the site belongs to the specified account.</p>
     *
     * @param accountId the account ID from the API key authentication
     * @param siteId the site ID to list files for
     * @return list of file DTOs with metadata
     * @throws SecurityException if the site does not belong to the account
     */
    @Transactional(readOnly = true)
    public List<FileDto> listFiles(UUID accountId, UUID siteId) {
        log.debug("Listing files for siteId={}, accountId={}", siteId, accountId);

        // Validate site ownership
        Site site = validateSiteOwnership(accountId, siteId);

        // V2 (Delta) sites expose reconstructed checkpoint snapshots instead of raw uploads.
        if (site.isDeltaV2()) {
            return listCheckpointFiles(siteId);
        }

        // Get latest version of each file
        List<LatestFileInfoWithS3Key> files = uploadedFileRepository
                .findLatestByOriginalFileNameForSite(siteId);

        log.info("Found {} files for siteId={}", files.size(), siteId);

        meterRegistry.counter("plugin.api.files.listed",
                "siteId", siteId.toString(),
                "count", String.valueOf(files.size())).increment();

        return files.stream()
                .map(file -> FileDto.of(
                        file.getOriginalFileName(),
                        file.getFileSize(),
                        file.getUploadedAt()))
                .toList();
    }

    /**
     * Lists the reconstructed checkpoint snapshots for a V2 (Delta) site, one per table, exposed
     * under the legacy {@code <table>.csv.gz} naming so the Bit BI client contract is unchanged.
     */
    private List<FileDto> listCheckpointFiles(UUID siteId) {
        List<Checkpoint> checkpoints = checkpointRepository.findBySiteId(siteId).stream()
                .filter(cp -> cp.getS3KeyCsv() != null)
                .toList();

        log.info("Found {} checkpoint files for V2 siteId={}", checkpoints.size(), siteId);

        meterRegistry.counter("plugin.api.files.listed",
                "siteId", siteId.toString(),
                "count", String.valueOf(checkpoints.size())).increment();

        return checkpoints.stream()
                .map(cp -> FileDto.of(
                        cp.getTableName() + CHECKPOINT_FILE_SUFFIX,
                        checkpointStorage.contentLength(cp.getS3KeyCsv()),
                        cp.getUpdatedAt().toInstant(ZoneOffset.UTC)))
                .toList();
    }

    /**
     * Downloads a CSV file from S3.
     *
     * <p>Returns an InputStream that streams the file content directly from S3.
     * The caller is responsible for closing the stream.</p>
     *
     * @param accountId the account ID from the API key authentication
     * @param siteId the site ID the file belongs to
     * @param fileName the original file name to download
     * @return InputStream of the file content
     * @throws SecurityException if the site does not belong to the account
     * @throws FileNotFoundException if the file is not found
     */
    @Transactional(readOnly = true)
    public FileDownloadResult downloadFile(UUID accountId, UUID siteId, String fileName) {
        log.debug("Downloading file: siteId={}, fileName={}, accountId={}", siteId, fileName, accountId);

        // Validate site ownership
        Site site = validateSiteOwnership(accountId, siteId);

        // V2 (Delta) sites serve the reconstructed checkpoint CSV for the requested table.
        if (site.isDeltaV2()) {
            return downloadCheckpointFile(siteId, fileName);
        }

        // Find the file
        LatestFileInfoWithS3Key fileInfo = uploadedFileRepository
                .findLatestByOriginalFileNameForSiteAndFileName(siteId, fileName)
                .orElseThrow(() -> {
                    log.warn("File not found: siteId={}, fileName={}", siteId, fileName);
                    return new FileNotFoundException("File not found: " + fileName);
                });

        // Download from S3
        try {
            GetObjectRequest getObjectRequest = GetObjectRequest.builder()
                    .bucket(bucketName)
                    .key(fileInfo.getS3Key())
                    .build();

            ResponseInputStream<GetObjectResponse> s3Response = s3Client.getObject(getObjectRequest);

            log.info("Downloaded file from S3: siteId={}, fileName={}, s3Key={}",
                    siteId, fileName, fileInfo.getS3Key());

            meterRegistry.counter("plugin.api.files.downloaded",
                    "siteId", siteId.toString()).increment();

            return new FileDownloadResult(
                    s3Response,
                    fileInfo.getOriginalFileName(),
                    fileInfo.getFileSize(),
                    determineContentType(fileName)
            );

        } catch (S3Exception e) {
            log.error("Failed to download file from S3: s3Key={}, error={}",
                    fileInfo.getS3Key(), e.getMessage());
            throw new FileDownloadException("Failed to download file: " + fileName, e);
        }
    }

    /**
     * Downloads the reconstructed checkpoint CSV for a single table of a V2 (Delta) site.
     *
     * <p>The {@code fileName} is the {@code <table>.csv.gz} name exposed by {@link #listFiles}; the
     * table is resolved by stripping that suffix and matched against the site's checkpoints.</p>
     *
     * @throws FileNotFoundException if no checkpoint snapshot exists for the requested table
     */
    private FileDownloadResult downloadCheckpointFile(UUID siteId, String fileName) {
        String tableName = fileName.endsWith(CHECKPOINT_FILE_SUFFIX)
                ? fileName.substring(0, fileName.length() - CHECKPOINT_FILE_SUFFIX.length())
                : fileName;

        Checkpoint checkpoint = checkpointRepository.findBySiteIdAndTableName(siteId, tableName)
                .filter(cp -> cp.getS3KeyCsv() != null)
                .orElseThrow(() -> {
                    log.warn("Checkpoint snapshot not found: siteId={}, table={}", siteId, tableName);
                    return new FileNotFoundException("File not found: " + fileName);
                });

        S3CheckpointStorage.CheckpointObject object = checkpointStorage.open(checkpoint.getS3KeyCsv());

        log.info("Downloaded checkpoint CSV: siteId={}, table={}, s3Key={}",
                siteId, tableName, checkpoint.getS3KeyCsv());

        meterRegistry.counter("plugin.api.files.downloaded",
                "siteId", siteId.toString()).increment();

        return new FileDownloadResult(
                object.inputStream(),
                tableName + CHECKPOINT_FILE_SUFFIX,
                object.size(),
                "application/gzip"
        );
    }

    /**
     * Validates that the site belongs to the specified account.
     *
     * @return the owning site
     * @throws SecurityException if validation fails
     */
    private Site validateSiteOwnership(UUID accountId, UUID siteId) {
        Site site = siteRepository.findById(siteId)
                .orElseThrow(() -> {
                    log.warn("Site not found: siteId={}", siteId);
                    return new SecurityException("Site not found");
                });

        if (!site.getAccountId().equals(accountId)) {
            log.warn("Site ownership mismatch: siteId={}, requestedAccountId={}, actualAccountId={}",
                    siteId, accountId, site.getAccountId());
            throw new SecurityException("Site does not belong to your account");
        }
        return site;
    }

    /**
     * Determines the content type based on file extension.
     */
    private String determineContentType(String fileName) {
        String lowerName = fileName.toLowerCase();
        if (lowerName.endsWith(".csv.gz") || lowerName.endsWith(".gz")) {
            return "application/gzip";
        } else if (lowerName.endsWith(".csv")) {
            return "text/csv; charset=utf-8";
        }
        return "application/octet-stream";
    }

    /**
     * Result of a file download operation.
     */
    public record FileDownloadResult(
            InputStream inputStream,
            String fileName,
            long fileSize,
            String contentType
    ) {}

    /**
     * Exception thrown when a file is not found.
     */
    public static class FileNotFoundException extends RuntimeException {
        public FileNotFoundException(String message) {
            super(message);
        }
    }

    /**
     * Exception thrown when file download fails.
     */
    public static class FileDownloadException extends RuntimeException {
        public FileDownloadException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
