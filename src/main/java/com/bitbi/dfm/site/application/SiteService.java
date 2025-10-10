package com.bitbi.dfm.site.application;

import com.bitbi.dfm.site.domain.Site;
import com.bitbi.dfm.site.domain.SiteCredentials;
import com.bitbi.dfm.site.domain.SiteRepository;
import com.bitbi.dfm.shared.domain.events.AccountDeactivatedEvent;
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

    public SiteService(SiteRepository siteRepository) {
        this.siteRepository = siteRepository;
    }

    /**
     * Create new site for account.
     *
     * @param accountId   account identifier
     * @param domain      site domain (must be unique)
     * @param displayName site display name
     * @return SiteCreationResult with site and plaintext clientSecret (only shown once)
     * @throws SiteAlreadyExistsException if domain already exists
     */
    public SiteCreationResult createSite(UUID accountId, String domain, String displayName) {
        logger.info("Creating new site: accountId={}, domain={}, displayName={}", accountId, domain, displayName);

        if (siteRepository.findByDomain(domain).isPresent()) {
            throw new SiteAlreadyExistsException("Site with domain already exists: " + domain);
        }

        // Generate plaintext secret and bcrypt hash
        String[] secretPair = SiteCredentials.generateWithHash(domain);
        String plaintextSecret = secretPair[0];
        String hashedSecret = secretPair[1];

        Site site = Site.create(accountId, domain, displayName, hashedSecret);
        Site saved = siteRepository.save(site);

        logger.info("Site created successfully: id={}, domain={}", saved.getId(), saved.getDomain());
        return new SiteCreationResult(saved, plaintextSecret);
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
