/**
 * Zod validation schemas for file comparison API requests and responses.
 *
 * These schemas provide runtime validation and type inference for
 * comparison-related data, ensuring API responses match expected types.
 *
 * @module features/file-comparison/model/schemas
 */

import { z } from 'zod';

/**
 * Schema for ComparisonStatus enum.
 */
export const comparisonStatusSchema = z.enum([
  'PENDING',
  'IN_PROGRESS',
  'COMPLETED',
  'FAILED',
]);

/**
 * Schema for ChangeType enum.
 */
export const changeTypeSchema = z.enum(['ADDED', 'MODIFIED', 'UNCHANGED', 'REMOVED']);

/**
 * Schema for DiffChange object.
 */
export const diffChangeSchema = z.object({
  type: z.enum(['ADDED', 'REMOVED', 'UNCHANGED']),
  lineNumber: z.number().int().min(1),
  content: z.string(),
});

/**
 * Schema for DiffHunk object.
 */
export const diffHunkSchema = z.object({
  oldStart: z.number().int().min(1),
  oldLines: z.number().int().min(0),
  newStart: z.number().int().min(1),
  newLines: z.number().int().min(0),
  changes: z.array(diffChangeSchema),
});

/**
 * Schema for UnifiedDiff object.
 */
export const unifiedDiffSchema = z.object({
  hunks: z.array(diffHunkSchema),
  oldFileName: z.string(),
  newFileName: z.string(),
});

/**
 * Schema for Comparison entity.
 */
export const comparisonSchema = z.object({
  id: z.number().int().positive(),
  currentBatchId: z.number().int().positive(),
  targetBatchId: z.number().int().positive(),
  accountId: z.number().int().positive(),
  status: comparisonStatusSchema,
  createdAt: z.string().datetime(),
  startedAt: z.string().datetime().nullable(),
  completedAt: z.string().datetime().nullable(),
  totalFilesCompared: z.number().int().min(0),
  filesChanged: z.number().int().min(0),
  filesAdded: z.number().int().min(0),
  filesUnchanged: z.number().int().min(0),
  totalChangeSize: z.number().int().min(0),
  errorMessage: z.string().nullable(),
});

/**
 * Schema for ComparisonResult entity.
 */
export const comparisonResultSchema = z.object({
  id: z.number().int().positive(),
  comparisonId: z.number().int().positive(),
  fileId: z.number().int().positive(),
  targetFileId: z.number().int().positive().nullable(),
  fileName: z.string(),
  targetFileName: z.string().nullable(),
  changeType: changeTypeSchema,
  lineAdditions: z.number().int().min(0),
  lineDeletions: z.number().int().min(0),
  changeSize: z.number().int().min(0),
  unifiedDiff: unifiedDiffSchema.nullable(),
  createdAt: z.string().datetime(),
});

/**
 * Schema for ComparisonSummary.
 */
export const comparisonSummarySchema = z.object({
  totalFilesCompared: z.number().int().min(0),
  filesChanged: z.number().int().min(0),
  filesAdded: z.number().int().min(0),
  filesUnchanged: z.number().int().min(0),
  totalChangeSize: z.number().int().min(0),
  comparisonTimestamp: z.string().datetime(),
  currentBatchId: z.number().int().positive(),
  targetBatchId: z.number().int().positive(),
});

/**
 * Schema for CreateComparisonRequest.
 */
export const createComparisonRequestSchema = z
  .object({
    currentBatchId: z.number().int().positive({
      message: 'Current batch ID must be a positive integer',
    }),
    targetBatchId: z.number().int().positive({
      message: 'Target batch ID must be a positive integer',
    }),
    fileIds: z.array(z.number().int().positive()).nullable().optional(),
  })
  .refine((data) => data.currentBatchId !== data.targetBatchId, {
    message: 'Cannot compare a batch with itself',
    path: ['targetBatchId'],
  })
  .refine(
    (data) => {
      if (Array.isArray(data.fileIds)) {
        return data.fileIds.length > 0;
      }
      return true;
    },
    {
      message: 'File IDs list cannot be empty. Use null to compare all files.',
      path: ['fileIds'],
    }
  );

/**
 * Schema for PagedComparisonResponse.
 */
export const pagedComparisonResponseSchema = z.object({
  content: z.array(comparisonSchema),
  page: z.number().int().min(0),
  size: z.number().int().positive(),
  totalElements: z.number().int().min(0),
  totalPages: z.number().int().min(0),
});

/**
 * Schema for PagedComparisonResultResponse.
 */
export const pagedComparisonResultResponseSchema = z.object({
  content: z.array(comparisonResultSchema),
  page: z.number().int().min(0),
  size: z.number().int().positive(),
  totalElements: z.number().int().min(0),
  totalPages: z.number().int().min(0),
});

/**
 * Type inference helpers - extract TypeScript types from schemas.
 */
export type ComparisonStatusType = z.infer<typeof comparisonStatusSchema>;
export type ChangeTypeType = z.infer<typeof changeTypeSchema>;
export type ComparisonType = z.infer<typeof comparisonSchema>;
export type ComparisonResultType = z.infer<typeof comparisonResultSchema>;
export type ComparisonSummaryType = z.infer<typeof comparisonSummarySchema>;
export type CreateComparisonRequestType = z.infer<typeof createComparisonRequestSchema>;
export type PagedComparisonResponseType = z.infer<typeof pagedComparisonResponseSchema>;
export type PagedComparisonResultResponseType = z.infer<
  typeof pagedComparisonResultResponseSchema
>;

/**
 * Validation helper functions.
 */
export const validateComparison = (data: unknown) => {
  return comparisonSchema.parse(data);
};

export const validateComparisonResult = (data: unknown) => {
  return comparisonResultSchema.parse(data);
};

export const validateCreateComparisonRequest = (data: unknown) => {
  return createComparisonRequestSchema.parse(data);
};

export const validatePagedComparisonResponse = (data: unknown) => {
  return pagedComparisonResponseSchema.parse(data);
};

export const validatePagedComparisonResultResponse = (data: unknown) => {
  return pagedComparisonResultResponseSchema.parse(data);
};
