# Docker Deployment Guide

## Table of Contents

- [Overview](#overview)
- [Docker Images](#docker-images)
- [Backend Deployment](#backend-deployment)
- [Frontend Deployment](#frontend-deployment)
- [Docker Compose Setup](#docker-compose-setup)
- [Kubernetes Deployment](#kubernetes-deployment)
- [Production Checklist](#production-checklist)
- [Troubleshooting](#troubleshooting)

---

## Overview

DataForge Middleware consists of two Docker images:

1. **Backend** (`ghcr.io/quantum-soft-dev/data-forge-middleware`) - Spring Boot API
2. **Frontend** (`ghcr.io/quantum-soft-dev/data-forge-middleware-frontend`) - React SPA with NGINX

Both images are built automatically via GitHub Actions CI/CD pipeline and pushed to GitHub Container Registry (GHCR).

### Architecture

```
┌─────────────┐      ┌──────────────┐      ┌────────────┐
│   Browser   │─────▶│   Frontend   │─────▶│   Backend  │
│             │      │ NGINX + React│      │ Spring Boot│
└─────────────┘      └──────────────┘      └────────────┘
                            │                      │
                            │                      ├─▶ PostgreSQL
                            └─▶ /api/*             ├─▶ Redis
                                                   └─▶ AWS S3
```

**Flow:**
1. User accesses frontend (port 80)
2. React app loads, uses `/api` for backend calls
3. NGINX reverse proxy forwards `/api/*` to backend
4. Backend processes requests and returns data

---

## Docker Images

### Image Naming Convention

**Backend:**
- Repository: `ghcr.io/quantum-soft-dev/data-forge-middleware`
- Tags: `main`, `develop`, `release`, `pr-123`, `main-a1b2c3d`

**Frontend:**
- Repository: `ghcr.io/quantum-soft-dev/data-forge-middleware-frontend`
- Tags: Same as backend (synchronized versioning)

### Pulling Images

```bash
# Pull backend
docker pull ghcr.io/quantum-soft-dev/data-forge-middleware:main

# Pull frontend
docker pull ghcr.io/quantum-soft-dev/data-forge-middleware-frontend:main
```

---

## Backend Deployment

### Required Environment Variables

| Variable | Description | Example |
|----------|-------------|---------|
| `SPRING_PROFILES_ACTIVE` | Active Spring profile | `prod` |
| `SPRING_DATASOURCE_URL` | PostgreSQL JDBC URL | `jdbc:postgresql://postgres:5432/dataforge` |
| `SPRING_DATASOURCE_USERNAME` | PostgreSQL username | `dataforge` |
| `SPRING_DATASOURCE_PASSWORD` | PostgreSQL password | `secure_password` |
| `SPRING_DATA_REDIS_HOST` | Redis host | `redis` |
| `SPRING_DATA_REDIS_PORT` | Redis port | `6379` |
| `S3_ENDPOINT` | S3 endpoint URL | `https://s3.amazonaws.com` |
| `S3_BUCKET_NAME` | S3 bucket name | `dataforge-uploads` |
| `S3_REGION` | S3 region | `us-east-1` |
| `S3_ACCESS_KEY` | AWS access key | `AKIA...` |
| `S3_SECRET_KEY` | AWS secret key | `secret...` |
| `AUTH0_DOMAIN` | Auth0 tenant domain | `your-tenant.us.auth0.com` |
| `AUTH0_AUDIENCE` | Auth0 API audience | `https://api.dataforge.com` |
| `AUTH0_CLIENT_ID` | Auth0 M2M client ID | `m2m-client-id` |
| `AUTH0_CLIENT_SECRET` | Auth0 M2M client secret | `m2m-secret` |

See [environment-variables.md](./environment-variables.md) for complete reference.

### Basic Docker Run

```bash
docker run -d \
  --name dataforge-backend \
  -p 8080:8080 \
  -e SPRING_PROFILES_ACTIVE=prod \
  -e SPRING_DATASOURCE_URL=jdbc:postgresql://postgres-host:5432/dataforge \
  -e SPRING_DATASOURCE_USERNAME=dataforge \
  -e SPRING_DATASOURCE_PASSWORD=secure_password \
  -e SPRING_DATA_REDIS_HOST=redis-host \
  -e SPRING_DATA_REDIS_PORT=6379 \
  -e S3_ENDPOINT=https://s3.amazonaws.com \
  -e S3_BUCKET_NAME=dataforge-uploads \
  -e S3_REGION=us-east-1 \
  -e S3_ACCESS_KEY=AKIA... \
  -e S3_SECRET_KEY=secret... \
  -e AUTH0_DOMAIN=your-tenant.us.auth0.com \
  -e AUTH0_AUDIENCE=https://api.dataforge.com \
  -e AUTH0_CLIENT_ID=m2m-client-id \
  -e AUTH0_CLIENT_SECRET=m2m-secret \
  ghcr.io/quantum-soft-dev/data-forge-middleware:main
```

### Health Check

```bash
# Check health endpoint
curl http://localhost:8080/actuator/health

# Expected response
{
  "status": "UP",
  "components": {
    "db": {"status": "UP"},
    "redis": {"status": "UP"},
    "s3": {"status": "UP"}
  }
}
```

### Logs

```bash
# View logs
docker logs -f dataforge-backend

# Structured JSON logs in production
docker logs dataforge-backend | jq '.timestamp,.level,.message'
```

---

## Frontend Deployment

### Required Environment Variables

| Variable | Description | Example | Default |
|----------|-------------|---------|---------|
| `VITE_AUTH0_DOMAIN` | Auth0 tenant domain | `your-tenant.us.auth0.com` | - |
| `VITE_AUTH0_CLIENT_ID` | Auth0 SPA client ID | `spa-client-id` | - |
| `VITE_AUTH0_AUDIENCE` | Auth0 API audience | `https://api.dataforge.com` | - |
| `VITE_API_BASE_URL` | API base URL (browser) | `/api` | `/api` |
| `BACKEND_URL` | Backend URL (NGINX proxy) | `http://backend:8080` | `http://backend:8080` |

**Important:**
- `VITE_AUTH0_*`: Used by React app for Auth0 authentication
- `VITE_API_BASE_URL`: Static path `/api` for browser requests
- `BACKEND_URL`: Dynamic URL where NGINX proxies `/api/*` requests

### Basic Docker Run

```bash
docker run -d \
  --name dataforge-frontend \
  -p 80:80 \
  -e VITE_AUTH0_DOMAIN=your-tenant.us.auth0.com \
  -e VITE_AUTH0_CLIENT_ID=spa-client-id \
  -e VITE_AUTH0_AUDIENCE=https://api.dataforge.com \
  -e VITE_API_BASE_URL=/api \
  -e BACKEND_URL=http://dataforge-backend:8080 \
  ghcr.io/quantum-soft-dev/data-forge-middleware-frontend:main
```

### Health Check

```bash
# Check health endpoint
curl http://localhost/health

# Expected response
OK
```

### Testing Frontend

```bash
# Open in browser
open http://localhost

# Check NGINX logs
docker logs -f dataforge-frontend

# Check Auth0 configuration (browser console)
console.log(window._env_)
# Should show: { VITE_AUTH0_DOMAIN: "...", VITE_AUTH0_CLIENT_ID: "...", ... }
```

---

## Docker Compose Setup

### Full Stack Example

Create `docker-compose.yml`:

```yaml
version: '3.8'

services:
  # PostgreSQL Database
  postgres:
    image: postgres:16-alpine
    container_name: dataforge-postgres
    environment:
      POSTGRES_DB: dataforge
      POSTGRES_USER: dataforge
      POSTGRES_PASSWORD: secure_password
    ports:
      - "5432:5432"
    volumes:
      - postgres-data:/var/lib/postgresql/data
    healthcheck:
      test: ["CMD-SHELL", "pg_isready -U dataforge"]
      interval: 10s
      timeout: 5s
      retries: 5

  # Redis Cache
  redis:
    image: redis:7-alpine
    container_name: dataforge-redis
    ports:
      - "6379:6379"
    healthcheck:
      test: ["CMD", "redis-cli", "ping"]
      interval: 10s
      timeout: 5s
      retries: 5

  # Backend API
  backend:
    image: ghcr.io/quantum-soft-dev/data-forge-middleware:main
    container_name: dataforge-backend
    depends_on:
      postgres:
        condition: service_healthy
      redis:
        condition: service_healthy
    environment:
      SPRING_PROFILES_ACTIVE: prod
      SPRING_DATASOURCE_URL: jdbc:postgresql://postgres:5432/dataforge
      SPRING_DATASOURCE_USERNAME: dataforge
      SPRING_DATASOURCE_PASSWORD: secure_password
      SPRING_DATA_REDIS_HOST: redis
      SPRING_DATA_REDIS_PORT: 6379
      S3_ENDPOINT: https://s3.amazonaws.com
      S3_BUCKET_NAME: dataforge-uploads
      S3_REGION: us-east-1
      S3_ACCESS_KEY: ${AWS_ACCESS_KEY_ID}
      S3_SECRET_KEY: ${AWS_SECRET_ACCESS_KEY}
      AUTH0_DOMAIN: ${AUTH0_DOMAIN}
      AUTH0_AUDIENCE: https://api.dataforge.com
      AUTH0_CLIENT_ID: ${AUTH0_M2M_CLIENT_ID}
      AUTH0_CLIENT_SECRET: ${AUTH0_M2M_CLIENT_SECRET}
    ports:
      - "8080:8080"
    healthcheck:
      test: ["CMD", "wget", "--quiet", "--tries=1", "--spider", "http://localhost:8080/actuator/health"]
      interval: 30s
      timeout: 10s
      retries: 3

  # Frontend UI
  frontend:
    image: ghcr.io/quantum-soft-dev/data-forge-middleware-frontend:main
    container_name: dataforge-frontend
    depends_on:
      - backend
    environment:
      VITE_AUTH0_DOMAIN: ${AUTH0_DOMAIN}
      VITE_AUTH0_CLIENT_ID: ${AUTH0_SPA_CLIENT_ID}
      VITE_AUTH0_AUDIENCE: https://api.dataforge.com
      VITE_API_BASE_URL: /api
      BACKEND_URL: http://backend:8080
    ports:
      - "80:80"
    healthcheck:
      test: ["CMD", "wget", "--quiet", "--tries=1", "--spider", "http://localhost/health"]
      interval: 30s
      timeout: 10s
      retries: 3

volumes:
  postgres-data:
```

### Environment File (.env)

Create `.env` file for Docker Compose:

```bash
# AWS Credentials
AWS_ACCESS_KEY_ID=AKIA...
AWS_SECRET_ACCESS_KEY=secret...

# Auth0 Configuration
AUTH0_DOMAIN=your-tenant.us.auth0.com
AUTH0_SPA_CLIENT_ID=spa-client-id-here
AUTH0_M2M_CLIENT_ID=m2m-client-id-here
AUTH0_M2M_CLIENT_SECRET=m2m-secret-here
```

### Running the Stack

```bash
# Start all services
docker-compose up -d

# Check status
docker-compose ps

# View logs
docker-compose logs -f

# Stop all services
docker-compose down

# Stop and remove volumes
docker-compose down -v
```

---

## Kubernetes Deployment

### Namespace

```yaml
apiVersion: v1
kind: Namespace
metadata:
  name: dataforge
```

### ConfigMap (Non-Sensitive Config)

```yaml
apiVersion: v1
kind: ConfigMap
metadata:
  name: dataforge-config
  namespace: dataforge
data:
  # Backend
  SPRING_PROFILES_ACTIVE: "prod"
  SPRING_DATASOURCE_URL: "jdbc:postgresql://postgres-service:5432/dataforge"
  SPRING_DATA_REDIS_HOST: "redis-service"
  SPRING_DATA_REDIS_PORT: "6379"
  S3_ENDPOINT: "https://s3.amazonaws.com"
  S3_BUCKET_NAME: "dataforge-uploads-prod"
  S3_REGION: "us-east-1"
  AUTH0_DOMAIN: "prod.us.auth0.com"
  AUTH0_AUDIENCE: "https://api.dataforge.com"

  # Frontend
  VITE_AUTH0_DOMAIN: "prod.us.auth0.com"
  VITE_AUTH0_AUDIENCE: "https://api.dataforge.com"
  VITE_API_BASE_URL: "/api"
  BACKEND_URL: "http://dataforge-backend-svc:8080"
```

### Secret (Sensitive Data)

```yaml
apiVersion: v1
kind: Secret
metadata:
  name: dataforge-secrets
  namespace: dataforge
type: Opaque
stringData:
  SPRING_DATASOURCE_USERNAME: "dataforge"
  SPRING_DATASOURCE_PASSWORD: "secure_password"
  S3_ACCESS_KEY: "AKIA..."
  S3_SECRET_KEY: "secret..."
  AUTH0_CLIENT_ID: "m2m-client-id"
  AUTH0_CLIENT_SECRET: "m2m-secret"
  VITE_AUTH0_CLIENT_ID: "spa-client-id"
```

### Backend Deployment

```yaml
apiVersion: apps/v1
kind: Deployment
metadata:
  name: dataforge-backend
  namespace: dataforge
spec:
  replicas: 3
  selector:
    matchLabels:
      app: dataforge-backend
  template:
    metadata:
      labels:
        app: dataforge-backend
    spec:
      containers:
      - name: backend
        image: ghcr.io/quantum-soft-dev/data-forge-middleware:main
        ports:
        - containerPort: 8080
        envFrom:
        - configMapRef:
            name: dataforge-config
        - secretRef:
            name: dataforge-secrets
        livenessProbe:
          httpGet:
            path: /actuator/health/liveness
            port: 8080
          initialDelaySeconds: 60
          periodSeconds: 10
        readinessProbe:
          httpGet:
            path: /actuator/health/readiness
            port: 8080
          initialDelaySeconds: 30
          periodSeconds: 5
        resources:
          requests:
            memory: "512Mi"
            cpu: "500m"
          limits:
            memory: "1Gi"
            cpu: "1000m"
---
apiVersion: v1
kind: Service
metadata:
  name: dataforge-backend-svc
  namespace: dataforge
spec:
  selector:
    app: dataforge-backend
  ports:
  - port: 8080
    targetPort: 8080
  type: ClusterIP
```

### Frontend Deployment

```yaml
apiVersion: apps/v1
kind: Deployment
metadata:
  name: dataforge-frontend
  namespace: dataforge
spec:
  replicas: 2
  selector:
    matchLabels:
      app: dataforge-frontend
  template:
    metadata:
      labels:
        app: dataforge-frontend
    spec:
      containers:
      - name: frontend
        image: ghcr.io/quantum-soft-dev/data-forge-middleware-frontend:main
        ports:
        - containerPort: 80
        envFrom:
        - configMapRef:
            name: dataforge-config
        - secretRef:
            name: dataforge-secrets
        livenessProbe:
          httpGet:
            path: /health
            port: 80
          initialDelaySeconds: 10
          periodSeconds: 10
        readinessProbe:
          httpGet:
            path: /health
            port: 80
          initialDelaySeconds: 5
          periodSeconds: 5
        resources:
          requests:
            memory: "64Mi"
            cpu: "100m"
          limits:
            memory: "128Mi"
            cpu: "200m"
---
apiVersion: v1
kind: Service
metadata:
  name: dataforge-frontend-svc
  namespace: dataforge
spec:
  selector:
    app: dataforge-frontend
  ports:
  - port: 80
    targetPort: 80
  type: LoadBalancer
```

### Ingress (Optional)

```yaml
apiVersion: networking.k8s.io/v1
kind: Ingress
metadata:
  name: dataforge-ingress
  namespace: dataforge
  annotations:
    cert-manager.io/cluster-issuer: "letsencrypt-prod"
    nginx.ingress.kubernetes.io/ssl-redirect: "true"
spec:
  ingressClassName: nginx
  tls:
  - hosts:
    - dataforge.example.com
    secretName: dataforge-tls
  rules:
  - host: dataforge.example.com
    http:
      paths:
      - path: /
        pathType: Prefix
        backend:
          service:
            name: dataforge-frontend-svc
            port:
              number: 80
```

### Deploying to Kubernetes

```bash
# Create namespace
kubectl apply -f namespace.yaml

# Create ConfigMap and Secrets
kubectl apply -f configmap.yaml
kubectl apply -f secret.yaml

# Deploy backend
kubectl apply -f backend-deployment.yaml

# Deploy frontend
kubectl apply -f frontend-deployment.yaml

# Check status
kubectl get pods -n dataforge
kubectl get svc -n dataforge

# View logs
kubectl logs -f -n dataforge deployment/dataforge-backend
kubectl logs -f -n dataforge deployment/dataforge-frontend
```

---

## Production Checklist

### Pre-Deployment

- [ ] **Auth0 Configuration**
  - [ ] SPA application created for frontend
  - [ ] M2M application created for backend
  - [ ] Custom claims configured (roles, accountId)
  - [ ] Allowed callback URLs configured
  - [ ] API audience configured

- [ ] **Database**
  - [ ] PostgreSQL 16+ running
  - [ ] Database created (`dataforge`)
  - [ ] User credentials secured
  - [ ] Connection tested

- [ ] **Redis**
  - [ ] Redis 7+ running
  - [ ] Connection tested

- [ ] **AWS S3**
  - [ ] S3 bucket created
  - [ ] IAM user with bucket access
  - [ ] Access key and secret generated
  - [ ] Bucket policy configured

- [ ] **Secrets Management**
  - [ ] All secrets stored securely (Kubernetes Secrets, AWS Secrets Manager, etc.)
  - [ ] No secrets in environment files
  - [ ] `.env` files in `.gitignore`

### Post-Deployment

- [ ] **Health Checks**
  - [ ] Backend health: `curl https://api.dataforge.com/actuator/health`
  - [ ] Frontend health: `curl https://dataforge.com/health`

- [ ] **Smoke Tests**
  - [ ] Login with Auth0 works
  - [ ] API endpoints respond correctly
  - [ ] File upload works (S3 integration)
  - [ ] Redis caching works

- [ ] **Monitoring**
  - [ ] Application logs visible
  - [ ] Metrics collected (Prometheus/Grafana)
  - [ ] Alerts configured

- [ ] **Security**
  - [ ] HTTPS enabled (SSL certificates)
  - [ ] CORS configured correctly
  - [ ] Security headers present (X-Frame-Options, etc.)

---

## Troubleshooting

### Backend Issues

#### Database Connection Failed

**Error:**
```
org.postgresql.util.PSQLException: Connection refused
```

**Solution:**
1. Check `SPRING_DATASOURCE_URL` is correct
2. Verify PostgreSQL is running: `docker ps | grep postgres`
3. Test connection: `psql -h localhost -U dataforge -d dataforge`

#### Redis Connection Failed

**Error:**
```
io.lettuce.core.RedisConnectionException: Unable to connect to localhost:6379
```

**Solution:**
1. Check `SPRING_DATA_REDIS_HOST` and `SPRING_DATA_REDIS_PORT`
2. Verify Redis is running: `docker ps | grep redis`
3. Test connection: `redis-cli -h localhost ping`

#### S3 Access Denied

**Error:**
```
com.amazonaws.services.s3.model.AmazonS3Exception: Access Denied
```

**Solution:**
1. Verify `S3_ACCESS_KEY` and `S3_SECRET_KEY` are correct
2. Check IAM user has `s3:PutObject`, `s3:GetObject` permissions
3. Verify bucket name is correct

#### Auth0 Token Validation Failed

**Error:**
```
InvalidTokenException: An error occurred while attempting to decode the Jwt
```

**Solution:**
1. Verify `AUTH0_DOMAIN` matches tenant domain
2. Check `AUTH0_AUDIENCE` matches API identifier
3. Ensure token is not expired
4. Verify Auth0 custom claims action is deployed

### Frontend Issues

#### Blank Page / White Screen

**Solution:**
1. Open browser DevTools console
2. Check for JavaScript errors
3. Verify `env-config.js` loaded: `console.log(window._env_)`
4. Check NGINX logs: `docker logs dataforge-frontend`

#### Auth0 Login Redirect Loop

**Solution:**
1. Verify `VITE_AUTH0_DOMAIN` matches Auth0 tenant
2. Check `VITE_AUTH0_CLIENT_ID` is SPA client (not M2M!)
3. Verify allowed callback URLs in Auth0 dashboard include your domain
4. Check browser console for Auth0 errors

#### API Requests Fail (404/500)

**Error:**
```
GET http://localhost/api/v1/accounts 404 Not Found
```

**Solution:**
1. Verify `BACKEND_URL` is correct in frontend container
2. Check backend is reachable from frontend: `docker exec dataforge-frontend wget -O- http://backend:8080/actuator/health`
3. Check NGINX proxy configuration: `docker exec dataforge-frontend cat /etc/nginx/conf.d/default.conf`
4. Verify backend is running: `curl http://localhost:8080/actuator/health`

#### CORS Errors

**Error:**
```
Access to fetch at 'http://backend:8080/api/v1/accounts' from origin 'http://localhost' has been blocked by CORS policy
```

**Solution:**
1. This should NOT happen with reverse proxy setup
2. Verify `VITE_API_BASE_URL=/api` (relative path)
3. Check NGINX proxy is working: `curl -I http://localhost/api/actuator/health`
4. If using direct backend URL, configure CORS in backend

### General Issues

#### Container Restarts Continuously

**Solution:**
1. Check logs: `docker logs dataforge-backend`
2. Check health endpoint manually
3. Increase health check timeouts
4. Verify all dependencies are ready (postgres, redis)

#### Out of Memory

**Error:**
```
java.lang.OutOfMemoryError: Java heap space
```

**Solution:**
1. Increase Docker memory limit
2. Adjust JVM heap size: `-e JAVA_OPTS="-Xmx1g"`
3. Check for memory leaks in application

---

## Additional Resources

- [Environment Variables Reference](./environment-variables.md)
- [Auth0 Migration Report](../auth0-migration-report.md)
- [Auth0 Configuration Guide](../auth0-config.md)
- [API Documentation](../api-unification.md)

---

**Last Updated:** 2025-11-23
**Version:** 1.0.0
