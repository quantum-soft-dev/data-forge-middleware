# Plugin SQL Generation Extension (PRD-013b)

**Feature Branch**: `013-plugin-system`
**Created**: 2025-12-22
**Status**: Draft
**Extends**: PRD-013 (Plugin System & Bit BI OAuth Integration)

## Overview

Расширение плагин-системы (PRD-013) для автоматической генерации SQL файлов на основе различий между последовательными загрузками батчей.

### Ключевые возможности:
1. Автоматическая генерация diff при завершении загрузки батча
2. Создание PostgreSQL SQL файлов на основе результатов diff
3. Plugin API endpoints для получения SQL изменений по сайту и дате

---

## User Stories

### User Story 1 - Автоматическая генерация Diff (Priority: P1)

После завершения загрузки батча для аккаунта с активированным плагином Bit BI система автоматически сравнивает все файлы текущего батча с предыдущим батчем того же сайта.

**Acceptance Scenarios:**

1. **Given** аккаунт с активированным плагином Bit BI, **When** батч загрузка завершается (`BATCH_COMPLETED`), **Then** система автоматически запускает diff по всем файлам с предыдущим батчем того же сайта.

2. **Given** это первый батч для сайта, **When** батч загрузка завершается, **Then** генерируются INSERT запросы для всех строк всех файлов.

3. **Given** предыдущий батч существует, **When** diff выполняется, **Then** результат содержит добавленные, измененные и удаленные строки.

---

### User Story 2 - Генерация SQL файлов (Priority: P1)

На основе результатов diff генерируются PostgreSQL SQL файлы с INSERT, UPDATE и DELETE запросами.

**SQL Generation Rules:**

| Тип изменения | SQL Statement | WHERE Clause |
|---------------|---------------|--------------|
| Строка добавлена | `INSERT INTO` | N/A |
| Строка изменена | `UPDATE` | По всем неизмененным полям |
| Строка удалена | `DELETE` | По всем полям |

**Acceptance Scenarios:**

1. **Given** diff показывает добавленную строку, **When** SQL генерируется, **Then** создается `INSERT INTO {table} (...) VALUES (...)`.

2. **Given** diff показывает измененную строку, **When** SQL генерируется, **Then** создается `UPDATE {table} SET changed_col=new_val WHERE unchanged_col1=val1 AND unchanged_col2=val2`.

3. **Given** diff показывает удаленную строку, **When** SQL генерируется, **Then** создается `DELETE FROM {table} WHERE col1=val1 AND col2=val2 AND ...` (все поля).

4. **Given** любой SQL запрос, **When** он генерируется, **Then** после него добавляется комментарий `--- END OF COMMAND "{filename}:{line_number}" ---`.

---

### User Story 3 - Получение SQL изменений через API (Priority: P1)

Bit BI пользователь с Plugin API Key может получить все SQL изменения для сайта после указанной даты.

**Endpoint:** `GET /api/v1/plugins/bit-bi/sql-changes`

**Acceptance Scenarios:**

1. **Given** валидный Plugin API Key, **When** запрос `GET /sql-changes?siteId=X&since=2025-01-01T00:00:00Z`, **Then** возвращаются все SQL изменения для сайта X после указанной даты.

2. **Given** невалидный API Key, **When** запрос выполняется, **Then** возвращается 401 Unauthorized.

3. **Given** siteId не принадлежит аккаунту, **When** запрос выполняется, **Then** возвращается 403 Forbidden.

4. **Given** нет изменений после указанной даты, **When** запрос выполняется, **Then** возвращается пустой ответ (200 OK с пустым body).

---

### User Story 4 - Список доступных сайтов (Priority: P2)

Bit BI пользователь может получить список сайтов доступных для его аккаунта.

**Endpoint:** `GET /api/v1/plugins/bit-bi/sites`

**Acceptance Scenarios:**

