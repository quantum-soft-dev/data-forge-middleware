package com.bitbi.dfm.site.application;

import com.bitbi.dfm.site.domain.SiteSchema;
import com.bitbi.dfm.site.domain.SiteSchemaRepository;
import com.bitbi.dfm.site.domain.TableSchema;
import com.bitbi.dfm.upload.presentation.dto.SchemaUploadRequestDto;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Application service for site schema management.
 *
 * <p>Handles upsert, retrieval and validation of table schemas for sites.
 * Used by CDC sites to store table structures (columns, PK, unique keys)
 * required for precise SQL generation.</p>
 *
 * @author Data Forge Team
 * @version 1.0.0
 */
@Service
@Transactional
public class SiteSchemaService {

    private static final Logger logger = LoggerFactory.getLogger(SiteSchemaService.class);

    /**
     * PostgreSQL identifier pattern: starts with letter or underscore,
     * followed by letters, digits or underscores, max 63 chars.
     */
    private static final Pattern IDENTIFIER_PATTERN =
            Pattern.compile("^[a-zA-Z_][a-zA-Z0-9_]{0,62}$");

    /**
     * MSSQL type alias normalization map. Maps MSSQL-specific type names
     * (case-insensitive) to unified types used by the SQL generation pipeline.
     *
     * Feature: 021-unified-upload-api (US1)
     */
    private static final Map<String, String> MSSQL_TYPE_ALIASES = Map.ofEntries(
            Map.entry("nvarchar", "VARCHAR"),
            Map.entry("nvarchar(max)", "VARCHAR"),
            Map.entry("int", "INTEGER"),
            Map.entry("datetime2", "TIMESTAMP"),
            Map.entry("datetime", "TIMESTAMP"),
            Map.entry("uniqueidentifier", "UUID"),
            Map.entry("money", "MONEY"),
            Map.entry("smallmoney", "MONEY"),
            Map.entry("bit", "BOOLEAN"),
            Map.entry("ntext", "TEXT"),
            Map.entry("text", "TEXT"),
            Map.entry("float", "FLOAT")
    );

    private final SiteSchemaRepository siteSchemaRepository;
    private final ObjectMapper objectMapper;

    public SiteSchemaService(SiteSchemaRepository siteSchemaRepository, ObjectMapper objectMapper) {
        this.siteSchemaRepository = siteSchemaRepository;
        this.objectMapper = objectMapper;
    }

    /**
     * Upsert site schema — create on first call, update on subsequent calls.
     *
     * <p>Validates the schema structure before persisting:
     * <ul>
     *   <li>Each table must have at least one column</li>
     *   <li>PK columns must exist in the column list</li>
     *   <li>Unique key columns must exist in the column list</li>
     *   <li>Column and table names validated against PostgreSQL identifier pattern</li>
     * </ul>
     * </p>
     *
     * @param siteId  site identifier
     * @param request schema upload request DTO
     * @return saved SiteSchema entity
     * @throws InvalidSchemaException if validation fails
     */
    public SiteSchema upsertSchema(UUID siteId, SchemaUploadRequestDto request) {
        logger.info("Upserting schema for site: siteId={}, tables={}", siteId, request.tables().keySet());

        validateSchema(request);

        // Normalize MSSQL type aliases before persisting
        request = normalizeTypeAliases(request);

        @SuppressWarnings("unchecked")
        Map<String, Object> schemaData = objectMapper.convertValue(request, Map.class);

        Optional<SiteSchema> existing = siteSchemaRepository.findBySiteId(siteId);

        SiteSchema saved;
        if (existing.isPresent()) {
            SiteSchema schema = existing.get();
            schema.update(schemaData);
            saved = siteSchemaRepository.save(schema);
            logger.info("Schema updated: siteId={}, version={}", siteId, saved.getSchemaVersion());
        } else {
            SiteSchema schema = SiteSchema.create(siteId, schemaData);
            saved = siteSchemaRepository.save(schema);
            logger.info("Schema created: siteId={}, version={}", siteId, saved.getSchemaVersion());
        }

        return saved;
    }

    /**
     * Get parsed table schemas for a site.
     *
     * @param siteId site identifier
     * @return map of table name → TableSchema (empty map if no schema stored)
     */
    @Transactional(readOnly = true)
    public Map<String, TableSchema> getTableSchemas(UUID siteId) {
        return siteSchemaRepository.findBySiteId(siteId)
                .map(this::parseSchemaData)
                .orElse(Map.of());
    }

    /**
     * Get the raw SiteSchema entity for a site.
     *
     * @param siteId site identifier
     * @return optional SiteSchema
     */
    @Transactional(readOnly = true)
    public Optional<SiteSchema> findSchema(UUID siteId) {
        return siteSchemaRepository.findBySiteId(siteId);
    }

