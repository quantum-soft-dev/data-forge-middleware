/**
 * Client diagnostic log types.
 *
 * Feature: 021-unified-upload-api (T041, US11)
 */

export interface ClientLog {
  logId: string;
  siteId: string;
  filename: string;
  fileSize: number;
  clientVersion: string | null;
  os: string | null;
  periodFrom: string | null;
  periodTo: string | null;
  tags: string[] | null;
  description: string | null;
  uploadedAt: string;
  expiresAt: string;
}

export interface ClientLogListResponse {
  content: ClientLog[];
  page: number;
  size: number;
  totalElements: number;
}

export interface ClientLogDownloadResponse {
  url: string;
  expiresAt: string;
  filename: string;
  fileSize: number;
}
