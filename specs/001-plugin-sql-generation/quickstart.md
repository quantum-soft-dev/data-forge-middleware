# Quickstart: Plugin SQL Generation Extension

**Feature Branch**: `001-plugin-sql-generation`
**Date**: 2025-12-22

## Prerequisites

- Java 21 (LTS)
- Docker & Docker Compose (for PostgreSQL, LocalStack)
- Gradle 8.x (wrapper included)

## Local Development Setup

### 1. Start Infrastructure

```bash
# Start PostgreSQL and LocalStack (S3)
docker-compose up -d postgres localstack

# Wait for services to be ready
docker-compose logs -f postgres  # Should show "database system is ready"

# Create S3 bucket in LocalStack
aws --endpoint-url=http://localhost:4566 s3 mb s3://dataforge-uploads
```

### 2. Run Database Migrations

```bash
./gradlew flywayMigrate
```

### 3. Run the Application

```bash
./gradlew bootRun --args='--spring.profiles.active=dev'
```

Application will be available at `http://localhost:8080`

### 4. Verify Setup

```bash
# Check health
curl http://localhost:8080/actuator/health

# Check Swagger UI
open http://localhost:8080/swagger-ui.html
```

---

## TDD Development Workflow (MANDATORY)

All development MUST follow the Red-Green-Refactor cycle:

### Step 1: Write Failing Test (RED)

```bash
# Run tests to see them fail
./gradlew test --tests "BitBiPluginApiContractTest"
# Expected: FAILED - classes don't exist yet
```

### Step 2: Implement Minimum Code (GREEN)

```bash
# Implement just enough to make tests pass
# Run tests again
./gradlew test --tests "BitBiPluginApiContractTest"
# Expected: PASSED
```

### Step 3: Refactor

```bash
# Clean up code while keeping tests green
./gradlew test
# Expected: All tests still pass
```

---

## Test Commands

```bash
# Run all tests
./gradlew test

# Run specific test class
./gradlew test --tests "BitBiPluginApiContractTest"

# Run integration tests only
./gradlew test --tests "*IntegrationTest"

# Run with coverage
./gradlew test jacocoTestReport
open build/reports/jacoco/test/html/index.html
```

---

## API Testing Examples

### Activate Bit BI Plugin (get API Key)

```bash
# First, get an admin JWT token (Auth0)
TOKEN="eyJhbG..."  # Your Auth0 token

# Activate plugin for an account
curl -X POST "http://localhost:8080/api/v1/plugins/bit-bi/activate" \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{
    "tenantId": "my-tenant-id"
  }'

# Response includes the generated API Key:
# {
#   "pluginId": "bit-bi",
#   "activatedAt": "2025-12-22T10:00:00Z",
#   "apiKey": "plk_a1B2c3D4e5F6g7H8i9J0k1L2m3N4o5P6"
# }
```

### List Sites (Plugin API)

```bash
API_KEY="plk_a1B2c3D4e5F6g7H8i9J0k1L2m3N4o5P6"

curl "http://localhost:8080/api/v1/plugins/bit-bi/sites" \
  -H "X-Plugin-Api-Key: $API_KEY"

# Response:
# {
#   "sites": [
#     {"id": "...", "domain": "example.com", "displayName": "Example"}
#   ]
# }
```

### Get SQL Changes (Plugin API)

```bash
API_KEY="plk_a1B2c3D4e5F6g7H8i9J0k1L2m3N4o5P6"
SITE_ID="550e8400-e29b-41d4-a716-446655440000"
SINCE="2025-01-01T00:00:00Z"

curl "http://localhost:8080/api/v1/plugins/bit-bi/sql-changes?siteId=$SITE_ID&since=$SINCE" \
  -H "X-Plugin-Api-Key: $API_KEY"

# Response (text/plain):
# INSERT INTO customers (id, name) VALUES ('1', 'John');
# --- END OF COMMAND "customers.csv:2" ---
```

---