    /**
     * Check whether a schema exists for a site.
     *
     * @param siteId site identifier
     * @return true if schema is stored
     */
    @Transactional(readOnly = true)
    public boolean hasSchema(UUID siteId) {
        return siteSchemaRepository.existsBySiteId(siteId);
    }

    /**
     * Delete schema for a site (called on site hard-delete).
     *
     * @param siteId site identifier
     */
    public void deleteSchema(UUID siteId) {
        siteSchemaRepository.deleteBySiteId(siteId);
        logger.info("Schema deleted for site: siteId={}", siteId);
    }

    // --- Type normalization ---

    /**
     * Normalize MSSQL type aliases in schema column types to unified types.
     * <p>
     * Replaces known MSSQL-specific type names (e.g., nvarchar, datetime2, uniqueidentifier)
     * with their unified equivalents (VARCHAR, TIMESTAMP, UUID). Also normalizes
     * parameterized types like {@code nvarchar(100)} to {@code VARCHAR}.
     * </p>
     *
     * Feature: 021-unified-upload-api (US1)
     */
    private SchemaUploadRequestDto normalizeTypeAliases(SchemaUploadRequestDto request) {
        boolean anyNormalized = false;
        Map<String, SchemaUploadRequestDto.TableSchemaDto> normalizedTables = new LinkedHashMap<>();

        for (Map.Entry<String, SchemaUploadRequestDto.TableSchemaDto> entry : request.tables().entrySet()) {
            SchemaUploadRequestDto.TableSchemaDto tableSchema = entry.getValue();
            List<SchemaUploadRequestDto.ColumnDto> normalizedColumns = new ArrayList<>();

            for (SchemaUploadRequestDto.ColumnDto column : tableSchema.columns()) {
                String normalizedType = normalizeColumnType(column.type());
                if (!normalizedType.equals(column.type())) {
                    anyNormalized = true;
                    logger.debug("Normalized type alias: table={}, column={}, {} -> {}",
                            entry.getKey(), column.name(), column.type(), normalizedType);
                }
                normalizedColumns.add(new SchemaUploadRequestDto.ColumnDto(
                        column.name(), normalizedType, column.nullable()));
            }

            normalizedTables.put(entry.getKey(), new SchemaUploadRequestDto.TableSchemaDto(
                    normalizedColumns, tableSchema.primaryKey(), tableSchema.uniqueKeys()));
        }

        if (anyNormalized) {
            logger.info("Normalized MSSQL type aliases in schema");
            return new SchemaUploadRequestDto(normalizedTables);
        }
        return request;
    }

    /**
     * Normalize a single column type by checking against the MSSQL type alias map.
     * Handles both exact matches (e.g., "nvarchar") and parameterized types (e.g., "nvarchar(100)").
     */
    private String normalizeColumnType(String type) {
        String lowerType = type.toLowerCase(Locale.ROOT).trim();

        // Exact match first
        String normalized = MSSQL_TYPE_ALIASES.get(lowerType);
        if (normalized != null) {
            return normalized;
        }

        // Check for parameterized types like "nvarchar(100)" — strip the parenthesized part
        int parenIndex = lowerType.indexOf('(');
        if (parenIndex > 0) {
            String baseType = lowerType.substring(0, parenIndex).trim();
            normalized = MSSQL_TYPE_ALIASES.get(baseType);
            if (normalized != null) {
                return normalized;
            }
        }

        return type;
    }

    // --- Validation ---

    private void validateSchema(SchemaUploadRequestDto request) {
        if (request.tables() == null || request.tables().isEmpty()) {
            throw new InvalidSchemaException("Schema must contain at least one table");
        }

        for (Map.Entry<String, SchemaUploadRequestDto.TableSchemaDto> entry : request.tables().entrySet()) {
            String tableName = entry.getKey();
            SchemaUploadRequestDto.TableSchemaDto tableSchema = entry.getValue();

            validateIdentifier(tableName, "table");

            if (tableSchema.columns() == null || tableSchema.columns().isEmpty()) {
                throw new InvalidSchemaException("Table '" + tableName + "' must have at least one column");
            }

            List<String> columnNames = new ArrayList<>();
            for (SchemaUploadRequestDto.ColumnDto column : tableSchema.columns()) {
                if (column.name() == null || column.name().isBlank()) {
                    throw new InvalidSchemaException("Column name cannot be blank in table '" + tableName + "'");
                }
                validateIdentifier(column.name(), "column");
                if (column.type() == null || column.type().isBlank()) {
                    throw new InvalidSchemaException("Column type cannot be blank for column '" + column.name() + "' in table '" + tableName + "'");
                }
                columnNames.add(column.name());
            }

            if (tableSchema.primaryKey() != null) {
                for (String pkCol : tableSchema.primaryKey()) {
                    if (!columnNames.contains(pkCol)) {
                        throw new InvalidSchemaException(
                                "Primary key column '" + pkCol + "' not found in table '" + tableName + "' columns");
                    }
                }
            }

            if (tableSchema.uniqueKeys() != null) {
                for (SchemaUploadRequestDto.UniqueKeyDto uk : tableSchema.uniqueKeys()) {
                    if (uk.columns() != null) {
                        for (String ukCol : uk.columns()) {
                            if (!columnNames.contains(ukCol)) {
                                throw new InvalidSchemaException(
                                        "Unique key column '" + ukCol + "' not found in table '" + tableName + "' columns");
                            }
                        }
                    }
                }
            }
        }
    }

