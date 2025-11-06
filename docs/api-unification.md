# API Unification 

**Branch:** `feature/api-unification`
**Дата:** 2025-11-05

## Цель

Унификация API endpoints в единообразную структуру:
- **Device API**: `/api/v1/device/*` - для клиентских устройств (Custom JWT)
- **UI/Admin API**: `/api/v1/*` - для веб-интерфейса (Keycloak OAuth2)

---

## Этап 1: Создание констант и базовой структуры

### 1.1 Создать файл с константами API путей
- **Путь**: `src/main/java/com/bitbi/dfm/shared/api/ApiRoutes.java`
- **Описание**: Все константы для Device API и UI/Admin API endpoints

---

## Этап 2: Device API - Новые контроллеры

### 2.1 Device Authentication
- **Создать**: `src/main/java/com/bitbi/dfm/device/presentation/DeviceAuthController.java`
- **Путь**: `/api/v1/device/auth`
- **Endpoints**:
  - `POST /token`

### 2.2 Device Batch Management
- **Создать**: `src/main/java/com/bitbi/dfm/device/presentation/DeviceBatchController.java`
- **Путь**: `/api/v1/device/batches`
- **Endpoints**:
  - `POST /start`
  - `POST /{id}/complete`
  - `POST /{id}/fail`
  - `POST /{id}/cancel`
  - `GET /{id}`

### 2.3 Device File Operations
- **Создать**: `src/main/java/com/bitbi/dfm/device/presentation/DeviceFileController.java`
- **Путь**: `/api/v1/device/files`
- **Endpoints**:
  - `POST /batches/{batchId}/upload`
  - `GET /batches/{batchId}/files/{fileId}`

### 2.4 Device Error Logging
- **Создать**: `src/main/java/com/bitbi/dfm/device/presentation/DeviceErrorController.java`
- **Путь**: `/api/v1/device/errors`
- **Endpoints**:
  - `POST /`
  - `POST /batches/{batchId}`
  - `GET /{errorId}`

---

## Этап 3: UI/Admin API - Обновление путей

### 3.1 Accounts
- **Обновить**: `src/main/java/com/bitbi/dfm/account/presentation/AccountAdminController.java`
- **Старый путь**: `/api/admin/accounts`
- **Новый путь**: `/api/v1/accounts`

### 3.2 Sites
- **Обновить**: `src/main/java/com/bitbi/dfm/site/presentation/SiteAdminController.java`
- **Старый путь**: `/api/admin/sites`
- **Новый путь**: `/api/v1/sites`

### 3.3 Batches
- **Обновить**: `src/main/java/com/bitbi/dfm/batch/presentation/BatchAdminController.java`
- **Старый путь**: `/api/admin/batches`
- **Новый путь**: `/api/v1/batches`

### 3.4 History
- **Обновить**:
  - `src/main/java/com/bitbi/dfm/batch/presentation/BatchHistoryController.java`
  - `src/main/java/com/bitbi/dfm/batch/presentation/BatchHistoryAdminController.java`
- **Старый путь**: `/api/user/batches`
- **Новый путь**: `/api/v1/history`

### 3.5 Errors
- **Обновить**: `src/main/java/com/bitbi/dfm/error/presentation/ErrorAdminController.java`
- **Старый путь**: `/api/admin/errors`
- **Новый путь**: `/api/v1/errors`

### 3.6 Comparisons
- **Обновить**: `src/main/java/com/bitbi/dfm/comparison/presentation/ComparisonController.java`
- **Старый путь**: `/api/v1/comparisons` (уже правильный)
- **Новый путь**: `/api/v1/comparisons` (без изменений)

---

## Этап 4: Удаление устаревших файлов

### 4.1 Удалить старые Device API контроллеры
- `src/main/java/com/bitbi/dfm/auth/presentation/AuthController.java`
- `src/main/java/com/bitbi/dfm/batch/presentation/BatchController.java`
- `src/main/java/com/bitbi/dfm/upload/presentation/FileUploadController.java`
- `src/main/java/com/bitbi/dfm/error/presentation/ErrorLogController.java`

### 4.2 Удалить дубликат
- `src/main/java/com/bitbi/dfm/site/presentation/SiteController.java`

---

## Этап 5: Обновление Security Configuration

### 5.1 Security Filter Chains
- **Обновить**: `src/main/java/com/bitbi/dfm/security/SecurityConfiguration.java`
- **Filter Chain 1**: `/api/v1/device/**` → Custom JWT
- **Filter Chain 2**: `/api/v1/**` → Keycloak OAuth2

---

## Этап 6: Обновление OpenAPI документации

### 6.1 OpenAPI Configuration
- **Обновить**: `src/main/java/com/bitbi/dfm/config/OpenApiConfiguration.java`
- Разделение на "Device API" и "UI/Admin API"
- Обновление security schemes

---

## Этап 7: Тесты

### 7.1 Device API Contract Tests
- **Создать**: `src/test/java/com/bitbi/dfm/device/presentation/DeviceApiContractTest.java`
- Все endpoint тесты для Device API

