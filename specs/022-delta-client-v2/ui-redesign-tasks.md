# Задачи: реализация Delta Sync UI (редизайн по handoff v2)

**Источники** (все согласованы):
- ТЗ: `specs/022-delta-client-v2/ui-requirements.md`
- Дизайн-бандл v2: `frontend/design_handoff_delta_sync/` (`README.md` — спецификация, `prototype/Delta Sync.dc.html` — интерактивный прототип, `design-feedback-response.md` — принятые решения D1–D10)
- Бэкенд-контекст: ветка `feature/022-delta-client-v2` (должна быть смержена в `develop` до старта, либо ветвиться от неё)

**Формат работы**: отдельная ветка `feature/023-delta-sync-ui` от `develop`; TDD, WIP=1, один атомарный коммит на задачу (см. CLAUDE.md Development Policy). Backend-задачи — до frontend (§7 ТЗ: эндпоинты — предпосылка UI).

**Открытые продуктовые решения** (блокируют только помеченные задачи):
- [ ] **P1**: Geist глобально? (рекомендация: да) → блокирует F13
- [ ] **P2**: owner'у доступна lite-проекция сегментов (recordCount+createdAt)? (рекомендация: да) → влияет на B6/F6

> **Статус на старте реализации (2026-07-05)**: P1 и P2 не решены продуктом → по договорённости **F13 пропущена**, **B6 реализован admin-only** (owner-lite-проекция не открыта), в **F6** Segment throughput рендерится только для admin (`canManage`); owner видит Lag history на всю ширину карточки. При положительном решении P2 — добавить owner-lite endpoint в B6 и вернуть throughput owner'у; при P1 — выполнить F13.

---

## Фаза 0 — Backend (contract-тесты MockMvc + unit; интеграционные перед PR)

- [x] **B1** `feat(site): expose clientApiVersion in site DTOs` — добавить `clientApiVersion` в `SiteResponseDto` (user + admin выдача). Поле уже есть на entity `Site`.
- [x] **B2** `feat(delta): persistent rebaseline/rebuild request flags` — миграция (следующий свободный номер): `site_sync_state.rebaseline_requested`, `rebuild_requested` (boolean, default false). Логика: `GetSyncState` возвращает `NEED_REBASELINE` при взведённом rebaseline-флаге (сейчас захардкожен `false` в `DeltaSyncStateService`); флаг сбрасывается при старте FULL_SNAPSHOT-сессии; rebuild-флаг сбрасывается по завершении пересборки чекпоинта.
- [x] **B3** `feat(delta): reinstate full Parquet checkpoint materialization` — вернуть `s3_key_parquet` (миграция) + запись полного Parquet-снапшота per table из чекпоинт-реконструкции (та же, что строит CSV). Обновить `docs/delta-client-v2-guide.md` и `docs/cr-delta-client-v2.md`, где задокументировано обратное (T8.6). Решение: Parquet — целевой формат (дельты по сегментам + полные loads), CSV — legacy для Bit BI (см. design-feedback-response D1).
- [x] **B4** `feat(delta): sync-state REST endpoint` — `GET /api/v1/account/sites/{siteId}/delta/sync-state` + admin-вариант. DTO: `lastAppliedSeq`, `lastCheckpointSeq`, `lastCheckpointAt`, `schemaVersion`, `updatedAt`, `rebaselineRequested`, `rebuildRequested`. 404/null-состояние, если строки sync_state нет (empty state UI). Проверка владения сайтом (owner) / роли (admin).
- [x] **B5** `feat(delta): checkpoints REST endpoint + presigned downloads` — `GET .../delta/checkpoints` (список: table, seq, rowCount, updatedAt, наличие csv/parquet) + отдельный endpoint выдачи presigned URL (15 мин) по клику — не генерировать URL пачкой на каждый poll. `parquet=null при csv!=null` → состояние «Parquet pending» в UI.
- [x] **B6** `feat(delta): segments REST endpoint (admin full + owner lite)` — `GET .../delta/segments?limit=20`, сортировка `createdAt desc`. Admin: firstSeq, lastSeq, recordCount, mode, createdAt. Owner (**после P2**): lite-проекция recordCount + createdAt (без seq-диапазонов и S3-ключей) — для графика throughput.
- [x] **B7** `feat(delta): checkpoint rebuild trigger` — `POST .../delta/checkpoints/rebuild` (admin only): взводит `rebuild_requested`, асинхронно запускает `CheckpointService.buildCheckpoint(siteId)` вне расписания `CheckpointScheduler`, сбрасывает флаг по завершении.
- [x] **B8** `feat(delta): rebaseline trigger` — `POST .../delta/rebaseline` (owner + admin): взводит `rebaseline_requested` (эффект — `NEED_REBASELINE` из B2).
- [x] **B9** `feat(batch): session mode + seq range in BatchDetailDto` — `mode` и `seqRange {first,last}` из `changelog_segments` батча (min firstSeq / max lastSeq). Пусто для v1-батчей.
- [x] **B10** `feat(site): bulk sync health for site list` — health-данные для всех V2-сайтов аккаунта одним запросом (lag, updatedAt, hasSyncState): либо обогащение site-list DTO, либо `GET .../sites/delta/health`. Для бейджа в списке сайтов (poll 30 s недопустим как N per-site запросов).
- [x] **B11** `docs(delta): OpenAPI + guide` — все новые эндпоинты в OpenAPI (SpringDoc), обновить `docs/delta-client-v2-guide.md` («появился UI-слой», DoD п.4).

## Фаза 1 — Frontend: фундамент (Vitest + RTL на каждую задачу)

