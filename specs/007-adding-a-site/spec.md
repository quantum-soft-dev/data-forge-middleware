# Feature Specification: Site Management for Users and Admins

**Feature Branch**: `007-adding-a-site`
**Created**: 2025-10-30
**Status**: Draft
**Input**: User description: "Adding a site - The user must be able to add/delete/deactivate/activate a site. The administrator must be able to create a site for the user. This option must be located within the User Management section."

## Clarifications

### Session 2025-10-30

- Q: How should site uniqueness be guaranteed in the system? → A: Create site identifier as accountId + "_" + site name to ensure uniqueness
- Q: What is the relationship between domain name and display name when creating a site? → A: Display name and domain name are the same thing (use single term for consistency)
- Q: How should sites be ordered in the site list, and is pagination required? → A: Sort by creation date (newest first), no pagination needed for initial release
- Q: What should the auto-generated passwords look like? → A: Random letters and numbers 8-12
- Q: What should the error message include when a client attempts to upload to a deactivated site? → A: "Site is inactive" plus site domain and account contact information

## User Scenarios & Testing *(mandatory)*

### User Story 1 - User Creates Own Site with Generated Credentials (Priority: P1)

A logged-in user wants to add a new monitored site to their account. They navigate to their Account Management section, access Site Management, and create a new site by providing a domain name and either entering a password or generating one automatically.

**Why this priority**: This is the core self-service capability that enables users to independently manage their monitoring infrastructure without admin intervention. It's the primary value proposition for end users.

**Independent Test**: Can be fully tested by logging in as a regular user, navigating to Account Management > Site Management, clicking "Add Site", filling out the form (domain + password), and verifying the site appears in the list with "Active" status.

**Acceptance Scenarios**:

1. **Given** a logged-in user on the Site Management page, **When** they click "Add Site" and enter domain "example.com" with a manually typed password "SecurePass123!", **Then** the site is created and appears in their site list with Active status
2. **Given** a logged-in user on the Add Site form, **When** they click "Generate Password" button, **Then** a strong random password is generated and displayed in the password field
3. **Given** a logged-in user viewing their site list, **When** the list contains multiple sites, **Then** each site shows its domain, status, and available actions (deactivate, delete)

---

### User Story 2 - User Deactivates and Reactivates Site (Priority: P1)

A user needs to temporarily suspend monitoring for a site without losing its configuration. They locate the site in their list and use the deactivate action. Later, they reactivate it when needed.

**Why this priority**: Essential operational control for users managing seasonal sites, maintenance windows, or testing scenarios. Part of the core CRUD operations (Create, Read, Update, Delete/Deactivate).

**Independent Test**: Can be fully tested by creating a site, clicking "Deactivate" on the site row, verifying status changes to "Inactive" and the action button changes to "Activate", then clicking "Activate" to restore Active status.

**Acceptance Scenarios**:

1. **Given** an active site in the user's site list, **When** they click the "Deactivate" button, **Then** the site status changes to "Inactive" and the site stops accepting data uploads
2. **Given** an inactive site in the user's site list, **When** they click the "Activate" button, **Then** the site status changes to "Active" and the site can resume accepting data uploads
3. **Given** a deactivated site, **When** the site's client attempts to upload data, **Then** the upload is rejected with an appropriate error message

---

### User Story 3 - User Deletes Site (Priority: P2)

A user wants to permanently remove a site from their account. They navigate to their site list, find the site they no longer need, and use the delete action to remove it.

**Why this priority**: Important for data hygiene and account cleanup, but less critical than create/activate/deactivate operations. Users typically delete sites less frequently.

**Independent Test**: Can be fully tested by creating a test site, clicking "Delete" on the site row, confirming the deletion in a confirmation dialog, and verifying the site is removed from the list.

**Acceptance Scenarios**:

