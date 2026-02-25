package com.bitbi.dfm.site.application;

import com.bitbi.dfm.auth.application.RefreshTokenService;
import com.bitbi.dfm.batch.domain.Batch;
import com.bitbi.dfm.batch.domain.BatchRepository;
import com.bitbi.dfm.deviceauth.domain.DeviceAuthorizationRepository;
import com.bitbi.dfm.error.domain.ErrorLogRepository;
import com.bitbi.dfm.site.domain.Site;
import com.bitbi.dfm.site.domain.SiteCredentials;
import com.bitbi.dfm.site.domain.SiteRepository;
import com.bitbi.dfm.site.domain.SiteType;
import com.bitbi.dfm.shared.domain.events.AccountDeactivatedEvent;
import com.bitbi.dfm.upload.domain.UploadedFileRepository;
import com.bitbi.dfm.upload.infrastructure.S3FileStorageService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/**
 * Application service for site management operations.
 * <p>
 * Handles site CRUD operations, clientSecret generation,
 * and listens for account deactivation events.
 * </p>
 *
 * @author Data Forge Team
 * @version 1.0.0
 */
@Service
@Transactional
public class SiteService {

    private static final Logger logger = LoggerFactory.getLogger(SiteService.class);

    private final SiteRepository siteRepository;
    private final BatchRepository batchRepository;
    private final ErrorLogRepository errorLogRepository;
    private final UploadedFileRepository uploadedFileRepository;
    private final S3FileStorageService s3FileStorageService;
    private final DeviceAuthorizationRepository deviceAuthorizationRepository;
    private final RefreshTokenService refreshTokenService;
    private final SiteSchemaService siteSchemaService;

    public SiteService(SiteRepository siteRepository,
                       BatchRepository batchRepository,
                       ErrorLogRepository errorLogRepository,
                       UploadedFileRepository uploadedFileRepository,
                       S3FileStorageService s3FileStorageService,
                       DeviceAuthorizationRepository deviceAuthorizationRepository,
                       RefreshTokenService refreshTokenService,
                       SiteSchemaService siteSchemaService) {
        this.siteRepository = siteRepository;
        this.batchRepository = batchRepository;
        this.errorLogRepository = errorLogRepository;
        this.uploadedFileRepository = uploadedFileRepository;
        this.s3FileStorageService = s3FileStorageService;
        this.deviceAuthorizationRepository = deviceAuthorizationRepository;
        this.refreshTokenService = refreshTokenService;
        this.siteSchemaService = siteSchemaService;
    }

    /**
     * Create new site for account. Site type defaults to DBF.
     *
     * @param accountId   account identifier
     * @param siteName    site name (must be unique within account)
     * @param displayName site display name
     * @return SiteCreationResult with site and plaintext clientSecret (only shown once)
     * @throws SiteAlreadyExistsException if site name already exists for account
     */
    public SiteCreationResult createSite(UUID accountId, String siteName, String displayName) {
        return createSite(accountId, siteName, displayName, SiteType.DBF);
    }

    /**
     * Create new site for account with explicit site type.
     *
     * @param accountId   account identifier
     * @param siteName    site name (must be unique within account)
     * @param displayName site display name
     * @param siteType    site type (DBF or POSTGRES_CDC); defaults to DBF if null
     * @return SiteCreationResult with site and plaintext clientSecret (only shown once)
     * @throws SiteAlreadyExistsException if site name already exists for account
     */
    public SiteCreationResult createSite(UUID accountId, String siteName, String displayName, SiteType siteType) {
        logger.info("Creating new site: accountId={}, siteName={}, displayName={}, siteType={}",
                accountId, siteName, displayName, siteType);

        String cleanSiteName = siteName.toLowerCase().trim();

        // Check uniqueness by (accountId, siteName)
        if (siteRepository.findByAccountIdAndSiteName(accountId, cleanSiteName).isPresent()) {
            throw new SiteAlreadyExistsException("Site with name already exists: " + siteName);
        }

        // Create composite domain for backward compatibility
        String compositeDomain = accountId.toString() + "_" + cleanSiteName;

        SiteType resolvedType = siteType != null ? siteType : SiteType.DBF;
        Site site = Site.create(accountId, compositeDomain, cleanSiteName, displayName, null, resolvedType);
        Site saved = siteRepository.save(site);

        logger.info("Site created successfully: id={}, siteName={}, siteType={}", saved.getId(), saved.getSiteName(), resolvedType);
        return new SiteCreationResult(saved, null);
    }

