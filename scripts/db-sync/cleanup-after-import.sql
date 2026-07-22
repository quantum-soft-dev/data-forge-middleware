-- ============================================================================
-- Очистка исторических данных после импорта дампа dev -> test.
--
-- Оставляем только справочные/конфигурационные данные:
--   accounts, sites, site_schemas, app_settings,
--   account_plugins, plugin_configs, flyway_schema_history
--
-- Удаляем всё историческое и всё, что ссылается на S3-объекты dev-бакета
-- (файлы батчей, чекпоинты, delta-сегменты, сгенерированный SQL), а также
-- перенесённые секреты сессий (refresh-токены, device-авторизации).
--
-- Запуск: psql "host=... dbname=..." -f cleanup-after-import.sql
-- ============================================================================

\set ON_ERROR_STOP on

BEGIN;

-- FK account_plugins.baseline_batch_id -> batches объявлен как RESTRICT (V25):
-- без обнуления TRUNCATE batches невозможен. Baseline будет заново захвачен
-- при реинициализации плагина.
UPDATE account_plugins SET baseline_batch_id = NULL WHERE baseline_batch_id IS NOT NULL;

-- Один TRUNCATE на все исторические таблицы: взаимные FK внутри списка
-- разрешаются, партиции (error_logs, plugin_audit_logs) очищаются вместе
-- с родительской таблицей.
TRUNCATE TABLE
    refresh_tokens,
    device_authorizations,
    admin_action_logs,
    error_logs,
    plugin_audit_logs,
    plugin_sql_generations,
    plugin_delta_baselines,
    comparison_results,
    file_comparisons,
    checkpoints,
    changelog_segments,
    site_sync_state,
    uploaded_files,
    batches;

COMMIT;

-- ------------------------------------------------------------------------
-- Проверка результата: справочные таблицы непустые, исторические пустые.
-- ------------------------------------------------------------------------
SELECT 'accounts (keep)'        AS tbl, count(*) FROM accounts
UNION ALL SELECT 'sites (keep)',            count(*) FROM sites
UNION ALL SELECT 'site_schemas (keep)',     count(*) FROM site_schemas
UNION ALL SELECT 'app_settings (keep)',     count(*) FROM app_settings
UNION ALL SELECT 'account_plugins (keep)',  count(*) FROM account_plugins
UNION ALL SELECT 'plugin_configs (keep)',   count(*) FROM plugin_configs
UNION ALL SELECT 'batches (=0)',            count(*) FROM batches
UNION ALL SELECT 'uploaded_files (=0)',     count(*) FROM uploaded_files
UNION ALL SELECT 'checkpoints (=0)',        count(*) FROM checkpoints
UNION ALL SELECT 'changelog_segments (=0)', count(*) FROM changelog_segments
UNION ALL SELECT 'site_sync_state (=0)',    count(*) FROM site_sync_state
UNION ALL SELECT 'refresh_tokens (=0)',     count(*) FROM refresh_tokens
UNION ALL SELECT 'device_auth (=0)',        count(*) FROM device_authorizations
UNION ALL SELECT 'error_logs (=0)',         count(*) FROM error_logs
ORDER BY 1;
