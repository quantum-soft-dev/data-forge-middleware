/**
 * Site API client for user and admin endpoints.
 *
 * Provides type-safe API methods for site management operations.
 * Uses axios instance from shared/api/client.
 *
 * Feature: 007-adding-a-site (T021)
 */

import { apiClient } from '@/shared/api/client';
import {
  SITES_USER,
  SITES_USER_ID,
  SITES_USER_ACTIVATE,
  SITES_USER_DEACTIVATE,
  SITES,
  SITES_BY_ACCOUNT,
  SITES_CREATE,
  SITES_ACTIVATE,
  SITES_DEACTIVATE,
  SITES_DELETE_BY_ACCOUNT,
  SITES_RETENTION
} from '@/shared/api/apiRoutes';
import type { Site, CreateSiteRequest, CreateSiteResponse } from '../model/types';

/**
 * User Site Management API
 */

/**
 * List all sites (both active and inactive) for the authenticated user.
 *
 * GET /api/v1/account/sites
 *
 * @returns List of all sites sorted by creation date (newest first)
 */
export async function listUserSites(): Promise<Site[]> {
  const response = await apiClient.get<Site[]>(SITES_USER);
  return response.data;
}

/**
 * Create a new site for the authenticated user.
 *
 * POST /api/v1/account/sites
 *
 * @param request - Site creation request (domain + password)
 * @returns Created site with plaintext password (only returned once)
 */
export async function createUserSite(request: CreateSiteRequest): Promise<CreateSiteResponse> {
  const response = await apiClient.post<CreateSiteResponse>(SITES_USER, request);
  return response.data;
}

/**
 * Deactivate a site (user operation).
 *
 * POST /api/v1/account/sites/{siteId}/deactivate
 *
 * @param siteId - Site identifier
 * @returns Updated site entity
 */
export async function deactivateUserSite(siteId: string): Promise<Site> {
  const response = await apiClient.post<Site>(SITES_USER_DEACTIVATE(siteId));
  return response.data;
}

/**
 * Activate a site (user operation).
 *
 * POST /api/v1/account/sites/{siteId}/activate
 *
 * @param siteId - Site identifier
 * @returns Updated site entity
 */
export async function activateUserSite(siteId: string): Promise<Site> {
  const response = await apiClient.post<Site>(SITES_USER_ACTIVATE(siteId));
  return response.data;
}

/**
 * Delete a site (soft delete, user operation).
 *
 * DELETE /api/v1/account/sites/{siteId}
 *
 * @param siteId - Site identifier
 */
export async function deleteUserSite(siteId: string): Promise<void> {
  await apiClient.delete(SITES_USER_ID(siteId));
}

/**
 * Admin Site Management API
 */

/**
 * List all sites (both active and inactive) for a specific account (admin operation).
 *
 * GET /api/v1/accounts/{accountId}/sites
 *
 * @param accountId - Account identifier
 * @returns List of all sites for the account
 */
export async function listAdminSites(accountId: string): Promise<Site[]> {
  const response = await apiClient.get<Site[]>(SITES_BY_ACCOUNT(accountId));
  return response.data;
}

/**
 * Get a single site by id (admin view).
 *
 * GET /api/v1/sites/{siteId} (023, F3 — admin site-detail page)
 */
export async function getAdminSite(siteId: string): Promise<Site> {
  const response = await apiClient.get<Site>(`${SITES}/${siteId}`);
  return response.data;
}

/**
 * Create a new site for a user (admin operation).
 *
 * POST /api/v1/accounts/{accountId}/sites
 *
 * @param accountId - Account identifier
 * @param request - Site creation request (domain + password)
 * @returns Created site with plaintext password
 */
export async function createAdminSite(
  accountId: string,
  request: CreateSiteRequest
): Promise<CreateSiteResponse> {
  const response = await apiClient.post<CreateSiteResponse>(
    SITES_CREATE(accountId),
    request
  );
  return response.data;
}

/**
 * Deactivate a user's site (admin operation).
 *
 * POST /api/v1/accounts/{accountId}/sites/{siteId}/deactivate
 *
 * @param accountId - Account identifier
 * @param siteId - Site identifier
 * @returns Updated site entity
 */
export async function deactivateAdminSite(accountId: string, siteId: string): Promise<Site> {
  const response = await apiClient.post<Site>(
    SITES_DEACTIVATE(accountId, siteId)
  );
  return response.data;
}

/**
 * Activate a user's site (admin operation).
 *
 * POST /api/v1/accounts/{accountId}/sites/{siteId}/activate
 *
 * @param accountId - Account identifier
 * @param siteId - Site identifier
 * @returns Updated site entity
 */
export async function activateAdminSite(accountId: string, siteId: string): Promise<Site> {
  const response = await apiClient.post<Site>(
    SITES_ACTIVATE(accountId, siteId)
  );
  return response.data;
}

/**
 * Delete a user's site (admin operation, soft delete).
 *
 * DELETE /api/v1/accounts/{accountId}/sites/{siteId}
 *
 * @param accountId - Account identifier
 * @param siteId - Site identifier
 */
export async function deleteAdminSite(accountId: string, siteId: string): Promise<void> {
  await apiClient.delete(SITES_DELETE_BY_ACCOUNT(accountId, siteId));
}

/**
 * Update site retention policy (admin operation).
 *
 * PATCH /api/v1/sites/{siteId}/retention
 *
 * @param siteId - Site identifier
 * @param retentionDays - Retention period in days
 * @returns Updated site entity
 */
export async function updateAdminSiteRetention(siteId: string, retentionDays: number): Promise<Site> {
  const response = await apiClient.patch<Site>(SITES_RETENTION(siteId), { retentionDays });
  return response.data;
}