1. **Given** валидный Plugin API Key, **When** запрос `GET /sites`, **Then** возвращается список всех сайтов аккаунта с id, name, domain.

---

### User Story 5 - Хранение SQL файлов в S3 (Priority: P2)

Сгенерированные SQL файлы сохраняются в AWS S3 со структурированными путями.

**Acceptance Scenarios:**

1. **Given** SQL файл сгенерирован, **When** сохраняется в S3, **Then** путь: `plugins/bit-bi/{accountId}/{siteName}/{source_datetime}-{comparison_datetime}.sql`.

2. **Given** файл успешно сохранен, **When** запрашивается через API, **Then** содержимое загружается из S3 и возвращается клиенту.

---

## Functional Requirements

### SQL Generation

- **FR-019**: Система ДОЛЖНА автоматически запускать diff при событии `BATCH_COMPLETED` для аккаунтов с активированным Bit BI плагином
- **FR-020**: Система ДОЛЖНА генерировать `INSERT INTO` для добавленных строк
- **FR-021**: Система ДОЛЖНА генерировать `UPDATE` для измененных строк с WHERE по неизмененным полям
- **FR-022**: Система ДОЛЖНА генерировать `DELETE FROM` для удаленных строк с WHERE по всем полям
- **FR-023**: Система ДОЛЖНА обрабатывать NULL значения согласно правилам типов DBF
- **FR-024**: Система ДОЛЖНА сохранять SQL файлы в S3 по пути `plugins/bit-bi/{accountId}/{siteName}/{datetime}.sql`
- **FR-025**: Система ДОЛЖНА добавлять комментарий `--- END OF COMMAND "{filename}:{line_number}" ---` после каждого SQL выражения

### Plugin API

- **FR-026**: ~~Система ДОЛЖНА генерировать Plugin API Key при активации плагина~~ (УЖЕ РЕАЛИЗОВАНО - используется существующий `account_plugins.plugin_data` JSONB)
- **FR-027**: Система ДОЛЖНА предоставлять endpoint `GET /sql-changes` с фильтрами siteId и since
- **FR-028**: Система ДОЛЖНА предоставлять endpoint `GET /sites` для списка сайтов аккаунта
- **FR-029**: Система ДОЛЖНА валидировать API Key и проверять принадлежность siteId к аккаунту

### Existing Infrastructure (PRD-013)

Следующие компоненты УЖЕ РЕАЛИЗОВАНЫ и будут использоваться:

- `account_plugins` таблица с `plugin_data` (JSONB) для хранения apiKey
- `PluginActivationService` для управления активацией
- `PluginEventDispatcher` для обработки событий `BATCH_COMPLETED`
- `BitBiPlugin` класс для обработки событий
- JSON Schema валидация для plugin_data
- Audit logging в `plugin_audit_logs`

---

## Technical Specification

### API Endpoints

#### GET /api/v1/plugins/bit-bi/sql-changes

Получить SQL изменения для сайта после указанной даты.

```http
GET /api/v1/plugins/bit-bi/sql-changes?siteId={uuid}&since={datetime}
Authorization: Bearer plk_xxxxxxxxxxxxx
```

**Parameters:**
| Name | Type | Required | Description |
|------|------|----------|-------------|
| siteId | UUID | Yes | ID сайта |
| since | ISO 8601 DateTime | Yes | Вернуть изменения после этой даты |

**Response:** `200 OK`
```
Content-Type: text/plain; charset=utf-8

INSERT INTO users (name, email, age) VALUES ('John', 'john@example.com', 30);
--- END OF COMMAND "users.csv:1" ---
UPDATE products SET price = 99.99 WHERE name = 'Widget' AND category = 'Tools';
--- END OF COMMAND "products.csv:42" ---
DELETE FROM orders WHERE id = 123 AND customer_id = 456 AND amount = 100.00;
--- END OF COMMAND "orders.csv:15" ---
```

