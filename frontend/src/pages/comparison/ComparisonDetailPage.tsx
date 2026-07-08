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
import { useParams, useNavigate } from '@tanstack/react-router';
import { useQuery } from '@tanstack/react-query';
import { ArrowLeft, RefreshCw, Trash2, AlertCircle, ChevronLeft, ChevronRight, Eye, List } from 'lucide-react';

import { useComparisonDetails } from '@/features/file-comparison/hooks/useComparisonDetails';
import { ComparisonSummary } from '@/features/file-comparison/ui/ComparisonSummary';
import { DiffViewerWidget } from '@/widgets/comparison/DiffViewerWidget';
import { DiffViewerProvider } from '@/features/file-comparison/model/DiffViewerContext';
import { comparisonApi } from '@/features/file-comparison/api/comparisonApi';
import DownloadButton from '@/features/file-comparison/ui/DownloadButton';
import type { ChangeType } from '@/entities/comparison/model/types';

import { Button } from '@/shared/ui/ui/button';
import { PageHeader } from '@/shared/ui/page-header';
import { COMPARISON_STATUS_LABELS, COMPARISON_STATUS_VARIANT } from '@/entities/comparison/model/comparisonStatus';
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
import { Tabs, TabsContent, TabsList, TabsTrigger } from '@/shared/ui/ui/tabs';


/**
 * Change type badge colors mapping.
 */
