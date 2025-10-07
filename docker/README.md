# Docker Configuration for Data Forge Middleware

This directory contains Docker configuration files for the Data Forge Middleware project.

## Directory Structure

```
docker/
├── postgres/
│   └── init.sql          # PostgreSQL initialization script
├── keycloak/
│   └── dfm-realm.json    # Keycloak realm configuration
└── README.md             # This file
```

## PostgreSQL Configuration

### Databases Created

The `init.sql` script creates two separate databases:

1. **dfm** - Main application database
   - User: `dfm`
   - Password: `dfm_password`
   - Extensions: `uuid-ossp`, `pg_stat_statements`

2. **keycloak** - Identity and access management database
   - User: `keycloak`
   - Password: `keycloak_password`

### Schema Permissions

Both users have full privileges on their respective databases and public schemas.

## Keycloak Configuration

### Realm: dfm

The `dfm-realm.json` file configures:

#### Roles
- **ROLE_ADMIN** - Full access to admin endpoints
- **ROLE_USER** - Standard user access

#### Pre-configured Users

1. **Admin User**
   - Username: `admin`
   - Password: `admin`
   - Email: `admin@dfm.local`
   - Roles: `ROLE_ADMIN`, `ROLE_USER`

2. **Test User**
   - Username: `user`
   - Password: `user`
   - Email: `user@dfm.local`
   - Roles: `ROLE_USER`

#### Clients

1. **dfm-backend**
   - Client ID: `dfm-backend`
   - Secret: `dfm-backend-secret`
   - Type: Confidential
   - Purpose: Backend REST API authentication
   - Grant Types: Authorization Code, Client Credentials, Direct Access
   - Redirect URIs: `http://localhost:8080/*`, `http://localhost:3000/*`

2. **dfm-ui**
   - Client ID: `dfm-ui`
   - Secret: `dfm-ui-secret`
   - Type: Public (PKCE enabled)
   - Purpose: Web UI authentication
   - Grant Types: Authorization Code with PKCE
   - Redirect URIs: `http://localhost:3000/*`
   - CORS: `http://localhost:3000`

### Security Features

- Brute force protection enabled (5 failures = temporary lockout)
- SSL not required for development (enable in production)
- Password reset enabled
- Email login enabled
- Event logging enabled

## Using with Docker Compose

### Start All Services

```bash
docker-compose up -d
```

### Start Individual Services

```bash
# Start only database
docker-compose up -d postgres

# Start database and Keycloak
docker-compose up -d postgres keycloak

# Start all including application
docker-compose up -d
```

### Stop Services

```bash
docker-compose down
```

### Remove All Data (Clean Start)

```bash
docker-compose down -v
```

## Service URLs

- **Application**: http://localhost:8080
- **Keycloak Admin**: http://localhost:8081 (admin/admin)
- **PostgreSQL**: localhost:5432
- **LocalStack S3**: http://localhost:4566

## Health Checks

All services include health checks:

```bash
# Check all services
docker-compose ps

# Check individual service
docker exec dfm-postgres pg_isready -U postgres
docker exec dfm-keycloak curl -f http://localhost:8080/health/ready
docker exec dfm-backend curl -f http://localhost:8080/actuator/health
```

## Production Considerations

### Security

1. **Change Default Passwords**
   ```sql
   ALTER USER dfm WITH PASSWORD 'strong_password_here';
   ALTER USER keycloak WITH PASSWORD 'strong_password_here';
   ```

2. **Update Keycloak Admin Password**
   ```bash
   docker exec dfm-keycloak /opt/keycloak/bin/kc.sh user:password --user admin --new-password newpass
   ```

3. **Update JWT Secret**
   - Set environment variable `JWT_SECRET` to a strong 256-bit key

4. **Enable SSL/TLS**
   - Configure `KC_HOSTNAME` and `KC_HTTPS_CERTIFICATE_FILE` for Keycloak
   - Use reverse proxy (nginx/traefik) for SSL termination

### Performance

1. **Database Tuning**
   - Increase `shared_buffers` for production workload
   - Configure connection pooling in application
   - Enable `pg_stat_statements` for query analysis

2. **Keycloak Optimization**
   - Use persistent database instead of dev-file
   - Configure caching appropriately
   - Set reasonable session timeouts

3. **Application Scaling**
   - Use `replicas` in docker-compose for horizontal scaling
   - Configure load balancer
   - Use external S3 (not LocalStack)

### Monitoring

Add monitoring services:

```yaml
services:
  prometheus:
    image: prom/prometheus
    # ... configuration

  grafana:
    image: grafana/grafana
    # ... configuration
```

## Troubleshooting

### Database Connection Issues

```bash
# Check if database is ready
docker exec dfm-postgres pg_isready -U postgres -d dfm

# View database logs
docker logs dfm-postgres

# Connect to database
docker exec -it dfm-postgres psql -U dfm -d dfm
```

### Keycloak Issues

```bash
# View Keycloak logs
docker logs dfm-keycloak

# Re-import realm
docker-compose restart keycloak

# Access Keycloak admin console
open http://localhost:8081
```

### Application Issues

```bash
# View application logs
docker logs dfm-backend

# Check health endpoint
curl http://localhost:8080/actuator/health

# Restart application
docker-compose restart dfm-backend
```

## Development vs Production

### Development Mode
- Uses LocalStack for S3
- Keycloak in dev mode
- Debug logging enabled
- Hot reload (if configured)

### Production Mode
- Real AWS S3
- Keycloak with PostgreSQL backend
- INFO level logging
- Optimized JVM settings
- Health checks and metrics enabled
