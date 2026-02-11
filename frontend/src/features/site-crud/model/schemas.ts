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
 * - siteName: 1-255 chars, unicode allowed (case-insensitive, stored as lowercase)
 * - displayName: 2-100 chars, human-readable site name
 * - password: 8+ chars, alphanumeric, optional (auto-generated if not provided)
 */
export const CreateSiteFormSchema = z.object({
  siteName: z
    .string()
    .trim()
    .min(1, 'Site name is required')
    .max(255, 'Site name must be at most 255 characters'),
  displayName: z
    .string()
    .trim()
    .min(1, 'Display name is required')
    .min(2, 'Display name must be at least 2 characters')
    .max(100, 'Display name must be at most 100 characters'),
  password: z
    .string()
    .optional()
    .or(z.literal(''))
    .refine(
      (val) => !val || val.length === 0 || val.length >= 8,
      'Password must be at least 8 characters'
    )
    .refine(
      (val) => !val || val.length === 0 || /^[a-zA-Z0-9]+$/.test(val),
      'Password can only contain letters and numbers'
    ),
});

/**
 * Inferred TypeScript type from CreateSiteFormSchema.
 */
export type CreateSiteFormData = z.infer<typeof CreateSiteFormSchema>;

/**
 * Schema for site name validation (standalone).
 */
export const SiteNameSchema = z
  .string()
  .min(1, 'Site name is required')
  .max(255, 'Site name must be at most 255 characters')
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