**Error Responses:**
- `401 Unauthorized` - Invalid or missing API key
- `403 Forbidden` - Site does not belong to account
- `400 Bad Request` - Missing required parameters

---

#### GET /api/v1/plugins/bit-bi/sites

Получить список доступных сайтов.

```http
GET /api/v1/plugins/bit-bi/sites
Authorization: Bearer plk_xxxxxxxxxxxxx
```

**Response:** `200 OK`
```json
{
  "sites": [
    {
      "id": "550e8400-e29b-41d4-a716-446655440000",
      "name": "Main Store",
      "domain": "main-store.com"
    },
    {
      "id": "550e8400-e29b-41d4-a716-446655440001",
      "name": "Warehouse",
      "domain": "warehouse.local"
    }
  ]
}
```

---

### Plugin API Key

**Format:** `plk_` + 32 символа (alphanumeric)

**Generation:** При активации плагина через POST /api/v1/plugins/bit-bi/activate

**Storage:** В существующем `account_plugins.plugin_data` (JSONB) - расширение текущей схемы:

```json
{
  "tenantId": "bit-bi-tenant-123",
  "apiKey": "plk_a1b2c3d4e5f6g7h8i9j0k1l2m3n4o5p6",
  "apiKeyCreatedAt": "2025-01-01T00:00:00Z"
}
```

**Обновление JSON Schema (BitBiPlugin):**
```json
{
  "$schema": "http://json-schema.org/draft-07/schema#",
  "type": "object",
  "required": ["tenantId"],
  "properties": {
    "tenantId": {
      "type": "string",
      "minLength": 1,
      "maxLength": 64,
      "pattern": "^[a-zA-Z0-9-_]+$"
    },
    "apiKey": {
      "type": "string",
      "minLength": 36,
      "maxLength": 64,
      "pattern": "^plk_[a-zA-Z0-9]+$"
    },
    "apiKeyCreatedAt": {
      "type": "string",
      "format": "date-time"
    }
  },
  "additionalProperties": false
}
```

**Security:**
- API Key привязан к Account (не к Site)
- Дает доступ ко всем сайтам аккаунта
- Endpoint проверяет принадлежность siteId к аккаунту

---

### SQL Statement Format

#### Table Naming
- Имя файла без расширения = имя таблицы
- `users.csv` -> `users`
- `product_catalog.csv` -> `product_catalog`

#### Row Identification
- Все поля вместе = составной ключ
- Нет отдельного Primary Key

#### Comment Format
```sql
{SQL_STATEMENT};
--- END OF COMMAND "{filename}:{line_number}" ---
```

**Example:**
```sql
INSERT INTO users (name, email, phone) VALUES ('Alice', 'alice@test.com', '+1234567890');
--- END OF COMMAND "users.csv:1" ---

UPDATE customers SET balance = 150.00 WHERE name = 'Bob' AND email = 'bob@test.com' AND created_at = '2025-01-01';
--- END OF COMMAND "customers.csv:42" ---

DELETE FROM products WHERE sku = 'ABC123' AND name = 'Widget' AND price = 19.99 AND category = 'Tools';
--- END OF COMMAND "products.csv:15" ---
```

---

### NULL Handling (DBF Types)

| DBF Type | Empty String -> NULL | Example |
|----------|---------------------|---------|
| Character (C) | Yes | `''` -> `NULL` |
| Numeric (N) | Yes | `''` -> `NULL` |
| Logical (L) | Yes | `''` -> `NULL` |
| Date (D) | Yes | `''` -> `NULL` |
| Float (F) | Yes | `''` -> `NULL` |
| DateTime (T) | Yes | `''` -> `NULL` |
| Integer (I) | **No** | Всегда числовое значение |
| Currency (Y) | **No** | Всегда числовое значение |

**SQL Examples:**
```sql
-- Character field with empty string
INSERT INTO users (name, phone) VALUES ('John', NULL);

-- Integer field (never NULL)
INSERT INTO products (name, quantity) VALUES ('Widget', 0);

-- Multiple NULLs
UPDATE customers SET phone = NULL, address = NULL WHERE id = 123;
```

