# Change Request: Delta Client v2 и Stateful Changelog

**Версия документа**: 0.1.0 (ЧЕРНОВИК — design review)
**Обновлён**: 2026-06-05
**Статус**: Предложение — ещё не утверждено к реализации
**Концептуально заменяет**: [cr-site-types-postgres-cdc.md](./cr-site-types-postgres-cdc.md) (CDC v1)
**Связанный контракт**: [delta-ingestion.proto](../src/main/proto/delta-ingestion.proto)
**Английская версия**: [cr-delta-client-v2.md](./cr-delta-client-v2.md)

---

## Контекст

CDC v1 ([cr-site-types-postgres-cdc.md](./cr-site-types-postgres-cdc.md)) ввёл идею клиента, который вычисляет изменения у источника и шлёт **только дельты**: сайты `POSTGRES_CDC` отправляют схему, делают однократную полную загрузку CSV, а затем заливают `.jsonl.gz`-файлы дельт (`I`/`U`/`D`) через старый HTTP-API клиента (`/api/dfc/**`). Сервер конвертирует эти дельты **напрямую в SQL-текст** для плагина Bit BI и не хранит собственного состояния.

Этот CR обобщает идею в полноценный путь приёма данных — **Delta Client v2** — и вносит два структурных изменения:

1. **Сервер становится stateful поверх changelog.** Вместо stateless-транскодера «дельта → SQL» сервер хранит append-only **changelog** как источник правды и периодически материализует из него **checkpoint'ы** (полные снимки). Из того же changelog он отдаёт три проекции: legacy-CSV (для Bit BI), Parquet change feed (для Power BI) и re-baseline-снимки (для самоисцеления).
2. **Новый транспорт.** Приём переходит с multipart-HTTP-загрузок файлов на **gRPC bidirectional stream** с типизированным Protobuf-контрактом, заменяя per-batch `.jsonl.gz` upload на стримовую сессию.

Выигрыш первого порядка — отправка только дельт вместо полных снимков — унаследован от CDC v1. Этот CR — про то, чтобы сделать модель **общей, надёжной и потребляемой** (Power BI), а не про новый трюк со сжатием.

### Почему сейчас

- Клиент доставки файлов — **наш**, поэтому мы можем захватывать изменения у источника (самое дешёвое и корректное место), а не пере-выводить их в центре эвристиками.
- Старый DBF-путь выводит `UPDATE` против `DELETE`+`INSERT` по лексикографическому соседству **без первичного ключа** ([CsvDiffService](../src/main/java/com/bitbi/dfm/plugin/application/CsvDiffService.java)) — известный пробел в корректности. Перенос диффа на источник с объявленными ключами это чинит.
- Нам нужен чистый, типизированный, инкрементально-обновляемый источник для **Power BI** (см. §12), который текущий SQL-текст дать не может.

---

## Связь с CDC v1 — что меняется

| Аспект | CDC v1 (сейчас) | Delta Client v2 (этот CR) |
|---|---|---|
| Кто считает дифф | Клиент (Postgres WAL) | Клиент (Postgres WAL **или** локальный дифф снимков) |
| Транспорт | HTTP multipart `.jsonl.gz` в `/api/dfc/**` | gRPC bidirectional stream |
| Формат на проводе | JSONL (`op`/`k`/`d`), строки | Protobuf `ChangeRecord`, **типизированные значения** |
| Порядок / resume | неявно в рамках батча | явный per-site **`seq`** + watermark + resume |
| Бесключевые таблицы | не рассмотрены (CDC требует PK) | **ключ из всех полей** → только `INSERT`/`DELETE` (§6) |
| Состояние сервера | нет (дельта → SQL-текст) | **changelog (источник правды) + checkpoint'ы** (§4) |
| Выход для Bit BI | SQL-текст per batch | **реконструированный CSV** из changelog (§11) |
| Выход для Power BI | нет | **Parquet change feed + пол-checkpoint** (§12) |
| Обработка разрывов/дрейфа | нет | детекция разрыва seq + re-baseline (§10) |

