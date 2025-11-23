# Environment Variables Reference

Complete reference for all environment variables used in DataForge Middleware Docker images.

## Table of Contents

- [Backend Environment Variables](#backend-environment-variables)
- [Frontend Environment Variables](#frontend-environment-variables)
- [Quick Reference Tables](#quick-reference-tables)
- [Environment-Specific Examples](#environment-specific-examples)

---

## Backend Environment Variables

### Spring Boot Configuration

#### `SPRING_PROFILES_ACTIVE`

**Description:** Active Spring profile
**Required:** Yes
**Type:** String
**Default:** None
**Values:**
- `dev` - Development mode (human-readable logs, debug enabled)
- `test` - Testing mode (used by CI/CD)
- `prod` - Production mode (JSON logs, optimizations enabled)

**Example:**
```bash
SPRING_PROFILES_ACTIVE=prod
```

---

### Database Configuration

#### `SPRING_DATASOURCE_URL`

**Description:** PostgreSQL JDBC connection URL
**Required:** Yes
**Type:** String (JDBC URL)
**Default:** None
**Format:** `jdbc:postgresql://<host>:<port>/<database>`

**Examples:**
```bash
# Local development
SPRING_DATASOURCE_URL=jdbc:postgresql://localhost:5432/dataforge

# Docker Compose
SPRING_DATASOURCE_URL=jdbc:postgresql://postgres:5432/dataforge

# Kubernetes
SPRING_DATASOURCE_URL=jdbc:postgresql://postgres-service.dataforge.svc.cluster.local:5432/dataforge

# External database
SPRING_DATASOURCE_URL=jdbc:postgresql://db.example.com:5432/dataforge_prod
```

#### `SPRING_DATASOURCE_USERNAME`

**Description:** PostgreSQL username
**Required:** Yes
**Type:** String
**Default:** None
**Security:** Store in Kubernetes Secret or environment variable

**Example:**
```bash
SPRING_DATASOURCE_USERNAME=dataforge
```

#### `SPRING_DATASOURCE_PASSWORD`

**Description:** PostgreSQL password
**Required:** Yes
**Type:** String
**Default:** None
**Security:** **CRITICAL** - Must be stored securely (Kubernetes Secret, AWS Secrets Manager, etc.)

**Example:**
```bash
SPRING_DATASOURCE_PASSWORD=secure_random_password_here
```

---

### Redis Configuration

#### `SPRING_DATA_REDIS_HOST`

**Description:** Redis server hostname
**Required:** Yes
**Type:** String (hostname/IP)
**Default:** None

**Examples:**
```bash
# Local development
SPRING_DATA_REDIS_HOST=localhost

# Docker Compose
SPRING_DATA_REDIS_HOST=redis

# Kubernetes
SPRING_DATA_REDIS_HOST=redis-service.dataforge.svc.cluster.local
```

#### `SPRING_DATA_REDIS_PORT`

**Description:** Redis server port
**Required:** No
**Type:** Integer
**Default:** `6379`

**Example:**
```bash
SPRING_DATA_REDIS_PORT=6379
```

---

### AWS S3 Configuration

#### `S3_ENDPOINT`

**Description:** S3 endpoint URL
**Required:** Yes
**Type:** String (URL)
**Default:** None

**Examples:**
```bash
# AWS S3 (us-east-1)
S3_ENDPOINT=https://s3.amazonaws.com

# AWS S3 (specific region)
S3_ENDPOINT=https://s3.eu-west-1.amazonaws.com

# LocalStack (development)
S3_ENDPOINT=http://localstack:4566

# MinIO (self-hosted)
S3_ENDPOINT=https://minio.example.com
```

#### `S3_BUCKET_NAME`

**Description:** S3 bucket name for file uploads
**Required:** Yes
**Type:** String
**Default:** None

**Examples:**
```bash
# Development
S3_BUCKET_NAME=dataforge-uploads-dev

# Production
S3_BUCKET_NAME=dataforge-uploads-prod
```

#### `S3_REGION`

**Description:** AWS S3 region
**Required:** Yes
**Type:** String (AWS region code)
**Default:** None

**Examples:**
```bash
S3_REGION=us-east-1
S3_REGION=eu-west-1
S3_REGION=ap-southeast-1
```

#### `S3_ACCESS_KEY`

**Description:** AWS access key ID
**Required:** Yes
**Type:** String
**Default:** None
**Security:** **CRITICAL** - Store securely

**Example:**
```bash
S3_ACCESS_KEY=AKIAIOSFODNN7EXAMPLE
```

#### `S3_SECRET_KEY`

**Description:** AWS secret access key
**Required:** Yes
**Type:** String
**Default:** None
**Security:** **CRITICAL** - Store securely

**Example:**
```bash
S3_SECRET_KEY=wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY
```

---

### Auth0 Configuration (Backend)

#### `AUTH0_DOMAIN`

**Description:** Auth0 tenant domain
**Required:** Yes
**Type:** String (domain)
**Default:** None
**Format:** `<tenant>.us.auth0.com` or `<tenant>.eu.auth0.com`

**Examples:**
```bash
# Development tenant
AUTH0_DOMAIN=dev-abc123.us.auth0.com

# Production tenant
AUTH0_DOMAIN=prod-xyz789.eu.auth0.com
```

#### `AUTH0_AUDIENCE`

**Description:** Auth0 API audience/identifier
**Required:** Yes
**Type:** String (URL)
**Default:** None
**Note:** Must match API identifier configured in Auth0 Dashboard

**Example:**
```bash
AUTH0_AUDIENCE=https://api.dataforge.com
```

#### `AUTH0_CLIENT_ID`

**Description:** Auth0 Machine-to-Machine (M2M) application client ID
**Required:** Yes (for user management)
**Type:** String
**Default:** None
**Note:** This is the **M2M** client ID, NOT the SPA client ID

**Example:**
```bash
AUTH0_CLIENT_ID=m2m_client_id_here_32chars
```

#### `AUTH0_CLIENT_SECRET`

**Description:** Auth0 M2M application client secret
**Required:** Yes (for user management)
**Type:** String
**Default:** None
**Security:** **CRITICAL** - Store securely

**Example:**
```bash
AUTH0_CLIENT_SECRET=m2m_secret_here_64chars_long
```

---

### Optional Backend Variables

#### `SERVER_PORT`

**Description:** Backend server port
**Required:** No
**Type:** Integer
**Default:** `8080`

**Example:**
```bash
SERVER_PORT=8080
```

#### `LOGGING_LEVEL_ROOT`

**Description:** Root logging level
**Required:** No
**Type:** String
**Default:** `INFO` (prod), `DEBUG` (dev)
**Values:** `TRACE`, `DEBUG`, `INFO`, `WARN`, `ERROR`

**Example:**
```bash
LOGGING_LEVEL_ROOT=INFO
```

#### `JAVA_OPTS`

**Description:** JVM options
**Required:** No
**Type:** String
**Default:** Optimized for container (set in Dockerfile)

**Examples:**
```bash
# Increase heap size
JAVA_OPTS=-Xmx2g -Xms1g

# Enable remote debugging
JAVA_OPTS=-agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=*:5005
```

---

## Frontend Environment Variables

### Auth0 Configuration (Frontend)

#### `VITE_AUTH0_DOMAIN`

**Description:** Auth0 tenant domain (for browser authentication)
**Required:** Yes
**Type:** String (domain)
**Default:** None
**Note:** Must match backend `AUTH0_DOMAIN`

**Examples:**
```bash
VITE_AUTH0_DOMAIN=dev-abc123.us.auth0.com
VITE_AUTH0_DOMAIN=prod-xyz789.eu.auth0.com
```

#### `VITE_AUTH0_CLIENT_ID`

**Description:** Auth0 Single Page Application (SPA) client ID
**Required:** Yes
**Type:** String
**Default:** None
**Note:** This is the **SPA** client ID, NOT the M2M client ID

**Example:**
```bash
VITE_AUTH0_CLIENT_ID=spa_client_id_here_32chars
```

#### `VITE_AUTH0_AUDIENCE`

**Description:** Auth0 API audience/identifier
**Required:** Yes
**Type:** String (URL)
**Default:** None
**Note:** Must match backend `AUTH0_AUDIENCE`

**Example:**
```bash
VITE_AUTH0_AUDIENCE=https://api.dataforge.com
```

---

### API Configuration (Frontend)

#### `VITE_API_BASE_URL`

**Description:** API base URL for browser requests
**Required:** No
**Type:** String (path or URL)
**Default:** `/api`
**Note:** Use relative path `/api` for NGINX reverse proxy setup

**Examples:**
```bash
# Reverse proxy (recommended)
VITE_API_BASE_URL=/api

# Direct backend URL (requires CORS configuration)
VITE_API_BASE_URL=http://localhost:8080

# Production
VITE_API_BASE_URL=/api
```

---

### NGINX Configuration (Frontend)

#### `BACKEND_URL`

**Description:** Backend URL for NGINX reverse proxy
**Required:** No
**Type:** String (URL)
**Default:** `http://backend:8080`
**Note:** This is where NGINX forwards `/api/*` requests

**Examples:**
```bash
# Docker Compose (service name)
BACKEND_URL=http://backend:8080

# Kubernetes (service DNS)
BACKEND_URL=http://dataforge-backend-svc.dataforge.svc.cluster.local:8080

# External backend
BACKEND_URL=https://api.dataforge.com
```

---

## Quick Reference Tables

### Backend - Required Variables

| Variable | Example | Security Level |
|----------|---------|---------------|
| `SPRING_PROFILES_ACTIVE` | `prod` | Low |
| `SPRING_DATASOURCE_URL` | `jdbc:postgresql://postgres:5432/dataforge` | Medium |
| `SPRING_DATASOURCE_USERNAME` | `dataforge` | Medium |
| `SPRING_DATASOURCE_PASSWORD` | `secure_password` | **HIGH** |
| `SPRING_DATA_REDIS_HOST` | `redis` | Low |
| `S3_ENDPOINT` | `https://s3.amazonaws.com` | Low |
| `S3_BUCKET_NAME` | `dataforge-uploads` | Low |
| `S3_REGION` | `us-east-1` | Low |
| `S3_ACCESS_KEY` | `AKIA...` | **HIGH** |
| `S3_SECRET_KEY` | `secret...` | **HIGH** |
| `AUTH0_DOMAIN` | `your-tenant.us.auth0.com` | Low |
| `AUTH0_AUDIENCE` | `https://api.dataforge.com` | Low |
| `AUTH0_CLIENT_ID` | `m2m-client-id` | Medium |
| `AUTH0_CLIENT_SECRET` | `m2m-secret` | **HIGH** |

### Frontend - Required Variables

| Variable | Example | Security Level |
|----------|---------|---------------|
| `VITE_AUTH0_DOMAIN` | `your-tenant.us.auth0.com` | Low |
| `VITE_AUTH0_CLIENT_ID` | `spa-client-id` | Medium |
| `VITE_AUTH0_AUDIENCE` | `https://api.dataforge.com` | Low |
| `VITE_API_BASE_URL` | `/api` | Low |
| `BACKEND_URL` | `http://backend:8080` | Low |

---

## Environment-Specific Examples

### Local Development

**Backend (`.env`):**
```bash
SPRING_PROFILES_ACTIVE=dev
SPRING_DATASOURCE_URL=jdbc:postgresql://localhost:5432/dataforge_dev
SPRING_DATASOURCE_USERNAME=dataforge
SPRING_DATASOURCE_PASSWORD=dev_password
SPRING_DATA_REDIS_HOST=localhost
SPRING_DATA_REDIS_PORT=6379
S3_ENDPOINT=http://localhost:4566
S3_BUCKET_NAME=dataforge-dev-bucket
S3_REGION=us-east-1
S3_ACCESS_KEY=test
S3_SECRET_KEY=test
AUTH0_DOMAIN=dev-abc123.us.auth0.com
AUTH0_AUDIENCE=https://api.dataforge.dev
AUTH0_CLIENT_ID=dev-m2m-client-id
AUTH0_CLIENT_SECRET=dev-m2m-secret
```

**Frontend (`.env.local`):**
```bash
VITE_AUTH0_DOMAIN=dev-abc123.us.auth0.com
VITE_AUTH0_CLIENT_ID=dev-spa-client-id
VITE_AUTH0_AUDIENCE=https://api.dataforge.dev
VITE_API_BASE_URL=http://localhost:8080
```

---

### Docker Compose

**`.env` file:**
```bash
# AWS
AWS_ACCESS_KEY_ID=AKIA...
AWS_SECRET_ACCESS_KEY=secret...

# Database
POSTGRES_PASSWORD=secure_docker_password

# Auth0
AUTH0_DOMAIN=dev-abc123.us.auth0.com
AUTH0_SPA_CLIENT_ID=spa-client-id
AUTH0_M2M_CLIENT_ID=m2m-client-id
AUTH0_M2M_CLIENT_SECRET=m2m-secret
```

**docker-compose.yml uses:**
```yaml
backend:
  environment:
    SPRING_DATASOURCE_PASSWORD: ${POSTGRES_PASSWORD}
    S3_ACCESS_KEY: ${AWS_ACCESS_KEY_ID}
    S3_SECRET_KEY: ${AWS_SECRET_ACCESS_KEY}
    AUTH0_DOMAIN: ${AUTH0_DOMAIN}
    AUTH0_CLIENT_ID: ${AUTH0_M2M_CLIENT_ID}
    AUTH0_CLIENT_SECRET: ${AUTH0_M2M_CLIENT_SECRET}

frontend:
  environment:
    VITE_AUTH0_DOMAIN: ${AUTH0_DOMAIN}
    VITE_AUTH0_CLIENT_ID: ${AUTH0_SPA_CLIENT_ID}
    BACKEND_URL: http://backend:8080
```

---

### Kubernetes Staging

**ConfigMap (non-sensitive):**
```yaml
SPRING_PROFILES_ACTIVE: "prod"
SPRING_DATASOURCE_URL: "jdbc:postgresql://postgres-staging:5432/dataforge_staging"
SPRING_DATA_REDIS_HOST: "redis-staging"
S3_ENDPOINT: "https://s3.amazonaws.com"
S3_BUCKET_NAME: "dataforge-uploads-staging"
S3_REGION: "us-east-1"
AUTH0_DOMAIN: "staging.us.auth0.com"
AUTH0_AUDIENCE: "https://api-staging.dataforge.com"
VITE_AUTH0_DOMAIN: "staging.us.auth0.com"
VITE_AUTH0_AUDIENCE: "https://api-staging.dataforge.com"
VITE_API_BASE_URL: "/api"
BACKEND_URL: "http://dataforge-backend-svc:8080"
```

**Secret (sensitive):**
```yaml
SPRING_DATASOURCE_USERNAME: "dataforge_staging"
SPRING_DATASOURCE_PASSWORD: "staging_db_password"
S3_ACCESS_KEY: "AKIA..."
S3_SECRET_KEY: "secret..."
AUTH0_CLIENT_ID: "staging-m2m-client-id"
AUTH0_CLIENT_SECRET: "staging-m2m-secret"
VITE_AUTH0_CLIENT_ID: "staging-spa-client-id"
```

---

### Kubernetes Production

**ConfigMap (non-sensitive):**
```yaml
SPRING_PROFILES_ACTIVE: "prod"
SPRING_DATASOURCE_URL: "jdbc:postgresql://postgres-prod.rds.amazonaws.com:5432/dataforge_prod"
SPRING_DATA_REDIS_HOST: "redis-prod.cache.amazonaws.com"
S3_ENDPOINT: "https://s3.amazonaws.com"
S3_BUCKET_NAME: "dataforge-uploads-production"
S3_REGION: "us-east-1"
AUTH0_DOMAIN: "prod.us.auth0.com"
AUTH0_AUDIENCE: "https://api.dataforge.com"
VITE_AUTH0_DOMAIN: "prod.us.auth0.com"
VITE_AUTH0_AUDIENCE: "https://api.dataforge.com"
VITE_API_BASE_URL: "/api"
BACKEND_URL: "http://dataforge-backend-svc:8080"
```

**Secret (from AWS Secrets Manager):**
```yaml
# Managed by external-secrets operator
# References AWS Secrets Manager secret: dataforge/production
SPRING_DATASOURCE_USERNAME: <from AWS>
SPRING_DATASOURCE_PASSWORD: <from AWS>
S3_ACCESS_KEY: <from AWS>
S3_SECRET_KEY: <from AWS>
AUTH0_CLIENT_ID: <from AWS>
AUTH0_CLIENT_SECRET: <from AWS>
VITE_AUTH0_CLIENT_ID: <from AWS>
```

---

## Security Best Practices

### High-Security Variables

The following variables contain sensitive credentials and **MUST** be protected:

1. `SPRING_DATASOURCE_PASSWORD` - Database password
2. `S3_SECRET_KEY` - AWS secret key
3. `AUTH0_CLIENT_SECRET` - Auth0 M2M secret

### Recommendations

- **Kubernetes:** Use Secrets, not ConfigMaps
- **AWS:** Use AWS Secrets Manager with IAM roles
- **Docker Compose:** Use `.env` file (add to `.gitignore`)
- **CI/CD:** Use GitHub Secrets / GitLab CI Variables
- **Never:** Commit secrets to version control
- **Rotate:** Rotate secrets regularly (quarterly)

### Encryption at Rest

- Enable encryption for Kubernetes Secrets (ETCD encryption)
- Use AWS Secrets Manager with KMS encryption
- Use HashiCorp Vault for enterprise deployments

---

## Validation

### Backend Startup Validation

Backend validates all required variables on startup. Missing variables cause immediate failure:

```
***************************
APPLICATION FAILED TO START
***************************

Description:

Binding to target org.springframework.boot.autoconfigure.jdbc.DataSourceProperties@... failed:

    Property: spring.datasource.password
    Value: null
    Reason: must not be null
```

### Frontend Runtime Validation

Frontend validates Auth0 variables at runtime:

```javascript
if (!domain || !clientId || !audience) {
  throw new Error(
    'Auth0 configuration missing. Please set VITE_AUTH0_DOMAIN, VITE_AUTH0_CLIENT_ID, and VITE_AUTH0_AUDIENCE'
  );
}
```

### Manual Validation

Check environment variables in running container:

```bash
# Backend
docker exec dataforge-backend env | grep SPRING

# Frontend
docker exec dataforge-frontend env | grep VITE
docker exec dataforge-frontend cat /usr/share/nginx/html/env-config.js
```

---

## Additional Resources

- [Docker Deployment Guide](./docker-deployment.md)
- [Auth0 Configuration Guide](../auth0-config.md)
- [Security Best Practices](https://spring.io/guides/gs/securing-web/)

---

**Last Updated:** 2025-11-23
**Version:** 1.0.0
