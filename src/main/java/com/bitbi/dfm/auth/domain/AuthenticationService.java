package com.bitbi.dfm.auth.domain;

import com.bitbi.dfm.site.domain.Site;
import com.bitbi.dfm.site.domain.SiteCredentials;
import org.springframework.stereotype.Service;

import java.util.Objects;

/**
 * Domain service for site authentication and credential validation.
 * <p>
 * Encapsulates business logic for validating site credentials
 * and determining authentication eligibility.
 * </p>
 *
 * @author Data Forge Team
 * @version 1.0.0
 */
@Service
public class AuthenticationService {

    /**
     * Validate site credentials.
     * <p>
     * A site can authenticate if:
     * <ul>
     *   <li>Site exists</li>
     *   <li>Site is active (isActive = true)</li>
     *   <li>Parent account is active</li>
     *   <li>Provided credentials match site credentials</li>
     * </ul>
     * </p>
     *
     * @param site              site to authenticate
     * @param providedDomain    domain from Basic Auth
     * @param providedSecret    clientSecret from Basic Auth
     * @param isAccountActive   whether parent account is active
     * @return true if authentication should succeed
     */
    public boolean validateCredentials(Site site, String providedDomain,
                                        String providedSecret, boolean isAccountActive) {
        Objects.requireNonNull(site, "Site cannot be null");
        Objects.requireNonNull(providedDomain, "Provided domain cannot be null");
        Objects.requireNonNull(providedSecret, "Provided secret cannot be null");

        // Check site is active
        if (!site.canAuthenticate()) {
            return false;
        }

        // Check parent account is active
        if (!isAccountActive) {
            return false;
        }

        // Validate credentials match
        SiteCredentials credentials = site.getCredentials();
        return credentials.matches(providedDomain, providedSecret);
    }

    /**
     * Determine authentication failure reason for error messages.
     *
     * @param siteExists        whether site exists
     * @param siteActive        whether site is active
     * @param accountActive     whether parent account is active
     * @param credentialsMatch  whether credentials match
     * @return authentication failure reason
     */
    public String getAuthenticationFailureReason(boolean siteExists, boolean siteActive,
                                                   boolean accountActive, boolean credentialsMatch) {
        if (!siteExists) {
            return "Invalid credentials"; // Don't reveal site doesn't exist
        }

        if (!siteActive) {
            return "Invalid credentials"; // Don't reveal site is inactive
        }

        if (!accountActive) {
            return "Invalid credentials"; // Don't reveal account is inactive
        }

        if (!credentialsMatch) {
            return "Invalid credentials";
        }

        return "Authentication successful"; // Should not reach here
    }

    /**
     * Generic error message for all authentication failures (security best practice).
     *
     * @return generic error message
     */
    public String getGenericFailureMessage() {
        return "Invalid credentials";
    }
}