Модель схемы CDC v1 (`site_schemas`, `POST /api/dfc/schema`, columns/PK/uniqueKeys) **сохраняется** и переиспользуется.

---

## Scope

| В scope | Вне scope |
|---|---|
| gRPC-сервис `DeltaIngestion` + Protobuf-контракт | Замена egress/Power-BI read-path (файловый, отдельный) |
| Per-site `seq` watermark, идемпотентность, детекция разрывов | Онлайн point-запросы к текущему состоянию (нет живой mutable-таблицы) |
| Changelog-хранилище (append-only сегменты) | Полный lakehouse (Iceberg/Delta/Hudi) — оверкилл при текущем масштабе |
| Периодические **checkpoint'ы** (CSV + Parquet) из changelog | Генерация DDL (CREATE/ALTER TABLE) |
| Ключ из всех полей для бесключевых таблиц | Немедленный перевод legacy DBF/CDC HTTP-сайтов со старого пути |
| Обратно-совместимая реконструкция CSV для Bit BI | Multi-region хранилище |
| Непрерывный режим стрима определён (реализация отложена) | Тюнинг rate limiting для gRPC (отдельно) |

### Non-goals (явно)

- **Не** строим queryable-базу клиентских данных на сервере. Сервер хранит changelog + материализованные файлы, **а не** живую per-row mutable-таблицу состояния (см. §4, отказ от «Варианта 1»).
- **Не** меняем работу Bit BI. Bit BI продолжает читать CSV из `/sites/{siteId}/files`; меняется только то, как этот CSV производится (§11).

---

## 1. Обзор архитектуры

```
        ┌────────────┐   gRPC stream (дельты)   ┌──────────────────────────────┐
        │ Delta      │ ───────────────────────► │  Приём (StreamChanges)       │
        │ Client v2  │ ◄─── ack / recovery ───── │  сессия = batch lifecycle     │
        └────────────┘                          └──────────────┬───────────────┘
                                                                │ append
                                                                ▼
                                                   ┌────────────────────────┐
                                                   │  CHANGELOG (S3)         │  ← источник правды
                                                   │  append-only сегменты   │     (только дозапись)
                                                   └───────────┬────────────┘
                                                               │ периодическая свёртка (scheduler)
                                                               ▼
                                                   ┌────────────────────────┐
                                                   │  CHECKPOINT @seq        │  = кадр из одних INSERT
                                                   │  (per site / table)     │
                                                   └───┬──────────┬─────┬────┘
                                       legacy CSV ◄────┘          │     └────► re-baseline
                                  (Bit BI /files)                 ▼            (самоисцеление)
                                                        Parquet change feed
                                                        (пол Power BI + дельты)
```

**Ключевой принцип:** *полный снимок* — это просто кадр changelog, где все записи = `INSERT`. Есть **один** логический артефакт (changelog) и **одна** операция свёртки; CSV, Parquet-feed и re-baseline — три его проекции.

---

## 2. Базовые понятия

| Понятие | Определение |
|---|---|
| **Сессия** | Единица приёма = один `StreamChanges`-стрим = один **Batch** (`IN_PROGRESS` → `COMPLETED`). Переиспользует [BatchLifecycleService](../src/main/java/com/bitbi/dfm/batch/application/BatchLifecycleService.java). |
| **`seq`** | Монотонный per-site счётчик, назначаемый **клиентом** (источник порядка). Строго возрастает между сессиями. |
| **Watermark** | Наибольший `seq`, который сервер durably закоммитил. Клиент держит свою копию; обе выравниваются через `GetSyncState`. |
| **Сегмент changelog** | Записи одной сессии, сохранённые append-only в объектном хранилище. Иммутабельны. |
| **Checkpoint** | Материализованный полный снимок таблицы на заданном `seq`, производимый шедулером. Обслуживает CSV, Parquet-пол и re-baseline. |

---

## 3. Жизненный цикл сессии (начало / конец)

