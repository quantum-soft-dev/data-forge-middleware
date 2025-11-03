/**
 * Page component for displaying detailed comparison information.
 *
 * This page shows:
 * - Comparison status and metadata
 * - Summary statistics
 * - List of file differences with pagination
 * - Loading states during processing
 * - Error handling
 *
 * @module pages/comparison/ComparisonDetailPage
 *
 * Feature: File Diff Comparison Between Upload Sessions
 * Task: T066 - Create ComparisonDetailPage
 * Phase: Phase 4 - User Story 2 (Compare Files)
 */

import React, { useState } from 'react';
import { useParams, useNavigate } from 'react-router-dom';
import { useQuery } from '@tanstack/react-query';
import { ArrowLeft, RefreshCw, Download, Trash2, AlertCircle } from 'lucide-react';

import { useComparisonDetails } from '@/features/file-comparison/hooks/useComparisonDetails';
import { ComparisonSummary } from '@/features/file-comparison/ui/ComparisonSummary';
import { comparisonApi } from '@/features/file-comparison/api/comparisonApi';

import { Button } from '@/shared/ui/ui/button';
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from '@/shared/ui/ui/card';
import { Badge } from '@/shared/ui/ui/badge';
import { Alert, AlertDescription, AlertTitle } from '@/shared/ui/ui/alert';
import { Skeleton } from '@/shared/ui/ui/skeleton';
import {
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableHeader,
  TableRow
} from '@/shared/ui/ui/table';

/**
 * Status badge colors mapping.
 */
const statusColors = {
  PENDING: 'secondary' as const,
  IN_PROGRESS: 'default' as const,
  COMPLETED: 'success' as const,
  FAILED: 'destructive' as const,
};

/**
 * Change type badge colors mapping.
 */
const changeTypeColors = {
  ADDED: 'success' as const,
  MODIFIED: 'default' as const,
  UNCHANGED: 'secondary' as const,
  REMOVED: 'destructive' as const,
};

/**
 * Page component for displaying comparison details.
 *
 * Features:
 * - Auto-polling while comparison is IN_PROGRESS
 * - Summary statistics display
 * - Paginated results table
 * - Download options (ZIP and report)
 * - Delete functionality
 * - Loading skeletons
 * - Error handling
 *
 * @returns The rendered page component
 *
 * @example
 * ```tsx
 * // Route configuration
 * <Route path="/comparisons/:comparisonId" element={<ComparisonDetailPage />} />
 * ```
 */
