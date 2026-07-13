# Фича 026-bitbi-delta-sql: SQL-генерация Bit BI из changelog-сегментов Delta v2

## Контекст

Bit BI-плагин (единственный клиент репортинга) генерирует инкрементальный SQL только из `UploadedFile`-строк батча. У V2-сайтов (gRPC-дельты, 022) данные лежат в `ChangelogSegment` (protobuf в S3) — файлов нет, поэтому каждый `BATCH_COMPLETED` для V2-сайта — тихий no-op: SQL не создаётся никогда, без ошибки. Эндпоинт `/files` для V2 уже адаптирован (отдаёт чекпоинт-CSV), а `/sql-changes` — нет.

Цель: V2-сайты получают инкрементальный SQL через неизменённый контракт `/sql-changes`, с надёжной доставкой, порядком по seq и корректным baseline. Работа — в текущей ветке `feature/022-delta-client-v2`, spec-папка `specs/026-bitbi-delta-sql/`, миграция **V38**.

## Ключевые решения

**D1. Триггер — воркер по образцу egress (wake + sweep), не инлайн из события.**
- V38 добавляет `changelog_segments.plugin_sql_at` (NULL = ожидает SQL) + частичный индекс — точная копия паттерна `egress_at` (V33).
- Новый `DeltaSqlSweepWorker` (клон `DeltaEgressWorker`): `wake()` + `@Scheduled` sweep (60s) + drain; выборка `findNextPendingPluginSql(limit)` — head-of-line per-site по `first_seq`, `FOR UPDATE SKIP LOCKED`. Это гарантирует генерацию строго в порядке seq → `created_at ASC` в `/sql-changes` остаётся корректным без изменения контракта.
- `BitBiPlugin.execute`: для V2-батча — только `worker.wake()`; V1 — как раньше инлайн. Воркер вызывает существующий `sqlGenerationService.generateSqlForBatch(batchId, accountPluginId)` — семафор (020), идемпотентность (`uk_sql_gen_source_batch`), аудит, персист переиспользуются целиком.
- Фикс гонки pre-commit: `BatchEventListener` переводится с `@EventListener` на `@TransactionalEventListener(AFTER_COMMIT, fallbackExecution=true)` — сейчас `DeltaSessionCommitService.commit` вызывает `completeBatch` внутри своей транзакции, и `@Async`-диспетчер может стартовать до коммита (не увидит строку сегмента). Для V1 это строгое улучшение (rollback → нет события).
- Неактивный плагин / отсутствие активации → сегмент помечается обработанным (не копится вечно). Ошибка → rollback → sweep повторит.

**D2. Загрузка данных — ветка `site.isDeltaV2()` в `SqlGenerationService` + новая `DeltaSqlGenerationStrategy`.**
- Ветвление именно по `isDeltaV2()` (не по `SiteType` — у V2-сайта SiteType может быть любым).
- `loadBatchData`: для V2 — `changelogSegmentRepository.findByBatchId` вместо файлов (1 батч = 1 сегмент); guard `existsBySourceBatchId` сохраняется.
- `DeltaSqlGenerationStrategy` (отдельный класс, интерфейс `SqlGenerationStrategy`/`SqlGenerationContext` не трогаем): `ChangelogSegmentService.readRecords(s3Key)` → proto `ChangeRecord` → маппинг в существующий `JsonlChangeRecord` (INSERT→"I", UPDATE→"U", DELETE→"D"; key/data через `ValueMapper.toMap`; для INSERT key вливается в data; lineNumber = seq) → рендер существующим `SqlStatementGenerator.generateFromJsonl`. Фильтр неизвестных колонок и skip-без-схемы — как в CDC-стратегии; схемы из `SiteSchemaService.getTableSchemas` (то, что клиент прислал через `SubmitSchema`).
- `comparisonBatchId` = null (дельты самодостаточны); в `PluginSqlGeneration` добавляются nullable `first_seq`/`last_seq` (диагностика/порядок).

**D3. Дыры типизации в `SqlStatementGenerator.formatJsonValue`** (обязательны — `ValueMapper` отдаёт эти типы): `byte[]` → bytea-литерал `'\x<hex>'` (сейчас — мусор `[B@...`), `BigDecimal` → `toPlainString()` без кавычек (иначе научная нотация). V1-вывод байт-в-байт не меняется.

