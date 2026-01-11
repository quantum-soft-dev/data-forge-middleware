package com.bitbi.dfm.error.domain;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Projection interface for global error queries with site name.
 * <p>
 * Used to fetch error data with site name in a single query,
 * avoiding N+1 query problem.
 * </p>
 */
public interface GlobalErrorProjection {

    UUID getId();

    UUID getSiteId();

    String getSiteName();

    String getType();

    String getTitle();

    ErrorSeverity getSeverity();

    boolean getIsRead();

    LocalDateTime getOccurredAt();
}