### 7.2 Обновить существующие Contract Tests
- `src/test/java/com/bitbi/dfm/account/presentation/AccountAdminControllerTest.java`
- `src/test/java/com/bitbi/dfm/site/presentation/SiteAdminControllerTest.java`
- `src/test/java/com/bitbi/dfm/batch/presentation/BatchAdminControllerTest.java`
- `src/test/java/com/bitbi/dfm/batch/presentation/BatchHistoryContractTest.java`
- `src/test/java/com/bitbi/dfm/error/presentation/ErrorAdminControllerTest.java`
- `src/test/java/com/bitbi/dfm/comparison/presentation/ComparisonContractTest.java`

### 7.3 Обновить Integration Tests
- Все файлы в `src/test/java/com/bitbi/dfm/integration/`

---

## Этап 8: Документация

### 8.1 Обновить CLAUDE.md
- **Путь**: `/CLAUDE.md`
- Новая структура API
- Mapping таблица (старые → новые пути)

### 8.2 Создать Migration Guide
- **Создать**: `/API_MIGRATION.md`
- Инструкции для frontend разработчиков
- Примеры изменений для каждого endpoint

---

## Этап 9: Финальная проверка

### 9.1 Запуск тестов
```bash
./gradlew test
./gradlew integrationTest
```

### 9.2 Проверка build
```bash
./gradlew build
```

---

## Структура файлов после рефакторинга

```
src/main/java/com/bitbi/dfm/
├── shared/api/
│   └── ApiRoutes.java                    [НОВЫЙ]
│
├── device/presentation/                   [НОВЫЙ ПАКЕТ]
│   ├── DeviceAuthController.java         [НОВЫЙ]
│   ├── DeviceBatchController.java        [НОВЫЙ]
│   ├── DeviceFileController.java         [НОВЫЙ]
│   └── DeviceErrorController.java        [НОВЫЙ]
│
├── account/presentation/
│   └── AccountAdminController.java       [ОБНОВЛЕН]
│
├── site/presentation/
│   ├── SiteAdminController.java          [ОБНОВЛЕН]
│   └── SiteController.java               [УДАЛЕН]
│
├── batch/presentation/
│   ├── BatchAdminController.java         [ОБНОВЛЕН]
│   ├── BatchHistoryController.java       [ОБНОВЛЕН]
│   ├── BatchHistoryAdminController.java  [ОБНОВЛЕН]
│   └── BatchController.java              [УДАЛЕН]
│
├── error/presentation/
│   ├── ErrorAdminController.java         [ОБНОВЛЕН]
│   └── ErrorLogController.java           [УДАЛЕН]
│
├── comparison/presentation/
│   └── ComparisonController.java         [ОБНОВЛЕН]
│
├── auth/presentation/
│   └── AuthController.java               [УДАЛЕН]
│
├── upload/presentation/
│   └── FileUploadController.java         [УДАЛЕН]
│
└── security/
    └── SecurityConfiguration.java        [ОБНОВЛЕН]
```

---

## Итоговая структура API endpoints

### Device API (Custom JWT)
```
/api/v1/device/auth/token
/api/v1/device/batches/start
/api/v1/device/batches/{id}/complete
/api/v1/device/batches/{id}/fail
/api/v1/device/batches/{id}/cancel
/api/v1/device/batches/{id}
/api/v1/device/files/batches/{batchId}/upload
/api/v1/device/files/batches/{batchId}/files/{fileId}
/api/v1/device/errors
/api/v1/device/errors/batches/{batchId}
/api/v1/device/errors/{errorId}
```

### UI/Admin API (Keycloak OAuth2)
```
/api/v1/accounts/**
/api/v1/sites/**
/api/v1/batches/**
/api/v1/history/**
/api/v1/errors/**
/api/v1/comparisons/**
```

---

## Endpoint Mapping (Старые → Новые)

