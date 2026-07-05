/**
 * Site entity types and interfaces.
 *
 * Defines TypeScript types for Site domain entities, matching backend DTOs.
 *
 * Feature: 007-adding-a-site (T020)
 */

/**
 * Site type enum — determines the data ingestion method.
 *
 * DBF        = Legacy DBF/CSV upload (default)
 * POSTGRES_CDC = Postgres Change Data Capture via JSONL delta files
 */
export type SiteType = 'DBF' | 'POSTGRES_CDC';

/**
 * Client ingestion API version — a separate axis from SiteType.
 *
 * V1 = legacy HTTP client API (full snapshot uploads)
 * V2 = Delta gRPC client API (changelog ingest + checkpoints)
 */
export type ClientApiVersion = 'V1' | 'V2';

/**
 * Site entity representing a monitored domain/website.
 *
 * @property id - Unique site identifier
 * @property accountId - Account this site belongs to
 * @property siteName - Site name (unique within account, unicode allowed)
 * @property name - Display name for the site
 * @property isActive - Activation status (true = active, false = inactive/deleted)
 * @property retentionDays - Retention period in days for batch cleanup
 * @property createdAt - Creation timestamp (ISO 8601 string)
 * @property siteType - Site type determining data ingestion method
 * @property clientApiVersion - Client ingestion API version (V1 legacy HTTP, V2 Delta gRPC)
 */
export interface Site {
  id: string;
  accountId: string;
  siteName: string;
  name: string;
  isActive: boolean;
  retentionDays: number;
  createdAt: string;
  siteType: SiteType;
  clientApiVersion: ClientApiVersion;
}

/**
 * Request payload for creating a new site.
 *
 * @property siteName - Site name (1-255 chars, unicode allowed)
 * @property displayName - Human-readable site name (2-100 chars)
 * @property password - Site password (8+ chars, optional - will be auto-generated if not provided)
 */
export interface CreateSiteRequest {
  siteName: string;
  displayName: string;
  password?: string;
}

/**
 * Response from site creation endpoint (Auth V2).
 *
 * @property site - Created site entity
 * @property password - Plaintext password (only returned once at creation)
 */
export interface CreateSiteResponse {
  site: Site;
  password: string;
}

/**
 * Site status enum.
 */
export enum SiteStatus {
  ACTIVE = 'ACTIVE',
  INACTIVE = 'INACTIVE',
}

/**
 * Admin action types for audit logging.
 */
export enum AdminActionType {
  CREATE_SITE = 'CREATE_SITE',
  DEACTIVATE_SITE = 'DEACTIVATE_SITE',
  ACTIVATE_SITE = 'ACTIVATE_SITE',
  DELETE_SITE = 'DELETE_SITE',
  UPDATE_SITE_RETENTION = 'UPDATE_SITE_RETENTION',
}