    /**
     * Create new site for account with custom password (DBF type).
     *
     * @param accountId   account identifier
     * @param siteName    site name (must be unique within account)
     * @param displayName site display name
     * @param password    plaintext password (min 8 chars)
     * @return SiteCreationResult with site and plaintext clientSecret
     * @throws SiteAlreadyExistsException if site name already exists for account
     * @throws IllegalArgumentException   if password is invalid
     */
    public SiteCreationResult createSite(UUID accountId, String siteName, String displayName, String password) {
        logger.info("Creating new site with custom password: accountId={}, siteName={}, displayName={}",
                    accountId, siteName, displayName);

        if (password == null || password.length() < 8) {
            throw new IllegalArgumentException("Password must be at least 8 characters");
        }

        String cleanSiteName = siteName.toLowerCase().trim();

        if (siteRepository.findByAccountIdAndSiteName(accountId, cleanSiteName).isPresent()) {
            throw new SiteAlreadyExistsException("Site with name already exists: " + siteName);
        }

        // Hash the provided password
        String[] secretPair = SiteCredentials.hashPassword(password);
        String hashedSecret = secretPair[1];

        // Create composite domain for backward compatibility
        String compositeDomain = accountId.toString() + "_" + cleanSiteName;

        Site site = Site.create(accountId, compositeDomain, cleanSiteName, displayName, hashedSecret, SiteType.DBF);
        Site saved = siteRepository.save(site);

        logger.info("Site created successfully with custom password: id={}, siteName={}",
                    saved.getId(), saved.getSiteName());
        return new SiteCreationResult(saved, password);
    }

    /**
     * Result of site creation containing site entity and plaintext secret.
     *
     * @param site            created site entity
     * @param plaintextSecret plaintext clientSecret (only returned once at creation)
     */
    public record SiteCreationResult(Site site, String plaintextSecret) {
    }

    /**
     * Get existing site or create new one, regenerating credentials in both cases.
     * <p>
     * Used by Device Flow when user approves authorization:
     * - If site exists: Regenerate secret (old clients lose access)
     * - If site doesn't exist: Create new site
     * </p>
     *
     * @param accountId   account identifier
     * @param domain      site domain (without accountId prefix)
     * @param displayName site display name
     * @return SiteCreationResult with site and new plaintext clientSecret
     */
    /**
     * Get existing site or create new one (Auth V2 - no credential generation). Site type defaults to DBF.
     * <p>
     * Used by Device Flow when user approves authorization:
     * - If site exists: Reactivate if needed, return existing site
     * - If site doesn't exist: Create new site
     * </p>
     */
    public SiteCreationResult getOrCreateSite(UUID accountId, String siteName, String displayName) {
        return getOrCreateSite(accountId, siteName, displayName, SiteType.DBF);
    }

    /**
     * Get existing site or create new one with explicit site type (Auth V2 - no credential generation).
     * <p>
     * Used by Device Flow when user approves authorization:
     * - If site exists: Reactivate if needed, return existing site (type unchanged — immutable)
     * - If site doesn't exist: Create new site with given type
     * </p>
     *
     * @param accountId   account identifier
     * @param siteName    site name
     * @param displayName site display name
     * @param siteType    site type for new site creation (ignored if site already exists)
     */
    public SiteCreationResult getOrCreateSite(UUID accountId, String siteName, String displayName, SiteType siteType) {
        logger.info("GetOrCreate site: accountId={}, siteName={}, siteType={}", accountId, siteName, siteType);

        String cleanSiteName = siteName.toLowerCase().trim();

        java.util.Optional<Site> existingSite = siteRepository.findByAccountIdAndSiteName(accountId, cleanSiteName);

        if (existingSite.isPresent()) {
            Site site = existingSite.get();

            // Reactivate if deactivated
            if (!site.getIsActive()) {
                site.activate();
                logger.info("Reactivated deactivated site: siteId={}", site.getId());
            }

            Site saved = siteRepository.save(site);

            logger.info("Found existing site: siteId={}, siteName={}, siteType={}", saved.getId(), saved.getSiteName(), saved.getSiteType());
            return new SiteCreationResult(saved, null);
        }

        // Site doesn't exist - create new one with given type
        return createSite(accountId, siteName, displayName, siteType);
    }

    /**
     * @deprecated Use {@link #getOrCreateSite(UUID, String, String)} instead
     */
    @Deprecated
    public SiteCreationResult getOrCreateSiteWithNewCredentials(UUID accountId, String domain, String displayName) {
        return getOrCreateSite(accountId, domain, displayName);
    }