1. **Given** a site in the user's site list, **When** they click "Delete" and confirm the action, **Then** the site is permanently removed from their account
2. **Given** a user attempting to delete a site, **When** they click "Delete", **Then** a confirmation dialog appears asking them to confirm the permanent deletion
3. **Given** a deleted site, **When** the user views their site list, **Then** the deleted site no longer appears

---

### User Story 4 - Admin Creates Site for User (Priority: P1)

An administrator needs to set up a new site on behalf of a user (e.g., during onboarding or support requests). They navigate to User Management, select a user account, access the user's site management, and create a new site with domain and password.

**Why this priority**: Critical for admin-assisted onboarding and support workflows. Administrators need parity with user self-service capabilities to provide effective support.

**Independent Test**: Can be fully tested by logging in as admin, navigating to User Management, selecting a user, clicking "Manage Sites", adding a site with domain and password, and verifying the site appears in both admin view and user's account.

**Acceptance Scenarios**:

1. **Given** an admin viewing a specific user's account in User Management, **When** they navigate to the user's Site Management section, **Then** they see the same site list and actions available to the user
2. **Given** an admin in a user's Site Management section, **When** they create a new site with domain "client-site.com" and password, **Then** the site is created under that user's account and appears in the user's site list
3. **Given** an admin creating a site for a user, **When** they use the "Generate Password" feature, **Then** a strong password is generated and the admin can copy it to share with the user

---

### User Story 5 - Admin Manages User's Sites (Priority: P2)

An administrator needs to deactivate, activate, or delete sites on behalf of users (e.g., during incident response, account suspension, or cleanup operations).

**Why this priority**: Important for administrative control and incident response, but secondary to creation capabilities. Admins need these operations less frequently than create operations.

**Independent Test**: Can be fully tested by logging in as admin, navigating to a user's site management, and performing deactivate/activate/delete actions while verifying the changes persist in the user's account.

**Acceptance Scenarios**:

1. **Given** an admin viewing a user's active site, **When** they click "Deactivate", **Then** the site is deactivated for that user and the user cannot use it for uploads
2. **Given** an admin viewing a user's inactive site, **When** they click "Activate", **Then** the site is reactivated for that user
3. **Given** an admin viewing a user's site, **When** they delete it, **Then** the site is permanently removed from the user's account

---

### Edge Cases

- What happens when a user tries to create a site with a domain that already exists in their account?
- What happens when a user tries to create a site with a domain that exists under a different user's account?
- How does the system handle deletion of a site that has active batches or historical data?
- What happens when an admin attempts to manage sites for a deactivated user account?
- How does the system validate domain names (format, special characters, length)?
- What happens when a user reaches a maximum site limit per account (if such a limit exists)?
- How does the system handle password validation (minimum length, complexity requirements)?
- What happens when a site is deactivated while an upload is in progress?

## Requirements *(mandatory)*

### Functional Requirements

#### User Self-Service Site Management

- **FR-001**: Authenticated users MUST be able to view a list of all sites associated with their account
- **FR-002**: Users MUST be able to create a new site by providing a domain name and password
- **FR-003**: Users MUST be able to generate a strong random password automatically when creating a site (8-12 characters, mixed letters and numbers)
- **FR-004**: Users MUST be able to deactivate an active site, which prevents it from accepting data uploads
- **FR-005**: Users MUST be able to reactivate an inactive site, which restores its ability to accept data uploads
- **FR-006**: Users MUST be able to permanently delete a site from their account
- **FR-007**: System MUST display a confirmation dialog before permanently deleting a site
- **FR-008**: System MUST show each site's domain, status (Active/Inactive), and available actions in the site list
- **FR-009**: System MUST sort the site list by creation date with newest sites first

#### Admin-Assisted Site Management

- **FR-010**: Administrators MUST be able to access site management functionality within the User Management section
- **FR-011**: Administrators MUST be able to view all sites for any user account
- **FR-012**: Administrators MUST be able to create sites on behalf of users with the same capabilities as user self-service (domain, password, generate password)
- **FR-013**: Administrators MUST be able to deactivate, activate, and delete sites for any user account
- **FR-014**: System MUST audit log all admin actions on user sites (create, deactivate, activate, delete) with timestamp, admin ID, and target user ID

