package com.bitbi.dfm.site.domain;

import io.hypersistence.utils.hibernate.type.json.JsonBinaryType;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.Type;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.UUID;

/**
 * JPA entity representing the stored table schema for a site.
 *
 * <p>One row per site (UNIQUE on site_id). Schema is replaced on update;
 * schema_version is incremented on each upsert for optimistic concurrency checks.</p>
 *
 * <p>schema_data stores the full JSON schema document as a JSONB column.</p>
 *
 * @author Data Forge Team
 * @version 1.0.0
 */
@Entity
@Table(name = "site_schemas")
@Getter
@NoArgsConstructor
public class SiteSchema {

    @Id
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    @Column(name = "site_id", nullable = false, unique = true)
    private UUID siteId;

    @Type(JsonBinaryType.class)
    @Column(name = "schema_data", columnDefinition = "jsonb", nullable = false)
    private Map<String, Object> schemaData;

    @Column(name = "schema_version", nullable = false)
    private Integer schemaVersion;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    /**
     * Create a new SiteSchema record for the given site.
     *
     * @param siteId     site identifier
     * @param schemaData raw JSONB schema document (key = table name, value = table schema object)
     * @return new SiteSchema entity with version 1
     */
    public static SiteSchema create(UUID siteId, Map<String, Object> schemaData) {
        SiteSchema schema = new SiteSchema();
        schema.id = UUID.randomUUID();
        schema.siteId = siteId;
        schema.schemaData = schemaData;
        schema.schemaVersion = 1;
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        schema.createdAt = now;
        schema.updatedAt = now;
        return schema;
    }

    /**
     * Update the schema data and increment the version counter.
     *
     * @param newSchemaData updated JSONB schema document
     */
    public void update(Map<String, Object> newSchemaData) {
        this.schemaData = newSchemaData;
        this.schemaVersion = this.schemaVersion + 1;
        this.updatedAt = LocalDateTime.now(ZoneOffset.UTC);
    }

    @PrePersist
    protected void onCreate() {
        if (createdAt == null) createdAt = LocalDateTime.now(ZoneOffset.UTC);
        if (updatedAt == null) updatedAt = LocalDateTime.now(ZoneOffset.UTC);
        if (schemaVersion == null) schemaVersion = 1;
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now(ZoneOffset.UTC);
    }
}
