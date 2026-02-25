package com.bitbi.dfm.plugin.application;

import com.bitbi.dfm.plugin.domain.JsonlChangeRecord;
import com.bitbi.dfm.site.domain.TableSchema;
import com.bitbi.dfm.upload.domain.UploadedFile;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.io.IOException;
import java.lang.reflect.Method;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for CdcSqlGenerationStrategy.
 */
@DisplayName("CdcSqlGenerationStrategy")
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class CdcSqlGenerationStrategyTest {

    @Mock
    private JsonlParserService jsonlParserService;

    @Mock
    private SqlStatementGenerator sqlStatementGenerator;

    private SimpleMeterRegistry meterRegistry;
    private CdcSqlGenerationStrategy strategy;

    private UUID batchId;
    private UUID siteId;

    @BeforeEach
    void setUp() {
        meterRegistry = new SimpleMeterRegistry();
        strategy = new CdcSqlGenerationStrategy(jsonlParserService, sqlStatementGenerator, meterRegistry);
        batchId = UUID.randomUUID();
        siteId = UUID.randomUUID();
    }

    // --- helpers ---

    private UploadedFile mockFile(String originalFileName, String s3Key) {
        UploadedFile file = mock(UploadedFile.class);
        when(file.getOriginalFileName()).thenReturn(originalFileName);
        when(file.getS3Key()).thenReturn(s3Key);
        return file;
    }

    private TableSchema buildSchema(String... columnNames) {
        List<TableSchema.ColumnDefinition> cols = java.util.Arrays.stream(columnNames)
                .map(name -> new TableSchema.ColumnDefinition(name, "text", true))
                .toList();
        return new TableSchema(cols, List.of(), List.of());
    }

    private SqlGenerationContext buildContext(List<UploadedFile> files, Map<String, TableSchema> schemas) {
        return new SqlGenerationContext(batchId, siteId, files, Map.of(), schemas);
    }

    @Nested
    @DisplayName("generate()")
    class Generate {

        @Test
        @DisplayName("should return null when no files are provided")
        void shouldReturnNullForEmptyFileList() throws IOException {
            SqlGenerationContext ctx = buildContext(List.of(), Map.of());

            SqlGenerationResult result = strategy.generate(ctx);

            assertThat(result).isNull();
        }

        @Test
        @DisplayName("should skip file when no schema found for derived table name")
        void shouldSkipFileWithNoSchema() throws IOException {
            UploadedFile file = mockFile("orders.jsonl", "account/site/orders.jsonl");
            SqlGenerationContext ctx = buildContext(List.of(file), Map.of()); // no schema for 'orders'

            SqlGenerationResult result = strategy.generate(ctx);

            assertThat(result).isNull();
            assertThat(meterRegistry.counter("sql.generation.cdc.files.skipped.no_schema").count()).isEqualTo(1.0);
            verifyNoInteractions(jsonlParserService);
        }

        @Test
        @DisplayName("should process file and generate SQL for INSERT records")
        void shouldGenerateSqlForInsertRecords() throws IOException {
            UploadedFile file = mockFile("customers.jsonl", "account/site/customers.jsonl");
            TableSchema schema = buildSchema("id", "name");
            Map<String, TableSchema> schemas = Map.of("customers", schema);

            Map<String, Object> data = new LinkedHashMap<>(Map.of("id", 1, "name", "Alice"));
            JsonlChangeRecord insertRecord = new JsonlChangeRecord("I", null, data, 1);
            when(jsonlParserService.parseFromS3("account/site/customers.jsonl")).thenReturn(List.of(insertRecord));
            when(sqlStatementGenerator.generateFromJsonl(any(), eq("customers"))).thenReturn("INSERT INTO customers ...\n");

            SqlGenerationContext ctx = buildContext(List.of(file), schemas);
            SqlGenerationResult result = strategy.generate(ctx);

            assertThat(result).isNotNull();
            assertThat(result.sqlContent()).contains("INSERT INTO customers");
            assertThat(result.stats().inserts()).isEqualTo(1);
            assertThat(result.stats().updates()).isEqualTo(0);
            assertThat(result.stats().deletes()).isEqualTo(0);
            assertThat(meterRegistry.counter("sql.generation.cdc.files.processed").count()).isEqualTo(1.0);
        }

        @Test
        @DisplayName("should count INSERT, UPDATE, DELETE separately in stats")
        void shouldCountAllOperationTypes() throws IOException {
            UploadedFile file = mockFile("products.jsonl", "s3/products.jsonl");
            TableSchema schema = buildSchema("id", "name", "price");
            Map<String, TableSchema> schemas = Map.of("products", schema);

            Map<String, Object> iData = new LinkedHashMap<>(Map.of("id", 1, "name", "A", "price", 100));
            Map<String, Object> uKey = new LinkedHashMap<>(Map.of("id", 2));
            Map<String, Object> uData = new LinkedHashMap<>(Map.of("price", 200));
            Map<String, Object> dKey = new LinkedHashMap<>(Map.of("id", 3));

            List<JsonlChangeRecord> records = List.of(
                    new JsonlChangeRecord("I", null, iData, 1),
                    new JsonlChangeRecord("U", uKey, uData, 2),
                    new JsonlChangeRecord("D", dKey, null, 3)
            );
            when(jsonlParserService.parseFromS3("s3/products.jsonl")).thenReturn(records);
            when(sqlStatementGenerator.generateFromJsonl(any(), eq("products"))).thenReturn("SQL;\n");

            SqlGenerationContext ctx = buildContext(List.of(file), schemas);
            SqlGenerationResult result = strategy.generate(ctx);

            assertThat(result).isNotNull();
            assertThat(result.stats().inserts()).isEqualTo(1);
            assertThat(result.stats().updates()).isEqualTo(1);
            assertThat(result.stats().deletes()).isEqualTo(1);
            assertThat(result.stats().filesProcessed()).isEqualTo(1);
        }

        @Test
        @DisplayName("should re-throw InvalidJsonlException and increment failed counter")
        void shouldRethrowInvalidJsonlException() throws IOException {
            UploadedFile file = mockFile("broken.jsonl", "s3/broken.jsonl");
            TableSchema schema = buildSchema("id");
            Map<String, TableSchema> schemas = Map.of("broken", schema);

            when(jsonlParserService.parseFromS3("s3/broken.jsonl"))
                    .thenThrow(new JsonlParserService.InvalidJsonlException("Missing 'k' at line 2"));

            SqlGenerationContext ctx = buildContext(List.of(file), schemas);

            assertThatThrownBy(() -> strategy.generate(ctx))
                    .isInstanceOf(JsonlParserService.InvalidJsonlException.class)
                    .hasMessageContaining("Missing 'k' at line 2");

            assertThat(meterRegistry.counter("sql.generation.cdc.files.failed").count()).isEqualTo(1.0);
        }

        @Test
        @DisplayName("should process .gz file by stripping .gz when deriving table name")
        void shouldProcessGzFile() throws IOException {
            UploadedFile file = mockFile("invoices.jsonl.gz", "s3/invoices.jsonl.gz");
            TableSchema schema = buildSchema("id", "amount");
            Map<String, TableSchema> schemas = Map.of("invoices", schema);

            Map<String, Object> data = new LinkedHashMap<>(Map.of("id", 1, "amount", 999));
            List<JsonlChangeRecord> records = List.of(new JsonlChangeRecord("I", null, data, 1));
            when(jsonlParserService.parseFromS3("s3/invoices.jsonl.gz")).thenReturn(records);
            when(sqlStatementGenerator.generateFromJsonl(any(), eq("invoices"))).thenReturn("INSERT ...\n");

            SqlGenerationContext ctx = buildContext(List.of(file), schemas);
            SqlGenerationResult result = strategy.generate(ctx);

            assertThat(result).isNotNull();
        }

        @Test
        @DisplayName("should filter unknown columns from record data before generating SQL")
        void shouldFilterUnknownColumnsFromData() throws IOException {
            UploadedFile file = mockFile("items.jsonl", "s3/items.jsonl");
            TableSchema schema = buildSchema("id", "name"); // 'extra' is not in schema
            Map<String, TableSchema> schemas = Map.of("items", schema);

            Map<String, Object> data = new LinkedHashMap<>();
            data.put("id", 1);
            data.put("name", "Widget");
            data.put("extra", "unknown_field"); // not in schema
            JsonlChangeRecord record = new JsonlChangeRecord("I", null, data, 1);
            when(jsonlParserService.parseFromS3("s3/items.jsonl")).thenReturn(List.of(record));
            when(sqlStatementGenerator.generateFromJsonl(any(), eq("items"))).thenAnswer(inv -> {
                JsonlChangeRecord r = inv.getArgument(0);
                // Assert that 'extra' was filtered out before calling generator
                assertThat(r.data()).doesNotContainKey("extra");
                assertThat(r.data()).containsKey("id");
                assertThat(r.data()).containsKey("name");
                return "INSERT INTO items ...\n";
            });

            SqlGenerationContext ctx = buildContext(List.of(file), schemas);
            strategy.generate(ctx);

            verify(sqlStatementGenerator).generateFromJsonl(any(), eq("items"));
        }
    }

    @Nested
    @DisplayName("deriveTableName() - via reflection")
    class DeriveTableName {

        private String invokeDerive(String fileName) throws Exception {
            Method m = CdcSqlGenerationStrategy.class.getDeclaredMethod("deriveTableName", String.class);
            m.setAccessible(true);
            return (String) m.invoke(strategy, fileName);
        }

        @Test
        @DisplayName("should strip .jsonl extension and lowercase")
        void shouldStripJsonlAndLowercase() throws Exception {
            assertThat(invokeDerive("Orders.jsonl")).isEqualTo("orders");
        }

        @Test
        @DisplayName("should replace non-alphanumeric chars with underscore")
        void shouldReplaceSpecialChars() throws Exception {
            assertThat(invokeDerive("my-table.jsonl")).isEqualTo("my_table");
            assertThat(invokeDerive("my table.jsonl")).isEqualTo("my_table");
        }

        @Test
        @DisplayName("should prepend underscore if result starts with digit")
        void shouldPrependUnderscoreForDigitStart() throws Exception {
            assertThat(invokeDerive("2024_data.jsonl")).startsWith("_");
        }

        @Test
        @DisplayName("should handle name without .jsonl extension")
        void shouldHandleNameWithoutExtension() throws Exception {
            assertThat(invokeDerive("table_name")).isEqualTo("table_name");
        }
    }

    @Nested
    @DisplayName("normalizeFileName() - via reflection")
    class NormalizeFileName {

        private String invokeNormalize(String fileName) throws Exception {
            Method m = CdcSqlGenerationStrategy.class.getDeclaredMethod("normalizeFileName", String.class);
            m.setAccessible(true);
            return (String) m.invoke(strategy, fileName);
        }

        @Test
        @DisplayName("should strip .gz suffix from filename")
        void shouldStripGzSuffix() throws Exception {
            assertThat(invokeNormalize("orders.jsonl.gz")).isEqualTo("orders.jsonl");
        }

        @Test
        @DisplayName("should leave filename unchanged if not .gz")
        void shouldLeaveUnchangedIfNotGz() throws Exception {
            assertThat(invokeNormalize("orders.jsonl")).isEqualTo("orders.jsonl");
            assertThat(invokeNormalize("orders.csv")).isEqualTo("orders.csv");
        }

        @Test
        @DisplayName("should be case-insensitive for .gz check")
        void shouldBeCaseInsensitiveForGzCheck() throws Exception {
            assertThat(invokeNormalize("orders.jsonl.GZ")).isEqualTo("orders.jsonl");
        }
    }
}