**D4. Baseline — per-table seq, захват при активации/reinit (V38 таблица `plugin_delta_baselines`).**
- Проблема: batch-baseline (последний батч аккаунта) ≠ чекпоинты по таблицам (отстают от watermark и едут асинхронно) → клиент теряет диапазон `(X_t, Y]` или получает перекрытие.
- Решение: при `onActivate`/`reinit` для V2-сайтов захватываем `baseline_seq = Checkpoint.seq` по каждой таблице в `plugin_delta_baselines(account_plugin_id, site_id, table_name, baseline_seq)`. Правило фильтрации: запись с seq `s` таблицы `t` попадает в SQL ⇔ `s > baseline_seq(t)` (нет строки → 0, т.е. новые таблицы стримятся с начала — bootstrap без CSV).
- Batch-baseline кейсы (`isBaselineBatch`/`hasBaselineBatch`) для V2-сайтов обходятся; для V1 — без изменений.
- `reinit` дополнительно сбрасывает `plugin_sql_at` у сегментов V2-сайтов аккаунта и будит воркер → диапазон «checkpoint-lag» перегенерируется с новым фильтром.
- Остаточная гонка (документируем): чекпоинт может уехать вперёд между захватом и скачиванием CSV → небольшое перекрытие (UPDATE/DELETE идемпотентны, INSERT может конфликтовать на клиенте). Окно = каденс фолда; рекомендация — скачивать сразу после reinit.

**D5. FULL_SNAPSHOT-сегменты (rebaseline источника):** SQL не генерируется (иначе дубль всего датасета); baseline известных таблиц сайта поднимается в `Long.MAX_VALUE` (SQL приостановлен до ручного reinit — клиент обязан перекачать снапшоты), аудит-warning + метрика. Таблицы, появившиеся после снапшота, стримятся (нет строки → 0).

**D6. Rollout:** V38 бэкфиллит `plugin_sql_at = created_at` у всех существующих сегментов (ретро-SQL не генерируем) и сидит `plugin_delta_baselines` из текущих чекпоинтов для активных bit-bi активаций. Существующим V2-пользователям плагина — один reinit после деплоя (в CR-доке).

**Вне скоупа:** потребление parquet-egress, изменение контракта Bit BI-клиента, `regenerateForBatch` для V2 (явный отказ с ошибкой), capture-at-download baseline.

## Изменения по файлам

Новые:
- `src/main/resources/db/migration/V38__bitbi_delta_sql.sql`
- `plugin/domain/PluginDeltaBaseline.java` + `PluginDeltaBaselineRepository.java` + `infrastructure/persistence/JpaPluginDeltaBaselineRepository.java`
- `plugin/application/PluginDeltaBaselineService.java` (capture / suspend / `baselineSeqs(siteId)`)
- `plugin/application/DeltaSqlGenerationStrategy.java`
- `plugin/application/DeltaSqlQueueService.java` (транзакционный claim → generate → mark; FULL_SNAPSHOT suspend; skip неактивных)
- `plugin/application/DeltaSqlSweepWorker.java`

Изменяемые:
- `delta/domain/ChangelogSegment.java` — `pluginSqlAt` + `markPluginSqlProcessed()`
- `delta/domain/ChangelogSegmentRepository.java` + `delta/infrastructure/JpaChangelogSegmentRepository.java` — `findNextPendingPluginSql(limit)`, `clearPluginSqlBySiteId(siteId)`
- `plugin/application/SqlStatementGenerator.java` — bytea/BigDecimal
- `plugin/application/SqlGenerationService.java` — V2-ветка (loadBatchData/generateSqlContent/baseline-bypass), first/last seq в записи, отказ `regenerateForBatch` для V2
- `plugin/infrastructure/events/BatchEventListener.java` — `@TransactionalEventListener(AFTER_COMMIT, fallbackExecution=true)`
- `plugin/application/BitBiPlugin.java` — V2-роутинг в `execute`, capture в `onActivate`
- `plugin/application/PluginHistoryService.java` — reinit: recapture + clear `plugin_sql_at` + wake
- `plugin/domain/PluginSqlGeneration.java` — `firstSeq`/`lastSeq`
- `application.yml` — `plugin.sql-generation.delta-max-concurrent:2`, `delta-sweep-ms:60000`

## V38 (эскиз)

```sql
ALTER TABLE changelog_segments ADD COLUMN plugin_sql_at TIMESTAMP;
UPDATE changelog_segments SET plugin_sql_at = created_at;  -- history: не ретро-генерим
CREATE INDEX idx_changelog_segments_plugin_sql_pending
    ON changelog_segments (site_id, first_seq) WHERE plugin_sql_at IS NULL;

CREATE TABLE plugin_delta_baselines (
    id BIGSERIAL PRIMARY KEY,
    account_plugin_id BIGINT NOT NULL REFERENCES account_plugins(id) ON DELETE CASCADE,
    site_id UUID NOT NULL REFERENCES sites(id) ON DELETE CASCADE,
    table_name VARCHAR(63) NOT NULL,
    baseline_seq BIGINT NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMP NOT NULL DEFAULT NOW(),
    CONSTRAINT uk_plugin_delta_baseline UNIQUE (account_plugin_id, site_id, table_name)
);
CREATE INDEX idx_plugin_delta_baseline_site ON plugin_delta_baselines(site_id);

ALTER TABLE plugin_sql_generations ADD COLUMN first_seq BIGINT;
ALTER TABLE plugin_sql_generations ADD COLUMN last_seq  BIGINT;

INSERT INTO plugin_delta_baselines (account_plugin_id, site_id, table_name, baseline_seq)
SELECT ap.id, c.site_id, c.table_name, c.seq
FROM account_plugins ap
JOIN sites s ON s.account_id = ap.account_id
JOIN checkpoints c ON c.site_id = s.id
WHERE ap.plugin_id = 'bit-bi' AND ap.is_active = TRUE;
```
(имена колонок `account_plugins` сверены с V8: `plugin_id VARCHAR(64)`, `is_active`.)