    /**
     * Get site by ID.
     *
     * @param siteId site identifier
     * @return site
     * @throws SiteNotFoundException if site not found
     */
    @Transactional(readOnly = true)
    public Site getSite(UUID siteId) {
        return siteRepository.findById(siteId)
                .orElseThrow(() -> new SiteNotFoundException("Site not found: " + siteId));
    }

    /**
     * Get site by domain.
     *
     * @param domain site domain
     * @return site
     * @throws SiteNotFoundException if site not found
     */
    @Transactional(readOnly = true)
    public Site getSiteByDomain(String domain) {
        return siteRepository.findByDomain(domain)
                .orElseThrow(() -> new SiteNotFoundException("Site not found: " + domain));
    }

    /**
     * List all sites for account.
     *
     * @param accountId account identifier
     * @return list of sites
     */
    @Transactional(readOnly = true)
    public List<Site> listSitesByAccount(UUID accountId) {
        return siteRepository.findByAccountId(accountId);
    }

    /**
     * List all active sites for account.
     *
     * @param accountId account identifier
     * @return list of active sites
     */
    @Transactional(readOnly = true)
    public List<Site> listActiveSitesByAccount(UUID accountId) {
        return siteRepository.findActiveByAccountId(accountId);
    }

    /**
     * List all sites for account (both active and inactive), sorted by creation date (newest first).
     *
     * @param accountId account identifier
     * @return list of all sites sorted by createdAt DESC
     */
    @Transactional(readOnly = true)
    public List<Site> listAccountSites(UUID accountId) {
        return siteRepository.findByAccountId(accountId);
    }

    /**
     * List all sites with pagination (admin endpoint).
     *
     * @param pageable pagination parameters
     * @return paginated list of sites
     */
    @Transactional(readOnly = true)
    public Page<Site> listAllSites(Pageable pageable) {
        return siteRepository.findAll(pageable);
    }

    /**
     * Update site display name.
     *
     * @param siteId site identifier
     * @param displayName new display name
     * @return updated site
     * @throws SiteNotFoundException if site not found
     */
    public Site updateSite(UUID siteId, String displayName) {
        logger.info("Updating site: id={}, displayName={}", siteId, displayName);

        Site site = getSite(siteId);
        site.updateDisplayName(displayName);
        Site saved = siteRepository.save(site);

        logger.info("Site updated successfully: siteId={}", siteId);
        return saved;
    }

    /**
     * Update site retention policy (days).
     *
     * @param siteId site identifier
     * @param retentionDays retention period in days
     * @return updated site
     * @throws SiteNotFoundException if site not found
     */
    public Site updateRetentionDays(UUID siteId, int retentionDays) {
        logger.info("Updating site retention policy: id={}, retentionDays={}", siteId, retentionDays);

        Site site = getSite(siteId);
        site.updateRetentionDays(retentionDays);
        Site saved = siteRepository.save(site);

        logger.info("Site retention policy updated: siteId={}, retentionDays={}", siteId, retentionDays);
        return saved;
    }

    /**
     * Deactivate site (soft delete).
     *
     * @param siteId site identifier
     * @throws SiteNotFoundException if site not found
     */
    public void deactivateSite(UUID siteId) {
        logger.info("Deactivating site: id={}", siteId);

        Site site = getSite(siteId);

        if (!site.getIsActive()) {
            logger.warn("Site already deactivated: id={}", siteId);
            return;
        }

        site.deactivate();
        siteRepository.save(site);

        // Revoke all refresh tokens for this site (Auth V2)
        refreshTokenService.revokeAllForSite(siteId);

        logger.info("Site deactivated successfully: id={}", siteId);
    }

    /**
     * Reactivate previously deactivated site.
     *
     * @param siteId site identifier
     * @return reactivated site
     * @throws SiteNotFoundException if site not found
     */
    public Site reactivateSite(UUID siteId) {
        logger.info("Reactivating site: id={}", siteId);

        Site site = getSite(siteId);

        if (site.getIsActive()) {
            logger.warn("Site already active: id={}", siteId);
            return site;
        }

        site.activate();
        Site saved = siteRepository.save(site);

        logger.info("Site reactivated successfully: id={}", siteId);
        return saved;
    }