| Старый endpoint | Новый endpoint | API Type |
|-----------------|----------------|----------|
| `POST /api/v1/auth/token` | `POST /api/v1/device/auth/token` | Device |
| `POST /api/dfc/batch/start` | `POST /api/v1/device/batches/start` | Device |
| `POST /api/dfc/batch/{id}/complete` | `POST /api/v1/device/batches/{id}/complete` | Device |
| `POST /api/dfc/batch/{id}/fail` | `POST /api/v1/device/batches/{id}/fail` | Device |
| `POST /api/dfc/batch/{id}/cancel` | `POST /api/v1/device/batches/{id}/cancel` | Device |
| `GET /api/dfc/batch/{id}` | `GET /api/v1/device/batches/{id}` | Device |
| `POST /api/dfc/batch/{batchId}/upload` | `POST /api/v1/device/files/batches/{batchId}/upload` | Device |
| `GET /api/dfc/batch/{batchId}/files/{fileId}` | `GET /api/v1/device/files/batches/{batchId}/files/{fileId}` | Device |
| `POST /api/dfc/error` | `POST /api/v1/device/errors` | Device |
| `POST /api/dfc/error/{batchId}` | `POST /api/v1/device/errors/batches/{batchId}` | Device |
| `GET /api/dfc/error/log/{errorId}` | `GET /api/v1/device/errors/{errorId}` | Device |
| `GET /api/admin/accounts` | `GET /api/v1/accounts` | UI |
| `POST /api/admin/accounts` | `POST /api/v1/accounts` | UI |
| `GET /api/admin/accounts/with-keycloak` | `GET /api/v1/accounts/with-keycloak` | UI |
| `POST /api/admin/accounts/with-keycloak` | `POST /api/v1/accounts/with-keycloak` | UI |
| `GET /api/admin/accounts/{id}` | `GET /api/v1/accounts/{id}` | UI |
| `PUT /api/admin/accounts/{id}` | `PUT /api/v1/accounts/{id}` | UI |
| `DELETE /api/admin/accounts/{id}` | `DELETE /api/v1/accounts/{id}` | UI |
| `POST /api/admin/accounts/{id}/lock` | `POST /api/v1/accounts/{id}/lock` | UI |
| `POST /api/admin/accounts/{id}/unlock` | `POST /api/v1/accounts/{id}/unlock` | UI |
| `POST /api/admin/accounts/{id}/reset-password` | `POST /api/v1/accounts/{id}/reset-password` | UI |
| `GET /api/admin/accounts/{id}/audit-logs` | `GET /api/v1/accounts/{id}/audit-logs` | UI |
| `GET /api/admin/sites` | `GET /api/v1/sites` | UI |
| `POST /api/admin/accounts/{accountId}/sites` | `POST /api/v1/accounts/{accountId}/sites` | UI |
| `GET /api/admin/accounts/{accountId}/sites` | `GET /api/v1/accounts/{accountId}/sites` | UI |
| `GET /api/admin/sites/{id}` | `GET /api/v1/sites/{id}` | UI |
| `PUT /api/admin/sites/{id}` | `PUT /api/v1/sites/{id}` | UI |
| `DELETE /api/admin/sites/{id}` | `DELETE /api/v1/sites/{id}` | UI |
| `POST /api/admin/accounts/{accountId}/sites/{siteId}/activate` | `POST /api/v1/accounts/{accountId}/sites/{siteId}/activate` | UI |
| `POST /api/admin/accounts/{accountId}/sites/{siteId}/deactivate` | `POST /api/v1/accounts/{accountId}/sites/{siteId}/deactivate` | UI |
| `DELETE /api/admin/accounts/{accountId}/sites/{siteId}` | `DELETE /api/v1/accounts/{accountId}/sites/{siteId}` | UI |
| `GET /api/admin/sites/{id}/statistics` | `GET /api/v1/sites/{id}/statistics` | UI |
| `GET /api/admin/batches` | `GET /api/v1/batches` | UI |
| `GET /api/admin/batches/{id}` | `GET /api/v1/batches/{id}` | UI |
| `DELETE /api/admin/batches/{id}` | `DELETE /api/v1/batches/{id}` | UI |
| `GET /api/user/batches` | `GET /api/v1/history/batches` | UI |
| `GET /api/user/batches/{batchId}` | `GET /api/v1/history/batches/{batchId}` | UI |
| `GET /api/user/batches/{batchId}/files/{fileId}/download` | `GET /api/v1/history/batches/{batchId}/files/{fileId}/download` | UI |
| `POST /api/user/batches/{batchId}/download-zip` | `POST /api/v1/history/batches/{batchId}/download-zip` | UI |
| `POST /api/user/batches/{batchId}/export-excel` | `POST /api/v1/history/batches/{batchId}/export-excel` | UI |
| `GET /api/user/batches/{batchId}/errors` | `GET /api/v1/history/batches/{batchId}/errors` | UI |
| `GET /api/admin/errors` | `GET /api/v1/errors` | UI |
| `GET /api/admin/errors/export` | `GET /api/v1/errors/export` | UI |
| `POST /api/v1/comparisons` | `POST /api/v1/comparisons` | UI |
| `GET /api/v1/comparisons` | `GET /api/v1/comparisons` | UI |
| `GET /api/v1/comparisons/{id}` | `GET /api/v1/comparisons/{id}` | UI |
| `GET /api/v1/comparisons/{id}/results` | `GET /api/v1/comparisons/{id}/results` | UI |
| `GET /api/v1/comparisons/{id}/summary` | `GET /api/v1/comparisons/{id}/summary` | UI |
| `GET /api/v1/comparisons/by-batch/{batchId}` | `GET /api/v1/comparisons/by-batch/{batchId}` | UI |
| `DELETE /api/v1/comparisons/{id}` | `DELETE /api/v1/comparisons/{id}` | UI |
| `GET /api/v1/comparisons/{id}/download` | `GET /api/v1/comparisons/{id}/download` | UI |
| `GET /api/v1/comparisons/{id}/summary/download` | `GET /api/v1/comparisons/{id}/summary/download` | UI |
