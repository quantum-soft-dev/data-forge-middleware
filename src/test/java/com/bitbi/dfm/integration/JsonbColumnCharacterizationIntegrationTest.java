package com.bitbi.dfm.integration;

import com.bitbi.dfm.account.domain.AdminActionLog;
import com.bitbi.dfm.account.domain.AdminActionType;
import com.bitbi.dfm.account.infrastructure.AdminActionLogRepository;
import com.bitbi.dfm.comparison.domain.ChangeType;
import com.bitbi.dfm.comparison.domain.ComparisonRepository;
import com.bitbi.dfm.comparison.domain.ComparisonResult;
import com.bitbi.dfm.comparison.domain.FileComparison;
import com.bitbi.dfm.delta.domain.ChangelogSegment;
import com.bitbi.dfm.delta.domain.TableChangeStats;
import com.bitbi.dfm.plugin.domain.AccountPlugin;
import com.bitbi.dfm.plugin.domain.AccountPluginRepository;
import com.bitbi.dfm.plugin.domain.PluginActionType;
import com.bitbi.dfm.plugin.domain.PluginAuditLog;
import com.bitbi.dfm.plugin.domain.PluginConfig;
import com.bitbi.dfm.plugin.domain.PluginConfigRepository;
import com.bitbi.dfm.plugin.infrastructure.persistence.JpaPluginAuditLogRepository;
import com.bitbi.dfm.site.domain.SiteSchema;
import com.bitbi.dfm.site.domain.SiteSchemaRepository;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Characterization of the seven JSONB columns mapped with {@code @Type(JsonBinaryType.class)},
 * pinned on Spring Boot 3.5 / Hibernate 6.6 / Jackson 2 before the move to Hibernate 7 and the
 * Jackson 3 only {@code hypersistence-utils-hibernate-73} (issue #300, the safety net for #302).
 * <p>
 * Two directions per entity, because they fail differently:
 * </p>
 * <ul>
 *   <li><b>write</b> — the entity is saved through its repository and the column is read back raw as
 *       {@code jsonb::text}, compared with the literal PostgreSQL normalizes the expected document to.
 *       That pins what a <em>new</em> mapper would put next to rows already on disk: the number form
 *       of a {@code long} and a {@code double}, an explicit {@code null} member, and how an
 *       {@code Instant} and a {@code UUID} placed in a free-form map are stored.</li>
 *   <li><b>read</b> — the column is set by raw SQL to a document in the shape older code wrote
 *       (members missing, {@code null} values, integers wider than {@code int}, a decimal with a
 *       trailing zero), the persistence context is cleared, and the entity is loaded through its
 *       repository. The assertion is on the <em>Java types</em> of what came back, since a mapper
 *       with other defaults reads the same text into {@code BigDecimal}, {@code BigInteger} or a
 *       failure, which no {@code equals} on a map of numbers would notice.</li>
 * </ul>
 * The raw value is taken from the entity's field rather than its getter: {@code AccountPlugin}
 * returns an immutable {@code Map.copyOf}, which would hide the deserialized types behind the copy.
 * The class is {@code @Transactional}; every row it writes is rolled back.
 */
@Transactional
@DisplayName("#300 — JSONB columns: stored form and historical rows (Boot 3.5 characterization)")
class JsonbColumnCharacterizationIntegrationTest extends BaseIntegrationTest {

    private static final UUID ACCOUNT_ID = UUID.fromString("a1b2c3d4-e5f6-7890-abcd-ef1234567890");
    private static final UUID PLUGIN_TEST_ACCOUNT_ID = UUID.fromString("0199baac-f851-7ed9-5963-00dbaf07b233");
    private static final UUID STORE_01_SITE_ID = UUID.fromString("0199baac-f852-753f-6fc3-7c994fc38654");
    private static final UUID STORE_02_SITE_ID = UUID.fromString("0199baaf-ea7a-bd1f-6f6c-8610b9ddc4d7");
    private static final UUID IN_PROGRESS_BATCH_ID = UUID.fromString("b1c2d3e4-f5a6-7890-bcde-f12345678903");

    private static final UUID A_UUID = UUID.fromString("0195aaaa-bbbb-cccc-dddd-eeeeeeeeeeee");
    private static final Instant AN_INSTANT = Instant.parse("2026-01-15T10:30:00.123456Z");

    /**
     * A historical free-form document: every JSON value kind, an integer wider than {@code int},
     * and a decimal written with a trailing zero.
     */
    private static final String HISTORICAL_DOCUMENT = "{\"s\": \"x\", \"i\": 7, \"l\": 9007199254740993, "
            + "\"d\": 2.50, \"b\": true, \"n\": null, \"arr\": [1, \"two\", null], \"obj\": {\"k\": \"v\"}}";

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private SiteSchemaRepository siteSchemaRepository;

    @Autowired
    private AccountPluginRepository accountPluginRepository;

    @Autowired
    private PluginConfigRepository pluginConfigRepository;

    @Autowired
    private JpaPluginAuditLogRepository pluginAuditLogRepository;

    @Autowired
    private AdminActionLogRepository adminActionLogRepository;

    @Autowired
    private ComparisonRepository comparisonRepository;

    /** The free-form map every {@code Map<String, Object>} column is written with. */
    private static Map<String, Object> writtenDocument() {
        Map<String, Object> document = new LinkedHashMap<>();
        document.put("s", "x");
        document.put("i", 7);
        document.put("l", 9007199254740993L);
        document.put("d", 2.5);
        document.put("b", true);
        document.put("n", null);
        document.put("arr", Arrays.asList(1, "two", null));
        document.put("obj", Map.of("k", "v"));
        document.put("uuid", A_UUID);
        document.put("at", AN_INSTANT);
        return document;
    }

    /**
     * The stored text of {@link #writtenDocument()}: the {@code UUID} as a string and the
     * {@code Instant} as epoch seconds with nanoseconds — Jackson 2's {@code WRITE_DATES_AS_TIMESTAMPS}
     * default in the mapper hypersistence-utils builds, which Jackson 3 turns off.
     */
    private static final String WRITTEN_DOCUMENT_TEXT = "{\"b\": true, \"d\": 2.5, \"i\": 7, \"l\": 9007199254740993, "
            + "\"n\": null, \"s\": \"x\", \"at\": 1768473000.123456000, \"arr\": [1, \"two\", null], \"obj\": {\"k\": \"v\"}, "
            + "\"uuid\": \"0195aaaa-bbbb-cccc-dddd-eeeeeeeeeeee\"}";

    private String columnText(String table, String column, String idColumn, Object id) {
        entityManager.flush();
        return jdbc.queryForObject("SELECT " + column + "::text FROM " + table + " WHERE " + idColumn + " = ?",
                String.class, id);
    }

    private String normalized(String json) {
        return jdbc.queryForObject("SELECT CAST(? AS jsonb)::text", String.class, json);
    }

    private void setColumn(String table, String column, String idColumn, Object id, String json) {
        entityManager.flush();
        int updated = jdbc.update("UPDATE " + table + " SET " + column + " = CAST(? AS jsonb) WHERE " + idColumn + " = ?",
                json, id);
        assertThat(updated).as("fixture row in " + table).isEqualTo(1);
        entityManager.clear();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> rawMap(Object entity, String field) {
        return (Map<String, Object>) ReflectionTestUtils.getField(entity, field);
    }

    /** The types Jackson 2 gives each member of {@link #HISTORICAL_DOCUMENT}. */
    private static void assertHistoricalTypes(Map<String, Object> read) {
        assertThat(read).isInstanceOf(LinkedHashMap.class);
        assertThat(read.get("s")).isEqualTo("x");
        assertThat(read.get("i")).isInstanceOf(Integer.class).isEqualTo(7);
        assertThat(read.get("l")).isInstanceOf(Long.class).isEqualTo(9007199254740993L);
        assertThat(read.get("d")).isInstanceOf(Double.class).isEqualTo(2.5);
        assertThat(read.get("b")).isEqualTo(Boolean.TRUE);
        assertThat(read).containsKey("n");
        assertThat(read.get("n")).isNull();
        assertThat(read.get("arr")).isInstanceOf(ArrayList.class).isEqualTo(Arrays.asList(1, "two", null));
        assertThat(read.get("obj")).isInstanceOf(LinkedHashMap.class).isEqualTo(Map.of("k", "v"));
    }

    @Nested
    @DisplayName("site_schemas.schema_data (SiteSchema)")
    class SiteSchemas {

        @Test
        @DisplayName("write: stored form of a free-form document")
        void write() {
            SiteSchema saved = siteSchemaRepository.save(SiteSchema.create(STORE_02_SITE_ID, writtenDocument()));
            assertThat(columnText("site_schemas", "schema_data", "id", saved.getId()))
                    .isEqualTo(normalized(WRITTEN_DOCUMENT_TEXT));
        }

        @Test
        @DisplayName("read: a historical document keeps Integer/Long/Double/null/List/Map types")
        void read() {
            SiteSchema saved = siteSchemaRepository.save(SiteSchema.create(STORE_02_SITE_ID, Map.of()));
            setColumn("site_schemas", "schema_data", "id", saved.getId(), HISTORICAL_DOCUMENT);
            SiteSchema read = siteSchemaRepository.findBySiteId(STORE_02_SITE_ID).orElseThrow();
            assertHistoricalTypes(rawMap(read, "schemaData"));
        }
    }

    @Nested
    @DisplayName("account_plugins.plugin_data (AccountPlugin)")
    class AccountPlugins {

        @Test
        @DisplayName("write: stored form of a free-form document")
        void write() {
            AccountPlugin saved = accountPluginRepository.save(
                    AccountPlugin.activate(PLUGIN_TEST_ACCOUNT_ID, "bit-bi", writtenDocument()));
            assertThat(columnText("account_plugins", "plugin_data", "id", saved.getId()))
                    .isEqualTo(normalized(WRITTEN_DOCUMENT_TEXT));
        }

        @Test
        @DisplayName("read: a historical document keeps its types (read from the field, behind the getter's Map.copyOf)")
        void read() {
            AccountPlugin saved = accountPluginRepository.save(
                    AccountPlugin.activate(PLUGIN_TEST_ACCOUNT_ID, "bit-bi", Map.of("apiKeyHash", "h")));
            setColumn("account_plugins", "plugin_data", "id", saved.getId(), HISTORICAL_DOCUMENT);
            AccountPlugin read = accountPluginRepository.findByAccountIdAndPluginId(PLUGIN_TEST_ACCOUNT_ID, "bit-bi")
                    .orElseThrow();
            assertHistoricalTypes(rawMap(read, "pluginData"));
        }
    }

    @Nested
    @DisplayName("plugin_configs.config (PluginConfig)")
    class PluginConfigs {

        @Test
        @DisplayName("write: stored form of a free-form document")
        void write() {
            String suffix = Long.toHexString(ThreadLocalRandom.current().nextLong());
            PluginConfig config = PluginConfig.create("jsonb-300-" + suffix, "jsonb-300-" + suffix, "JSONB #300");
            ReflectionTestUtils.setField(config, "config", writtenDocument());
            PluginConfig saved = pluginConfigRepository.save(config);
            assertThat(columnText("plugin_configs", "config", "id", saved.getId()))
                    .isEqualTo(normalized(WRITTEN_DOCUMENT_TEXT));
        }

        @Test
        @DisplayName("read: the seeded bit-bi row with a historical document keeps its types")
        void read() {
            setColumn("plugin_configs", "config", "plugin_id", "bit-bi", HISTORICAL_DOCUMENT);
            PluginConfig read = pluginConfigRepository.findByPluginId("bit-bi").orElseThrow();
            assertHistoricalTypes(rawMap(read, "config"));
        }
    }

    @Nested
    @DisplayName("plugin_audit_logs.metadata (PluginAuditLog)")
    class PluginAuditLogs {

        @Test
        @DisplayName("write: stored form of a free-form document")
        void write() {
            PluginAuditLog saved = pluginAuditLogRepository.saveAndFlush(PluginAuditLog
                    .success("bit-bi", PLUGIN_TEST_ACCOUNT_ID, PluginActionType.SQL_GENERATION_COMPLETED)
                    .withMetadata(writtenDocument()));
            assertThat(columnText("plugin_audit_logs", "metadata", "id", saved.getId()))
                    .isEqualTo(normalized(WRITTEN_DOCUMENT_TEXT));
        }

        @Test
        @DisplayName("read: a historical document keeps its types, and a row without metadata reads null")
        void read() {
            PluginAuditLog saved = pluginAuditLogRepository.saveAndFlush(PluginAuditLog
                    .success("bit-bi", PLUGIN_TEST_ACCOUNT_ID, PluginActionType.ACTIVATE));
            entityManager.flush();
            entityManager.clear();
            assertThat(pluginAuditLogRepository.findById(saved.getId()).orElseThrow().getMetadata()).isNull();

            setColumn("plugin_audit_logs", "metadata", "id", saved.getId(), HISTORICAL_DOCUMENT);
            assertHistoricalTypes(rawMap(pluginAuditLogRepository.findById(saved.getId()).orElseThrow(), "metadata"));
        }
    }

    @Nested
    @DisplayName("admin_action_logs.details (AdminActionLog)")
    class AdminActionLogs {

        @Test
        @DisplayName("write: stored form of a free-form document")
        void write() {
            AdminActionLog saved = adminActionLogRepository.save(AdminActionLog
                    .success(AdminActionType.CREATE_ACCOUNT, ACCOUNT_ID, null, null, null)
                    .withDetails(writtenDocument()));
            assertThat(columnText("admin_action_logs", "details", "id", saved.getId()))
                    .isEqualTo(normalized(WRITTEN_DOCUMENT_TEXT));
        }

        @Test
        @DisplayName("read: a pre-V48 row (details NULL) reads null; a historical document keeps its types")
        void read() {
            AdminActionLog saved = adminActionLogRepository.save(AdminActionLog
                    .success(AdminActionType.CREATE_ACCOUNT, ACCOUNT_ID, null, null, null));
            entityManager.flush();
            entityManager.clear();
            assertThat(adminActionLogRepository.findById(saved.getId()).orElseThrow().getDetails()).isNull();

            setColumn("admin_action_logs", "details", "id", saved.getId(), HISTORICAL_DOCUMENT);
            assertHistoricalTypes(rawMap(adminActionLogRepository.findById(saved.getId()).orElseThrow(), "details"));
        }
    }

    @Nested
    @DisplayName("changelog_segments.stats (ChangelogSegment, Map<String, TableChangeStats>)")
    class ChangelogSegments {

        private ChangelogSegment saveSegment(Map<String, TableChangeStats> stats) {
            long seq = 900_000_000L + ThreadLocalRandom.current().nextLong(1_000_000L);
            return changelogSegmentRepository.save(ChangelogSegment.create(STORE_01_SITE_ID, IN_PROGRESS_BATCH_ID,
                    seq, seq, 1, "hash-300", "delta/jsonb-300/" + seq + ".pb.gz", "DELTA", stats));
        }

        @Test
        @DisplayName("write: record components only, total() is not stored")
        void write() {
            Map<String, TableChangeStats> stats = new LinkedHashMap<>();
            stats.put("orders", new TableChangeStats(3, 1, 0));
            stats.put("customers", new TableChangeStats(0, 0, 9_000_000_000L));
            ChangelogSegment saved = saveSegment(stats);

            assertThat(columnText("changelog_segments", "stats", "id", saved.getId())).isEqualTo(normalized(
                    "{\"orders\": {\"deletes\": 0, \"inserts\": 3, \"updates\": 1}, "
                            + "\"customers\": {\"deletes\": 9000000000, \"inserts\": 0, \"updates\": 0}}"));
        }

        @Test
        @DisplayName("read: a missing member reads as 0, a null member as 0, and a pre-stats row (NULL) as null")
        void read() {
            ChangelogSegment saved = saveSegment(null);
            entityManager.flush();
            entityManager.clear();
            assertThat(changelogSegmentRepository.findById(saved.getId()).orElseThrow().getStats()).isNull();

            setColumn("changelog_segments", "stats", "id", saved.getId(),
                    "{\"orders\": {\"inserts\": 3, \"updates\": 1}, \"customers\": {\"inserts\": null, \"updates\": 2, \"deletes\": 1}}");
            Map<String, TableChangeStats> read = changelogSegmentRepository.findById(saved.getId()).orElseThrow().getStats();
            assertThat(read).containsExactlyInAnyOrderEntriesOf(Map.of(
                    "orders", new TableChangeStats(3, 1, 0),
                    "customers", new TableChangeStats(0, 2, 1)));
        }

        @Test
        @DisplayName("read: an unknown member inside a stats object fails the load")
        void unknownMemberFailsTheLoad() {
            ChangelogSegment saved = saveSegment(Map.of("orders", new TableChangeStats(1, 0, 0)));
            setColumn("changelog_segments", "stats", "id", saved.getId(),
                    "{\"orders\": {\"inserts\": 1, \"updates\": 0, \"deletes\": 0, \"total\": 1}}");

            Throwable failure = null;
            try {
                changelogSegmentRepository.findById(saved.getId());
            } catch (RuntimeException e) {
                failure = e;
            }
            assertThat(failure).as("loading a stats object with a member TableChangeStats does not declare").isNotNull();
        }
    }

    @Nested
    @DisplayName("comparison_results.unified_diff (ComparisonResultEntity, String)")
    class ComparisonResults {

        private static final UUID CURRENT_BATCH_ID = UUID.fromString("a1b2c3d4-e5f6-7890-abcd-ef1234567890");
        private static final UUID TARGET_BATCH_ID = UUID.fromString("c3d4e5f6-a7b8-9012-cdef-123456789012");
        private static final UUID FILE_ID = UUID.fromString("a1b2c3d4-e5f6-7890-abcd-111111111111");

        /** The document {@code DiffServiceImpl} writes, key order as its LinkedHashMap builds it. */
        private static final String DIFF = "{\"hunks\":[{\"oldStart\":1,\"oldLines\":0,\"newStart\":1,\"newLines\":1,"
                + "\"changes\":[{\"type\":\"ADDED\",\"lineNumber\":1,\"content\":\"a,b\"}]}],"
                + "\"oldFileName\":null,\"newFileName\":\"data.csv\"}";

        private Long saveComparison() {
            // As ComparisonService does: the comparison gets its id first, the result carries it.
            FileComparison comparison = comparisonRepository.save(
                    new FileComparison(CURRENT_BATCH_ID, TARGET_BATCH_ID, ACCOUNT_ID));
            comparison.addResult(new ComparisonResult(comparison.getId(), FILE_ID, null, ChangeType.ADDED, DIFF, 1, 0, 3L));
            return comparisonRepository.save(comparison).getId();
        }

        @Test
        @DisplayName("write: the String is stored as a JSONB document, not as a JSON string")
        void write() {
            Long comparisonId = saveComparison();
            assertThat(columnText("comparison_results", "unified_diff", "comparison_id", comparisonId))
                    .isEqualTo(normalized(DIFF));
        }

        @Test
        @DisplayName("read: the String comes back as PostgreSQL's normalized text of the document")
        void read() {
            Long comparisonId = saveComparison();
            entityManager.flush();
            entityManager.clear();

            String read = comparisonRepository.findByIdWithResults(comparisonId).orElseThrow()
                    .getResults().get(0).getUnifiedDiff();
            assertThat(read).isEqualTo(normalized(DIFF));
        }
    }

    @Test
    @DisplayName("the seven entities pinned here are every @Type(JsonBinaryType.class) mapping")
    void everyJsonbMappingIsCovered() {
        List<String> mapped = entityManager.getMetamodel().getEntities().stream()
                .flatMap(entity -> Arrays.stream(entity.getJavaType().getDeclaredFields())
                        .filter(field -> {
                            org.hibernate.annotations.Type type = field.getAnnotation(org.hibernate.annotations.Type.class);
                            return type != null && type.value().getSimpleName().equals("JsonBinaryType");
                        })
                        .map(field -> entity.getJavaType().getSimpleName() + "." + field.getName()))
                .toList();

        assertThat(mapped).containsExactlyInAnyOrder(
                "SiteSchema.schemaData", "AccountPlugin.pluginData", "PluginConfig.config",
                "PluginAuditLog.metadata", "AdminActionLog.details", "ChangelogSegment.stats",
                "ComparisonResultEntity.unifiedDiff");
    }
}
