# Feature Specification: Additions to BACKEND

**Feature Branch**: `002-additions-to-backend`
**Created**: 2025-10-09
**Status**: Clarified
**Input**: User description: "Here is the corrected version:
Additions to BACKEND
Add DTO to Web Response
Replace Map<String, Object> with DTO objects in all endpoints
Use Lombok Builder for DTO conversion classes
Changes in Security
Configure authentication method separation for the following controllers:
error-log-controller
batch-controller
file-upload-controller
Authentication rules:
GET methods in the specified controllers: available for both token types (internal JWT and Keycloak)
Other methods in the specified controllers: available only with internal JWT
All other controllers: available only with Keycloak token (all methods)"

## Execution Flow (main)
```
1. Parse user description from Input
   → If empty: ERROR "No feature description provided"
2. Extract key concepts from description
   → Identify: actors, actions, data, constraints
3. For each unclear aspect:
   → Mark with [NEEDS CLARIFICATION: specific question]
4. Fill User Scenarios & Testing section
   → If no clear user flow: ERROR "Cannot determine user scenarios"
5. Generate Functional Requirements
   → Each requirement must be testable
   → Mark ambiguous requirements
6. Identify Key Entities (if data involved)
7. Run Review Checklist
   → If any [NEEDS CLARIFICATION]: WARN "Spec has uncertainties"
   → If implementation details found: ERROR "Remove tech details"
8. Return: SUCCESS (spec ready for planning)
```

---

## ⚡ Quick Guidelines
- ✅ Focus on WHAT users need and WHY
- ❌ Avoid HOW to implement (no tech stack, APIs, code structure)
- 👥 Written for business stakeholders, not developers

### Section Requirements
- **Mandatory sections**: Must be completed for every feature
- **Optional sections**: Include only when relevant to the feature
- When a section doesn't apply, remove it entirely (don't leave as "N/A")

### For AI Generation
When creating this spec from a user prompt:
1. **Mark all ambiguities**: Use [NEEDS CLARIFICATION: specific question] for any assumption you'd need to make
2. **Don't guess**: If the prompt doesn't specify something (e.g., "login system" without auth method), mark it
3. **Think like a tester**: Every vague requirement should fail the "testable and unambiguous" checklist item
4. **Common underspecified areas**:
   - User types and permissions
   - Data retention/deletion policies
   - Performance targets and scale
   - Error handling behaviors
   - Integration requirements
   - Security/compliance needs

---

## Clarifications

### Session 2025-10-09
- Q: When a Keycloak token is used for non-GET operations on error-log, batch, or file-upload controllers, what HTTP status code should the system return? → A: 403 Forbidden
- Q: What should happen when a request includes both internal JWT and Keycloak tokens simultaneously? → A: Reject Request with 400 Bad Request
- Q: Is the authentication change an immediate breaking change, or is there a migration/transition period? → A: Immediate Breaking Change
- Q: What specific information should be logged for authentication failures? → A: Standard (timestamp, endpoint, method, status code, IP address, token type)
- Q: Should error messages distinguish between "wrong token type" and "invalid/expired token" or be generic for security? → A: Generic Messages

---

## User Scenarios & Testing

### Primary User Story
As an API consumer (both client applications and administrative users), I need consistent, type-safe response structures from all endpoints and appropriate access control based on my authentication method, so that I can reliably integrate with the system and access only the resources I'm authorized to use.

### Acceptance Scenarios

1. **Given** a client application authenticates with internal JWT, **When** it calls any GET endpoint on error-log, batch, or file-upload controllers, **Then** it receives a structured response with well-defined fields

2. **Given** a client application authenticates with internal JWT, **When** it calls any POST/PUT/DELETE endpoint on error-log, batch, or file-upload controllers, **Then** it successfully executes the operation and receives a structured response

3. **Given** an administrative user authenticates with Keycloak token, **When** they call any GET endpoint on error-log, batch, or file-upload controllers, **Then** they successfully retrieve data with structured responses

4. **Given** an administrative user authenticates with Keycloak token, **When** they attempt to call POST/PUT/DELETE endpoints on error-log, batch, or file-upload controllers, **Then** the request is rejected with 403 Forbidden