## Key Files to Create (TDD Order)

### Phase 1: Contract Tests (Write FIRST)

1. `src/test/java/com/bitbi/dfm/plugin/contract/BitBiPluginApiContractTest.java`

### Phase 2: Unit Tests

2. `src/test/java/com/bitbi/dfm/plugin/unit/CsvDiffServiceTest.java`
3. `src/test/java/com/bitbi/dfm/plugin/unit/SqlStatementGeneratorTest.java`
4. `src/test/java/com/bitbi/dfm/plugin/unit/PluginApiKeyTest.java`

### Phase 3: Integration Tests

5. `src/test/java/com/bitbi/dfm/plugin/integration/SqlGenerationIntegrationTest.java`
6. `src/test/java/com/bitbi/dfm/plugin/integration/PluginApiKeyIntegrationTest.java`

### Phase 4: Implementation

7. `src/main/java/com/bitbi/dfm/plugin/domain/PluginSqlGeneration.java`
8. `src/main/java/com/bitbi/dfm/plugin/domain/PluginSqlGenerationRepository.java`
9. `src/main/java/com/bitbi/dfm/plugin/application/CsvDiffService.java`
10. `src/main/java/com/bitbi/dfm/plugin/application/SqlStatementGenerator.java`
11. `src/main/java/com/bitbi/dfm/plugin/application/PluginApiKeyService.java`
12. `src/main/java/com/bitbi/dfm/plugin/application/SqlGenerationService.java`
13. `src/main/java/com/bitbi/dfm/plugin/infrastructure/persistence/JpaPluginSqlGenerationRepository.java`
14. `src/main/java/com/bitbi/dfm/plugin/infrastructure/storage/S3SqlFileStorageService.java`
15. `src/main/java/com/bitbi/dfm/plugin/presentation/BitBiPluginApiController.java`

### Phase 5: Database Migration

16. `src/main/resources/db/migration/V11__create_plugin_sql_generations_table.sql`

---

## Troubleshooting

### Tests fail with "Connection refused"

```bash
# Ensure Docker containers are running
docker-compose ps

# Restart if needed
docker-compose restart postgres localstack
```

### S3 bucket not found

```bash
# Recreate bucket
aws --endpoint-url=http://localhost:4566 s3 mb s3://dataforge-uploads
```

### Plugin not registered

```bash
# Check plugin_configs table
docker-compose exec postgres psql -U dfm -d dataforge -c "SELECT * FROM plugin_configs;"

# Should show 'bit-bi' plugin
```

### API Key validation fails

```bash
# Check account_plugins table for apiKey in plugin_data
docker-compose exec postgres psql -U dfm -d dataforge -c \
  "SELECT plugin_data FROM account_plugins WHERE plugin_id = 'bit-bi';"
```

---

## Configuration

### Environment Variables

| Variable | Description | Default |
|----------|-------------|---------|
| `SPRING_PROFILES_ACTIVE` | Active profile | `dev` |
| `S3_BUCKET_NAME` | S3 bucket for uploads | `dataforge-uploads` |
| `S3_ENDPOINT_URL` | S3 endpoint (LocalStack) | `http://localhost:4566` |
| `PLUGINS_BITBI_ENABLED` | Enable Bit BI plugin | `true` |

### application-dev.yml additions

```yaml
plugins:
  bitbi:
    enabled: true
    sql-generation:
      timeout-seconds: 60  # SC-001: <60s for 100 files
```

---

## Verification Checklist

- [ ] PostgreSQL running with Flyway migrations applied
- [ ] LocalStack S3 running with bucket created
- [ ] Contract tests written and failing (RED)
- [ ] Unit tests written and failing (RED)
- [ ] Implementation code passes all tests (GREEN)
- [ ] Integration tests pass with Testcontainers
- [ ] API responds correctly to Plugin API Key authentication
- [ ] SQL files generated and stored in S3
- [ ] All tests pass: `./gradlew test`
