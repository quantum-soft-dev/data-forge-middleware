package com.bitbi.dfm.account.infrastructure;

import com.bitbi.dfm.account.domain.AdminActionLog;
import com.bitbi.dfm.account.domain.AdminActionType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

/**
 * AdminActionLogRepository provides data access for admin action audit logs.
 *
 * @author Data Forge Team
 * @version 1.0.0
 */
@Repository
public interface AdminActionLogRepository extends JpaRepository<AdminActionLog, Long> {

    /**
     * Find all action logs for a specific target account with pagination.
     *
     * @param targetAccountId UUID of the target account
     * @param pageable       pagination parameters
     * @return page of admin action logs
     */
    Page<AdminActionLog> findByTargetAccountId(UUID targetAccountId, Pageable pageable);

    /**
     * Find all action logs for a specific target account (unpaginated).
     *
     * @param targetAccountId UUID of the target account
     * @return list of admin action logs
     */
    List<AdminActionLog> findByTargetAccountId(UUID targetAccountId);

    /**
     * Find all action logs performed by a specific admin with pagination.
     *
     * @param adminAccountId UUID of the admin account
     * @param pageable      pagination parameters
     * @return page of admin action logs
     */
    Page<AdminActionLog> findByAdminAccountId(UUID adminAccountId, Pageable pageable);

    /**
     * Find all action logs performed by a specific admin (unpaginated).
     *
     * @param adminAccountId UUID of the admin account
     * @return list of admin action logs
     */
    List<AdminActionLog> findByAdminAccountId(UUID adminAccountId);

    /**
     * Find all action logs of a specific type.
     *
     * @param actionType type of administrative action
     * @param pageable   pagination parameters
     * @return page of admin action logs
     */
    Page<AdminActionLog> findByActionType(AdminActionType actionType, Pageable pageable);
}