5. **Given** an administrative user authenticates with Keycloak token, **When** they call any endpoint on account or site controllers, **Then** they successfully execute operations and receive structured responses

6. **Given** a client application authenticates with internal JWT, **When** it attempts to call any endpoint on account or site controllers, **Then** the request is rejected with appropriate error response

7. **Given** any authenticated user, **When** they receive an error response from any endpoint, **Then** the error response follows the same structured format as success responses

### Edge Cases
- When a request includes both internal JWT and Keycloak tokens simultaneously, the system rejects the request with 400 Bad Request (ambiguous authentication)
- How does the system handle responses for endpoints that currently return empty or null data?
- What happens when a token expires during a long-running request?
- How are validation errors represented in the new structured response format?

## Requirements

### Functional Requirements

#### Response Standardization
- **FR-001**: System MUST return structured response objects with well-defined fields from all API endpoints instead of generic key-value maps
- **FR-002**: System MUST provide consistent field naming and data types across all endpoint responses
- **FR-003**: System MUST include all necessary information in response objects that was previously available in the map-based responses
- **FR-004**: Error responses MUST follow the same structured format as success responses with predictable error information fields

#### Authentication & Authorization
- **FR-005**: System MUST accept both internal JWT tokens and Keycloak tokens for GET requests on error-log, batch, and file-upload endpoints
- **FR-006**: System MUST accept only internal JWT tokens for POST, PUT, DELETE, and PATCH requests on error-log, batch, and file-upload endpoints
- **FR-007**: System MUST reject Keycloak tokens for non-GET requests on error-log, batch, and file-upload endpoints with 403 Forbidden status code
- **FR-008**: System MUST accept only Keycloak tokens for all requests (GET, POST, PUT, DELETE, PATCH) on all other controllers (account, site, etc.)
- **FR-009**: System MUST reject internal JWT tokens for requests to account and site controllers with appropriate error responses
- **FR-010**: System MUST validate token authenticity and expiration before processing any authenticated request
- **FR-011**: System MUST enforce new authentication rules immediately upon deployment without backward compatibility (immediate breaking change)

#### Security & Validation
- **FR-012**: System MUST prevent privilege escalation by strictly enforcing the authentication method requirements for each endpoint
- **FR-013**: System MUST log authentication failures with timestamp, endpoint path, HTTP method, status code, client IP address, and token type for security auditing
- **FR-014**: System MUST return generic error messages for all authentication failures (e.g., "Authentication failed") without distinguishing between invalid tokens, expired tokens, or wrong token types to prevent information disclosure
- **FR-015**: System MUST reject requests that include both internal JWT and Keycloak tokens with 400 Bad Request status code to prevent ambiguous authentication

### Key Entities

- **Response DTO**: Structured objects representing successful API responses with type-safe fields corresponding to the business domain (accounts, sites, batches, uploads, error logs)
- **Error Response DTO**: Structured objects representing error conditions with fields for error codes, messages, validation details, and timestamps
- **Authentication Context**: Information about the authenticated request including token type (internal JWT or Keycloak), user/site identity, and granted permissions

---

## Review & Acceptance Checklist

### Content Quality
- [x] No implementation details (languages, frameworks, APIs)
- [x] Focused on user value and business needs
- [x] Written for non-technical stakeholders
- [x] All mandatory sections completed

### Requirement Completeness
- [x] No [NEEDS CLARIFICATION] markers remain
- [x] Requirements are testable and unambiguous
- [x] Success criteria are measurable
- [x] Scope is clearly bounded
- [x] Dependencies and assumptions identified

---

## Execution Status

- [x] User description parsed
- [x] Key concepts extracted
- [x] Ambiguities marked
- [x] User scenarios defined
- [x] Requirements generated
- [x] Entities identified
- [x] Review checklist passed

---

## Notes

### Dependencies
- Existing internal JWT authentication system
- Existing Keycloak OAuth2 integration
- Current endpoint implementations across error-log, batch, file-upload, account, and site controllers

### Assumptions
- The current map-based responses contain all necessary data that needs to be preserved in DTOs
- Existing clients can be updated to handle the new response structure and authentication rules before deployment (breaking change)
- The authentication infrastructure supports differentiating between token types
- GET operations are considered read-only and less sensitive than write operations
- All affected clients will be notified and coordinated for simultaneous deployment
