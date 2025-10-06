package com.bitbi.dfm.account.infrastructure;

import com.bitbi.dfm.account.domain.Account;
import com.bitbi.dfm.account.domain.AccountRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

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
}
