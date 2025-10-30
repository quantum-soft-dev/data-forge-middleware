/**
 * TypeScript type definitions generated from OpenAPI specification
 * File: api-contracts.yaml
 * Generated: 2025-10-11
 *
 * These types match the backend API DTOs exactly. Use these for API calls.
 * For form validation, use Zod schemas from data-model.md.
 */

// ============================================================================
// API Response Types
// ============================================================================

/**
 * Subscriber data transfer object (response from backend)
 */
export interface SubscriberResponseDto {
  /** Unique subscriber identifier (UUID v4) */
  id: string;

  /** Subscriber full name (2-100 characters) */
  name: string;

  /** Subscriber email address (unique across system) */
  email: string;

  /** Subscriber phone number (E.164 format recommended, optional) */
  phoneNumber: string | null;

  /** Company name (2-100 characters, optional) */
  companyName: string | null;

  /** Subscriber status */
  status: SubscriberStatus;

  /** Creation timestamp (ISO 8601 format) */
  createdAt: string;

  /** Last update timestamp (ISO 8601 format) */
  updatedAt: string;
}

/**
 * Paginated list of subscribers (backend pagination response)
 */
export interface PageResponseSubscriberDto {
  /** List of subscribers on current page */
  content: SubscriberResponseDto[];

  /** Current page number (0-indexed) */
  page: number;

  /** Number of records per page */
  size: number;

  /** Total number of subscribers across all pages */
  totalElements: number;

  /** Total number of pages */
  totalPages: number;
}

/**
 * Error response from backend API
 */
export interface ErrorResponseDto {
  /** Error timestamp (ISO 8601 format) */
  timestamp: string;

  /** HTTP status code (e.g., 400, 404, 500) */
  status: number;

  /** HTTP status text (e.g., "Bad Request", "Not Found") */
  error: string;

  /** Human-readable error message */
  message: string;

  /** API path that caused the error */
  path: string;

  /** Field-specific validation errors (optional, present in 400 responses) */
  fieldErrors?: Record<string, string>;
}

// ============================================================================
// API Request Types
// ============================================================================

/**
 * Request body for creating a new subscriber (POST /api/admin/subscribers)
 */
export interface CreateSubscriberRequest {
  /** Subscriber full name (required, 2-100 characters) */
  name: string;

  /** Subscriber email address (required, must be unique) */
  email: string;

  /** Subscriber phone number (optional) */
  phoneNumber?: string | null;

  /** Company name (optional, 2-100 characters if provided) */
  companyName?: string | null;
}

/**
 * Request body for updating an existing subscriber (PUT /api/admin/subscribers/{id})
 * All fields are optional - only provided fields will be updated
 */
export interface UpdateSubscriberRequest {
  /** Subscriber full name (optional, 2-100 characters if provided) */
  name?: string;

  /** Subscriber email address (optional, must be unique if changed) */
  email?: string;

  /** Subscriber phone number (optional) */
  phoneNumber?: string | null;

  /** Company name (optional, 2-100 characters if provided) */
  companyName?: string | null;
}

// ============================================================================
// Enum Types
// ============================================================================

/**
 * Subscriber status enum
 * - active: Subscriber is active in the system
 * - inactive: Subscriber has been soft deleted or deactivated
 */
export type SubscriberStatus = 'active' | 'inactive';

/**
 * Sort order enum for list queries
 */
export type SortOrder = 'asc' | 'desc';

/**
 * Sort field enum for subscriber list
 */
export type SubscriberSortField = 'name' | 'email' | 'createdAt';

// ============================================================================
// Query Parameter Types
// ============================================================================

/**
 * Query parameters for GET /api/admin/subscribers
 */
export interface SubscriberListParams {
  /** Page number (0-indexed, default: 0) */
  page?: number;

  /** Page size (1-100, default: 50) */
  size?: number;

  /** Search term (searches name, email, companyName) */
  search?: string;

  /** Filter by subscriber status */
  status?: SubscriberStatus;

  /** Field to sort by (default: createdAt) */
  sortBy?: SubscriberSortField;

  /** Sort order (default: desc) */
  sortOrder?: SortOrder;
}

// ============================================================================
// HTTP Status Codes
// ============================================================================

/**
 * Expected HTTP status codes from backend API
 */
export enum HttpStatus {
  /** Request successful */
  OK = 200,

  /** Resource created successfully */
  CREATED = 201,

  /** Resource deleted successfully (no content) */
  NO_CONTENT = 204,

  /** Bad request - validation error */
  BAD_REQUEST = 400,

  /** Unauthorized - missing or invalid JWT token */
  UNAUTHORIZED = 401,