Сессия — это bidirectional gRPC-стрим. Клиент шлёт ровно один `SessionStart`, затем `ChangeRecord` со строго возрастающим `seq`, затем ровно один `SessionEnd`. Сервер отвечает `SessionOpened`, прогрессивными `Ack` и финальным `SessionCommitted` (или `ServerError`).

- **Начало** (`SessionStart`): `mode` (`DELTA` | `FULL_SNAPSHOT`), `first_seq`, `schema_version`, `client_session_id`. Сервер проверяет `first_seq == server_last_seq + 1`, открывает батч, возвращает `SessionOpened` с `server_last_seq` и `RecoveryAction`.
- **Тело** (`ChangeRecord`): см. §6. Сервер стейджит записи, может слать `Ack(acked_seq)` для backpressure/прогресса.
- **Конец** (`SessionEnd`): `last_seq`, per-table counts, `content_hash`. Сервер сверяет (§10), коммитит батч, дозаписывает сегмент changelog, возвращает `SessionCommitted(committed_seq, segment_s3_key)`. Клиент двигает watermark.

**Одна активная сессия на сайт** (зеркалит существующее правило one-active-batch). Второй параллельный `StreamChanges` отклоняется с `ACTIVE_SESSION_EXISTS`.

---

## 4. Модель состояния сервера — changelog + checkpoint'ы (решение «2b»)

Рассмотрены три модели:

| Модель | Описание | Вердикт |
|---|---|---|
| **1. Живое материализованное состояние** | Mutable per-(site,table,PK) текущее состояние, upsert на каждую дельту | ❌ Превращает middleware в мини-БД; онлайн point-запросы никому не нужны |
| **2a. Чистый changelog** | Хранить только журнал; сворачивать всю историю по запросу | ❌ Стоимость свёртки и хранения растут без границ с историей |
| **2b. Changelog + периодические checkpoint'ы** | Журнал — источник правды; периодические снимки ограничивают реконструкцию | ✅ **Выбрано** |

**Обоснование 2b:** реконструкция = `последний checkpoint + свёртка(дельты после него)` → ограниченная стоимость. Артефакт checkpoint одновременно обслуживает **четырёх** потребителей одним механизмом: (1) legacy-CSV для Bit BI, (2) re-baseline / самоисцеление, (3) bootstrap-пол для клиента, (4) пол-база для Power BI (чтобы старый changelog можно было прунить, а первые загрузки оставались быстрыми). Никому не нужна живая queryable-таблица состояния, поэтому Модель 1 не оправдана.

**Companion-решения (без них 2b не работает):**

- **Ретеншн changelog** — как только checkpoint@N durable, сырые сегменты с `seq ≤ N` можно прунить/уводить в холод после окна аудита/реплея. Без этого 2b вырождается в 2a по хранению.
- **Частота checkpoint** — регулятор: чаще → дешевле реконструкция, больше churn по хранению. Дефолт: привязка к каденции egress (раз в день), с более грубым «полом»-снимком (например, раз в неделю) под Power BI.
- **Детекция разрывов** — хранить `last_applied_seq` per site; момент checkpoint — естественная проверка непрерывности.

---

## 5. Модель данных (хранение)

### 5.1 Новые таблицы (Flyway `V29__delta_ingestion.sql`, эскиз)

