# DataForge Middleware Documentation

Welcome to the DataForge Middleware documentation. This directory contains comprehensive guides for developers, DevOps engineers, and administrators.

## Table of Contents

- [Deployment](#deployment)
- [Architecture & Design](#architecture--design)
- [API Documentation](#api-documentation)
- [Authentication](#authentication)
- [Development](#development)

---

## Deployment

### CI/CD (GitHub Actions → GKE)

- **[Workflows overview](../.github/workflows/README.md)** - CI pipeline, GKE deploy triggers per environment
- **[Tag-driven dev deploys](./cr-tag-driven-dev-deploy.md)** - Why merges to `develop` no longer deploy; how to deploy dev with a `deploy-dev/*` tag
- **[GKE migration](./cr-gke-migration.md)** - AWS → GKE change request (022)

### Docker Deployment

- **[Docker Deployment Guide](./deployment/docker-deployment.md)** - Complete guide for deploying backend and frontend Docker images
  - Docker Compose setup
  - Kubernetes deployment examples
  - Production checklist
  - Troubleshooting guide

- **[Environment Variables Reference](./deployment/environment-variables.md)** - Comprehensive reference for all environment variables
  - Backend configuration (Spring Boot, PostgreSQL, Redis, S3, Auth0)
  - Frontend configuration (Auth0, NGINX)
  - Security best practices
  - Environment-specific examples

---

## Architecture & Design

### UI/Frontend

- **[UI Base Documentation](./ui-base.md)** - Frontend architecture and component design
  - React 19.2 + TypeScript 5.6
  - TanStack Query + TanStack Router
  - Feature-Sliced Design architecture
  - Component library (shadcn/ui)

### API Design

- **[API Unification](./api-unification.md)** - API architecture and unification strategy
  - RESTful API design patterns
  - Client API vs Admin API separation
  - OpenAPI/Swagger documentation

### Delta Client v2 & Site Types

- **[Delta Client v2 — Change Request](./cr-delta-client-v2.md)** - gRPC ingestion design (022)
- **[Delta Client v2 — Client & Sync UI Guide](./delta-client-v2-guide.md)** - protocol, realtime segment egress, unified batch/table Parquet, and Delta Sync UI (022/023/025/036/038)
- **[Unified Batch/Table Parquet — Change Request](./cr-unified-batch-parquet.md)** - one completed-session download per table with batch-level fan-out finalization (036/038, issues #93/#97)
- **[Site History Wipe — Client Contract](./site-history-wipe-client-guide.md)** - generation epoch, wipe recovery sequence (035, issue #89)
- **[Delta v2 Wire Contract — Answers](./delta-v2-wire-contract-answers.md)** - presence, typed error codes, recovery matrix, delivery order (dbf-data-extractor#130)
- **[Site Types & POSTGRES_CDC — Change Request](./cr-site-types-postgres-cdc.md)** - DBF vs POSTGRES_CDC, site_schemas (019)
- **[POSTGRES_CDC Client Guide](./postgres-cdc-client-guide.md)** - CDC client integration
- **[Plugin Reinitialization](./reinit.md)** - re-baseline flow (015)

### Visual Language

- **[Visual Language Migration — Change Request](./cr-visual-language-migration.md)** - monitoring visual language (024)

### Product & Data Platform Concept

- **[Data Platform Concept (RU)](./data-platform-concept.ru.md)** - Product-level explanation of the data collection platform idea
  - Source adapters, gRPC/Protobuf ingestion, changelog and checkpoints
  - CSV, SQL, and Parquet egress for BI/plugin consumers
  - Infographics for the end-to-end architecture and lifecycle

---

## Authentication

### Auth0 Integration

- **[Auth0 Configuration Guide](./auth0-config.md)** - Complete Auth0 setup guide
  - Tenant configuration
  - SPA application setup (frontend)
  - M2M application setup (backend)
  - Custom claims and actions
  - RBAC configuration

- **[Auth0 Migration Report](./auth0-migration-report.md)** - Migration from Keycloak to Auth0
  - Migration rationale
  - Implementation steps
  - Testing results
  - Known issues and solutions

---

## Development

### Code Quality

- Request/Response DTO patterns, input validation with Jakarta Bean Validation, and type-safety
  conventions are documented in the repo-root `CLAUDE.md` (Code Style).

---

## Quick Links

### For Developers

- [Frontend Architecture](./ui-base.md)
- [API Documentation](./api-unification.md)
- [Auth0 Setup](./auth0-config.md)

### For DevOps

- [Docker Deployment](./deployment/docker-deployment.md)
- [Environment Variables](./deployment/environment-variables.md)

### For Administrators

- [Auth0 Configuration](./auth0-config.md)
- [Production Checklist](./deployment/docker-deployment.md#production-checklist)

---

## Document Index

| Document | Description | Audience |
|----------|-------------|----------|
| [docker-deployment.md](./deployment/docker-deployment.md) | Docker deployment guide | DevOps |
| [cr-gke-migration.md](./cr-gke-migration.md) | AWS → GKE migration — change request (022) | DevOps |
| [cr-tag-driven-dev-deploy.md](./cr-tag-driven-dev-deploy.md) | Tag-driven dev deploys (`deploy-dev/*`) | DevOps, Developers |
| [environment-variables.md](./deployment/environment-variables.md) | Environment variables reference | DevOps, Developers |
| [ui-base.md](./ui-base.md) | Frontend architecture | Frontend Developers |
| [api-unification.md](./api-unification.md) | API design patterns | Backend Developers |
| [data-platform-concept.ru.md](./data-platform-concept.ru.md) | Data platform product concept with diagrams | Product, Architects, Developers |
| [cr-delta-client-v2.md](./cr-delta-client-v2.md) | Delta Client v2 gRPC ingestion — change request (022) | Backend Developers |
| [delta-client-v2-guide.md](./delta-client-v2-guide.md) | Delta v2 protocol, segment egress, unified batch/table Parquet, Sync UI (022/023/025/036/038) | Backend, Client integrators |
| [cr-unified-batch-parquet.md](./cr-unified-batch-parquet.md) | Unified completed-batch Parquet contract, fan-out cost model, and compatibility boundary (036/038, issues #93/#97) | Backend, Architects |
| [site-history-wipe-client-guide.md](./site-history-wipe-client-guide.md) | Generation epoch + post-wipe recovery for the Delta v2 client (035) | Client integrators, Backend |
| [delta-v2-wire-contract-answers.md](./delta-v2-wire-contract-answers.md) | Wire-contract answers: presence, typed codes, recovery matrix, delivery order (dbf-data-extractor#130) | Client integrators, Backend |
| [cr-site-types-postgres-cdc.md](./cr-site-types-postgres-cdc.md) | Site types (DBF/POSTGRES_CDC) — change request (019) | Backend Developers |
| [postgres-cdc-client-guide.md](./postgres-cdc-client-guide.md) | POSTGRES_CDC client integration | Client integrators |
| [reinit.md](./reinit.md) | Plugin reinitialization / re-baseline (015) | Backend Developers |
| [cr-visual-language-migration.md](./cr-visual-language-migration.md) | Monitoring visual language — change request (024) | Frontend Developers |
| [auth0-config.md](./auth0-config.md) | Auth0 configuration | Administrators, DevOps |
| [auth0-migration-report.md](./auth0-migration-report.md) | Auth0 migration details | All |

---

## Getting Started

### For New Developers

1. Read [Frontend Architecture](./ui-base.md) to understand the React app structure
2. Review [API Documentation](./api-unification.md) for backend integration
3. Set up [Auth0 locally](./auth0-config.md) for authentication

### For DevOps Engineers

1. Start with [Docker Deployment Guide](./deployment/docker-deployment.md)
2. Reference [Environment Variables](./deployment/environment-variables.md) for configuration
3. Follow [Production Checklist](./deployment/docker-deployment.md#production-checklist) before deploying

### For System Administrators

1. Configure [Auth0 tenant](./auth0-config.md) for authentication
2. Review security best practices in [Environment Variables](./deployment/environment-variables.md#security-best-practices)
3. Set up monitoring and alerts per [Deployment Guide](./deployment/docker-deployment.md#post-deployment)

---

## Contributing

When adding new documentation:

1. Place files in appropriate subdirectories (`deployment/`, `architecture/`, etc.)
2. Update this README.md with links to new documents
3. Include last updated date and version at the bottom of each document
4. Use consistent Markdown formatting (headers, code blocks, tables)
5. Add practical examples and code snippets

---

## External Resources

- [Spring Boot Documentation](https://docs.spring.io/spring-boot/docs/current/reference/html/)
- [React Documentation](https://react.dev/)
- [Auth0 Documentation](https://auth0.com/docs)
- [Docker Documentation](https://docs.docker.com/)
- [Kubernetes Documentation](https://kubernetes.io/docs/)

---

**Last Updated:** 2026-08-01
**Version:** 1.1.0