  /** Forbidden - insufficient permissions */
  FORBIDDEN = 403,

  /** Not found - resource does not exist */
  NOT_FOUND = 404,

  /** Conflict - duplicate resource (e.g., email already exists) */
  CONFLICT = 409,

  /** Payload too large */
  PAYLOAD_TOO_LARGE = 413,

  /** Internal server error */
  INTERNAL_SERVER_ERROR = 500,
}

// ============================================================================
// API Client Types (for axios/fetch wrappers)
// ============================================================================

/**
 * API client configuration
 */
export interface ApiConfig {
  /** Base URL for API requests (e.g., http://localhost:8080) */
  baseURL: string;

  /** Request timeout in milliseconds (default: 30000) */
  timeout?: number;

  /** Whether to include credentials (cookies) in requests */
  withCredentials?: boolean;

  /** Custom headers to include in all requests */
  headers?: Record<string, string>;
}

/**
 * API client error (thrown by axios interceptor)
 */
export class ApiError extends Error {
  constructor(
    public readonly response: ErrorResponseDto,
    public readonly status: number,
    public readonly originalError?: unknown
  ) {
    super(response.message);
    this.name = 'ApiError';
  }

  /**
   * Check if error is due to validation failure (400 Bad Request)
   */
  isValidationError(): boolean {
    return this.status === HttpStatus.BAD_REQUEST && !!this.response.fieldErrors;
  }

  /**
   * Check if error is due to unauthorized access (401 Unauthorized)
   */
  isUnauthorized(): boolean {
    return this.status === HttpStatus.UNAUTHORIZED;
  }

  /**
   * Check if error is due to forbidden access (403 Forbidden)
   */
  isForbidden(): boolean {
    return this.status === HttpStatus.FORBIDDEN;
  }

  /**
   * Check if error is due to resource not found (404 Not Found)
   */
  isNotFound(): boolean {
    return this.status === HttpStatus.NOT_FOUND;
  }

  /**
   * Check if error is due to conflict (409 Conflict)
   */
  isConflict(): boolean {
    return this.status === HttpStatus.CONFLICT;
  }

  /**
   * Get field-specific error messages for form validation
   */
  getFieldErrors(): Record<string, string> {
    return this.response.fieldErrors || {};
  }
}

// ============================================================================
// Type Guards
// ============================================================================

/**
 * Type guard to check if value is ErrorResponseDto
 */
export function isErrorResponse(value: unknown): value is ErrorResponseDto {
  return (
    typeof value === 'object' &&
    value !== null &&
    'timestamp' in value &&
    'status' in value &&
    'error' in value &&
    'message' in value &&
    'path' in value
  );
}

/**
 * Type guard to check if value is SubscriberResponseDto
 */
export function isSubscriberResponse(value: unknown): value is SubscriberResponseDto {
  return (
    typeof value === 'object' &&
    value !== null &&
    'id' in value &&
    'name' in value &&
    'email' in value &&
    'status' in value &&
    'createdAt' in value &&
    'updatedAt' in value
  );
}

/**
 * Type guard to check if value is PageResponseSubscriberDto
 */
export function isPageResponse(value: unknown): value is PageResponseSubscriberDto {
  return (
    typeof value === 'object' &&
    value !== null &&
    'content' in value &&
    Array.isArray((value as any).content) &&
    'page' in value &&
    'size' in value &&
    'totalElements' in value &&
    'totalPages' in value
  );
}

// ============================================================================
// Constants
// ============================================================================

/**
 * API endpoints (use with base URL)
 */
export const API_ENDPOINTS = {
  /** List/create subscribers */
  SUBSCRIBERS: '/api/admin/subscribers',

  /** Get/update/delete specific subscriber by ID */
  SUBSCRIBER_BY_ID: (id: string) => `/api/admin/subscribers/${id}`,
} as const;

/**
 * Default pagination values
 */
export const DEFAULT_PAGINATION = {
  /** Default page number */
  PAGE: 0,

  /** Default page size */
  SIZE: 50,

  /** Default sort field */
  SORT_BY: 'createdAt' as SubscriberSortField,

  /** Default sort order */
  SORT_ORDER: 'desc' as SortOrder,
} as const;

/**
 * Validation constraints (must match backend)
 */
export const VALIDATION_CONSTRAINTS = {
  NAME: {
    MIN_LENGTH: 2,
    MAX_LENGTH: 100,
  },
  COMPANY_NAME: {
    MIN_LENGTH: 2,
    MAX_LENGTH: 100,
  },
  PAGE_SIZE: {
    MIN: 1,
    MAX: 100,
  },
} as const;