```sql
-- Per-site watermark приёма и указатели на checkpoint
CREATE TABLE site_sync_state (
    site_id              UUID PRIMARY KEY REFERENCES sites(id) ON DELETE CASCADE,
    last_applied_seq     BIGINT NOT NULL DEFAULT 0,
    last_checkpoint_seq  BIGINT NOT NULL DEFAULT 0,
    last_checkpoint_at   TIMESTAMP,
    schema_version       INTEGER NOT NULL DEFAULT 0,
    updated_at           TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- Метаданные сегмента changelog (сами записи лежат в объектном хранилище)
CREATE TABLE changelog_segments (
    id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    site_id       UUID NOT NULL REFERENCES sites(id) ON DELETE CASCADE,
    batch_id      UUID NOT NULL REFERENCES batches(id),
    first_seq     BIGINT NOT NULL,
    last_seq      BIGINT NOT NULL,
    record_count  BIGINT NOT NULL,
    content_hash  VARCHAR(128) NOT NULL,
    s3_key        VARCHAR(1000) NOT NULL,
    mode          VARCHAR(20) NOT NULL,      -- DELTA | FULL_SNAPSHOT
    created_at    TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_segment_site_first_seq UNIQUE (site_id, first_seq)
);
CREATE INDEX idx_segment_site_seq ON changelog_segments(site_id, last_seq);

-- Материализованные checkpoint'ы (один текущий на site/table)
CREATE TABLE checkpoints (
    id             UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    site_id        UUID NOT NULL REFERENCES sites(id) ON DELETE CASCADE,
    table_name     VARCHAR(63) NOT NULL,
    seq            BIGINT NOT NULL,
    row_count      BIGINT NOT NULL,
    s3_key_csv     VARCHAR(1000),
    s3_key_parquet VARCHAR(1000),
    created_at     TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_checkpoint_site_table UNIQUE (site_id, table_name)
);
```

`site_schemas` (CDC v1) переиспользуется без изменений для метаданных колонок/PK/типов.

### 5.2 Раскладка объектного хранилища

```
delta/{siteId}/segments/{batchId}.pb.gz              # bronze changelog (сырые protobuf-записи, gzip)
egress/{siteId}/{table}/_change_date=YYYY-MM-DD/*.parquet   # gold change feed (Power BI)
checkpoints/{siteId}/{table}/seq={seq}/snapshot.csv.gz      # legacy CSV (Bit BI)
checkpoints/{siteId}/{table}/seq={seq}/snapshot.parquet     # Parquet-пол (Power BI)
```

**На проводе — сырьё, Parquet — на serving.** Приём пишет сырые сегменты (bronze); шедулер материализует Parquet (gold). Это избегает small-files-издержек per-session Parquet и держит ценность Parquet там, где она окупается (Power BI).

---

## 6. Change record и ключи

Каждый `ChangeRecord` несёт `table`, `op`, `seq`, `key`, `data`, `source_ts` (см. [proto](../src/main/proto/delta-ingestion.proto)).

- **INSERT** — `data` = вся строка; `key` = значения PK.
- **UPDATE** — `key` = PK; `data` = только изменённые колонки (after-image).
- **DELETE** — `key` = PK; `data` пусто (tombstone).

### Бесключевые таблицы (ключ из всех полей)

У большинства таблиц есть объявленный PK. Для таблиц **без** него `primary_key` в схеме пуст, и **идентифицирующим ключом считается весь набор колонок**. Следствия (документированное поведение, зеркалит legacy DBF-семантику):

1. **`UPDATE` невозможен.** Любое изменение поля меняет «ключ», поэтому выражается как `DELETE`(старая полная строка) + `INSERT`(новая полная строка). Клиент НЕ ДОЛЖЕН слать `UPDATE` для бесключевых таблиц; сервер его отклоняет.
2. **Идентичные дубликаты строк неоднозначны** под full-row-ключом (две байт-в-байт одинаковые строки неразличимы). См. Open Question OQ-1 — зависит от того, встречаются ли такие дубликаты; если да, нужен счётчик кратности строки.

### Типизация значений

Значения типизированы на проводе (`Value` oneof: null / int / double / string / bool / decimal-как-строка / bytes), поэтому сервер больше не выводит типы из строк, как в JSONL CDC v1. `decimal_value` несёт точные numeric как строки, чтобы избежать дрейфа float.

---

## 7. gRPC-протокол

Полный контракт: [delta-ingestion.proto](../src/main/proto/delta-ingestion.proto). Три RPC:

| RPC | Тип | Назначение |
|---|---|---|
| `GetSyncState` | unary | Resume-хелпер — возвращает `last_applied_seq`, `schema_version`, `RecoveryAction` перед сессией |
| `SubmitSchema` | unary | Замена полной схемы таблиц (только при изменении) |
| `StreamChanges` | bidi stream | Сессия (start → records → end), с ack и recovery |

Auth: `Authorization: Bearer <accessToken>` в gRPC-метаданных; refresh через существующий device-flow (`POST /api/v1/device/auth/refresh`). `site_id` выводится из токена.

