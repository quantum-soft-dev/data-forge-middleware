-- V8: Create plugin_configs and account_plugins tables for Plugin System feature
-- Feature: 013-plugin-system
-- Date: 2025-12-16

-- =============================================================================
-- plugin_configs: Configuration for registered plugins
-- =============================================================================
CREATE TABLE plugin_configs (
    id BIGSERIAL PRIMARY KEY,
    plugin_id VARCHAR(64) NOT NULL,
    client_id VARCHAR(64) NOT NULL,
    display_name VARCHAR(100) NOT NULL,
    is_enabled BOOLEAN NOT NULL DEFAULT true,
    config JSONB,
    created_at TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMP NOT NULL DEFAULT NOW(),

    CONSTRAINT uk_plugin_configs_plugin_id UNIQUE (plugin_id),
    CONSTRAINT uk_plugin_configs_client_id UNIQUE (client_id)
);

-- Indexes for plugin_configs
CREATE INDEX idx_plugin_configs_is_enabled ON plugin_configs(is_enabled) WHERE is_enabled = true;

COMMENT ON TABLE plugin_configs IS 'Configuration for registered plugins linking to Auth0 client credentials';
COMMENT ON COLUMN plugin_configs.plugin_id IS 'Unique plugin identifier matching Plugin.getId() in code';
COMMENT ON COLUMN plugin_configs.client_id IS 'Auth0 M2M application client_id for this plugin';
COMMENT ON COLUMN plugin_configs.display_name IS 'Human-readable plugin name for UI display';
COMMENT ON COLUMN plugin_configs.is_enabled IS 'Runtime flag to enable/disable plugin (false prevents new activations)';
COMMENT ON COLUMN plugin_configs.config IS 'Plugin-specific configuration stored as JSONB';

-- =============================================================================
-- account_plugins: Activation records linking accounts to plugins
-- =============================================================================
CREATE TABLE account_plugins (
    id BIGSERIAL PRIMARY KEY,
    account_id UUID NOT NULL,
    plugin_id VARCHAR(64) NOT NULL,
    plugin_data JSONB NOT NULL,
    is_active BOOLEAN NOT NULL DEFAULT true,
    activated_at TIMESTAMP NOT NULL,
    deactivated_at TIMESTAMP,
    last_used_at TIMESTAMP,
    created_at TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMP NOT NULL DEFAULT NOW(),

    CONSTRAINT uk_account_plugins_account_plugin UNIQUE (account_id, plugin_id),
    CONSTRAINT fk_account_plugins_account FOREIGN KEY (account_id) REFERENCES accounts(id) ON DELETE CASCADE,
    CONSTRAINT fk_account_plugins_plugin FOREIGN KEY (plugin_id) REFERENCES plugin_configs(plugin_id) ON DELETE RESTRICT
);

-- Indexes for account_plugins
CREATE INDEX idx_account_plugins_plugin_id ON account_plugins(plugin_id);
CREATE INDEX idx_account_plugins_is_active ON account_plugins(is_active) WHERE is_active = true;
CREATE INDEX idx_account_plugins_data ON account_plugins USING GIN(plugin_data);

COMMENT ON TABLE account_plugins IS 'Plugin activation records linking accounts to plugins with plugin-specific data';
COMMENT ON COLUMN account_plugins.account_id IS 'Account that activated this plugin';
COMMENT ON COLUMN account_plugins.plugin_id IS 'Plugin identifier from plugin_configs';
COMMENT ON COLUMN account_plugins.plugin_data IS 'Plugin-specific data validated against plugin schema (e.g., tenantId for Bit BI)';
COMMENT ON COLUMN account_plugins.is_active IS 'Current activation status (false = deactivated, soft delete)';
COMMENT ON COLUMN account_plugins.activated_at IS 'Timestamp of first activation';
COMMENT ON COLUMN account_plugins.deactivated_at IS 'Timestamp of most recent deactivation (null if active)';
COMMENT ON COLUMN account_plugins.last_used_at IS 'Timestamp of last event received by this plugin activation';

-- =============================================================================
-- Seed data for Bit BI plugin
-- =============================================================================
-- IMPORTANT: The client_id below is a PLACEHOLDER that MUST be updated for production.
-- Production Setup:
--   1. Create an Auth0 Machine-to-Machine application for the Bit BI plugin
--   2. Run the following SQL to update the client_id:
--      UPDATE plugin_configs SET client_id = 'YOUR_AUTH0_CLIENT_ID' WHERE plugin_id = 'bit-bi';
--   3. Configure the corresponding client_secret in application.yml or environment variables
-- The placeholder value allows the application to start in development mode.
INSERT INTO plugin_configs (plugin_id, client_id, display_name, is_enabled, config, created_at, updated_at)
VALUES (
    'bit-bi',
    'BITBI_CLIENT_ID_PLACEHOLDER',
    'Bit BI',
    true,
    '{}',
    NOW(),
    NOW()
);
