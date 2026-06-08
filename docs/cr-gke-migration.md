# CR: Міграція data-forge-middleware з AWS у GKE

**Feature:** `022-gke-migration` · **Гілка:** `feature/022-gke-migration` · **Статус:** in progress

## Передумова

`data-forge-middleware` (forge/DFM) задеплоєний у AWS ECS Fargate (`us-east-1`): backend +
frontend (nginx), RDS PostgreSQL, зовнішній Redis, бакет S3 `dfm-prod-uploads`, Auth0. Сервіс
споживається проектом **bitbi (DynamicVisualizer)**, який уже працює в **GKE** за моделлю:
три окремі GCP-проекти/кластери — `bitbi-dev` / `bitbi-stage` / `bitbi-production`, неймспейс
застосунку, kustomize overlays, Terraform-інфра, GitHub Actions + Workload Identity Federation.

## Мета

Перенести forge у ту саму модель: три кластери, неймспейс **`forge`**, гілка→кластер
(`develop`→dev, `stage`→stage, `main`→prod). Два етапи:

1. **Dev-перенос** у `forge` неймспейс `dev-bitbi-cluster`, тестування, перепідключення bitbi-dev.
2. **Prod-cutover** у `prod-bitbi-cluster`.

## Узгоджені рішення

| Тема | Рішення |
|---|---|
| Сховище об'єктів | Міграція на **GCS** через S3-сумісний XML API (зміна коду S3-клієнта) |
| БД/кеш (dev) | Переюзати Cloud SQL `dev-bitbi-db` (нова БД `dfm`) + **Redis in-cluster** |
| Дані | Скопіювати прод-дані (pg_dump RDS→Cloud SQL, об'єкти S3→GCS) |
| Автоматизація | Повний конвеєр: Terraform + GitHub Actions + WIF |
| Ingress (dev) | **Internal-only** (ClusterIP); bitbi → `forge-backend.forge.svc.cluster.local:8080`; адмінка через port-forward |
| Terraform state | Спільний `bitbi-terraform-state`, префікс `forge/environments/{env}` |

## Зміни

### Етап 1 — підтримка GCS (S3-сумісність) ✅

Prod-біни S3 зроблено конфігурованими через property — той самий `prod`-профіль працює і з
AWS S3, і з GCS, відрізняючись лише env. Без зміни SDK (2.28.11 ще не має дефолтних flexible-checksum);
GCS-режим вмикається через legacy `serviceConfiguration`.

- `upload/infrastructure/S3Configuration.java` — нові property (`s3.endpoint`, `s3.access-key`,
  `s3.secret-key`, `s3.path-style-access-enabled`, `s3.checksum.enabled`); хелпери
  `resolveCredentials` (static HMAC ↔ IAM default), `shouldOverrideEndpoint`, `serviceConfiguration`
  (для GCS: `pathStyleAccessEnabled(true)`, `chunkedEncodingEnabled(false)`, `checksumValidationEnabled(false)`).
- `resources/application.yml` — `path-style-access-enabled` та `checksum.enabled` стали env-керованими.
- Тест `upload/infrastructure/S3ConfigurationTest.java` — покриває вибір кредів, endpoint-override,
  service-config toggles.

**GCS env (GKE):** `AWS_S3_ENDPOINT=https://storage.googleapis.com`, `S3_PATH_STYLE_ACCESS=true`,
`S3_CHECKSUM_ENABLED=false`, `AWS_S3_REGION=auto`, `AWS_ACCESS_KEY_ID/SECRET=<GCS HMAC>`,
`AWS_S3_BUCKET_NAME=dfm-dev-uploads`. AWS-режим: лишити дефолти (endpoint `s3.amazonaws.com`,
checksum on, path-style off).

### Етап 2 — інфра (Terraform), k8s (kustomize), CI/CD (GitHub Actions + WIF)

Див. план переносу; додаються каталоги `infra/`, `k8s/`, воркфлоу `app-deploy.yml`/`infra-deploy.yml`.

### Етап 3 — міграція даних і перепідключення bitbi-dev

pg_dump RDS→Cloud SQL/`dfm`, S3→GCS rsync; перемкнення `DFM_*` у bitbi-dev на новий forge.

## Ризики

- **Checksum/chunked-encoding** — без вимкнення GCS відкидає PutObject (вирішено `S3_CHECKSUM_ENABLED=false`).
- **Path-style** обов'язковий для GCS; presigner мусить мати той самий endpoint/creds (інакше 403).
- Перевірка проти реального GCS — лише на деплої (LocalStack-тести не покривають GCS-специфіку).
