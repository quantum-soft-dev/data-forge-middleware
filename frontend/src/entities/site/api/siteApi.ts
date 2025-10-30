/**
 * Site API client for user and admin endpoints.
 *
 * Provides type-safe API methods for site management operations.
 * Uses axios instance from shared/api/client.
 *
 * Feature: 007-adding-a-site (T021)
 */

import { apiClient } from '@/shared/api/client';
import type { Site, CreateSiteRequest, CreateSiteResponse } from '../model/types';

/**
 * User Site Management API
 */

/**
 * List all active sites for the authenticated user.
 *
 * GET /api/sites
 *
 * @returns List of active sites sorted by creation date (newest first)
 */
export async function listUserSites(): Promise<Site[]> {
  const response = await apiClient.get<Site[]>('/api/sites');
  return response.data;
}

/**
 * Create a new site for the authenticated user.
 *
 * POST /api/sites
 *
 * @param request - Site creation request (domain + password)
 * @returns Created site with plaintext password (only returned once)
 */
export async function createUserSite(request: CreateSiteRequest): Promise<CreateSiteResponse> {
  const response = await apiClient.post<CreateSiteResponse>('/api/sites', request);
  return response.data;
}

/**
 * Deactivate a site (user operation).
 *
 * POST /api/sites/{siteId}/deactivate
 *
 * @param siteId - Site identifier
 * @returns Updated site entity
 */
export async function deactivateUserSite(siteId: string): Promise<Site> {
  const response = await apiClient.post<Site>(`/api/sites/${siteId}/deactivate`);
  return response.data;
}

/**
 * Activate a site (user operation).
 *
 * POST /api/sites/{siteId}/activate
 *
 * @param siteId - Site identifier
 * @returns Updated site entity
 */
export async function activateUserSite(siteId: string): Promise<Site> {
  const response = await apiClient.post<Site>(`/api/sites/${siteId}/activate`);
  return response.data;
}

/**
 * Delete a site (soft delete, user operation).
 *
 * DELETE /api/sites/{siteId}
 *
 * @param siteId - Site identifier
 */
export async function deleteUserSite(siteId: string): Promise<void> {
  await apiClient.delete(`/api/sites/${siteId}`);
}

/**
 * Admin Site Management API
 */

/**
 * List all active sites for a specific account (admin operation).
 *
 * GET /api/admin/accounts/{accountId}/sites
 *
 * @param accountId - Account identifier
 * @returns List of active sites for the account
 */
export async function listAdminSites(accountId: string): Promise<Site[]> {
  const response = await apiClient.get<Site[]>(`/api/admin/accounts/${accountId}/sites`);
  return response.data;
}

/**
 * Create a new site for a user (admin operation).
 *
 * POST /api/admin/accounts/{accountId}/sites
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
    `/api/admin/accounts/${accountId}/sites`,
    request
  );
  return response.data;
}

/**
 * Deactivate a user's site (admin operation).
 *
 * POST /api/admin/accounts/{accountId}/sites/{siteId}/deactivate
 *
 * @param accountId - Account identifier
 * @param siteId - Site identifier
 * @returns Updated site entity
 */
export async function deactivateAdminSite(accountId: string, siteId: string): Promise<Site> {
  const response = await apiClient.post<Site>(
    `/api/admin/accounts/${accountId}/sites/${siteId}/deactivate`
  );
  return response.data;
}

/**
 * Activate a user's site (admin operation).
 *
 * POST /api/admin/accounts/{accountId}/sites/{siteId}/activate
 *
 * @param accountId - Account identifier
 * @param siteId - Site identifier
 * @returns Updated site entity
 */
export async function activateAdminSite(accountId: string, siteId: string): Promise<Site> {
  const response = await apiClient.post<Site>(
    `/api/admin/accounts/${accountId}/sites/${siteId}/activate`
  );
  return response.data;
}

/**
 * Delete a user's site (admin operation, soft delete).
 *
 * DELETE /api/admin/accounts/{accountId}/sites/{siteId}
 *
 * @param accountId - Account identifier
 * @param siteId - Site identifier
 */
export async function deleteAdminSite(accountId: string, siteId: string): Promise<void> {
  await apiClient.delete(`/api/admin/accounts/${accountId}/sites/${siteId}`);
}