---

## 8. Схемы взаимодействия

### A. Онбординг (однократно)

```
Client                                   Server
 │ POST /api/v1/device/authorize ────────────►│ создаёт device authorization
 │ ◄──────────── deviceCode, userCode ─────────│
 │ (пользователь подтверждает в браузере)       │
 │ POST /api/v1/device/token (poll) ──────────►│
 │ ◄──── accessToken, refreshToken, siteId ────│
 │ SubmitSchema(tables, PK) ──────────────────►│ store site_schemas v1
 │ ◄──────────── schemaVersion = 1 ────────────│
 │ StreamChanges ▼  (mode = FULL_SNAPSHOT)      │
 │   SessionStart(first_seq = 1) ─────────────►│ batch start
 │   ChangeRecord(INSERT, …) × N ─────────────►│ stage records
 │   SessionEnd(last_seq = N) ────────────────►│ reconcile + batch complete
 │ ◄──── SessionCommitted(committed_seq = N) ──│ сегмент записан; checkpoint@N (пол)
```

### B. Стабильная дельта-сессия (каждый период)

```
Client (например, раз в час)             Server
 │ GetSyncState(siteId) ──────────────────────►│
 │ ◄── last_applied_seq = 120, action=PROCEED ─│
 │ (клиент считает дифф от watermark = 120)     │
 │ StreamChanges ▼  (mode = DELTA)              │
 │   SessionStart(first_seq = 121) ───────────►│ проверка 121 == 120+1 ✓ → batch start
 │   ChangeRecord(UPDATE, k, d, seq=121) ─────►│
 │   ChangeRecord(INSERT, d,    seq=122) ─────►│
 │   ChangeRecord(DELETE, k,    seq=123) ─────►│
 │ ◄────────── Ack(acked_seq = 122) ───────────│ прогрессивный watermark
 │   SessionEnd(last_seq=123, per_table) ─────►│ сверка counts/hash → batch complete
 │ ◄──── SessionCommitted(committed_seq=123) ──│ дозапись сегмента [121..123]
 │ (клиент двигает watermark → 123)             │
```

### C. Разрыв / восстановление

```
 │ GetSyncState ──────────────────────────────►│
 │ ◄── last_applied_seq = 123 ─────────────────│
 │ SessionStart(first_seq = 130) ─────────────►│ 130 ≠ 124 → SEQUENCE_GAP
 │ ◄── SessionOpened(action = NEED_REBASELINE) │
 │ StreamChanges ▼  (mode = FULL_SNAPSHOT) ────►│ клиент шлёт полный снимок заново
 │   ChangeRecord(INSERT, …) × все строки ────►│ → новый checkpoint; seq перевыровнен
```

### D. Серверный checkpoint (асинхронно, без клиента)

```
Scheduler (например, ночью)              Server
 │ для каждого site / table:                    │
 │   взять последний checkpoint@M               │
 │   свернуть changelog (M, now] → состояние     │
 │   записать checkpoint@now (кадр из INSERT):   │
 │     └─ snapshot.csv.gz   → legacy (Bit BI)    │
 │   прунить сегменты changelog старше retention  │
```

Parquet-egress здесь **не** строится — он материализуется посегментно при коммите сессии (§12, Task 8).

### E. Потребители (только pull)

```
Power BI (incremental refresh, раз в час):
   читает checkpoint-пол (редко) + change-feed партиции с watermark (каждый раз)
   → fold latest-per-key в модели

Bit BI (без изменений):
   GET /api/v1/plugins/bit-bi/sites/{siteId}/files
   → сервер отдаёт реконструированный CSV (= последний checkpoint)
```

---

## 9. Режимы: периодический vs непрерывный

Один протокол, два режима; единственная разница — шлёт ли клиент `SessionEnd`.

