# Перенос базы dev (AWS RDS) → test (Google Cloud SQL)

Разовая синхронизация базы PostgreSQL из AWS dev-окружения (`dev.dfm.bitbi.io`)
в GKE test-окружение (`test.dfm.bitbi.io`). Цель — перенести справочные данные
(`accounts`, `sites`, `site_schemas`, настройки, активации плагинов), чтобы
существующие Auth0-пользователи заработали на test: `app_metadata.accountId`
у пользователя один на общий тенант, и строка `accounts` с этим UUID должна
существовать в базе каждого окружения.

Историческая часть (батчи, файлы, чекпоинты, delta-сегменты) после импорта
удаляется скриптом [`scripts/db-sync/cleanup-after-import.sql`](../scripts/db-sync/cleanup-after-import.sql) —
она ссылается на объекты S3-бакета dev, которых в GCP нет.

> **Важно:** это разовая операция. После неё базы живут независимо: пользователи,
> созданные позже в одном окружении, во втором не появятся.

---

## 0. Предварительные требования

- Клиент PostgreSQL **той же мажорной версии, что сервер (16)**: `pg_dump`,
  `pg_restore`, `psql`. На macOS: `brew install libpq` (потом `brew link --force libpq`)
  или `postgresql@16`.
- Сетевой доступ к RDS (VPN/бастион или публичный endpoint с вашим IP в security group).
- `gcloud` CLI с доступом к проекту GCP + [Cloud SQL Auth Proxy](https://cloud.google.com/sql/docs/postgres/connect-auth-proxy)
  (`brew install cloud-sql-proxy`).
- `kubectl` с контекстом GKE-кластера (чтобы остановить бэкенд на время импорта).
- Реквизиты обеих БД (хост/имя базы/пользователь/пароль).

Проверка версий: мажор клиента ≥ мажора сервера-источника.

```bash
pg_dump --version
psql "host=<RDS_ENDPOINT> user=<DEV_DB_USER> dbname=<DEV_DB>" -c "SHOW server_version;"
```

---

## 1. Экспорт из AWS RDS

```bash
export PGPASSWORD='<пароль dev-БД>'

pg_dump "host=<RDS_ENDPOINT> port=5432 dbname=<DEV_DB> user=<DEV_DB_USER> sslmode=require" \
  --format=custom \
  --no-owner \
  --no-privileges \
  --file="dfm-dev-$(date +%F).dump"
```

Пояснения:

- `--format=custom` — сжатый формат для `pg_restore` (позволяет выборочное
  восстановление, если понадобится).
- `--no-owner --no-privileges` — обязательны при переносе между managed-сервисами:
  роли AWS (`rdsadmin` и т.п.) в Cloud SQL не существуют, без этих флагов restore
  засыплет ошибками GRANT/OWNER.
- Дамп включает `flyway_schema_history` — Flyway на test после импорта увидит
  актуальную схему и не будет ничего накатывать. Поэтому версия приложения на
  test не должна быть **старше**, чем на dev (иначе Flyway-валидация упадёт на
  «неизвестных» миграциях из будущего).

Быстрая проверка дампа:

```bash
pg_restore --list dfm-dev-*.dump | head -30   # оглавление читается — дамп цел
```

---

## 2. Импорт в Cloud SQL

### 2.1. Остановить бэкенд на test

Иначе приложение держит коннекты (DROP DATABASE не пройдёт) и пишет в базу во
время импорта.

```bash
kubectl config use-context <GKE_CONTEXT>
kubectl scale deployment <BACKEND_DEPLOYMENT> -n <NAMESPACE> --replicas=0
kubectl get pods -n <NAMESPACE> -w    # дождаться остановки подов
```

### 2.2. Поднять Cloud SQL Auth Proxy

```bash
gcloud auth login
gcloud config set project <GCP_PROJECT>
gcloud sql instances list   # взять NAME инстанса

CONN=$(gcloud sql instances describe <INSTANCE_NAME> --format='value(connectionName)')
cloud-sql-proxy --port 5433 "$CONN"   # оставить работать в отдельном терминале
```

### 2.3. Пересоздать базу

Импортируем в чистую базу — так не остаётся хвостов от предыдущей схемы.

```bash
export PGPASSWORD='<пароль postgres в Cloud SQL>'

psql -h 127.0.0.1 -p 5433 -U postgres -d postgres \
  -c "DROP DATABASE IF EXISTS <TEST_DB> WITH (FORCE);" \
  -c "CREATE DATABASE <TEST_DB> OWNER <TEST_DB_USER>;"
```

`WITH (FORCE)` сбрасывает оставшиеся коннекты. Если бэкенд остановлен, их быть
не должно.

### 2.4. Восстановить дамп

```bash
export PGPASSWORD='<пароль TEST_DB_USER>'

pg_restore -h 127.0.0.1 -p 5433 -U <TEST_DB_USER> -d <TEST_DB> \
  --no-owner --no-privileges \
  --exit-on-error \
  dfm-dev-*.dump
```

Восстанавливаем под пользователем приложения (`TEST_DB_USER`) — тогда все
объекты сразу принадлежат ему и с правами возиться не нужно.

### 2.5. Очистить исторические данные

```bash
psql -h 127.0.0.1 -p 5433 -U <TEST_DB_USER> -d <TEST_DB> \
  -f scripts/db-sync/cleanup-after-import.sql
```

Скрипт в конце печатает контрольную таблицу: справочные таблицы должны быть
непустыми, все `(=0)` — нулевыми.

### 2.6. Запустить бэкенд и проверить

```bash
kubectl scale deployment <BACKEND_DEPLOYMENT> -n <NAMESPACE> --replicas=1
kubectl logs -n <NAMESPACE> deploy/<BACKEND_DEPLOYMENT> -f
```

В логах старта: Flyway — `Schema ... is up to date. No migration necessary`,
без ошибок валидации.

Функциональная проверка:

1. Зайти на `https://test.dfm.bitbi.io` под существующим пользователем
   (`pliss.boris@gmail.com`) — дашборд открывается, 403/500 нет.
2. Пройти device-флоу: Approve & Create Site → сайт создаётся.

---

## 3. Альтернатива: импорт через GCS (`gcloud sql import`)

Если прямой доступ к Cloud SQL через proxy не подходит (например, импорт делает
CI), Cloud SQL умеет импортировать **plain-SQL** дамп из бакета GCS:

```bash
# экспорт в plain-формате
pg_dump "host=<RDS_ENDPOINT> ... sslmode=require" \
  --format=plain --no-owner --no-privileges --file=dfm-dev.sql

gsutil mb -l <REGION> gs://<BUCKET>          # если бакета ещё нет
gsutil cp dfm-dev.sql gs://<BUCKET>/

# сервис-аккаунту инстанса Cloud SQL нужен доступ на чтение объекта
SA=$(gcloud sql instances describe <INSTANCE_NAME> --format='value(serviceAccountEmailAddress)')
gsutil iam ch "serviceAccount:${SA}:roles/storage.objectViewer" gs://<BUCKET>

gcloud sql import sql <INSTANCE_NAME> gs://<BUCKET>/dfm-dev.sql --database=<TEST_DB>
```

Базу перед этим так же пересоздать (шаг 2.3), бэкенд остановить (2.1), очистку
(2.5) и проверку (2.6) выполнить после. Custom-формат `gcloud sql import` не
принимает — только plain SQL.

---

## 4. Что осознанно НЕ переносится

| Что | Почему |
|---|---|
| Файлы S3 (батчи, чекпоинты, delta-Parquet, SQL) | Лежат в бакете AWS; ссылки на них удаляются cleanup-скриптом. CDC-клиенты на test начнут с нового baseline. |
| `refresh_tokens`, `device_authorizations` | Секреты dev-окружения; устройства должны заново пройти device-флоу на test. |
| История батчей, ошибки, аудит плагинов | Историческая телеметрия dev, на test не нужна. |

`account_plugins` и `plugin_configs` (включая BCrypt-хэши API-ключей плагинов)
переносятся: активация Bit BI остаётся в силе, но `baseline_batch_id` обнулён —
после первого батча на test плагину нужна **реинициализация** (re-baseline),
см. `docs/reinit.md`.
