package com.bitbi.dfm.account.domain;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Repository interface for Account aggregate.
 * <p>
 * Defines persistence operations for Account domain model.
 * Implementation will be provided by infrastructure layer.
 * </p>
 *
 * @author Data Forge Team
 * @version 1.0.0
 */
public interface AccountRepository {

    /**
     * Find account by ID.
     *
     * @param id account identifier
     * @return Optional containing account if found
     */
    Optional<Account> findById(UUID id);

    /**
     * Find account by email.
     *
     * @param email account email
     * @return Optional containing account if found
     */
    Optional<Account> findByEmail(String email);

    /**
     * Find all accounts with pagination.
     *
     * @param pageable pagination parameters
     * @return page of accounts
     */
    Page<Account> findAll(Pageable pageable);

    /**
     * Find all active accounts.
     *
     * @return list of active accounts
     */
    List<Account> findAllActive();

    /**
     * Save account (create or update).
     *
     * @param account account to save
     * @return saved account
     */
    Account save(Account account);

    /**
     * Check if account exists with given email.
     *
     * @param email email to check
     * @return true if account exists
     */
    boolean existsByEmail(String email);

    /**
     * Delete account by ID.
     *
     * @param id account identifier
     */
    void deleteById(UUID id);
}