const changeTypeColors: Record<string, 'success' | 'info' | 'neutral' | 'critical'> = {
  ADDED: 'success',
  MODIFIED: 'info',
  UNCHANGED: 'neutral',
  REMOVED: 'critical',
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
  const { comparisonId } = useParams({ from: '/account/comparisons/$comparisonId' });
  const navigate = useNavigate();
  const [resultsPage, setResultsPage] = useState(0);
  const [changeTypeFilter, setChangeTypeFilter] = useState<ChangeType | undefined>(undefined);
  const [currentFileIndex, setCurrentFileIndex] = useState(0);
  const [viewMode, setViewMode] = useState<'list' | 'diff'>('list');

  // Parse comparison ID
  const parsedId = comparisonId ? parseInt(comparisonId, 10) : null;

  // Fetch comparison details with auto-polling
  const {
    data: comparison,
    isLoading: isLoadingComparison,
    error: comparisonError,
  } = useComparisonDetails({
    comparisonId: parsedId ?? 0,
    enabled: parsedId !== null,
  });

  // Check completion status (must be before conditional queries for hooks order)
  const isCompleted = comparison?.status === 'COMPLETED';

  // Fetch summary if comparison is completed
  const {
    data: summary,
    isLoading: isLoadingSummary,
  } = useQuery({
    queryKey: ['comparison', parsedId, 'summary'],
    queryFn: () => comparisonApi.getComparisonSummary(parsedId!),
    enabled: parsedId !== null && isCompleted,
  });

  // Fetch results if comparison is completed
  const {
    data: results,
    isLoading: isLoadingResults,
    error: resultsError,
  } = useQuery({
    queryKey: ['comparison', parsedId, 'results', resultsPage, changeTypeFilter],
    queryFn: () =>
      comparisonApi.getComparisonResults(parsedId!, {
        page: resultsPage,
        size: 20,
        changeType: changeTypeFilter,
      }),
    enabled: parsedId !== null && isCompleted,
  });

  // Get current file for diff viewer (before early returns to maintain hooks order)
  const currentFile = results && results.content.length > 0 ? results.content[currentFileIndex] : null;
  const hasMultipleFiles = results && results.content.length > 1;

  // Parse unifiedDiff from JSON string to object with error handling
  // IMPORTANT: useMemo must be called unconditionally (React Hooks rules)
  const parsedDiff = React.useMemo(() => {
    if (!isCompleted || !currentFile?.unifiedDiff) return null;
    try {
      return JSON.parse(currentFile.unifiedDiff);
    } catch (error) {
      console.error('Failed to parse unified diff:', error);
      return null;
    }
  }, [isCompleted, currentFile?.unifiedDiff]);

  // Debug logging
  console.log('[ComparisonDetailPage] Results data:', results);
  console.log('[ComparisonDetailPage] Is loading results:', isLoadingResults);
  console.log('[ComparisonDetailPage] Results error:', resultsError);
  console.log('[ComparisonDetailPage] Comparison status:', comparison?.status);

  // Handle back navigation - return to the current batch (source) detail page
  const handleBack = () => {
    if (comparison?.currentBatchId) {
      navigate({ to: `/account/upload-history/${comparison.currentBatchId}` });
    } else {
      // Fallback to upload history list if batch ID is not available
      navigate({ to: '/account/upload-history' });
    }
  };

  // Download handled by DownloadButton component (Phase 7 - Task T097)

  // Handle delete
  const handleDelete = async () => {
    if (!parsedId) return;
    if (!confirm('Are you sure you want to delete this comparison? This action cannot be undone.')) {
      return;
    }
    try {
      await comparisonApi.deleteComparison(parsedId);
      // After deletion, return to the batch detail page
      if (comparison?.currentBatchId) {
        navigate({ to: `/account/upload-history/${comparison.currentBatchId}` });
      } else {
        navigate({ to: '/account/upload-history' });
      }
    } catch (error) {
      console.error('Failed to delete comparison:', error);
    }
  };

  // Handle file navigation
  const handlePreviousFile = () => {
    setCurrentFileIndex((prev) => Math.max(0, prev - 1));
  };

  const handleNextFile = () => {
    if (results && results.content.length > 0) {
      setCurrentFileIndex((prev) => Math.min(results.content.length - 1, prev + 1));
    }
  };

  const handleFileSelect = (index: number) => {
    setCurrentFileIndex(index);
    setViewMode('diff');
  };

  // Loading state
  if (isLoadingComparison) {
    return (
      <div className="container mx-auto max-w-[1120px] px-6 py-6">
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
      <div className="container mx-auto max-w-[1120px] px-6 py-6">
        <Alert variant="destructive">
          <AlertCircle className="h-4 w-4" />
          <AlertTitle>Error</AlertTitle>
          <AlertDescription>
            {comparisonError?.message || 'Comparison not found'}
          </AlertDescription>
        </Alert>
        <Button onClick={() => navigate({ to: '/account/upload-history' })} className="mt-4" variant="outline">
          <ArrowLeft className="mr-2 h-4 w-4" />
          Back to Upload History
        </Button>
      </div>
    );
  }

  const isInProgress = comparison.status === 'IN_PROGRESS';
  // isCompleted is already defined above (before conditional queries)
  const isFailed = comparison.status === 'FAILED';

  return (
    <DiffViewerProvider>
      <div className="container mx-auto max-w-[1120px] space-y-6 px-6 py-6">
      {/* Header */}
      <PageHeader
        breadcrumb={
          <Button onClick={handleBack} variant="outline" size="sm">
            <ArrowLeft className="mr-2 h-4 w-4" />
            Back
          </Button>
        }
        title={`Comparison #${comparison.id}`}
        subtitle={`Created ${new Date(comparison.createdAt).toLocaleString()}`}
        actions={
          <>
            <Badge variant={COMPARISON_STATUS_VARIANT[comparison.status]}>
              {COMPARISON_STATUS_LABELS[comparison.status]}
            </Badge>
            {isInProgress && (
              <RefreshCw className="h-4 w-4 animate-spin text-muted-foreground" />
            )}
          </>
        }
      />

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

      {/* Actions (Phase 7 - Task T097) */}
      {isCompleted && (
        <div className="flex gap-2">
          <DownloadButton comparisonId={comparisonId} variant="outline" />
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

      {/* Results Section (only for completed comparisons) */}
      {isCompleted && (
        <Tabs value={viewMode} onValueChange={(value) => setViewMode(value as 'list' | 'diff')} className="w-full">
          <TabsList className="grid w-full max-w-md grid-cols-2">
            <TabsTrigger value="list">
              <List className="mr-2 h-4 w-4" />
              List View
            </TabsTrigger>
            <TabsTrigger value="diff" disabled={!currentFile}>
              <Eye className="mr-2 h-4 w-4" />
              Diff View
            </TabsTrigger>
          </TabsList>

          {/* List View Tab */}
          <TabsContent value="list" className="mt-6">
            <Card>
              <CardHeader>
                <div className="flex items-center justify-between">
                  <div>
                    <CardTitle>File Differences</CardTitle>
                    <CardDescription>
                      Click on a file to view its diff
                    </CardDescription>
                  </div>
                  <div className="flex gap-2">
                    <select
                      value={changeTypeFilter || 'all'}
                      onChange={(e) => {
                        const value = e.target.value;
                        setChangeTypeFilter(value === 'all' ? undefined : value as ChangeType);
                        setResultsPage(0); // Reset to first page when filtering
                      }}
                      className="h-10 px-3 py-2 text-sm border border-input bg-background rounded-md focus:outline-none focus:ring-2 focus:ring-ring"
                    >
                      <option value="all">All Changes</option>
                      <option value="MODIFIED">Modified</option>
                      <option value="ADDED">Added</option>
                      <option value="REMOVED">Removed</option>
                      <option value="UNCHANGED">Unchanged</option>
                    </select>
                  </div>
                </div>
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
                          <TableHead>Actions</TableHead>
                        </TableRow>
                      </TableHeader>
                      <TableBody>
                        {results.content.map((result, index) => (
                          <TableRow key={result.id}>
                            <TableCell className="font-medium">{result.fileName || `File ${result.fileId.substring(0, 8)}`}</TableCell>
                            <TableCell>
                              <Badge variant={changeTypeColors[result.changeType]}>
                                {result.changeType}
                              </Badge>
                            </TableCell>
                            <TableCell className="text-green-600">+{result.lineAdditions}</TableCell>
                            <TableCell className="text-red-600">-{result.lineDeletions}</TableCell>
                            <TableCell>{result.changeSize} bytes</TableCell>
                            <TableCell>
                              <Button
                                variant="ghost"
                                size="sm"
                                onClick={() => handleFileSelect(index)}
                              >
                                <Eye className="mr-1 h-4 w-4" />
                                View Diff
                              </Button>
                            </TableCell>
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
          </TabsContent>

          {/* Diff View Tab */}
          <TabsContent value="diff" className="mt-6 space-y-4">
            {currentFile && parsedDiff ? (
              <>
                {/* File Navigation */}
                {hasMultipleFiles && (
                  <div className="flex items-center justify-between">
                    <Button
                      variant="outline"
                      size="sm"
                      onClick={handlePreviousFile}
                      disabled={currentFileIndex === 0}
                    >
                      <ChevronLeft className="mr-1 h-4 w-4" />
                      Previous File
                    </Button>
                    <span className="text-sm text-muted-foreground">
                      File {currentFileIndex + 1} of {results?.content.length || 0}
                    </span>
                    <Button
                      variant="outline"
                      size="sm"
                      onClick={handleNextFile}
                      disabled={currentFileIndex >= (results?.content.length || 0) - 1}
                    >
                      Next File
                      <ChevronRight className="ml-1 h-4 w-4" />
                    </Button>
                  </div>
                )}

                {/* Diff Viewer Widget */}
                <DiffViewerWidget
                  diff={parsedDiff}
                  fileName={currentFile.fileName || 'Unknown file'}
                  changeType={currentFile.changeType}
                />
              </>
            ) : (
              <Card>
                <CardContent className="py-8">
                  <p className="text-center text-muted-foreground">
                    Select a file from the list to view its diff
                  </p>
                </CardContent>
              </Card>
            )}
          </TabsContent>
        </Tabs>
      )}
      </div>
    </DiffViewerProvider>
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