---

### Event Flow

```
1. BatchLifecycleService.completeBatch()
   +-- publishEvent(BatchCompletedEvent)

2. BatchEventListener.onBatchCompleted()
   +-- PluginEventDispatcher.dispatch(BATCH_COMPLETED)

3. BitBiPlugin.execute(event, accountPlugin)
   |-- Find previous batch for same site
   |-- If no previous batch -> Generate INSERT for all rows
   +-- If previous batch exists:
       |-- CsvFieldDiffService.compare(currentFiles, previousFiles)
       |-- SqlGenerationService.generate(diffResults)
       +-- S3 upload to plugins/bit-bi/{accountId}/{siteName}/{datetime}.sql

4. GET /sql-changes request
   |-- Validate API Key -> find AccountPlugin
   |-- Verify siteId belongs to account
   |-- Find all SQL files for site after 'since' date
   +-- Stream/concatenate files and return
```

---

## Database Changes

### New Table: plugin_sql_generations

```sql
CREATE TABLE plugin_sql_generations (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    account_plugin_id UUID NOT NULL REFERENCES account_plugins(id),
    site_id UUID NOT NULL REFERENCES sites(id),
    source_batch_id UUID NOT NULL REFERENCES batches(id),
    comparison_batch_id UUID REFERENCES batches(id), -- NULL for first batch
    s3_key VARCHAR(500) NOT NULL,
    file_size_bytes BIGINT NOT NULL,
    statement_count INTEGER NOT NULL,
    generated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),

    CONSTRAINT fk_account_plugin FOREIGN KEY (account_plugin_id)
        REFERENCES account_plugins(id) ON DELETE CASCADE
);

CREATE INDEX idx_plugin_sql_generations_site_date
    ON plugin_sql_generations(site_id, generated_at DESC);

CREATE INDEX idx_plugin_sql_generations_account_plugin
    ON plugin_sql_generations(account_plugin_id);
```

### Index for API Key Lookup

```sql
CREATE INDEX idx_account_plugins_api_key
    ON account_plugins USING GIN ((plugin_data->'apiKey'));
```

---

## S3 File Structure

```
s3://dataforge-uploads/
+-- plugins/
    +-- bit-bi/
        +-- {accountId}/
            +-- {siteName}/
                |-- 2025-01-15T10-30-00Z--2025-01-14T09-00-00Z.sql
                |-- 2025-01-16T14-45-00Z--2025-01-15T10-30-00Z.sql
                +-- 2025-01-17T08-00-00Z--first-batch.sql
```

**Naming Convention:**
- `{source_datetime}--{comparison_datetime}.sql`
- `{source_datetime}--first-batch.sql` (для первого батча)
- DateTime format: `YYYY-MM-DDTHH-mm-ssZ` (ISO 8601 без двоеточий)

---

## Edge Cases

| Scenario | Behavior |
|----------|----------|
| Первый батч для сайта | Все INSERT (comparison_batch_id = NULL) |
| Пустой diff (файлы идентичны) | SQL файл не создается |
| Бинарные файлы | Пропускаются (только CSV/текст) |
| Кодировка файлов | Auto-detect (ICU4J): UTF-8, Windows-1252, ISO-8859-1 |
| Батч без файлов | SQL файл не создается |
| Плагин деактивирован | События не обрабатываются |
| Site удален | SQL файлы остаются в S3 для audit |

---

## Success Criteria

- **SC-009**: SQL генерация завершается в течение 60 секунд для батча с 100 файлами
- **SC-010**: GET /sql-changes возвращает ответ в течение 2 секунд
- **SC-011**: 100% точность SQL генерации (все добавления/изменения/удаления отражены)
- **SC-012**: API Key валидация выполняется за <50ms
- **SC-013**: Audit trail сохраняет все API вызовы и генерации

