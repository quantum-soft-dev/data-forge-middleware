/**
 * Widget for displaying comparison summary with enhanced styling and navigation.
 *
 * Feature: File Diff Comparison Between Upload Sessions
 * Task: T083 - Create ComparisonSummaryWidget with enhanced styling and icons
 * Phase: Phase 6 - User Story 5 (View Summary Report)
 *
 * This widget wraps the ComparisonSummary component and adds:
 * - Navigation links to upload sessions
 * - "View Details" button to see individual file results
 * - Enhanced visual styling with icons
 * - Responsive layout
 *
 * @module widgets/comparison/ComparisonSummaryWidget
 */

import React from 'react';
import { ComparisonSummary } from '@/features/file-comparison/ui/ComparisonSummary';
import type { ComparisonSummary as ComparisonSummaryType } from '@/entities/comparison/model/types';
import { Card, CardContent, CardDescription, CardFooter, CardHeader, CardTitle } from '@/shared/ui/ui/card';
import { Button } from '@/shared/ui/ui/button';
import { Badge } from '@/shared/ui/ui/badge';
import { Separator } from '@/shared/ui/ui/separator';
import { ExternalLink, FileText, ArrowRight, Calendar, Database } from 'lucide-react';
import DownloadReportButton from '@/features/file-comparison/ui/DownloadReportButton';

/**
 * Props for ComparisonSummaryWidget
 */
export interface ComparisonSummaryWidgetProps {
  /**
   * The comparison summary data to display
   */
  summary: ComparisonSummaryType;

  /**
   * The comparison ID for navigation to details
   */
  comparisonId: number;

  /**
   * Optional callback when "View Details" is clicked
   */
  onViewDetails?: () => void;

  /**
   * Optional callback when current session link is clicked
   */
  onViewCurrentSession?: () => void;

  /**
   * Optional callback when target session link is clicked
   */
  onViewTargetSession?: () => void;

  /**
   * Optional CSS class name
   */
  className?: string;
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
 * ComparisonSummaryWidget component
 *
 * Provides an enhanced comparison summary view with:
 * - Statistics display using ComparisonSummary component
 * - Links to upload sessions (batch pages)
 * - "View Details" button for individual file results
 * - Timestamp and change size information
 *
 * @example
 * ```tsx
 * import { ComparisonSummaryWidget } from '@/widgets/comparison/ComparisonSummaryWidget';
 *
 * function ComparisonPage() {
 *   const { data: summary } = useQuery({
 *     queryKey: ['comparison', comparisonId, 'summary'],
 *     queryFn: () => comparisonApi.getComparisonSummary(comparisonId),
 *   });
 *
 *   if (!summary) return <div>Loading...</div>;
 *
 *   return (
 *     <ComparisonSummaryWidget
 *       summary={summary}
 *       comparisonId={comparisonId}
 *       onViewDetails={() => navigate(`/comparisons/${comparisonId}`)}
 *     />
 *   );
 * }
 * ```
 */
export function ComparisonSummaryWidget({
  summary,
  comparisonId,
  onViewDetails,
  onViewCurrentSession,
  onViewTargetSession,
  className,
}: ComparisonSummaryWidgetProps): React.ReactElement {
  const changePercentage =
    summary.totalFilesCompared > 0
      ? Math.round(((summary.filesChanged + summary.filesAdded) / summary.totalFilesCompared) * 100)
      : 0;

  return (
    <div className={className}>
      <Card>
        <CardHeader>
          <div className="flex items-center justify-between">
            <div>
              <CardTitle className="flex items-center gap-2 text-2xl">
                <FileText className="h-6 w-6" />
                Comparison Summary
              </CardTitle>
              <CardDescription className="mt-2">
                Overview of changes between upload sessions
              </CardDescription>
            </div>
            <Badge
              variant={changePercentage > 50 ? 'critical' : changePercentage > 20 ? 'info' : 'neutral'}
              className="text-lg px-3 py-1"
            >
              {changePercentage}% Changed
            </Badge>
          </div>
        </CardHeader>

        <Separator />

        <CardContent className="pt-6">
          {/* Display the core ComparisonSummary component */}
          <ComparisonSummary summary={summary} />

          {/* T085: Additional metadata with links */}
          <div className="mt-6 space-y-4">
            <Separator />

            {/* Timestamp */}
            <div className="flex items-center gap-3">
              <div className="flex items-center gap-2 text-sm text-muted-foreground">
                <Calendar className="h-4 w-4" />
                <span>Compared on:</span>
              </div>
              <span className="text-sm font-medium">{formatTimestamp(summary.comparisonTimestamp)}</span>
            </div>

            {/* Change Size */}
            <div className="flex items-center gap-3">
              <div className="flex items-center gap-2 text-sm text-muted-foreground">
                <Database className="h-4 w-4" />
                <span>Total change size:</span>
              </div>
              <span className="text-sm font-medium">{formatBytes(summary.totalChangeSize)}</span>
            </div>

            <Separator />

            {/* Upload Session Links */}
            <div className="space-y-3">
              <h4 className="text-sm font-semibold text-muted-foreground">Upload Sessions</h4>
              <div className="grid gap-3 md:grid-cols-2">
                {/* Current Session Link */}
                <button
                  onClick={onViewCurrentSession}
                  className="flex items-center justify-between rounded-lg border p-3 hover:bg-muted transition-colors text-left"
                  type="button"
                >
                  <div>
                    <p className="text-xs text-muted-foreground mb-1">Current Session</p>
                    <p className="font-mono text-sm">{summary.currentBatchId}</p>
                  </div>
                  <ExternalLink className="h-4 w-4 text-muted-foreground" />
                </button>

                {/* Target Session Link */}
                <button
                  onClick={onViewTargetSession}
                  className="flex items-center justify-between rounded-lg border p-3 hover:bg-muted transition-colors text-left"
                  type="button"
                >
                  <div>
                    <p className="text-xs text-muted-foreground mb-1">Target Session</p>
                    <p className="font-mono text-sm">{summary.targetBatchId}</p>
                  </div>
                  <ExternalLink className="h-4 w-4 text-muted-foreground" />
                </button>
              </div>
            </div>
          </div>
        </CardContent>

        <Separator />

        {/* T084 & T104: Action buttons - View Details and Download Report */}
        <CardFooter className="flex justify-between items-center pt-6">
          <p className="text-sm text-muted-foreground">
            View detailed file-by-file comparison results or download summary report
          </p>
          <div className="flex gap-3">
            {/* T104: Download Report button */}
            <DownloadReportButton
              comparisonId={comparisonId.toString()}
              variant="outline"
              size="default"
            />
            {/* T084: View Details button */}
            <Button
              onClick={onViewDetails}
              size="default"
              className="gap-2"
            >
              View Details
              <ArrowRight className="h-4 w-4" />
            </Button>
          </div>
        </CardFooter>
      </Card>
    </div>
  );
}

/**
 * Type-safe component export
 */
export default ComparisonSummaryWidget;
