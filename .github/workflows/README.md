# GitHub Actions CI/CD Pipeline

## Overview

Automated CI/CD pipeline for the Data Forge Middleware project with conditional workflows based on branch patterns.

## Workflow Triggers

- **Push** to: main, release, develop, feature/*, bugfix/*, hotfix/*
- **Pull Request** to: main, release, develop

## Pipeline Types

### Full CI/CD Pipeline
**Branches:** `main`, `release`, `develop`

**Jobs executed:**
1. ✅ **Test** - All tests (unit + integration) with Testcontainers
2. 🔍 **Code Quality** - Checkstyle, SpotBugs analysis
3. 🔒 **Security Scan** - OWASP Dependency Check
4. 🏗️ **Build** - Gradle build (production JAR)
5. 🐳 **Docker Build** - Build and push Docker image
6. 🚀 **Deploy** - Deploy to staging (main) or production (release)

### Tests Only
**Branches:** `feature/*`, `bugfix/*`, `hotfix/*`, and all others

**Jobs executed:**
1. ✅ **Test** - All tests (unit + integration) with Testcontainers

## Required Secrets

Configure these secrets in your GitHub repository settings:

| Secret Name | Description | Example |
|------------|-------------|---------|
| `DOCKER_USERNAME` | Docker Hub username | `your-dockerhub-username` |
| `DOCKER_PASSWORD` | Docker Hub access token | `dckr_pat_xxxxx` |

## Artifacts

The pipeline generates the following artifacts:

- **test-results** - JUnit test reports (HTML)
- **coverage-report** - JaCoCo code coverage reports
- **security-scan-results** - OWASP dependency check reports
- **application-jar** - Built JAR file (full pipeline only)

## Docker Image Tags

Images are tagged with multiple patterns:

- `main` - Latest from main branch
- `develop` - Latest from develop branch
- `release` - Latest from release branch
- `pr-123` - Pull request number
- `main-a1b2c3d` - Branch + commit SHA

## Deployment

### Staging (main branch)
- Triggered on push to `main` branch
- Deploys to staging environment

### Production (release branch)
- Triggered on push to `release` branch
- Deploys to production environment

**Note:** Deployment steps are placeholders. Update the `deploy` job with your actual deployment commands (kubectl, AWS CLI, etc.)

## Local Testing

Test the workflow locally before pushing:

```bash
# Run tests
./gradlew test integrationTest

# Generate coverage report
./gradlew jacocoTestReport

# Build application
./gradlew build

# Build Docker image
docker build -t data-forge-middleware:local .
```

## Gradle Cache

The pipeline uses GitHub Actions caching for Gradle dependencies to speed up builds:

- Cache key: Gradle wrapper + build files
- Automatically restored on subsequent runs
- Reduces build time by ~60%

## Troubleshooting

### Tests failing in CI but passing locally
- Ensure Testcontainers has Docker access in CI environment
- Check timezone differences (tests use UTC)
- Verify test data initialization in test-data.sql

### Docker build failing
- Verify Dockerfile syntax
- Check base image availability
- Ensure DOCKER_USERNAME and DOCKER_PASSWORD secrets are set

### Deployment skipped
- Verify branch name matches `main` or `release` exactly
- Check that docker-build job completed successfully
- Ensure it's a push event, not a pull request

## Performance

Typical pipeline execution times:

| Job | Duration (approx) |
|-----|------------------|
| Test | 3-5 minutes |
| Code Quality | 1-2 minutes |
| Security Scan | 2-3 minutes |
| Build | 1-2 minutes |
| Docker Build | 2-4 minutes |
| **Total (Full)** | **9-16 minutes** |
| **Total (Tests Only)** | **3-5 minutes** |

## Monitoring

View pipeline status:
- Repository → Actions tab
- Branch protection rules require passing checks
- Pull request checks must pass before merge
