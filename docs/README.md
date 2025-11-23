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

- **[API Device Migration](./api-device-migration.md)** - Device management API migration guide

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

- **[Code Security & DTO Refactoring](./code-security-dto-refactoring.md)** - Security improvements and DTO standardization
  - Request/Response DTO patterns
  - Input validation with Jakarta Bean Validation
  - Type safety improvements

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
| [environment-variables.md](./deployment/environment-variables.md) | Environment variables reference | DevOps, Developers |
| [ui-base.md](./ui-base.md) | Frontend architecture | Frontend Developers |
| [api-unification.md](./api-unification.md) | API design patterns | Backend Developers |
| [api-device-migration.md](./api-device-migration.md) | Device API migration | Backend Developers |
| [auth0-config.md](./auth0-config.md) | Auth0 configuration | Administrators, DevOps |
| [auth0-migration-report.md](./auth0-migration-report.md) | Auth0 migration details | All |
| [code-security-dto-refactoring.md](./code-security-dto-refactoring.md) | Code quality improvements | Backend Developers |

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

**Last Updated:** 2025-11-23
**Version:** 1.0.0
