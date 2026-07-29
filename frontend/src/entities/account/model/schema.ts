/**
 * Account Zod Schemas
 *
 * Runtime validation for account data.
 * Used for API response validation and form validation.
 */

import { z } from 'zod'

export const accountStatusSchema = z.enum(['active', 'inactive'])

export const accountSchema = z.object({
  id: z.string().min(1),
  name: z.string().min(1),
  email: z.string().email(),
  phone: z.string().nullable(),
  company: z.string().nullable(),
  status: accountStatusSchema,
  createdAt: z.string().datetime(), // ISO 8601
})

export const accountFiltersSchema = z.object({
  search: z.string().optional(),
  status: accountStatusSchema.optional(),
  page: z.number().int().min(1).optional(), // 1-indexed
  size: z.number().int().min(1).max(100).optional(),
})

export const accountListResponseSchema = z.object({
  content: z.array(accountSchema),
  page: z.number().int().min(0), // 0-indexed from API
  size: z.number().int().min(1),
  totalElements: z.number().int().min(0),
  totalPages: z.number().int().min(0),
})

export const createAccountSchema = z.object({
  name: z.string().min(1, 'Name is required'),
  email: z.string().min(1, 'Email is required').email('Invalid email format'),
  // Nullable: the forms send null (not undefined) to clear an optional field,
  // and the API distinguishes "clear it" from "leave it alone".
  phone: z.string().nullish(),
  company: z.string().nullish(),
})

export const updateAccountSchema = createAccountSchema.partial().extend({
  id: z.string().min(1),
})

// Type exports
export type AccountSchema = z.infer<typeof accountSchema>
export type AccountFiltersSchema = z.infer<typeof accountFiltersSchema>
export type AccountListResponseSchema = z.infer<typeof accountListResponseSchema>
export type CreateAccountFormData = z.infer<typeof createAccountSchema>
export type UpdateAccountFormData = z.infer<typeof updateAccountSchema>
