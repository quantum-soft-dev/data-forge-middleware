/**
 * Site entity public API.
 *
 * Re-exports types and API functions following FSD architecture.
 *
 * Feature: 007-adding-a-site (T022)
 */

// Types
export type { Site, CreateSiteRequest, CreateSiteResponse, SiteType, ClientApiVersion } from './model/types';
export { SiteStatus, AdminActionType } from './model/types';

// API functions
export {
  // User operations
  listUserSites,
  createUserSite,
  deactivateUserSite,
  activateUserSite,
  deleteUserSite,
  // Admin operations
  listAdminSites,
  getAdminSite,
  createAdminSite,
  deactivateAdminSite,
  activateAdminSite,
  deleteAdminSite,
  updateAdminSiteRetention,
} from './api/siteApi';
