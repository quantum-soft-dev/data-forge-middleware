package com.bitbi.dfm.clientlog.application;

import com.bitbi.dfm.batch.infrastructure.S3PresignedUrlService;
import com.bitbi.dfm.clientlog.domain.ClientDiagnosticLog;
import com.bitbi.dfm.clientlog.domain.ClientDiagnosticLogRepository;
import com.bitbi.dfm.shared.exception.InvalidLogFileException;
import com.bitbi.dfm.shared.exception.LogUploadLimitExceededException;
import com.bitbi.dfm.site.domain.Site;
import com.bitbi.dfm.site.domain.SiteRepository;
import com.bitbi.dfm.upload.infrastructure.S3FileStorageService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Application service for client diagnostic log operations.
 * <p>
 * Handles log upload with file validation, rate limiting, and S3 storage.
 * Also provides listing and download URL generation for admin consumption.
 * </p>
 *
 * Feature: 021-unified-upload-api
 * User Story: US4 (upload), US5 (list, download)
 */
@Service
@Transactional
public class ClientDiagnosticLogService {

    private static final Logger logger = LoggerFactory.getLogger(ClientDiagnosticLogService.class);

    private static final Set<String> ALLOWED_EXTENSIONS = Set.of(
            ".log", ".log.gz", ".txt", ".txt.gz"
    );

    private static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    private final ClientDiagnosticLogRepository logRepository;
    private final SiteRepository siteRepository;
    private final S3FileStorageService s3FileStorageService;
    private final S3PresignedUrlService s3PresignedUrlService;
    private final int maxFileSizeMb;
    private final int maxUploadsPerDay;
    private final int retentionDays;

    public ClientDiagnosticLogService(
            ClientDiagnosticLogRepository logRepository,
            SiteRepository siteRepository,
            S3FileStorageService s3FileStorageService,
            S3PresignedUrlService s3PresignedUrlService,
            @Value("${client-logs.max-file-size-mb:10}") int maxFileSizeMb,
            @Value("${client-logs.max-uploads-per-day:10}") int maxUploadsPerDay,
            @Value("${client-logs.retention-days:30}") int retentionDays
    ) {
        this.logRepository = logRepository;
        this.siteRepository = siteRepository;
        this.s3FileStorageService = s3FileStorageService;
        this.s3PresignedUrlService = s3PresignedUrlService;
        this.maxFileSizeMb = maxFileSizeMb;
        this.maxUploadsPerDay = maxUploadsPerDay;
        this.retentionDays = retentionDays;
    }

    /**
     * Upload a client diagnostic log file.
     * <p>
     * Validates file type (.log, .log.gz, .txt, .txt.gz), enforces size limit,
     * checks daily rate limit, uploads to S3, and clears requestLogs directive.
     * </p>
     *
     * @param siteId        site identifier
     * @param accountId     account identifier
     * @param file          multipart log file
     * @param clientVersion client application version
     * @param os            client operating system
     * @param periodFrom    log period start
     * @param periodTo      log period end
     * @param tags          comma-separated tags
     * @param description   problem description
     * @return created ClientDiagnosticLog
     * @throws InvalidLogFileException         if file type is invalid or file is empty
     * @throws LogUploadLimitExceededException if daily limit exceeded
     */
    public ClientDiagnosticLog uploadLog(
            UUID siteId,
            UUID accountId,
            MultipartFile file,
            String clientVersion,
            String os,
            LocalDateTime periodFrom,
            LocalDateTime periodTo,
            String tags,
            String description
    ) {
        logger.info("Uploading client log: siteId={}, filename={}, size={}",
                siteId, file.getOriginalFilename(), file.getSize());

        // Validate file
        validateFile(file);

        // Check rate limit
        checkRateLimit(siteId);

        // Parse tags
        List<String> tagList = parseTags(tags);

        // Generate S3 path: client-logs/{accountId}/{siteId}/{date}/{logId}/{filename}
        UUID logId = UUID.randomUUID();
        String date = LocalDate.now().format(DATE_FORMAT);
        String s3Path = String.format("client-logs/%s/%s/%s/%s/", accountId, siteId, date, logId);
        String filename = file.getOriginalFilename() != null ? file.getOriginalFilename() : "log-file";

        // Upload to S3
        String s3Key = s3FileStorageService.uploadFile(file, s3Path, filename);

        // Create entity
        ClientDiagnosticLog log = ClientDiagnosticLog.create(
                siteId, accountId, s3Key, filename, file.getSize(),
                file.getContentType(), clientVersion, os,
                periodFrom, periodTo, tagList, description, retentionDays
        );
        ClientDiagnosticLog saved = logRepository.save(log);

        // Clear requestLogs directive on the site
        siteRepository.findById(siteId).ifPresent(site -> {
            if (site.getRequestLogs()) {
                site.clearRequestLogs();
                siteRepository.save(site);
                logger.info("Cleared requestLogs directive for site: {}", siteId);
            }
        });

        logger.info("Client log uploaded: logId={}, siteId={}, s3Key={}", saved.getId(), siteId, s3Key);
        return saved;
    }

