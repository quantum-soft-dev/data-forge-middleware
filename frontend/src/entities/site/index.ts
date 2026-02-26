/**
 * Site entity public API.
 *
 * Re-exports types and API functions following FSD architecture.
 *
 * Feature: 007-adding-a-site (T022)
 */

// Types
export type { Site, SiteType, BatchType, CreateSiteRequest, CreateSiteResponse } from './model/types';
export { SiteStatus, AdminActionType, isCdcSiteType } from './model/types';

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
  createAdminSite,
  deactivateAdminSite,
  activateAdminSite,
  deleteAdminSite,
  updateAdminSiteRetention,
  forceRebaseline,
  requestLogs,
} from './api/siteApi';
export type { ForceRebaselineResponse } from './api/siteApi';
