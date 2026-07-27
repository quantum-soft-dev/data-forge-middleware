# Feature Specification: Parquet Export Plugin (Basic Auth + One-Time Download Links)

**Feature Branch**: `028-parquet-export-plugin`
**Created**: 2026-07-27
**Status**: Draft
**Input**: User description: "Parquet Export plugin (parquet-export) — Basic Auth listing of Parquet files with registered one-time S3 download links. A new account-level plugin that lets an external client pull Parquet data files produced by the Delta v2 pipeline: activation issues Basic Auth credentials (login + one-time-visible password), the client lists files by criteria (since, siteId, table, type) and receives registered one-time download links; each link redirects once to a short-lived S3 presigned URL and then becomes invalid."

## Clarifications

### Session 2026-07-27

- Q: Which Parquet files does the plugin expose? → A: Both delta segment Parquet files and full checkpoint snapshots (`type=delta|checkpoint` filter).
- Q: Is downloading via the one-time link credential-free (password only guards the listing)? → A: Yes — the link itself is the single-use credential; download requires no Basic Auth.
- Q: One-time link TTL before first use? → A: 1 hour (configurable).
- Q: Basic Auth login format? → A: Generated login (`pex_` + 12 alphanumerics), stored plaintext in plugin data for O(1) lookup; does not expose accountId.

## User Scenarios & Testing *(mandatory)*

### User Story 1 - Activate plugin and receive Basic Auth credentials (Priority: P1)

An account owner activates the `parquet-export` plugin through the existing plugin activation flow. The activation response contains generated Basic Auth credentials: a login (stable identifier, e.g. `pex_` + 12 alphanumerics) and a random password shown **exactly once**. The owner hands these credentials to the external client system that will pull Parquet files.

**Why this priority**: Nothing else in the feature is reachable without an activated plugin and credentials; this is the entry point.

**Independent Test**: Activate the plugin for an account via `POST /api/v1/plugins/parquet-export/activate` and verify the response contains login + raw password; verify a subsequent read of plugin state never exposes the raw password again.

**Acceptance Scenarios**:

1. **Given** an account without the plugin, **When** the owner activates `parquet-export`, **Then** the response contains a generated login and a raw password, and the password is stored only as a BCrypt hash.
2. **Given** an already-active plugin, **When** the owner activates it again, **Then** no new credentials are silently generated and the raw password is not re-displayed (idempotent activation, like Bit BI).
3. **Given** an active plugin, **When** the owner calls the password-rotation endpoint, **Then** a new raw password is returned once and the old password stops authenticating immediately.
4. **Given** a deactivated plugin, **When** the client authenticates with its credentials, **Then** access is denied (401).

---

### User Story 2 - List Parquet files by criteria and receive one-time links (Priority: P1)

The external client authenticates with HTTP Basic Auth and requests the list of Parquet files matching criteria: produced after a given date-time (`since`), optionally narrowed by site, table, and file type (`delta` segment Parquet or full `checkpoint` snapshot Parquet). The response contains file metadata plus a **registered one-time download URL** for each file.

**Why this priority**: This is the core value — discover new data files incrementally and obtain the means to download them.

**Independent Test**: Seed egressed delta segments and checkpoints for a site, call `GET /api/v1/plugins/parquet-export/files?since=...` with valid Basic Auth, verify the returned set, metadata, and that a `download_links` row is registered per file.

**Acceptance Scenarios**:

1. **Given** egressed delta segments and checkpoint Parquet files for the account's sites, **When** the client lists files with `since=T`, **Then** only files produced after `T` are returned, each with site, table, type, sequence range, producedAt, and a one-time download URL.
2. **Given** files belonging to another account, **When** the client lists files, **Then** those files are never visible (account scoping).
3. **Given** invalid or missing Basic Auth credentials, **When** the client lists files, **Then** the response is 401 and no links are registered.
4. **Given** filter combinations (`siteId`, `table`, `type`), **When** the client lists files, **Then** filters compose correctly; a `siteId` not owned by the account yields an empty result, never another account's data.
5. **Given** more matching files than the page limit, **When** the client lists files, **Then** results are paginated deterministically so the client can walk the full set.
6. **Given** the client exceeds the per-account rate limit, **When** it lists files, **Then** the response is 429 with `Retry-After`.

---

### User Story 3 - Download a file via one-time link (Priority: P1)

The client (or any tool it delegates to) opens a one-time download URL **without credentials**. The first request consumes the link and redirects (302) to a short-lived S3 presigned URL; any later request to the same link fails with 410 Gone. Unknown or expired links fail without revealing whether the token ever existed.

**Why this priority**: Completes the pull cycle; the one-time property is the security core of the feature.

**Independent Test**: Register a link via the listing endpoint, follow it once (expect 302 to S3), follow it again (expect 410), let one expire (expect 410).

**Acceptance Scenarios**:

1. **Given** a fresh unconsumed link, **When** the client requests it, **Then** the response is 302 with `Location` set to a short-lived (~60 s) presigned S3 URL, and the link is atomically marked consumed.
2. **Given** an already-consumed link, **When** it is requested again, **Then** the response is 410 Gone and no presigned URL is generated.
3. **Given** a link past its expiry (1 hour after registration, configurable), **When** it is requested, **Then** the response is 410 Gone.
4. **Given** a random unknown token, **When** it is requested, **Then** the response is 404 (no information leak about existing tokens).
5. **Given** two concurrent requests to the same link, **When** they race, **Then** exactly one receives 302 and the other receives 410 (atomic consumption).

---

### User Story 4 - Housekeeping of expired links (Priority: P2)