    /**
     * List diagnostic logs for a site with pagination.
     *
     * @param siteId   site identifier
     * @param pageable pagination parameters
     * @return page of diagnostic logs
     */
    @Transactional(readOnly = true)
    public Page<ClientDiagnosticLog> listLogs(UUID siteId, Pageable pageable) {
        return logRepository.findBySiteIdOrderByUploadedAtDesc(siteId, pageable);
    }

    /**
     * Get a single log entry by ID, verifying site ownership.
     *
     * @param siteId site identifier (for ownership verification)
     * @param logId  log identifier
     * @return the log entry
     * @throws IllegalArgumentException if log not found or site mismatch
     */
    @Transactional(readOnly = true)
    public ClientDiagnosticLog getLog(UUID siteId, UUID logId) {
        ClientDiagnosticLog log = logRepository.findById(logId)
                .orElseThrow(() -> new IllegalArgumentException("Client log not found: " + logId));

        if (!log.getSiteId().equals(siteId)) {
            throw new IllegalArgumentException("Client log does not belong to site: " + siteId);
        }

        return log;
    }

    /**
     * Generate a presigned download URL for a client log file.
     *
     * @param siteId site identifier (for ownership verification)
     * @param logId  log identifier
     * @return presigned URL result
     * @throws IllegalArgumentException if log not found or site mismatch
     */
    @Transactional(readOnly = true)
    public S3PresignedUrlService.PresignedUrlResult getDownloadUrl(UUID siteId, UUID logId) {
        ClientDiagnosticLog log = getLog(siteId, logId);
        return s3PresignedUrlService.generatePresignedUrl(log.getS3Key(), log.getFilename());
    }

    /**
     * Delete expired logs from S3 and database.
     *
     * @return number of logs deleted
     */
    public int deleteExpiredLogs() {
        List<ClientDiagnosticLog> expiredLogs = logRepository.findByExpiresAtBefore(LocalDateTime.now());

        if (expiredLogs.isEmpty()) {
            logger.debug("No expired client logs to delete");
            return 0;
        }

        logger.info("Deleting {} expired client logs", expiredLogs.size());

        int deleted = 0;
        for (ClientDiagnosticLog log : expiredLogs) {
            try {
                s3FileStorageService.deleteFile(log.getS3Key());
                logRepository.delete(log);
                deleted++;
            } catch (Exception e) {
                logger.error("Failed to delete expired log: logId={}, s3Key={}, error={}",
                        log.getId(), log.getS3Key(), e.getMessage());
            }
        }

        logger.info("Deleted {} expired client logs", deleted);
        return deleted;
    }

    private void validateFile(MultipartFile file) {
        if (file.isEmpty()) {
            throw new InvalidLogFileException("Log file is empty");
        }

        long maxSizeBytes = (long) maxFileSizeMb * 1024 * 1024;
        if (file.getSize() > maxSizeBytes) {
            throw new InvalidLogFileException(
                    String.format("Log file exceeds maximum size of %dMB", maxFileSizeMb));
        }

        String filename = file.getOriginalFilename();
        if (filename == null || filename.isBlank()) {
            throw new InvalidLogFileException("Log file must have a filename");
        }

        String lowerFilename = filename.toLowerCase();
        boolean validExtension = ALLOWED_EXTENSIONS.stream().anyMatch(lowerFilename::endsWith);
        if (!validExtension) {
            throw new InvalidLogFileException(
                    "Invalid file type. Allowed extensions: " + ALLOWED_EXTENSIONS);
        }
    }

    private void checkRateLimit(UUID siteId) {
        LocalDateTime startOfDay = LocalDateTime.of(LocalDate.now(), LocalTime.MIN);
        long todayCount = logRepository.countBySiteIdAndUploadedAtAfter(siteId, startOfDay);

        if (todayCount >= maxUploadsPerDay) {
            throw new LogUploadLimitExceededException(siteId, maxUploadsPerDay);
        }
    }

    private List<String> parseTags(String tags) {
        if (tags == null || tags.isBlank()) {
            return null;
        }
        return List.of(tags.split(",")).stream()
                .map(String::trim)
                .filter(t -> !t.isEmpty())
                .toList();
    }
}