- **Периодическая сессия (дефолт, рекомендуется первой):** клиент открывает `StreamChanges` по расписанию (час/день), сливает накопленные дельты, шлёт `SessionEnd`. Одна сессия = один сегмент. Соответствует требованию свежести и батч-модели; минимальный риск.
- **Непрерывный стрим (forward-compatible, реализация отложена):** клиент держит стрим открытым и пушит изменения по мере появления, не отправляя `SessionEnd`; **сервер запечатывает** сегменты по порогу времени/размера и эмитит `SessionCommitted` на каждый запечатанный сегмент. Near-real-time, тот же контракт.

---

## 10. Реконсиляция, детекция разрывов и восстановление

- **Порядок / идемпотентность:** записи несут строго возрастающий per-site `seq`. Сервер дедупит по `(site_id, seq)`; повторная доставка уже применённого `seq` игнорируется (at-least-once безопасно).
- **Детекция разрыва:** на `SessionStart` `first_seq` должен равняться `server_last_seq + 1`. Иначе `SEQUENCE_GAP` → `NEED_REBASELINE` (или `RESUME_FROM` для частичного реплея, который сервер может удовлетворить из застейдженных данных).
- **Реконсиляция в конце сессии:** `SessionEnd` несёт per-table counts и `content_hash`. При несовпадении сервер **отказывается коммитить** (`RECONCILIATION_FAILED`) и запрашивает восстановление. Решение: **hard-fail** — целостность компонуемой базы важнее мягкого пропуска (в отличие от CDC v1, который пропускает битые JSONL-строки с warning).
- **Самоисцеление:** любая `FULL_SNAPSHOT`-сессия заново ставит чистый пол-checkpoint, стирая накопленный дрейф.

---

## 11. Обратная совместимость — «симуляция старой БД» (CSV из changelog)

Bit BI и любой legacy-потребитель продолжают использовать `GET /api/v1/plugins/bit-bi/sites/{siteId}/files`. Изменение: CSV, который они скачивают, теперь не сырой upload клиента — это **реконструированный checkpoint** (последний `snapshot.csv.gz` для каждой таблицы), произведённый свёрткой из §8.D.

- Материализация по расписанию (не per-request) держит отдачу дешёвой.
- Это сохраняет существующий CSV-diff-путь Bit BI **без каких-либо изменений в Bit BI** на время перехода.
- Реконструированный CSV — байт-в-байт валидный полный снимок, поэтому baseline/инициализация Bit BI не затронуты.

---

## 12. Egress: Parquet / Bit BI

- **Потребители** читают **последовательный поток дельта-Parquet** на таблицу: `egress/{siteId}/{table}/delta/seq={first}-{last}.parquet` (seq с нулевым паддингом — порядок листинга = порядок применения). Один файл = записи одного закоммиченного сегмента: типизированные колонки из `site_schemas` (все nullable) плюс служебные `_op` (INSERT/UPDATE/DELETE) и `_seq`; строки DELETE несут колонки ключа. Файлы применяются последовательно по seq; файл `FULL_SNAPSHOT`-сессии — из одних INSERT, т.е. полная таблица по построению — отдельного серверного «пола» нет.
- Файлы материализуются **событийно, на каждый закоммиченный сегмент** (Task 8): durable-очередь — сама `changelog_segments` (`egress_at IS NULL` = pending, выборка головы по сайту `FOR UPDATE SKIP LOCKED`), коммит сессии будит ограниченный пул воркеров (`delta.egress.max-concurrent`), редкий sweep (`delta.egress.sweep-ms`) добирает пропущенное после сбоя. Латентность — секунды после `SessionCommitted`, независимо от checkpoint-крона. Материализуются только таблицы с задекларированной схемой.
- **Bit BI** без изменений (§11): реконструированный checkpoint CSV.
- _Выведено_: строившиеся чекпоинтом floor `snapshot.parquet` и партиции change-feed `_change_date` (исходный дизайн T4.2b/T4.3) — вытеснены дельта-потоком.

Этот egress — **read-only и файловый**; он намеренно развязан с gRPC-путём приёма.

---

## 13. Обоснование транспорта (почему gRPC)

Экономия первого порядка уже взята **отправкой только дельт** (унаследовано от CDC v1). Транспорт — второго порядка:

