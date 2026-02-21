package com.bitbi.dfm.site.application;

import com.bitbi.dfm.site.domain.SiteSchema;
import com.bitbi.dfm.site.domain.SiteSchemaRepository;
import com.bitbi.dfm.site.domain.TableSchema;
import com.bitbi.dfm.upload.presentation.dto.SchemaUploadRequestDto;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Unit tests for SiteSchemaService.
 */
@DisplayName("SiteSchemaService")
@ExtendWith(MockitoExtension.class)
class SiteSchemaServiceTest {

    @Mock
    private SiteSchemaRepository siteSchemaRepository;

    private SiteSchemaService service;

    @BeforeEach
    void setUp() {
        service = new SiteSchemaService(siteSchemaRepository, new ObjectMapper());
    }

    // --- Helpers ---

    private SchemaUploadRequestDto buildValidRequest(String tableName) {
        SchemaUploadRequestDto.ColumnDto col1 = new SchemaUploadRequestDto.ColumnDto("id", "integer", false);
        SchemaUploadRequestDto.ColumnDto col2 = new SchemaUploadRequestDto.ColumnDto("name", "varchar(255)", true);
        SchemaUploadRequestDto.TableSchemaDto tableDto = new SchemaUploadRequestDto.TableSchemaDto(
                List.of(col1, col2), List.of("id"), null);
        return new SchemaUploadRequestDto(Map.of(tableName, tableDto));
    }

    @Nested
    @DisplayName("upsertSchema()")
    class UpsertSchema {

        @Test
        @DisplayName("should create new schema when none exists")
        void shouldCreateNewSchemaWhenNoneExists() {
            UUID siteId = UUID.randomUUID();
            SchemaUploadRequestDto request = buildValidRequest("customers");

            when(siteSchemaRepository.findBySiteId(siteId)).thenReturn(Optional.empty());
            when(siteSchemaRepository.save(any(SiteSchema.class))).thenAnswer(inv -> inv.getArgument(0));

            SiteSchema result = service.upsertSchema(siteId, request);

            assertThat(result).isNotNull();
            assertThat(result.getSiteId()).isEqualTo(siteId);
            assertThat(result.getSchemaVersion()).isEqualTo(1);
            verify(siteSchemaRepository).save(any(SiteSchema.class));
        }

        @Test
        @DisplayName("should update existing schema and increment version")
        void shouldUpdateExistingSchema() {
            UUID siteId = UUID.randomUUID();
            SchemaUploadRequestDto request = buildValidRequest("customers");

            SiteSchema existing = SiteSchema.create(siteId, Map.of("tables", Map.of()));
            when(siteSchemaRepository.findBySiteId(siteId)).thenReturn(Optional.of(existing));
            when(siteSchemaRepository.save(existing)).thenReturn(existing);

            SiteSchema result = service.upsertSchema(siteId, request);

            assertThat(result.getSchemaVersion()).isEqualTo(2); // incremented from 1
            verify(siteSchemaRepository).save(existing);
        }
    }

    @Nested
    @DisplayName("validateSchema() — table-level validation")
    class TableValidation {

        @Test
        @DisplayName("should throw when tables map is empty")
        void shouldThrowForEmptyTables() {
            SchemaUploadRequestDto request = new SchemaUploadRequestDto(Map.of());

            assertThatThrownBy(() -> service.upsertSchema(UUID.randomUUID(), request))
                    .isInstanceOf(SiteSchemaService.InvalidSchemaException.class)
                    .hasMessageContaining("at least one table");
        }

        @Test
        @DisplayName("should throw for invalid table name (starts with digit)")
        void shouldThrowForInvalidTableNameStartingWithDigit() {
            SchemaUploadRequestDto.ColumnDto col = new SchemaUploadRequestDto.ColumnDto("id", "integer", false);
            SchemaUploadRequestDto.TableSchemaDto tableDto = new SchemaUploadRequestDto.TableSchemaDto(
                    List.of(col), List.of(), null);
            SchemaUploadRequestDto request = new SchemaUploadRequestDto(Map.of("1invalid", tableDto));

            assertThatThrownBy(() -> service.upsertSchema(UUID.randomUUID(), request))
                    .isInstanceOf(SiteSchemaService.InvalidSchemaException.class)
                    .hasMessageContaining("Invalid table name");
        }

        @Test
        @DisplayName("should throw for table name exceeding 63 characters")
        void shouldThrowForTableNameTooLong() {
            String longName = "a".repeat(64);
            SchemaUploadRequestDto.ColumnDto col = new SchemaUploadRequestDto.ColumnDto("id", "integer", false);
            SchemaUploadRequestDto.TableSchemaDto tableDto = new SchemaUploadRequestDto.TableSchemaDto(
                    List.of(col), List.of(), null);
            SchemaUploadRequestDto request = new SchemaUploadRequestDto(Map.of(longName, tableDto));

            assertThatThrownBy(() -> service.upsertSchema(UUID.randomUUID(), request))
                    .isInstanceOf(SiteSchemaService.InvalidSchemaException.class);
        }