    private void validateIdentifier(String name, String context) {
        if (!IDENTIFIER_PATTERN.matcher(name).matches()) {
            throw new InvalidSchemaException(
                    "Invalid " + context + " name '" + name + "': must match PostgreSQL identifier pattern (letters, digits, underscores; max 63 chars; must start with letter or underscore)");
        }
    }

    // --- Schema data parsing ---

    @SuppressWarnings("unchecked")
    private Map<String, TableSchema> parseSchemaData(SiteSchema siteSchema) {
        Map<String, Object> raw = siteSchema.getSchemaData();
        Object tablesObj = raw.get("tables");
        if (!(tablesObj instanceof Map)) {
            return Map.of();
        }

        Map<String, Object> tablesMap = (Map<String, Object>) tablesObj;
        Map<String, TableSchema> result = new LinkedHashMap<>();

        for (Map.Entry<String, Object> entry : tablesMap.entrySet()) {
            String tableName = entry.getKey();
            if (!(entry.getValue() instanceof Map)) {
                continue;
            }
            Map<String, Object> tableRaw = (Map<String, Object>) entry.getValue();

            List<TableSchema.ColumnDefinition> columns = parseColumns(tableRaw);
            List<String> primaryKey = parseStringList(tableRaw, "primaryKey");
            List<TableSchema.UniqueKeyDefinition> uniqueKeys = parseUniqueKeys(tableRaw);

            result.put(tableName, new TableSchema(columns, primaryKey, uniqueKeys));
        }

        return result;
    }

    @SuppressWarnings("unchecked")
    private List<TableSchema.ColumnDefinition> parseColumns(Map<String, Object> tableRaw) {
        Object columnsObj = tableRaw.get("columns");
        if (!(columnsObj instanceof List)) {
            return List.of();
        }
        List<Object> rawColumns = (List<Object>) columnsObj;
        List<TableSchema.ColumnDefinition> columns = new ArrayList<>();
        for (Object colObj : rawColumns) {
            if (!(colObj instanceof Map)) continue;
            Map<String, Object> colMap = (Map<String, Object>) colObj;
            String name = (String) colMap.get("name");
            String type = (String) colMap.get("type");
            boolean nullable = Boolean.TRUE.equals(colMap.get("nullable"));
            if (name != null && type != null) {
                columns.add(new TableSchema.ColumnDefinition(name, type, nullable));
            }
        }
        return columns;
    }

    @SuppressWarnings("unchecked")
    private List<String> parseStringList(Map<String, Object> map, String key) {
        Object val = map.get(key);
        if (!(val instanceof List)) {
            return List.of();
        }
        List<Object> rawList = (List<Object>) val;
        List<String> result = new ArrayList<>();
        for (Object item : rawList) {
            if (item instanceof String s) {
                result.add(s);
            }
        }
        return result;
    }

    @SuppressWarnings("unchecked")
    private List<TableSchema.UniqueKeyDefinition> parseUniqueKeys(Map<String, Object> tableRaw) {
        Object ukObj = tableRaw.get("uniqueKeys");
        if (!(ukObj instanceof List)) {
            return List.of();
        }
        List<Object> rawUks = (List<Object>) ukObj;
        List<TableSchema.UniqueKeyDefinition> result = new ArrayList<>();
        for (Object ukItem : rawUks) {
            if (!(ukItem instanceof Map)) continue;
            Map<String, Object> ukMap = (Map<String, Object>) ukItem;
            String name = (String) ukMap.get("name");
            List<String> cols = parseStringList(ukMap, "columns");
            result.add(new TableSchema.UniqueKeyDefinition(name, cols));
        }
        return result;
    }

    // --- Exceptions ---

    /**
     * Exception thrown when schema validation fails.
     */
    public static class InvalidSchemaException extends RuntimeException {
        public InvalidSchemaException(String message) {
            super(message);
        }
    }
}
