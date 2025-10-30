/**
 * Zod validation schemas for site management forms.
 *
 * Provides type-safe form validation matching backend validation rules.
 *
 * Feature: 007-adding-a-site (T023)
 */

import { z } from 'zod';

/**
 * Schema for creating a new site.
 *
 * Validation rules match backend CreateSiteRequestDto:
 * - domain: 3-255 chars, lowercase alphanumeric + dots/hyphens
 * - displayName: 2-100 chars, human-readable site name
 * - password: 8+ chars, alphanumeric, optional (auto-generated if not provided)
 */
export const CreateSiteFormSchema = z.object({
  domain: z
    .string()
    .min(3, 'Domain must be at least 3 characters')
    .max(255, 'Domain must be at most 255 characters')
    .regex(
      /^[a-z0-9.-]+$/,
      'Domain can only contain lowercase letters, numbers, dots, and hyphens'
    )
    .trim(),
  displayName: z
    .string()
    .min(2, 'Display name must be at least 2 characters')
    .max(100, 'Display name must be at most 100 characters')
    .trim(),
  password: z
    .string()
    .min(8, 'Password must be at least 8 characters')
    .max(12, 'Password must be at most 12 characters')
    .regex(/^[a-zA-Z0-9]+$/, 'Password can only contain letters and numbers')
    .trim()
    .optional()
    .or(z.literal('')),
});

/**
 * Inferred TypeScript type from CreateSiteFormSchema.
 */
export type CreateSiteFormData = z.infer<typeof CreateSiteFormSchema>;

/**
 * Schema for site domain validation (standalone).
 */
export const DomainSchema = z
  .string()
  .min(3, 'Domain must be at least 3 characters')
  .max(255, 'Domain must be at most 255 characters')
  .regex(
    /^[a-z0-9.-]+$/,
    'Domain can only contain lowercase letters, numbers, dots, and hyphens'
  )
  .trim();

/**
 * Schema for site password validation (standalone).
 */
export const PasswordSchema = z
  .string()
  .min(8, 'Password must be at least 8 characters')
  .max(12, 'Password must be at most 12 characters')
  .regex(/^[a-zA-Z0-9]+$/, 'Password can only contain letters and numbers')
  .trim();