        @Test
        @DisplayName("should throw when table has no columns")
        void shouldThrowForTableWithNoColumns() {
            SchemaUploadRequestDto.TableSchemaDto tableDto = new SchemaUploadRequestDto.TableSchemaDto(
                    List.of(), List.of(), null);
            SchemaUploadRequestDto request = new SchemaUploadRequestDto(Map.of("orders", tableDto));

            assertThatThrownBy(() -> service.upsertSchema(UUID.randomUUID(), request))
                    .isInstanceOf(SiteSchemaService.InvalidSchemaException.class)
                    .hasMessageContaining("at least one column");
        }

        @Test
        @DisplayName("should throw when table has null columns list")
        void shouldThrowForNullColumnsList() {
            SchemaUploadRequestDto.TableSchemaDto tableDto = new SchemaUploadRequestDto.TableSchemaDto(
                    null, List.of(), null);
            SchemaUploadRequestDto request = new SchemaUploadRequestDto(Map.of("orders", tableDto));

            assertThatThrownBy(() -> service.upsertSchema(UUID.randomUUID(), request))
                    .isInstanceOf(SiteSchemaService.InvalidSchemaException.class)
                    .hasMessageContaining("at least one column");
        }
    }

    @Nested
    @DisplayName("validateSchema() — column validation")
    class ColumnValidation {

        @Test
        @DisplayName("should throw for blank column name")
        void shouldThrowForBlankColumnName() {
            SchemaUploadRequestDto.ColumnDto col = new SchemaUploadRequestDto.ColumnDto("", "integer", false);
            SchemaUploadRequestDto.TableSchemaDto tableDto = new SchemaUploadRequestDto.TableSchemaDto(
                    List.of(col), List.of(), null);
            SchemaUploadRequestDto request = new SchemaUploadRequestDto(Map.of("orders", tableDto));

            assertThatThrownBy(() -> service.upsertSchema(UUID.randomUUID(), request))
                    .isInstanceOf(SiteSchemaService.InvalidSchemaException.class)
                    .hasMessageContaining("Column name cannot be blank");
        }

        @Test
        @DisplayName("should throw for blank column type")
        void shouldThrowForBlankColumnType() {
            SchemaUploadRequestDto.ColumnDto col = new SchemaUploadRequestDto.ColumnDto("id", "", false);
            SchemaUploadRequestDto.TableSchemaDto tableDto = new SchemaUploadRequestDto.TableSchemaDto(
                    List.of(col), List.of(), null);
            SchemaUploadRequestDto request = new SchemaUploadRequestDto(Map.of("orders", tableDto));

            assertThatThrownBy(() -> service.upsertSchema(UUID.randomUUID(), request))
                    .isInstanceOf(SiteSchemaService.InvalidSchemaException.class)
                    .hasMessageContaining("Column type cannot be blank");
        }

        @Test
        @DisplayName("should throw for invalid column name (starts with digit)")
        void shouldThrowForInvalidColumnName() {
            SchemaUploadRequestDto.ColumnDto col = new SchemaUploadRequestDto.ColumnDto("2bad", "integer", false);
            SchemaUploadRequestDto.TableSchemaDto tableDto = new SchemaUploadRequestDto.TableSchemaDto(
                    List.of(col), List.of(), null);
            SchemaUploadRequestDto request = new SchemaUploadRequestDto(Map.of("orders", tableDto));

            assertThatThrownBy(() -> service.upsertSchema(UUID.randomUUID(), request))
                    .isInstanceOf(SiteSchemaService.InvalidSchemaException.class)
                    .hasMessageContaining("Invalid column name");
        }
    }

    @Nested
    @DisplayName("validateSchema() — primary key validation")
    class PrimaryKeyValidation {

        @Test
        @DisplayName("should throw when PK column not in column list")
        void shouldThrowWhenPkColumnNotInColumnList() {
            SchemaUploadRequestDto.ColumnDto col = new SchemaUploadRequestDto.ColumnDto("name", "varchar(255)", true);
            SchemaUploadRequestDto.TableSchemaDto tableDto = new SchemaUploadRequestDto.TableSchemaDto(
                    List.of(col), List.of("id"), null); // 'id' is not in columns
            SchemaUploadRequestDto request = new SchemaUploadRequestDto(Map.of("orders", tableDto));

            assertThatThrownBy(() -> service.upsertSchema(UUID.randomUUID(), request))
                    .isInstanceOf(SiteSchemaService.InvalidSchemaException.class)
                    .hasMessageContaining("Primary key column 'id' not found");
        }

        @Test
        @DisplayName("should accept when PK column exists in column list")
        void shouldAcceptValidPrimaryKey() {
            UUID siteId = UUID.randomUUID();
            SchemaUploadRequestDto request = buildValidRequest("customers");
            when(siteSchemaRepository.findBySiteId(siteId)).thenReturn(Optional.empty());
            when(siteSchemaRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            SiteSchema result = service.upsertSchema(siteId, request);

            assertThat(result).isNotNull();
        }
    }

    @Nested
    @DisplayName("validateSchema() — unique key validation")
    class UniqueKeyValidation {