export function ComparisonDetailPage(): React.ReactElement {
  const { comparisonId } = useParams<{ comparisonId: string }>();
  const navigate = useNavigate();
  const [resultsPage, setResultsPage] = useState(0);
  const [changeTypeFilter, setChangeTypeFilter] = useState<string | undefined>(undefined);

  // Parse comparison ID
  const parsedId = comparisonId ? parseInt(comparisonId, 10) : null;

  // Fetch comparison details with auto-polling
  const {
    data: comparison,
    isLoading: isLoadingComparison,
    error: comparisonError,
    refetch: refetchComparison,
  } = useComparisonDetails({
    comparisonId: parsedId ?? 0,
    enabled: parsedId !== null,
  });

  // Fetch summary if comparison is completed
  const {
    data: summary,
    isLoading: isLoadingSummary,
  } = useQuery({
    queryKey: ['comparison', parsedId, 'summary'],
    queryFn: () => comparisonApi.getComparisonSummary(parsedId!),
    enabled: parsedId !== null && comparison?.status === 'COMPLETED',
  });

  // Fetch results if comparison is completed
  const {
    data: results,
    isLoading: isLoadingResults,
  } = useQuery({
    queryKey: ['comparison', parsedId, 'results', resultsPage, changeTypeFilter],
    queryFn: () =>
      comparisonApi.getComparisonResults(parsedId!, {
        page: resultsPage,
        size: 20,
        changeType: changeTypeFilter,
      }),
    enabled: parsedId !== null && comparison?.status === 'COMPLETED',
  });

  // Handle back navigation
  const handleBack = () => {
    navigate('/comparisons');
  };

  // Handle download ZIP
  const handleDownloadZip = async () => {
    if (!parsedId) return;
    try {
      const blob = await comparisonApi.downloadComparisonZip(parsedId);
      const url = URL.createObjectURL(blob);
      const link = document.createElement('a');
      link.href = url;
      link.download = `comparison-${parsedId}.zip`;
      link.click();
      URL.revokeObjectURL(url);
    } catch (error) {
      console.error('Failed to download ZIP:', error);
    }
  };

  // Handle download report
  const handleDownloadReport = async () => {
    if (!parsedId) return;
    try {
      const blob = await comparisonApi.downloadSummaryReport(parsedId);
      const url = URL.createObjectURL(blob);
      const link = document.createElement('a');
      link.href = url;
      link.download = `comparison-summary-${parsedId}.txt`;
      link.click();
      URL.revokeObjectURL(url);
    } catch (error) {
      console.error('Failed to download report:', error);
    }
  };

  // Handle delete
  const handleDelete = async () => {
    if (!parsedId) return;
    if (!confirm('Are you sure you want to delete this comparison? This action cannot be undone.')) {
      return;
    }
    try {
      await comparisonApi.deleteComparison(parsedId);
      navigate('/comparisons');
    } catch (error) {
      console.error('Failed to delete comparison:', error);
    }
  };

  // Loading state
  if (isLoadingComparison) {
    return (
      <div className="container mx-auto py-8">
        <div className="space-y-4">
          <Skeleton className="h-10 w-64" />
          <Skeleton className="h-32 w-full" />
          <Skeleton className="h-64 w-full" />
        </div>
      </div>
    );
  }

  // Error state
  if (comparisonError || !comparison) {
    return (
      <div className="container mx-auto py-8">
        <Alert variant="destructive">
          <AlertCircle className="h-4 w-4" />
          <AlertTitle>Error</AlertTitle>
          <AlertDescription>
            {comparisonError?.message || 'Comparison not found'}
          </AlertDescription>
        </Alert>
        <Button onClick={handleBack} className="mt-4" variant="outline">
          <ArrowLeft className="mr-2 h-4 w-4" />
          Back to Comparisons
        </Button>
      </div>
    );
  }

  const isInProgress = comparison.status === 'IN_PROGRESS';
  const isCompleted = comparison.status === 'COMPLETED';
  const isFailed = comparison.status === 'FAILED';

  return (
    <div className="container mx-auto py-8 space-y-6">
      {/* Header */}
      <div className="flex items-center justify-between">
        <div className="flex items-center gap-4">
          <Button onClick={handleBack} variant="outline" size="sm">
            <ArrowLeft className="mr-2 h-4 w-4" />
            Back
          </Button>
          <div>
            <h1 className="text-3xl font-bold">Comparison #{comparison.id}</h1>
            <p className="text-muted-foreground">
              Created {new Date(comparison.createdAt).toLocaleString()}
            </p>
          </div>
        </div>
        <div className="flex items-center gap-2">
          <Badge variant={statusColors[comparison.status]}>
            {comparison.status}
          </Badge>
          {isInProgress && (
            <RefreshCw className="h-4 w-4 animate-spin text-muted-foreground" />
          )}
        </div>
      </div>

      {/* In Progress Alert */}
      {isInProgress && (
        <Alert>
          <RefreshCw className="h-4 w-4 animate-spin" />
          <AlertTitle>Processing</AlertTitle>
          <AlertDescription>
            Comparison is in progress. This page will automatically update when complete.
            ({comparison.totalFilesCompared} / {comparison.totalFilesCompared + (comparison.filesChanged || 0)} files processed)
          </AlertDescription>
        </Alert>
      )}

      {/* Failed Alert */}
      {isFailed && (
        <Alert variant="destructive">
          <AlertCircle className="h-4 w-4" />
          <AlertTitle>Comparison Failed</AlertTitle>
          <AlertDescription>
            {comparison.errorMessage || 'An error occurred during comparison processing.'}
          </AlertDescription>
        </Alert>
      )}

      {/* Actions */}
      {isCompleted && (
        <div className="flex gap-2">
          <Button onClick={handleDownloadZip} variant="outline">
            <Download className="mr-2 h-4 w-4" />
            Download ZIP
          </Button>
          <Button onClick={handleDownloadReport} variant="outline">
            <Download className="mr-2 h-4 w-4" />
            Download Report
          </Button>
          <Button onClick={handleDelete} variant="destructive" className="ml-auto">
            <Trash2 className="mr-2 h-4 w-4" />
            Delete
          </Button>
        </div>
      )}

      {/* Summary (only for completed comparisons) */}
      {isCompleted && summary && !isLoadingSummary && (
        <ComparisonSummary summary={summary} />
      )}

      {/* Results Table (only for completed comparisons) */}
      {isCompleted && (
        <Card>
          <CardHeader>
            <CardTitle>File Differences</CardTitle>
            <CardDescription>
              Detailed list of all file changes
            </CardDescription>
          </CardHeader>
          <CardContent>
            {isLoadingResults ? (
              <div className="space-y-2">
                <Skeleton className="h-10 w-full" />
                <Skeleton className="h-10 w-full" />
                <Skeleton className="h-10 w-full" />
              </div>
            ) : results && results.content.length > 0 ? (
              <>
                <Table>
                  <TableHeader>
                    <TableRow>
                      <TableHead>File Name</TableHead>
                      <TableHead>Change Type</TableHead>
                      <TableHead>Lines Added</TableHead>
                      <TableHead>Lines Deleted</TableHead>
                      <TableHead>Change Size</TableHead>
                    </TableRow>
                  </TableHeader>
                  <TableBody>
                    {results.content.map((result) => (
                      <TableRow key={result.id}>
                        <TableCell className="font-medium">{result.fileName}</TableCell>
                        <TableCell>
                          <Badge variant={changeTypeColors[result.changeType]}>
                            {result.changeType}
                          </Badge>
                        </TableCell>
                        <TableCell className="text-green-600">+{result.lineAdditions}</TableCell>
                        <TableCell className="text-red-600">-{result.lineDeletions}</TableCell>
                        <TableCell>{result.changeSize} bytes</TableCell>
                      </TableRow>
                    ))}
                  </TableBody>
                </Table>

                {/* Pagination */}
                {results.totalPages > 1 && (
                  <div className="flex items-center justify-between mt-4">
                    <p className="text-sm text-muted-foreground">
                      Showing {resultsPage * 20 + 1} to {Math.min((resultsPage + 1) * 20, results.totalElements)} of {results.totalElements} results
                    </p>
                    <div className="flex gap-2">
                      <Button
                        variant="outline"
                        size="sm"
                        onClick={() => setResultsPage(p => Math.max(0, p - 1))}
                        disabled={resultsPage === 0}
                      >
                        Previous
                      </Button>
                      <Button
                        variant="outline"
                        size="sm"
                        onClick={() => setResultsPage(p => p + 1)}
                        disabled={resultsPage >= results.totalPages - 1}
                      >
                        Next
                      </Button>
                    </div>
                  </div>
                )}
              </>
            ) : (
              <p className="text-center text-muted-foreground py-8">
                No results found
              </p>
            )}
          </CardContent>
        </Card>
      )}
    </div>
  );
}

/**
 * Type-safe page export.
 *
 * @example
 * ```tsx
 * import { ComparisonDetailPage } from '@/pages/comparison/ComparisonDetailPage';
 * ```
 */
export default ComparisonDetailPage;