---

## Out of Scope

- Web UI для просмотра SQL файлов (используйте API)
- Поддержка других SQL диалектов (только PostgreSQL)
- Ручной запуск diff (только автоматический при BATCH_COMPLETED)
- Редактирование сгенерированных SQL файлов
- Повторная генерация (regenerate) для существующих батчей

---

## Development Methodology: TDD (Test-Driven Development)

Разработка данной функциональности ДОЛЖНА вестись по методологии TDD.

### TDD Workflow

```
1. RED    - Написать failing test
2. GREEN  - Написать минимальный код для прохождения теста
3. REFACTOR - Улучшить код без изменения поведения
```

### Test Pyramid

| Layer | Technology | Coverage | Purpose |
|-------|------------|----------|---------|
| Unit | JUnit 5 + Mockito | 80%+ | Business logic, validation |
| Contract | MockMvc + @WebMvcTest | All endpoints | API contracts |
| Integration | Testcontainers (PostgreSQL, LocalStack S3) | Critical paths | E2E flows |

### Required Tests BEFORE Implementation

#### 1. SQL Generation Tests
```java
// Unit: SqlGenerationServiceTest
- shouldGenerateInsertForNewRow()
- shouldGenerateUpdateForModifiedRow()
- shouldGenerateDeleteForRemovedRow()
- shouldUseAllFieldsInDeleteWhere()
- shouldUseUnchangedFieldsInUpdateWhere()
- shouldHandleNullValuesPerDbfType()
- shouldAddEndOfCommandComment()
```

#### 2. Diff Service Tests
```java
// Unit: CsvFieldDiffServiceTest
- shouldDetectAddedRows()
- shouldDetectRemovedRows()
- shouldDetectModifiedRows()
- shouldIdentifyChangedFields()
- shouldHandleFirstBatchAsAllInserts()
```

#### 3. Plugin API Tests
```java
// Contract: BitBiPluginApiContractTest
- shouldReturnSqlChangesForValidApiKey()
- shouldReturn401ForInvalidApiKey()
- shouldReturn403ForSiteNotBelongingToAccount()
- shouldReturnEmptyForNoChanges()
- shouldReturnSiteListForValidApiKey()

// Integration: BitBiPluginApiIntegrationTest
- shouldFetchSqlChangesFromS3()
- shouldFilterBySinceDate()
- shouldCombineMultipleSqlFiles()
```

#### 4. Event Handler Tests
```java
// Unit: BitBiPluginTest
- shouldTriggerDiffOnBatchCompleted()
- shouldFindPreviousBatchForSameSite()
- shouldGenerateSqlAndUploadToS3()
- shouldSkipIfNoPreviousBatch()
```

### Test Data Fixtures

```java
// src/test/resources/fixtures/
├── csv/
│   ├── users_v1.csv         // Initial batch
│   ├── users_v2.csv         // With changes
│   └── products_empty.csv   // Edge case
├── expected-sql/
│   ├── users_insert.sql
│   ├── users_update.sql
│   └── users_delete.sql
└── plugin-data/
    └── valid_bit_bi_config.json
```

### Test Execution Order

1. **Unit tests** - Run first, fastest feedback
2. **Contract tests** - Verify API shape
3. **Integration tests** - Verify E2E with real DB/S3

### CI/CD Gate

- All tests MUST pass before merge
- Code coverage >= 80% for new code
- No skipped tests allowed

---

## Security Considerations

1. **API Key Storage**: Хранится хешированным в plugin_data (bcrypt)
2. **Key Rotation**: Возможность regenerate через отдельный endpoint (future)
3. **Rate Limiting**: 100 requests/minute per API Key
4. **Audit Logging**: Все API вызовы логируются в plugin_audit_logs
5. **Site Isolation**: Строгая проверка принадлежности siteId к аккаунту

---

*Document generated: 2025-12-22*