        @Test
        @DisplayName("should throw when unique key column not in column list")
        void shouldThrowWhenUkColumnNotInColumnList() {
            SchemaUploadRequestDto.ColumnDto col = new SchemaUploadRequestDto.ColumnDto("id", "integer", false);
            SchemaUploadRequestDto.UniqueKeyDto uk = new SchemaUploadRequestDto.UniqueKeyDto("uk_email", List.of("email"));
            SchemaUploadRequestDto.TableSchemaDto tableDto = new SchemaUploadRequestDto.TableSchemaDto(
                    List.of(col), List.of("id"), List.of(uk));
            SchemaUploadRequestDto request = new SchemaUploadRequestDto(Map.of("customers", tableDto));

            assertThatThrownBy(() -> service.upsertSchema(UUID.randomUUID(), request))
                    .isInstanceOf(SiteSchemaService.InvalidSchemaException.class)
                    .hasMessageContaining("Unique key column 'email' not found");
        }

        @Test
        @DisplayName("should accept when unique key columns all exist")
        void shouldAcceptValidUniqueKey() {
            UUID siteId = UUID.randomUUID();
            SchemaUploadRequestDto.ColumnDto id = new SchemaUploadRequestDto.ColumnDto("id", "integer", false);
            SchemaUploadRequestDto.ColumnDto email = new SchemaUploadRequestDto.ColumnDto("email", "varchar(255)", false);
            SchemaUploadRequestDto.UniqueKeyDto uk = new SchemaUploadRequestDto.UniqueKeyDto("uk_email", List.of("email"));
            SchemaUploadRequestDto.TableSchemaDto tableDto = new SchemaUploadRequestDto.TableSchemaDto(
                    List.of(id, email), List.of("id"), List.of(uk));
            SchemaUploadRequestDto request = new SchemaUploadRequestDto(Map.of("customers", tableDto));

            when(siteSchemaRepository.findBySiteId(siteId)).thenReturn(Optional.empty());
            when(siteSchemaRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            assertThat(service.upsertSchema(siteId, request)).isNotNull();
        }
    }

    @Nested
    @DisplayName("getTableSchemas()")
    class GetTableSchemas {

        @Test
        @DisplayName("should return empty map when no schema exists")
        void shouldReturnEmptyMapWhenNoSchema() {
            UUID siteId = UUID.randomUUID();
            when(siteSchemaRepository.findBySiteId(siteId)).thenReturn(Optional.empty());

            Map<String, TableSchema> result = service.getTableSchemas(siteId);

            assertThat(result).isEmpty();
        }

        @Test
        @DisplayName("should parse stored schema data into TableSchema objects")
        void shouldParseStoredSchemaData() {
            UUID siteId = UUID.randomUUID();
            // Build a schema with valid structure
            SchemaUploadRequestDto request = buildValidRequest("products");
            when(siteSchemaRepository.findBySiteId(siteId)).thenReturn(Optional.empty());
            when(siteSchemaRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
            SiteSchema stored = service.upsertSchema(siteId, request);

            when(siteSchemaRepository.findBySiteId(siteId)).thenReturn(Optional.of(stored));

            Map<String, TableSchema> result = service.getTableSchemas(siteId);

            assertThat(result).containsKey("products");
            assertThat(result.get("products").hasColumn("id")).isTrue();
            assertThat(result.get("products").hasColumn("name")).isTrue();
        }
    }

    @Nested
    @DisplayName("hasSchema()")
    class HasSchema {

        @Test
        @DisplayName("should delegate to repository existsBySiteId")
        void shouldDelegateToRepository() {
            UUID siteId = UUID.randomUUID();
            when(siteSchemaRepository.existsBySiteId(siteId)).thenReturn(true);

            assertThat(service.hasSchema(siteId)).isTrue();
            verify(siteSchemaRepository).existsBySiteId(siteId);
        }

        @Test
        @DisplayName("should return false when no schema exists")
        void shouldReturnFalseWhenNoSchema() {
            UUID siteId = UUID.randomUUID();
            when(siteSchemaRepository.existsBySiteId(siteId)).thenReturn(false);

            assertThat(service.hasSchema(siteId)).isFalse();
        }
    }

    @Nested
    @DisplayName("deleteSchema()")
    class DeleteSchema {

        @Test
        @DisplayName("should call deleteBySiteId on repository")
        void shouldCallRepository() {
            UUID siteId = UUID.randomUUID();

            service.deleteSchema(siteId);

            verify(siteSchemaRepository).deleteBySiteId(siteId);
        }
    }

    @Nested
    @DisplayName("findSchema()")
    class FindSchema {

        @Test
        @DisplayName("should return Optional from repository")
        void shouldReturnOptionalFromRepository() {
            UUID siteId = UUID.randomUUID();
            SiteSchema schema = SiteSchema.create(siteId, Map.of());
            when(siteSchemaRepository.findBySiteId(siteId)).thenReturn(Optional.of(schema));

            Optional<SiteSchema> result = service.findSchema(siteId);

            assertThat(result).isPresent().contains(schema);
        }
    }
}