The system periodically purges consumed and expired `download_links` rows after a retention window so the table does not grow unboundedly.

**Why this priority**: Operational hygiene; the feature works without it short-term.

**Independent Test**: Seed consumed/expired rows older than the retention window, run the purge job, verify only those rows are deleted.

**Acceptance Scenarios**:

1. **Given** consumed or expired links older than the retention period (default 7 days), **When** the scheduled purge runs, **Then** those rows are deleted and fresh/unconsumed-unexpired rows are kept.

---

### Edge Cases

- Listing returns a file whose S3 object was deleted between listing and download → the presigned URL will 404 at S3; the link is still consumed (documented behavior; client re-lists).
- A delta segment whose Parquet was skipped during egress (no declared schema / poison table) must not produce dead links — delta Parquet existence is derived the same way the owner download endpoint derives it (existence probe on the derived key).
- Re-listing the same criteria registers **new** links for the same files (links are not deduplicated; each listing response is self-sufficient). TTL and purge bound the growth.
- Password rotation while a client holds registered links: links remain valid (they are credential-independent by design).
- Plugin deactivation: listing stops (401); already-registered unconsumed links stop working.
- Malformed `since` (bad ISO 8601) → 400 with a clear error.
- Basic Auth header present but malformed (bad base64, no colon) → 401, audited as an authentication failure.

## Requirements *(mandatory)*

### Functional Requirements

- **FR-001**: System MUST register `parquet-export` as a second plugin in the existing plugin registry, activatable per account via the existing activation API and visible in the available-plugins list.
- **FR-002**: On first activation the system MUST generate Basic Auth credentials: a login `pex_` + 12 alphanumerics (stored in plain form in the activation's plugin data, unique across activations) and a high-entropy random password returned exactly once and stored only as a BCrypt hash.
- **FR-003**: The owner MUST be able to rotate the password; the new raw password is returned once and the old password stops authenticating immediately.
- **FR-004**: System MUST expose `GET /api/v1/plugins/parquet-export/files` protected by HTTP Basic Auth validated against the activation's stored login and password hash; only **active** activations authenticate. Auth failures return 401 with `WWW-Authenticate: Basic`.
- **FR-005**: The listing MUST support filters: `since` (ISO 8601 datetime; files produced after this moment; defaults to epoch when omitted), `siteId`, `table`, `type` ∈ {`delta`, `checkpoint`}, and pagination (`page`/`size`, size capped at 100). Delta files are derived from egressed changelog segments (`egress_at > since`); checkpoint files from checkpoint records that have a Parquet key (`updated_at > since`). Only sites of the authenticated account are ever visible.
- **FR-006**: For every file returned, the system MUST register a one-time download link: cryptographically random token (≥ 256 bits, URL-safe), bound to the account activation and the S3 object key, with `expires_at` = now + TTL (default **1 hour**, configurable) and `consumed_at` initially null. The response carries file metadata (siteId, site domain, table, type, seq range where applicable, producedAt, fileName) plus the absolute one-time URL.
- **FR-007**: `GET /api/v1/plugins/parquet-export/download/{token}` MUST require no authentication (the token IS the credential). It MUST consume the link atomically (single-use even under concurrent requests), then mint a short-lived (~60 s) S3 presigned URL and respond 302. Consumed or expired → 410 Gone; unknown token → 404.
- **FR-008**: The listing endpoint MUST be rate-limited per account (existing per-account token-bucket approach); excess requests get 429 with `Retry-After`.
- **FR-009**: Plugin actions MUST be audited via the existing plugin audit trail: activation/deactivation (existing action types), plus new action types for file listing (filter summary + file count) and link consumption (success/failure).
- **FR-010**: A scheduled job MUST purge consumed and expired links older than a retention window (default 7 days, configurable).
- **FR-011**: Deactivating the plugin MUST stop Basic Auth authentication and invalidate the account's unconsumed links.
- **FR-012**: The Basic Auth listing route and the anonymous download route MUST be wired as a dedicated stateless security filter chain ordered before the catch-all `/api/v1/**` chain, with matching deny rules in that chain and a mirrored test security configuration.

### Key Entities

- **ParquetExportPlugin**: second `Plugin` SPI implementation (with a `plugin_configs` seed row `parquet-export`); credential minting happens on activation.
- **AccountPlugin.plugin_data (parquet-export)**: `{ "login": "pex_…", "passwordHash": "$2a$…" }` — reuses the existing JSONB credential pattern (Bit BI stores `apiKeyHash` the same way).
- **DownloadLink** (new table `download_links`, migration **V39**): `id`, `token` (unique, indexed), `account_plugin_id`, `s3_key`, `file_name`, `expires_at`, `consumed_at`, `created_at`. One row per issued link.
- **Parquet file listing item** (DTO, not persisted): derived from `changelog_segments` (delta) and `checkpoints` (checkpoint) joined with the account's sites for scoping.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: An external client, given only login+password, can go from zero to a downloaded Parquet file using exactly two API calls (list, then follow one link) with no other credentials.
- **SC-002**: A one-time link never yields file content more than once, including under concurrent access (verified by a concurrency test).
- **SC-003**: The raw password is observable in exactly one API response over the credential's lifetime (activation or rotation).
- **SC-004**: No cross-account leakage: listing and download are provably scoped to the authenticated account's sites in tests.
- **SC-005**: Incremental sync works: after downloading files listed at `since=T1`, a follow-up call with `since=T2>T1` returns only files produced after `T2` — the client never needs to re-scan history.
- **SC-006**: `download_links` stays bounded: rows older than the retention window are removed by the purge job.
