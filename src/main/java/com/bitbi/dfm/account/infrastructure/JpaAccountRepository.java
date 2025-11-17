package com.bitbi.dfm.account.infrastructure;

import com.bitbi.dfm.account.domain.Account;
import com.bitbi.dfm.account.domain.AccountRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * JPA implementation of AccountRepository.
 * <p>
 * Provides database persistence for Account aggregate using Spring Data JPA.
 * </p>
 *
 * @author Data Forge Team
 * @version 1.0.0
 */
@Repository
public interface JpaAccountRepository extends JpaRepository<Account, UUID>, AccountRepository {

    /**
     * Find account by email (case-insensitive).
     *
     * @param email email address
     * @return Optional containing account if found
     */
    @Query("SELECT a FROM Account a WHERE LOWER(a.email) = LOWER(:email)")
    Optional<Account> findByEmail(String email);

    /**
     * Check if account exists with given email (case-insensitive).
     *
     * @param email email to check
     * @return true if account exists
     */
    @Query("SELECT COUNT(a) > 0 FROM Account a WHERE LOWER(a.email) = LOWER(:email)")
    boolean existsByEmail(String email);

    /**
     * Find all accounts with pagination.
     *
     * @param pageable pagination parameters
     * @return page of accounts
     */
    @Override
    Page<Account> findAll(Pageable pageable);

    /**
     * Find all active accounts.
     *
     * @return list of active accounts
     */
    @Query("SELECT a FROM Account a WHERE a.isActive = true ORDER BY a.createdAt DESC")
    List<Account> findAllActive();

    /**
     * Find account by identity provider user ID (Auth0 or legacy Keycloak).
     *
     * @param identityProviderUserId Identity provider user ID (e.g., auth0|xxx or UUID)
     * @return Optional containing account if found
     */
    Optional<Account> findByIdentityProviderUserId(String identityProviderUserId);

    /**
     * Check if account exists with given identity provider user ID.
     *
     * @param identityProviderUserId Identity provider user ID to check
     * @return true if account exists
     */
    boolean existsByIdentityProviderUserId(String identityProviderUserId);

    /**
     * Find all accounts with identity provider integration.
     * <p>
     * This query filters accounts that have identity provider user IDs (format: auth0|{id}).
     * Returns only accounts integrated with identity provider.
     * </p>
     *
     * @return list of accounts with identity provider integration
     */
    @Query("""
            SELECT a FROM Account a
            WHERE a.identityProviderUserId IS NOT NULL
            AND a.identityProviderUserId LIKE 'auth0|%'
            AND a.isActive = true
            ORDER BY a.createdAt DESC
            """)
    List<Account> findAccountsWithIdentityProvider();

    /**
     * Find all accounts with identity provider integration and search filter.
     * <p>
     * This query filters accounts that have identity provider user IDs (format: auth0|{id}).
     * If search parameter is provided, filters by email or name (case-insensitive).
     * Database-level filtering for performance and accurate pagination.
     * </p>
     *
     * @param search optional search term to filter by email or name
     * @param pageable pagination parameters
     * @return page of accounts with identity provider integration
     */
    @Query("""
            SELECT a FROM Account a
            WHERE a.identityProviderUserId IS NOT NULL
            AND a.identityProviderUserId LIKE 'auth0|%'
            AND (:search IS NULL OR :search = ''
                 OR LOWER(a.email) LIKE LOWER(CONCAT('%', :search, '%'))
                 OR LOWER(a.name) LIKE LOWER(CONCAT('%', :search, '%')))
            ORDER BY a.createdAt DESC
            """)
    Page<Account> findAccountsBySearch(@Param("search") String search, Pageable pageable);
}