- [x] **F1** `feat(shared): number/relative-time/short-date formatters` — `formatNumber` (`toLocaleString('en-US')`), относительное время (`date-fns formatDistanceToNow`), короткая дата «Jul 05, 12:41» для плотных ячеек. В `shared/lib/formatters`.
- [x] **F2** `feat(site): clientApiVersion in site model + API chip` — поле в типе `Site`/Zod; чип «Delta v2» (blue-50/blue) / «v1» (серый) в кластер бейджей `SiteListItem` после type-бейджа.
- [x] **F3** `feat(site): site-detail page shell` — роут `/account/sites/:siteId` (+ admin-вход, `canManage` из `useAuth0Roles`): хлебная крошка «All sites», заголовок = имя + чипы (type / API / Active) + подстрока, табы **Upload history** (дефолт; существующий Batch List, отфильтрованный по сайту) и **Delta Sync** (рендер только при `clientApiVersion === 'V2'`; для V1 — один таб). Клик по строке в списке сайтов ведёт сюда. Прототип: `store-berlin-01` (V2) и `warehouse-legacy` (V1).
- [x] **F4** `feat(delta-sync): feature skeleton (api/model)` — `features/delta-sync/{api,model,ui}`: Zod DTO, axios-клиенты, query keys (`deltaSyncState(siteId)` poll 15–30 s, `deltaCheckpoints`, `deltaSegments`), мутации rebuild/rebaseline с инвалидацией. Дизайн-токены monitoring-палитры (severity цвета/радиусы/тени из README «Design Tokens») — в переиспользуемый модуль.

## Фаза 2 — Frontend: виджет Delta Sync (`widgets/delta-sync/DeltaSyncWidget.tsx`, проп `canManage`)

- [x] **F5** `feat(delta-sync): sync state shell` — metric shell (внешний `#EFEFEF` r16 / внутренние белые r12): Lag card (severity-чип Healthy/Elevated/Critical/Stalled, headline 34px, lag track со sqrt-шкалой max 20k, тики 1k/10k, анимация `.6s ease`), правая колонка (Last checkpoint + «Rebuild queued» чип; Schema version + «View schema» ссылка; Last activity: live-пульс или «Sync stalled?» при updatedAt>24h). Severity-модель: lag=applied−checkpoint, пороги 1 000/10 000, stalled поверх. **Empty state**: весь таб → одна центрированная карточка «No sync activity yet» (сценарий `empty` прототипа).
- [x] **F6** `feat(delta-sync): activity card` — Lag history: клиентское накопление сэмплов с открытия страницы (подпись «Sampled on each poll · since page open», cold start — плоская линия), SVG-спарклайн 56px в severity-цвете + 12% заливка. Segment throughput: 16 баров, градиент `#3C82D8→#C9DCF4`; owner — по lite-данным (P2).
- [x] **F7** `feat(delta-sync): checkpoints card` — toggle Table|Cards; таблица `1.4fr .8fr 1fr 1fr 1fr`; stale-пилл (>24h, amber); Files: **Parquet** первичный (blue-50), «Parquet pending» dashed некликабельный (parquet null при csv), **CSV** muted + tooltip «Legacy · used by Bit BI», оба null → «—»; клик = свежий presigned URL + Sonner «Download link generated · valid 15 minutes»; фильтр по имени при >15 таблиц; cards-view с weight-баром. Кнопка «Rebuild checkpoint now» — только `canManage`.
- [x] **F8** `feat(delta-sync): recent segments card (admin)` — collapsible, fetch on expand, грид Seq range | Records | Mode | Created, mode-чипы DELTA/CONTINUOUS/FULL_SNAPSHOT, короткий формат дат (F1).
- [x] **F9** `feat(delta-sync): rebuild + rebaseline actions` — shadcn `AlertDialog` (тексты из README, re-baseline — с красной warning-панелью), Sonner-тосты успеха/ошибки («Something went wrong. Please try again.»), lifecycle чипов от флагов `rebuildRequested`/`rebaselineRequested` (кнопка re-baseline возвращается после сброса флага сервером). Никогда `window.confirm`.

## Фаза 3 — Frontend: остальные поверхности

- [x] **F10** `feat(upload-history): delta batch detail redesign` — при `deltaStats.length > 0`: скрыть Files/FileTable/Download/Excel/Compare; meta card (зелёный check-circle, «Batch #» + 8 симв. UUID, чипы Completed + «Delta session» + серый mode-чип, meta-строка Started/Completed/Seq range); «Table changes» (`1.6fr 1fr×4`, +зелёный/синий/−красный, Total-строка 600, client-side сортировка по имени). Оба пусто → «No changes in this session»; files-путь v1 не меняется. Тесты: три состояния (DoD п.1).
- [x] **F11** `feat(site-list): sync health pill` — на bulk-данных B10: Healthy «Synced · lag 12» / Elevated «Lag 2.3k» / Critical / Stalled «Stalled · 26 h» / «No sync yet» (серый); V1 — muted «Snapshot uploads»; во время загрузки — ничего; на узкой ширине пилл переносится под имя раньше кнопок.
- [x] **F12** `docs: UI layer over Delta v2` — CLAUDE.md «Recent Changes» + guide (DoD п.4).
- [ ] **F13** *(после P1 — пропущена, P1 не решён)* `feat(shared): adopt Geist font globally` — self-hosted Geist 400/500/600, проверка существующих экранов.

## Definition of Done (из ТЗ §10)

1. Экран Batch Detail: delta-статистика видна, файловый UI скрыт, Vitest+RTL на три состояния.
2. Вкладка Delta Sync только для V2, по бандлу v2; действия за `canManage`, все с confirmation + invalidate.
3. Эндпоинты §7 (в редакции задач B1–B10) с contract-тестами и OpenAPI.
4. Обновлены guide/CLAUDE.md.