    /**
     * Delete site (hard delete with cascade).
     * <p>
     * Permanently deletes site and all associated data:
     * - All batches for this site
     * - All uploaded files for these batches
     * - All error logs for this site
     * - The site record itself
     * </p>
     * <p>
     * WARNING: This action cannot be undone. All data will be permanently lost.
     * For temporary disabling, use deactivateSite() instead.
     * </p>
     * <p>
     * <b>Orphaned S3 Files:</b> If S3 deletion fails but database cleanup continues,
     * orphaned files may remain in S3. The method logs errors at ERROR level and
     * continues with database cleanup to prevent stuck state. Consider implementing
     * periodic orphan detection and cleanup jobs for production environments.
     * </p>
     *
     * @param siteId site identifier
     * @throws SiteNotFoundException if site not found
     */
    public void deleteSite(UUID siteId) {
        logger.warn("Hard deleting site and all associated data: id={}", siteId);

        Site site = getSite(siteId);

        // Step 1: Find all batches for this site
        List<Batch> batches = batchRepository.findBySiteId(siteId, Pageable.unpaged()).getContent();
        List<UUID> batchIds = batches.stream().map(Batch::getId).toList();

        logger.info("Found {} batches to delete for site: {}", batches.size(), siteId);

        // Step 2: Delete all uploaded files for these batches (from database AND S3)
        if (!batchIds.isEmpty()) {
            for (UUID batchId : batchIds) {
                List<com.bitbi.dfm.upload.domain.UploadedFile> files = uploadedFileRepository.findByBatchId(batchId);
                logger.info("Deleting {} uploaded files for batch: {}", files.size(), batchId);
                for (com.bitbi.dfm.upload.domain.UploadedFile file : files) {
                    // Delete from S3 first
                    try {
                        s3FileStorageService.deleteFile(file.getS3Key());
                        logger.debug("Deleted S3 file: {}", file.getS3Key());
                    } catch (Exception e) {
                        logger.error("Failed to delete S3 file (continuing with database cleanup): key={}, error={}",
                                   file.getS3Key(), e.getMessage());
                        // Continue with database cleanup even if S3 deletion fails
                    }
                    // Delete from database
                    uploadedFileRepository.deleteById(file.getId());
                }
            }
        }

        // Step 3: Delete all error logs for this site
        List<com.bitbi.dfm.error.domain.ErrorLog> errorLogs = errorLogRepository.findBySiteId(siteId);
        logger.info("Deleting {} error logs for site: {}", errorLogs.size(), siteId);
        for (com.bitbi.dfm.error.domain.ErrorLog errorLog : errorLogs) {
            errorLogRepository.deleteById(errorLog.getId());
        }

        // Step 4: Delete all batches for this site
        if (!batches.isEmpty()) {
            logger.info("Deleting {} batches for site: {}", batches.size(), siteId);
            for (Batch batch : batches) {
                batchRepository.deleteById(batch.getId());
            }
        }

        // Step 5: Delete all device authorizations for this site
        deviceAuthorizationRepository.deleteBySiteId(siteId);
        logger.info("Deleted device authorizations for site: {}", siteId);

        // Step 6: Delete site schema (also deleted by DB CASCADE, but explicit for clarity)
        siteSchemaService.deleteSchema(siteId);

        // Step 7: Delete the site itself
        logger.info("Deleting site record: id={}", siteId);
        siteRepository.deleteById(siteId);

        logger.warn("Site and all associated data permanently deleted: id={}, siteName={}", siteId, site.getSiteName());
    }

    /**
     * Event listener for account deactivation.
     * <p>
     * Cascade deactivates all sites belonging to the account.
     * </p>
     *
     * @param event account deactivated event
     */
    @EventListener
    public void onAccountDeactivated(AccountDeactivatedEvent event) {
        UUID accountId = event.accountId();
        logger.info("Handling AccountDeactivatedEvent: accountId={}", accountId);

        List<Site> activeSites = listActiveSitesByAccount(accountId);
        logger.info("Deactivating {} sites for account: {}", activeSites.size(), accountId);

        for (Site site : activeSites) {
            deactivateSite(site.getId());
        }

        logger.info("All sites deactivated for account: {}", accountId);
    }

    /**
     * Exception thrown when site already exists.
     */
    public static class SiteAlreadyExistsException extends RuntimeException {
        public SiteAlreadyExistsException(String message) {
            super(message);
        }
    }

    /**
     * Exception thrown when site is not found.
     */
    public static class SiteNotFoundException extends RuntimeException {
        public SiteNotFoundException(String message) {
            super(message);
        }
    }
}
