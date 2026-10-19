package com.bitbi.dfm.plugin.presentation.dto;

import com.bitbi.dfm.plugin.application.BitBiV2QueryService;
import com.bitbi.dfm.site.domain.TableSchema;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;
import java.util.UUID;

/**
 * {@code GET /api/v1/plugins/bit-bi/v2/sites/{siteId}/schema} (issue #386): the schema the client
 * declared in {@code SubmitSchema}, with the row identity a consumer keys each table by. A Parquet
 * file records neither the primary key nor that identity, which is why this is served.
 *
 * @param siteId        the site
 * @param schemaVersion the stored schema's version; 0 when none was submitted
 * @param tables        the declared tables, sorted by name
 */
@Schema(description = "Bit BI v2: a site's declared schema with each table's row identity")
public record BitBiV2SiteSchemaDto(
        @Schema(description = "Site identifier", example = "0199baac-f852-753f-6fc3-7c994fc38654")
        UUID siteId,

        @Schema(description = "Schema version; 0 = none submitted", example = "2")
        int schemaVersion,

        @Schema(description = "Declared tables, sorted by name")
        List<Table> tables
) {

    /**
     * @param view the read model
     * @return its wire form
     */
    public static BitBiV2SiteSchemaDto from(BitBiV2QueryService.SiteSchemaView view) {
        return new BitBiV2SiteSchemaDto(view.siteId(), view.schemaVersion(),
                view.tables().stream().map(Table::from).toList());
    }

    /**
     * One declared table.
     *
     * @param table      the table name
     * @param identity   {@code row_hash} (keyed by {@code _row_hash}; every DBF table) or
     *                   {@code primary_key} (keyed by {@code primaryKey})
     * @param primaryKey the declared primary key columns; empty for a {@code row_hash} table
     * @param uniqueKeys the declared unique keys
     * @param columns    the declared columns, in declared order
     */
    @Schema(description = "A declared table")
    public record Table(
            @Schema(description = "Table name", example = "nsftrns")
            String table,

            @Schema(description = "How the consumer keys this table's rows", allowableValues = {"row_hash", "primary_key"},
                    example = "row_hash")
            String identity,

            @Schema(description = "Declared primary key columns; empty for a row_hash table")
            List<String> primaryKey,

            @Schema(description = "Declared unique keys")
            List<UniqueKey> uniqueKeys,

            @Schema(description = "Declared columns, in declared order")
            List<Column> columns
    ) {

        static Table from(BitBiV2QueryService.TableView view) {
            TableSchema schema = view.schema();
            return new Table(
                    view.table(),
                    view.identity().wireValue(),
                    schema.primaryKey(),
                    schema.uniqueKeys().stream().map(uk -> new UniqueKey(uk.name(), uk.columns())).toList(),
                    schema.columns().stream().map(c -> new Column(c.name(), c.type(), c.nullable())).toList());
        }
    }

    /**
     * One declared column.
     *
     * @param name     column name
     * @param type     PostgreSQL type as declared, e.g. {@code numeric(12,2)}
     * @param nullable whether the column was declared nullable
     */
    @Schema(description = "A declared column")
    public record Column(
            @Schema(description = "Column name", example = "TR_NUM")
            String name,

            @Schema(description = "PostgreSQL type as declared", example = "numeric(12,2)")
            String type,

            @Schema(description = "Whether the column was declared nullable")
            boolean nullable
    ) {
    }

    /**
     * One declared unique key.
     *
     * @param name    constraint name, as declared (may be null)
     * @param columns its columns
     */
    @Schema(description = "A declared unique key")
    public record UniqueKey(
            @Schema(description = "Constraint name as declared")
            String name,

            @Schema(description = "Columns of the unique key")
            List<String> columns
    ) {
    }
}