- **gRPC/Protobuf** даёт **типизированный контракт** (`.proto`), компактные бинарные payload (меньше JSON), нативный **стриминг** (модель сессии), прогрессивные ack/backpressure и кодоген клиента. Это же отвечает на «не изобретай свой формат» — конверт изменения это стандартный IDL, а не bespoke-соглашение.
- **Цена:** нужен HTTP/2-совместимый путь (прокси/LB), больше настройки, чем REST, сложнее ad-hoc отладка. Spring поддерживает gRPC через `spring-grpc` / `grpc-java`.

При текущем масштабе выигрыш по bandwidth над HTTP/2 + сжатый JSONL скромный; gRPC выбран прежде всего ради **типизированного стримового контракта и resumability**, на которые опирается модель надёжности (§10).

---

## 14. Миграция и сосуществование (strangler)

- gRPC-сервис `DeltaIngestion` **аддитивен**, рядом с существующими HTTP-API `/api/dfc/**` и `/api/v1/device/**`. В день 1 ничего не удаляется.
- Существующие legacy DBF/CDC HTTP-сайты продолжают работать на старом пути. Сайты переходят на Delta Client v2 поодиночке; на первой `FULL_SNAPSHOT`-сессии у сайта начинается линия changelog/checkpoint.
- Как только сайт на v2, его CSV для Bit BI отдаётся из checkpoint'ов (§11) вместо сырых upload'ов — прозрачно для Bit BI.
- Серверный legacy-дифф (`CsvDiffService`, `DbfSqlGenerationStrategy`) **депрекейтится** по мере миграции источников и выводится, когда от него не зависит ни один сайт.
- **HTTP-путь файлов закрывается посайтово (Task 7).** Как только сайт помечен `client_api_version = V2`, **write**-эндпоинты HTTP файлового пути отклоняют его с `409 Conflict` и машиночитаемым `code: "CLIENT_API_V2_REQUIRED"` (`ClientApiVersionGuard`): `POST /api/dfc/batch/start`, `POST /api/dfc/batch/{batchId}/upload`, `POST /api/dfc/schema`, `POST /api/v1/device/batches/start`, `POST /api/v1/device/files/batches/{batchId}/upload`. Drain-эндпоинты (complete/complete-with-warnings/fail/cancel/get батча, метаданные файлов) остаются открыты — начатый до переключения батч можно закрыть; клиентский error-лог (`POST /api/dfc/error`, `/api/v1/device/errors`) остаётся открыт для **всех** сайтов — в Delta v2 пока нет RPC для репортинга ошибок. V1-сайты не затронуты.

---

## 15. Безопасность

- gRPC-auth через Bearer-токен в метаданных; те же токены Auth V2 device-flow и refresh, что и `/api/v1/device/**`.
- `site_id` выводится из токена; cross-site запись `seq` или схемы отклоняется.
- TLS обязателен (h2 over TLS). mTLS — опциональный follow-up для high-assurance клиентов.
- Egress Parquet/CSV наследуют существующие S3 access controls; в egress-проекции не пишутся идентифицирующие метаданные приёма (IP, client id).

---

## 16. Фазы реализации

### Фаза 1 — Контракт и скелет сессии
1. `delta-ingestion.proto` + gradle gRPC-кодоген (`spring-grpc`).
2. `DeltaIngestionService` (gRPC) с `StreamChanges`, завязанным на `BatchLifecycleService` (сессия = батч).
3. `GetSyncState` + таблица `site_sync_state` (`V29`).
4. Bearer-token interceptor с переиспользованием Auth V2.

### Фаза 2 — Приём changelog
5. Валидация per-site `seq`, идемпотентность `(site_id, seq)`, детекция разрывов.
6. Персистентность `changelog_segments`; запись сырого сегмента в S3 на коммите.
7. Реконсиляция `SessionEnd` (counts + `content_hash`), путь hard-fail.
8. `SubmitSchema` через gRPC (переиспользовать `SiteSchemaService`); обработка бесключевых / ключа из всех полей.

