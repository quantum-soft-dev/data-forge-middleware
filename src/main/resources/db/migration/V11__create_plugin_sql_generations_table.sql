-- V11__create_plugin_sql_generations_table.sql
-- Plugin SQL Generation Extension - Tracks SQL file generation events for Bit BI plugin

CREATE TABLE plugin_sql_generations (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),

    -- Foreign keys
    account_plugin_id BIGINT NOT NULL,
    site_id UUID NOT NULL,
    source_batch_id UUID NOT NULL,
    comparison_batch_id UUID,  -- NULL for first batch

    -- S3 storage
    s3_key VARCHAR(1000) NOT NULL,
    file_size_bytes BIGINT NOT NULL,

    -- Statistics
    statement_count INTEGER NOT NULL DEFAULT 0,
    insert_count INTEGER NOT NULL DEFAULT 0,
    update_count INTEGER NOT NULL DEFAULT 0,
    delete_count INTEGER NOT NULL DEFAULT 0,
    files_processed INTEGER NOT NULL DEFAULT 0,

    -- Performance tracking
    generation_duration_ms BIGINT NOT NULL DEFAULT 0,

    -- Timestamps
    created_at TIMESTAMP NOT NULL DEFAULT NOW(),

    -- Constraints
    CONSTRAINT fk_sql_gen_account_plugin
        FOREIGN KEY (account_plugin_id)
        REFERENCES account_plugins(id) ON DELETE CASCADE,
    CONSTRAINT fk_sql_gen_site
        FOREIGN KEY (site_id)
        REFERENCES sites(id) ON DELETE CASCADE,
    CONSTRAINT fk_sql_gen_source_batch
        FOREIGN KEY (source_batch_id)
        REFERENCES batches(id) ON DELETE CASCADE,
    CONSTRAINT fk_sql_gen_comparison_batch
        FOREIGN KEY (comparison_batch_id)
        REFERENCES batches(id) ON DELETE SET NULL,

    -- Ensure unique generation per source batch
    CONSTRAINT uk_sql_gen_source_batch UNIQUE (source_batch_id)
);

-- Indexes for common queries
CREATE INDEX idx_sql_gen_site_created ON plugin_sql_generations(site_id, created_at DESC);
CREATE INDEX idx_sql_gen_account_plugin ON plugin_sql_generations(account_plugin_id);

COMMENT ON TABLE plugin_sql_generations IS 'Tracks SQL file generation events for Bit BI plugin';
COMMENT ON COLUMN plugin_sql_generations.comparison_batch_id IS 'NULL for first batch (all INSERTs)';
COMMENT ON COLUMN plugin_sql_generations.s3_key IS 'Full S3 key path: plugins/bit-bi/{accountId}/{siteName}/{timestamp}.sql';
