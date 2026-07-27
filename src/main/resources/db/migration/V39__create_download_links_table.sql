-- V39: Parquet Export plugin (028-parquet-export-plugin).
-- download_links = registered one-time download tokens: the middleware row transition
-- (consumed_at NULL -> set, atomic UPDATE) is what makes a link single-use — S3 presigned
-- URLs cannot enforce that. Rows are short-lived: TTL 1h before first use, purged after
-- a retention window.

CREATE TABLE download_links (
    id                UUID          PRIMARY KEY,
    token             VARCHAR(64)   NOT NULL,
    account_plugin_id BIGINT        NOT NULL,
    s3_key            VARCHAR(1000) NOT NULL,
    file_name         VARCHAR(255)  NOT NULL,
    expires_at        TIMESTAMP     NOT NULL,
    consumed_at       TIMESTAMP,
    created_at        TIMESTAMP     NOT NULL DEFAULT NOW(),

    CONSTRAINT uk_download_links_token UNIQUE (token),
    CONSTRAINT fk_download_links_activation
        FOREIGN KEY (account_plugin_id) REFERENCES account_plugins(id) ON DELETE CASCADE
);

CREATE INDEX idx_download_links_purge ON download_links (created_at);
CREATE INDEX idx_download_links_activation ON download_links (account_plugin_id);

-- Seed the parquet-export plugin config (pattern: V8 bit-bi seed). No Auth0 M2M client is
-- involved — client_id is NOT NULL UNIQUE, so a reserved sentinel is used.
INSERT INTO plugin_configs (plugin_id, client_id, display_name, is_enabled, config)
VALUES ('parquet-export', 'parquet-export-internal', 'Parquet Export', true, '{}');
