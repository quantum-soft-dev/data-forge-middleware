package com.bitbi.dfm.plugin.application;

import com.bitbi.dfm.comparison.domain.DiffService;
import com.bitbi.dfm.plugin.domain.DbfColumnType;
import com.bitbi.dfm.site.domain.TableSchema;
import com.bitbi.dfm.upload.domain.UploadedFile;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Rendering through {@link DbfSqlGenerationStrategy}, not only through a direct
 * {@link SqlStatementGenerator#generate} call. Direct generator tests could (and did)
 * supply a type map the production caller never passed, which is how the empty-map
 * divergence of issue #263 lived.
 */
@DisplayName("DbfSqlGenerationStrategy")
@ExtendWith(MockitoExtension.class)
class DbfSqlGenerationStrategyTest {

    private static final String BUCKET = "test-bucket";

    @Mock
    private S3Client s3Client;

    @Mock
    private DiffService diffService;

    private DbfSqlGenerationStrategy strategy;
    private UUID batchId;
    private UUID siteId;

    @BeforeEach
    void setUp() {
        CsvDiffService csvDiffService = new CsvDiffService(diffService, new ObjectMapper());
        strategy = new DbfSqlGenerationStrategy(
                csvDiffService,
                new SqlStatementGenerator(),
                s3Client,
                BUCKET,
                new SimpleMeterRegistry());
        batchId = UUID.randomUUID();
        siteId = UUID.randomUUID();
    }

    private UploadedFile mockFile(String originalFileName, String s3Key) {
        UploadedFile file = mock(UploadedFile.class);
        when(file.getOriginalFileName()).thenReturn(originalFileName);
        when(file.getS3Key()).thenReturn(s3Key);
        return file;
    }

    private void s3Returns(String csv) {
        when(s3Client.getObject(any(GetObjectRequest.class))).thenReturn(
                new ResponseInputStream<>(
                        GetObjectResponse.builder().build(),
                        new ByteArrayInputStream(csv.getBytes(StandardCharsets.UTF_8))));
    }

    private TableSchema schema(TableSchema.ColumnDefinition... columns) {
        return new TableSchema(List.of(columns), List.of(), List.of());
    }

    private TableSchema.ColumnDefinition col(String name, String type) {
        return new TableSchema.ColumnDefinition(name, type, true);
    }

    private SqlGenerationContext context(UploadedFile file, Map<String, TableSchema> schemas) {
        return new SqlGenerationContext(batchId, siteId, List.of(file), Map.of(), schemas);
    }

    @Nested
    @DisplayName("column types from TableSchema")
    class ColumnTypesFromTableSchema {

        @Test
        @DisplayName("should emit 0 for an empty integer cell when the schema declares integer")
        void shouldEmitZeroForEmptyIntegerWhenSchemaDeclaresInteger() throws Exception {
            UploadedFile file = mockFile("orders.csv", "account/site/orders.csv");
            s3Returns("id,quantity\n1,\n");
            TableSchema orders = schema(col("id", "integer"), col("quantity", "integer"));

            SqlGenerationResult result = strategy.generate(context(file, Map.of("orders", orders)));

            assertThat(result).isNotNull();
            assertThat(result.sqlContent()).contains("VALUES (1, 0)");
            assertThat(result.sqlContent()).doesNotContain("NULL");
        }

        @Test
        @DisplayName("should emit NULL for an empty varchar cell")
        void shouldEmitNullForEmptyVarchar() throws Exception {
            UploadedFile file = mockFile("customers.csv", "account/site/customers.csv");
            s3Returns("id,name\n1,\n");
            TableSchema customers = schema(col("id", "integer"), col("name", "varchar(255)"));

            SqlGenerationResult result = strategy.generate(context(file, Map.of("customers", customers)));

            assertThat(result).isNotNull();
            assertThat(result.sqlContent()).contains("VALUES (1, NULL)");
        }

        @Test
        @DisplayName("should emit an unquoted numeric literal for a numeric column")
        void shouldEmitUnquotedNumericLiteral() throws Exception {
            UploadedFile file = mockFile("items.csv", "account/site/items.csv");
            s3Returns("id,weight\n1,12.50\n");
            TableSchema items = schema(col("id", "integer"), col("weight", "numeric(12,2)"));

            SqlGenerationResult result = strategy.generate(context(file, Map.of("items", items)));

            assertThat(result).isNotNull();
            assertThat(result.sqlContent()).contains("VALUES (1, 12.50)");
            assertThat(result.sqlContent()).doesNotContain("'12.50'");
        }

        @Test
        @DisplayName("should quote and escape a non-numeric cell in a numeric column (injection)")
        void shouldQuoteInjectionPayloadInNumericColumn() throws Exception {
            UploadedFile file = mockFile("payments.csv", "account/site/payments.csv");
            s3Returns("id,amount\n1,\"0); DROP TABLE customers; --\"\n");
            TableSchema payments = schema(col("id", "integer"), col("amount", "numeric"));

            SqlGenerationResult result = strategy.generate(context(file, Map.of("payments", payments)));

            assertThat(result).isNotNull();
            assertThat(result.sqlContent()).contains("'0); DROP TABLE customers; --'");
            assertThat(result.sqlContent()).doesNotContain("VALUES (1, 0); DROP");
        }

        @Test
        @DisplayName("should fall back to CHARACTER when the site has no schema")
        void shouldTreatEveryColumnAsCharacterWithoutSchema() throws Exception {
            UploadedFile file = mockFile("orders.csv", "account/site/orders.csv");
            s3Returns("id,quantity\n1,\n");

            SqlGenerationResult result = strategy.generate(context(file, Map.of()));

            assertThat(result).isNotNull();
            // Empty CHARACTER → NULL, and the populated id is quoted.
            assertThat(result.sqlContent()).contains("VALUES ('1', NULL)");
        }

        @Test
        @DisplayName("should fall back to CHARACTER when the schema is for a different table")
        void shouldIgnoreSchemaForADifferentTable() throws Exception {
            UploadedFile file = mockFile("orders.csv", "account/site/orders.csv");
            s3Returns("id,quantity\n1,\n");
            TableSchema other = schema(col("id", "integer"), col("quantity", "integer"));

            SqlGenerationResult result = strategy.generate(context(file, Map.of("customers", other)));

            assertThat(result).isNotNull();
            assertThat(result.sqlContent()).contains("VALUES ('1', NULL)");
        }

        @Test
        @DisplayName("should map money to CURRENCY so an empty cell becomes 0")
        void shouldEmitZeroForEmptyMoneyColumn() throws Exception {
            UploadedFile file = mockFile("payments.csv", "account/site/payments.csv");
            s3Returns("id,amount\n1,\n");
            TableSchema payments = schema(col("id", "integer"), col("amount", "money"));

            SqlGenerationResult result = strategy.generate(context(file, Map.of("payments", payments)));

            assertThat(result).isNotNull();
            assertThat(result.sqlContent()).contains("VALUES (1, 0)");
        }

        @Test
        @DisplayName("should quote a non-finite token in a float column")
        void shouldQuoteNonFiniteInFloatColumn() throws Exception {
            UploadedFile file = mockFile("samples.csv", "account/site/samples.csv");
            s3Returns("id,reading\n1,NaN\n");
            TableSchema samples = schema(col("id", "integer"), col("reading", "double precision"));

            SqlGenerationResult result = strategy.generate(context(file, Map.of("samples", samples)));

            assertThat(result).isNotNull();
            assertThat(result.sqlContent()).contains("VALUES (1, 'NaN')");
        }
    }
}
