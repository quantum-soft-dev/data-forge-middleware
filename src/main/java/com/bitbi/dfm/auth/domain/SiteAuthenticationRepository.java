package com.bitbi.dfm.auth.domain;

import java.util.Optional;
import java.util.UUID;

/**
 * Read-only port for the authentication status of a site and its parent account.
 * <p>
 * Exists as a dedicated port (rather than reusing {@code SiteRepository} plus
 * {@code AccountRepository}) because token validation runs on every client API request
 * and must not pay for two lookups.
 * </p>
 *
 * @author Data Forge Team
 * @version 1.0.0
 */
public interface SiteAuthenticationRepository {

    /**
     * Load the site's authentication status together with its parent account's status.
     *
     * @param siteId site identifier
     * @return the combined status, or empty if the site does not exist
     */
    Optional<SiteAuthenticationStatus> findAuthenticationStatus(UUID siteId);
}