## Задачи (TDD, 1 задача = тесты → реализация → 1 коммит; гейт `./gradlew test -PexcludeIntegration`)

- **T1** — `BatchEventListener` → `@TransactionalEventListener(AFTER_COMMIT, fallbackExecution=true)`; поправить тесты, полагающиеся на синхронный in-tx dispatch. `fix(plugin): dispatch plugin events after commit (T1)`
- **T2** — V38 + персистенция: `pluginSqlAt`, `findNextPendingPluginSql` (per-site head, SKIP LOCKED), `clearPluginSqlBySiteId`, `PluginDeltaBaseline` entity/repo, `firstSeq`/`lastSeq`. Интеграционные тесты репозитория (Testcontainers). `feat(plugin): V38 delta-sql queue and per-table baselines (T2)`
- **T3** — `SqlStatementGenerator`: bytea `'\x<hex>'`, `BigDecimal.toPlainString()`; юнит-тесты, V1-рендер неизменен. `fix(plugin): render bytea and BigDecimal SQL literals (T3)`
- **T4** — `DeltaSqlGenerationStrategy`: маппинг op/key/data, merge key в INSERT, фильтр `seq > baseline` (граница исключена), skip без схемы, фильтр неизвестных колонок, skip FULL_SNAPSHOT, seq в терминаторе, статистика. `feat(plugin): delta segment SQL generation strategy (T4)`
- **T5** — V2-ветка `SqlGenerationService`: обход batch-baseline, сегменты вместо файлов, идемпотентность, first/last seq, null comparisonBatchId, отказ regenerate для V2; существующие V1-тесты зелёные. `feat(plugin): route delta v2 batches to segment strategy (T5)`
- **T6** — `DeltaSqlQueueService` + `DeltaSqlSweepWorker` + роутинг в `BitBiPlugin.execute`: claim→generate→mark, skip неактивных, FULL_SNAPSHOT → suspend (MAX_VALUE) + аудит, ошибка → rollback/без mark, wake для V2 / инлайн для V1. `feat(plugin): delta SQL sweep worker (T6)`
- **T7** — baseline capture: `onActivate` + `reinit` (recapture, clear `plugin_sql_at`, wake); сайт без чекпоинтов → нет строк (default-0); V1-only аккаунт → no-op. `feat(plugin): capture per-table delta baselines on activate/reinit (T7)`
- **T8** — e2e интеграционный тест (Testcontainers + LocalStack): активация → 2 дельта-сегмента через `DeltaSessionCommitService` → drain воркера → `/sql-changes` отдаёт обе генерации в порядке seq с ожидаемым SQL (вкл. bytea/decimal) → reinit → recapture/regenerate → FULL_SNAPSHOT → suspend + аудит. Плюс `docs/cr-bitbi-delta-sql.md` и spec-артефакты. `feat(plugin): delta SQL e2e + CR doc (T8)`

Артефакты спеки (`specs/026-bitbi-delta-sql/spec.md`, `plan.md`, `tasks.md`) создаются в начале реализации, до T1.

## Верификация

- Пер-задачно: `./gradlew test -PexcludeIntegration` (гейт pre-commit хука).
- Перед завершением: `./gradlew integrationTest` (Testcontainers + LocalStack) — включая новый `BitBiDeltaSqlIntegrationTest`.
- e2e-тест T8 и есть сквозная проверка: gRPC-коммит сегмента → воркер → SQL в S3 → `/sql-changes`.

## Известные остаточные риски (документируются в CR)

1. Перекрытие reinit→download (окно = каденс чекпоинт-фолда): дубли UPDATE/DELETE безвредны, INSERT может конфликтовать у клиента.
2. FULL_SNAPSHOT приостанавливает SQL до ручного reinit (сигнал — аудит-warning).
3. Claim-транзакция держит DB-коннект на время S3 I/O — тот же принятый трейд-офф, что у parquet-egress (кап 2).
4. Существующие V2-активации не получают ретро-SQL — нужен один reinit после деплоя.
