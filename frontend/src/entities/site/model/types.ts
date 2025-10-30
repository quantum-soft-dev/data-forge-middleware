/**
 * Site entity types and interfaces.
 *
 * Defines TypeScript types for Site domain entities, matching backend DTOs.
 *
 * Feature: 007-adding-a-site (T020)
 */

/**
 * Site entity representing a monitored domain/website.
 *
 * @property id - Unique site identifier
 * @property accountId - Account this site belongs to
 * @property domain - Site domain name (e.g., "example.com")
 * @property name - Display name for the site
 * @property isActive - Activation status (true = active, false = inactive/deleted)
 * @property createdAt - Creation timestamp (ISO 8601 string)
 */
export interface Site {
  id: string;
  accountId: string;
  domain: string;
  name: string;
  isActive: boolean;
  createdAt: string;
}

/**
 * Request payload for creating a new site.
 *
 * @property domain - Site domain (3-255 chars, alphanumeric + dots/hyphens)
 * @property displayName - Human-readable site name (2-100 chars)
 * @property password - Site password (8+ chars, alphanumeric, optional - will be auto-generated if not provided)
 */
export interface CreateSiteRequest {
  domain: string;
  displayName: string;
  password?: string;
}

/**
 * Response from site creation endpoint.
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
}