### Фаза 3 — Checkpoint'инг и реконструкция
9. Шедулер: свёртка `последний checkpoint + дельты` → checkpoint (таблица `checkpoints`).
10. Запись `snapshot.csv.gz` (legacy) + `snapshot.parquet` (пол).
11. Завязать Bit BI `/sites/{siteId}/files` на отдачу реконструированного CSV.
12. Ретеншн/прунинг changelog за checkpoint'ами.

### Фаза 4 — Egress для Power BI
13. Материализация Parquet change-feed партиций (`_change_date`).
14. Валидация на реальной настройке Power BI Incremental Refresh.

### Фаза 5 — Закалка и непрерывный режим
15. Resume (`RESUME_FROM`), тюнинг backpressure, метрики.
16. Непрерывный режим стрима (серверное запечатывание сегментов).

---

## 17. План проверки

### Unit
- Порядок / идемпотентность `seq` (дубликат, out-of-order, разрыв).
- Бесключевые таблицы: `UPDATE` отклоняется; `DELETE`+`INSERT` round-trip.
- Типизация `Value` (null vs absent, точность decimal).
- Свёртка: `checkpoint + дельты` → ожидаемое состояние (I/U/D, удаления учтены).
- Реконсиляция: несовпадение count/hash → `RECONCILIATION_FAILED`.

### Integration (Testcontainers + in-process gRPC)
- Полная сессия: start → records → end → сегмент + watermark продвинут.
- Разрыв → `NEED_REBASELINE`; восстановление полным снимком ставит чистый checkpoint.
- Job checkpoint'а: changelog → CSV + Parquet; Bit BI `/files` отдаёт реконструированный CSV.
- Ретеншн: сегменты ниже checkpoint спрунены; реконструкция всё ещё корректна.
- Обратная совместимость: клиент Bit BI не видит изменения поведения.

### Manual E2E
1. Онбординг сайта, bootstrap полным снимком, проверка checkpoint@N.
2. Несколько дельта-сессий; проверка changelog + свёрнутого состояния.
3. Убить клиента посреди сессии; resume через `GetSyncState`.
4. Принудительный разрыв; проверка re-baseline.
5. Направить Power BI на Parquet-feed; подтвердить incremental refresh.

---

## 18. Открытые вопросы / отложенные решения

- **OQ-1 (дубликаты в бесключевых): РЕШЕНО — нет.** Клиент гарантирует отправку только уникальных дельт. **Таблицы с первичным/уникальным ключом** получают полный набор `INSERT/UPDATE/DELETE` (UPDATE = изменённые колонки по ключу); для DBF-таблиц UPDATE редок, но поддерживается при объявленном ключе. **Бесключевые таблицы** (ключ = все поля) делают сравнение множеств и эмитят **только INSERT / DELETE** (несовпавшая строка → DELETE, новая → INSERT) — **UPDATE нет** (подтверждает §6). Full-row-ключ считается уникальным → **счётчик кратности не нужен**.
- **OQ-2 (гранулярность seq):** per-site `seq` (выбрано — проще непрерывность) vs per-table `seq` (больше параллелизма). Пересмотреть, если per-table throughput станет узким местом.
- **OQ-3 (флаг приёма сайта): РЕШЕНО.** Добавляем поле сайта **`client_api_version`** (`V1` = legacy HTTP `/api/dfc`, `V2` = Delta gRPC). **`V2` — дефолт** для новых сайтов; существующие **бэкфиллятся в `V1`** в миграции V29, чтобы legacy-путь продолжал работать. `site_type` (DBF / POSTGRES_CDC — семантика данных) ортогонален и не меняется.
- **OQ-4 (каденция checkpoint):** дефолтные частоты для table-checkpoint vs пол Power BI; тюнить под реальные объёмы.
- **OQ-5 (формат сегмента на проводе at-rest):** сырой Protobuf vs JSONL для bronze-сегментов (предполагается Protobuf; JSONL проще отлаживать).

---

## История версий

| Версия | Дата | Изменения |
|---|---|---|
| 0.1.0 | 2026-06-05 | Первый черновик для design review |
