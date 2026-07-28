# Docker Configuration for Data Forge Middleware

This directory contains Docker configuration files for the Data Forge Middleware project.

## Directory Structure

```
docker/
├── postgres/
│   └── init.sql          # PostgreSQL initialization script
└── README.md             # This file
```

## PostgreSQL Configuration

### Databases Created

The `init.sql` script creates a single database:

1. **dfm** - Main application database
   - User: `dfm`
   - Password: `dfm_password`
   - Extensions: `uuid-ossp`, `pg_stat_statements`

### Schema Permissions

The `dfm` user has full privileges on its database and public schema.

## Authentication

Authentication is provided by **Auth0**, not by a container in this stack. The backend
validates Auth0-issued tokens as an OAuth2 resource server; configure it with `AUTH0_DOMAIN`,
`AUTH0_AUDIENCE` and the `AUTH0_MGMT_*` variables (see `.env.example`).

Keycloak was the original identity provider and has been fully removed.

## Using with Docker Compose

### Start All Services

```bash
docker-compose up -d
```

### Start Individual Services

```bash
# Start only database
docker-compose up -d postgres

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
- **PostgreSQL**: localhost:5432
- **LocalStack S3**: http://localhost:4566

## Health Checks

All services include health checks:

```bash
# Check all services
docker-compose ps

# Check individual service
docker exec dfm-postgres pg_isready -U postgres
docker exec dfm-backend curl -f http://localhost:8080/actuator/health
```

## Production Considerations

### Security

1. **Change Default Passwords**
   ```sql
   ALTER USER dfm WITH PASSWORD 'strong_password_here';
   ```

2. **Update JWT Secret**
   - Set environment variable `JWT_SECRET` to a strong 256-bit key

3. **Enable SSL/TLS**
   - Use a reverse proxy (nginx/traefik) for SSL termination

### Performance

1. **Database Tuning**
   - Increase `shared_buffers` for production workload
   - Configure connection pooling in application
   - Enable `pg_stat_statements` for query analysis

2. **Application Scaling**
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
- Debug logging enabled
- Hot reload (if configured)

### Production Mode
- Real AWS S3
- INFO level logging
- Optimized JVM settings
- Health checks and metrics enabled
