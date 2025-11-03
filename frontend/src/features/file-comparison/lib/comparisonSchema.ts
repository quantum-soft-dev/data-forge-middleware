/**
 * Zod validation schema for file comparison form
 *
 * Feature: 009-markdown-user-story (User Story 1)
 */

import { z } from 'zod';

/**
 * Schema for comparison creation form
 */
export const comparisonFormSchema = z.object({
  currentBatchId: z.string().uuid('Current batch must be selected'),
  targetBatchId: z.string().uuid('Target batch must be selected'),
  fileIds: z
    .array(z.string().uuid())
    .min(1, 'At least one file must be selected')
    .optional()
    .nullable(),
}).refine(
  (data) => data.currentBatchId !== data.targetBatchId,
  {
    message: 'Cannot compare a batch with itself',
    path: ['targetBatchId'],
  }
);

export type ComparisonFormData = z.infer<typeof comparisonFormSchema>;