#### Site Validation and Security

- **FR-015**: System MUST validate domain names to ensure they match expected format (alphanumeric, hyphens, dots, 3-255 characters)
- **FR-016**: System MUST enforce password requirements: minimum 8 characters (no additional complexity rules required)
- **FR-017**: System MUST prevent duplicate domain names within the same user account
- **FR-018**: System MUST allow the same domain name to exist across different user accounts (domains are unique per account only)
- **FR-019**: System MUST generate a unique site identifier by combining accountId + "_" + site name to ensure global uniqueness
- **FR-020**: System MUST hash and securely store site passwords using industry-standard encryption

#### Data Integrity and State Management

- **FR-021**: System MUST support permanent (hard) deletion of sites, which removes the site and all associated data (batches, uploads, error logs) from the system
- **FR-022**: System MUST prevent data uploads to deactivated sites and return error messages including "Site is inactive", site domain, and account contact information
- **FR-023**: System MUST allow in-progress uploads to complete when a site is deactivated (graceful degradation), but block new upload requests
- **FR-024**: System MUST preserve existing batches and upload history when a site is deactivated (soft delete via isActive=false), but permanently removes all data when site is deleted (hard delete)

### Key Entities

- **Site**: Represents a monitored domain/website under a user's account. Attributes include unique identifier (composed as accountId + "_" + domain), domain name, hashed password, status (Active/Inactive), creation timestamp, last modified timestamp, and association to parent Account.
- **Account**: The user account that owns sites. Each account can have multiple sites. Relationship: one-to-many with Site entity.
- **AdminActionLog**: Audit record of administrative actions on sites. Attributes include action type (CREATE_SITE, DEACTIVATE_SITE, ACTIVATE_SITE, DELETE_SITE), target account ID, target site ID, admin account ID, timestamp, IP address.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: Users can create a new site and begin uploading data within 2 minutes of starting the process
- **SC-002**: Site deactivation takes effect immediately, with upload rejection occurring within 1 second of status change
- **SC-003**: 95% of site creation attempts succeed on the first try without validation errors
- **SC-004**: Admin site management operations are completed within 3 clicks from the User Management dashboard
- **SC-005**: Zero data loss occurs during site deactivation or deletion operations (historical data remains accessible)
- **SC-006**: Password generation produces strong passwords meeting security requirements 100% of the time
- **SC-007**: Site list loading time remains under 2 seconds for accounts with up to 50 sites

## Dependencies and Assumptions

### Dependencies

- Existing Account Management UI must provide navigation to Site Management section
- Existing User Management admin interface must support embedding site management functionality
- Backend Site domain and API endpoints must support all CRUD operations (already exist per CLAUDE.md)
- Authentication system must provide user context (account ID) for frontend authorization checks

### Assumptions

- Domain names are unique only within each account (same domain can exist across different accounts)
- Soft delete is used for sites to preserve audit trails and batch references
- Generated passwords use a cryptographically secure random generator producing 8-12 characters with mixed letters (uppercase and lowercase) and numbers
- Password complexity requirements are minimal (minimum 8 characters only) to reduce friction, though generated passwords will use strong random character sets
- In-progress uploads are allowed to complete when a site is deactivated (graceful degradation)
- Maximum sites per account is unlimited unless business requirements dictate otherwise
- Site passwords are stored using the same security mechanisms as existing site credentials in the codebase

## Out of Scope

- Bulk site creation (importing multiple sites from CSV/file)
- Site transfer between user accounts
- Site configuration beyond domain and password (e.g., monitoring settings, alert thresholds)
- Site usage analytics and reporting
- Multi-factor authentication for site-level access
- Site credential rotation policies or scheduled password changes
- Webhook notifications for site status changes
- Integration with external domain verification services
- Site grouping or organizational hierarchy
