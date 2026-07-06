/**
 * Component for displaying comparison summary statistics.
 *
 * This component shows:
 * - Total files compared
 * - Files changed, added, unchanged counts
 * - Total change size
 * - Comparison timestamp
 * - Batch IDs being compared
 *
 * @module features/file-comparison/ui/ComparisonSummary
 *
 * Feature: File Diff Comparison Between Upload Sessions
 * Task: T065 - Create ComparisonSummary component
 * Phase: Phase 4 - User Story 2 (Compare Files)
 */

import React from 'react';
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from '@/shared/ui/ui/card';
import { Badge } from '@/shared/ui/ui/badge';
import { FileText, FilePlus, FileCheck, FileX, Calendar, Database } from 'lucide-react';
import type { ComparisonSummary as ComparisonSummaryType } from '@/entities/comparison/model/types';

/**
 * Props for the ComparisonSummary component.
 */
export interface ComparisonSummaryProps {
  /**
   * The comparison summary data to display.
   */
  summary: ComparisonSummaryType;

  /**
   * Optional CSS class name for custom styling.
   */
  className?: string;
}

/**
 * Formats a byte size into a human-readable string.
 *
 * @param bytes - The size in bytes
 * @returns Formatted size string (e.g., "1.5 MB")
 */
function formatBytes(bytes: number): string {
  if (bytes === 0) return '0 Bytes';

  const k = 1024;
  const sizes = ['Bytes', 'KB', 'MB', 'GB'];
  const i = Math.floor(Math.log(bytes) / Math.log(k));

  return `${parseFloat((bytes / Math.pow(k, i)).toFixed(2))} ${sizes[i]}`;
}

/**
 * Formats a timestamp into a human-readable date string.
 *
 * @param timestamp - ISO 8601 timestamp string
 * @returns Formatted date string
 */
function formatTimestamp(timestamp: string): string {
  const date = new Date(timestamp);
  return date.toLocaleString('en-US', {
    year: 'numeric',
    month: 'short',
    day: 'numeric',
    hour: '2-digit',
    minute: '2-digit',
  });
}

/**
 * Component for displaying comparison summary statistics.
 *
 * Displays a grid of statistics cards with icons and formatted values.
 *
 * @param props - Component props
 * @returns The rendered component
 *
 * @example
 * ```tsx
 * import { ComparisonSummary } from '@/features/file-comparison/ui/ComparisonSummary';
 *
 * function MyComponent() {
 *   const { data: summary } = useQuery({
 *     queryKey: ['comparison', 45, 'summary'],
 *     queryFn: () => comparisonApi.getComparisonSummary(45),
 *   });
 *
 *   if (!summary) return <div>Loading...</div>;
 *
 *   return <ComparisonSummary summary={summary} />;
 * }
 * ```
 */
export function ComparisonSummary({ summary, className }: ComparisonSummaryProps): React.ReactElement {
  const changePercentage =
    summary.totalFilesCompared > 0
      ? Math.round(((summary.filesChanged + summary.filesAdded) / summary.totalFilesCompared) * 100)
      : 0;

  return (
    <Card className={className}>
      <CardHeader>
        <CardTitle className="flex items-center gap-2">
          <FileText className="h-5 w-5" />
          Comparison Summary
        </CardTitle>
        <CardDescription>
          Overview of changes between upload sessions
        </CardDescription>
      </CardHeader>

      <CardContent>
        <div className="grid gap-4 md:grid-cols-2 lg:grid-cols-3">
          {/* Total Files Compared */}
          <div className="flex items-center gap-3 rounded-lg border p-4">
            <div className="rounded-full bg-blue-100 p-2 dark:bg-blue-900">
              <FileText className="h-5 w-5 text-blue-600 dark:text-blue-400" />
            </div>
            <div>
              <p className="text-2xl font-bold">{summary.totalFilesCompared}</p>
              <p className="text-sm text-muted-foreground">Total Files</p>
            </div>
          </div>

          {/* Files Changed */}
          <div className="flex items-center gap-3 rounded-lg border p-4">
            <div className="rounded-full bg-amber-100 p-2 dark:bg-amber-900">
              <FileX className="h-5 w-5 text-amber-600 dark:text-amber-400" />
            </div>
            <div>
              <p className="text-2xl font-bold">{summary.filesChanged}</p>
              <p className="text-sm text-muted-foreground">Modified</p>
            </div>
          </div>

          {/* Files Added */}
          <div className="flex items-center gap-3 rounded-lg border p-4">
            <div className="rounded-full bg-green-100 p-2 dark:bg-green-900">
              <FilePlus className="h-5 w-5 text-green-600 dark:text-green-400" />
            </div>
            <div>
              <p className="text-2xl font-bold">{summary.filesAdded}</p>
              <p className="text-sm text-muted-foreground">Added</p>
            </div>
          </div>

          {/* Files Unchanged */}
          <div className="flex items-center gap-3 rounded-lg border p-4">
            <div className="rounded-full bg-gray-100 p-2 dark:bg-gray-800">
              <FileCheck className="h-5 w-5 text-gray-600 dark:text-gray-400" />
            </div>
            <div>
              <p className="text-2xl font-bold">{summary.filesUnchanged}</p>
              <p className="text-sm text-muted-foreground">Unchanged</p>
            </div>
          </div>

          {/* Total Change Size */}
          <div className="flex items-center gap-3 rounded-lg border p-4">
            <div className="rounded-full bg-purple-100 p-2 dark:bg-purple-900">
              <Database className="h-5 w-5 text-purple-600 dark:text-purple-400" />
            </div>
            <div>
              <p className="text-2xl font-bold">{formatBytes(summary.totalChangeSize)}</p>
              <p className="text-sm text-muted-foreground">Change Size</p>
            </div>
          </div>

          {/* Comparison Timestamp */}
          <div className="flex items-center gap-3 rounded-lg border p-4">
            <div className="rounded-full bg-indigo-100 p-2 dark:bg-indigo-900">
              <Calendar className="h-5 w-5 text-indigo-600 dark:text-indigo-400" />
            </div>
            <div>
              <p className="text-sm font-medium">{formatTimestamp(summary.comparisonTimestamp)}</p>
              <p className="text-sm text-muted-foreground">Compared At</p>
            </div>
          </div>
        </div>

        {/* Change Percentage Badge */}
        <div className="mt-4 flex items-center justify-between">
          <div className="text-sm text-muted-foreground">
            Comparing batch <span className="font-mono text-foreground">{summary.currentBatchId}</span> with{' '}
            <span className="font-mono text-foreground">{summary.targetBatchId}</span>
          </div>
          <Badge variant={changePercentage > 50 ? 'critical' : changePercentage > 20 ? 'info' : 'neutral'}>
            {changePercentage}% Changed
          </Badge>
        </div>
      </CardContent>
    </Card>
  );
}

/**
 * Type-safe component export.
 *
 * @example
 * ```tsx
 * import { ComparisonSummary } from '@/features/file-comparison/ui/ComparisonSummary';
 * ```
 */
export default ComparisonSummary;
