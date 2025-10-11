/**
 * Subscriber Zod Schemas
 *
 * Runtime validation for subscriber data.
 * Used for API response validation and form validation.
 */

import { z } from 'zod'

export const subscriberStatusSchema = z.enum(['active', 'inactive'])

export const subscriberSchema = z.object({
  id: z.string().min(1),
  name: z.string().min(1),
  email: z.string().email(),
  phone: z.string().nullable(),
  company: z.string().nullable(),
  status: subscriberStatusSchema,
  createdAt: z.string().datetime(), // ISO 8601
})

export const subscriberFiltersSchema = z.object({
  search: z.string().optional(),
  status: subscriberStatusSchema.optional(),
  page: z.number().int().min(1).optional(), // 1-indexed
  size: z.number().int().min(1).max(100).optional(),
})

export const subscriberListResponseSchema = z.object({
  content: z.array(subscriberSchema),
  page: z.number().int().min(0), // 0-indexed from API
  size: z.number().int().min(1),
  totalElements: z.number().int().min(0),
  totalPages: z.number().int().min(0),
})

// Type exports
export type SubscriberSchema = z.infer<typeof subscriberSchema>
export type SubscriberFiltersSchema = z.infer<typeof subscriberFiltersSchema>
export type SubscriberListResponseSchema = z.infer<typeof subscriberListResponseSchema>
