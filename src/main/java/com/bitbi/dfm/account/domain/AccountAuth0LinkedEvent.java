package com.bitbi.dfm.account.domain;

import java.time.Instant;
import java.util.UUID;

/**
 * Domain event triggered when an Account is linked to Auth0.
 * This event is used for audit logging and potential event-driven workflows.
 *
 * @param accountId The unique identifier of the account
 * @param email The email address of the account
 * @param auth0UserId The Auth0 user ID (format: auth0|{id})
 * @param linkedAt The timestamp when the linking occurred
 */
public record AccountAuth0LinkedEvent(
    UUID accountId,
    String email,
    String auth0UserId,
    Instant linkedAt
) {
    /**
     * Factory method to create an event from an Account entity.
     *
     * @param account The account that was linked to Auth0
     * @return A new AccountAuth0LinkedEvent instance
     */
    public static AccountAuth0LinkedEvent of(Account account) {
        if (account == null) {
            throw new IllegalArgumentException("Account cannot be null");
        }
        if (account.getIdentityProviderUserId() == null) {
            throw new IllegalArgumentException("Account must have an identity provider user ID");
        }
        return new AccountAuth0LinkedEvent(
            account.getId(),
            account.getEmail(),
            account.getIdentityProviderUserId(),
            Instant.now()
        );
    }
}
