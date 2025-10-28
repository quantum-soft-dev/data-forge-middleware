/**
 * Zod validation schemas for user management features.
 *
 * @module features/user-management/model/userSchemas
 */

import { z } from 'zod';

export const createAccountSchema = z.object({
  email: z.string()
    .email('Invalid email format')
    .min(3)
    .max(255),
  name: z.string()
    .min(1, 'Name is required')
    .max(255),
  phone: z.string()
    .regex(/^\+?[0-9]{7,15}$/, 'Invalid phone format')
    .optional(),
  company: z.string()
    .min(2)
    .max(255)
    .optional(),
  role: z.string()
    .min(1, 'Role is required'),
});

export type CreateAccountFormData = z.infer<typeof createAccountSchema>;
