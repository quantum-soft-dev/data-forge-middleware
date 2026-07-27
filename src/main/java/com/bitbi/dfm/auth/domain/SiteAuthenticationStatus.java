package com.bitbi.dfm.auth.domain;

import java.util.UUID;

/**
 * Authentication status of a site together with its parent account.
 * <p>
 * Read on every client API request by {@code TokenService.validateToken}. Both flags are
 * resolved by a single joined query so that the hot validation path stays one round-trip:
 * a site is only usable while the site itself <em>and</em> its owning account are active.
 * </p>
 *
 * @param siteId        site identifier
 * @param accountId     owning account identifier
 * @param siteActive    whether the site is active
 * @param accountActive whether the parent account is active
 * @author Data Forge Team
 * @version 1.0.0
 */
public record SiteAuthenticationStatus(
        UUID siteId,
        UUID accountId,
        boolean siteActive,
        boolean accountActive
) {
}
