package com.bitbi.dfm.site.domain;

import java.util.Optional;
import java.util.UUID;

/**
 * Repository interface for SiteSchema persistence.
 *
 * @author Data Forge Team
 * @version 1.0.0
 */
public interface SiteSchemaRepository {

    /**
     * Find schema for a site.
     *
     * @param siteId site identifier
     * @return optional schema
     */
    Optional<SiteSchema> findBySiteId(UUID siteId);

    /**
     * Save (insert or update) a schema.
     *
     * @param schema schema entity
     * @return saved entity
     */
    SiteSchema save(SiteSchema schema);

    /**
     * Delete schema for a site (used on site hard-delete).
     *
     * @param siteId site identifier
     */
    void deleteBySiteId(UUID siteId);

    /**
     * Check whether a schema exists for a site.
     *
     * @param siteId site identifier
     * @return true if schema exists
     */
    boolean existsBySiteId(UUID siteId);
}
