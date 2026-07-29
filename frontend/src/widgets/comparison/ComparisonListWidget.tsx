/**
 * T129: ComparisonListWidget with pagination
 * T132: Enhanced with conditional virtualization for large lists (>100 items)
 *
 * Widget that integrates ComparisonListView with TanStack Query for data fetching.
 * Handles:
 * - Pagination state management
 * - Status filter state
 * - Data fetching with useComparisons hook
 * - Error handling
 * - Navigation to comparison details
 * - Automatic virtualization for lists >100 items (T132)
 *
 * Phase 10: List Comparisons (Supporting Feature)
 * Priority: P3
 *
 * @module widgets/comparison/ComparisonListWidget
 */

import { useState } from 'react';
import { useNavigate } from '@tanstack/react-router';
import { useComparisons, type PagedComparisonResponse } from '@/features/file-comparison/hooks/useComparisons';
import { ComparisonListView } from '@/features/file-comparison/ui/ComparisonListView';
import { VirtualizedComparisonList } from '@/features/file-comparison/ui/VirtualizedComparisonList';
import { Button } from '@/shared/ui/ui/button';
import { Alert, AlertDescription } from '@/shared/ui/ui/alert';
import { ChevronLeft, ChevronRight } from 'lucide-react';

/**
 * T132: Threshold for using virtualized list.
 * Lists with more items than this will use VirtualizedComparisonList for better performance.
 */
const VIRTUALIZATION_THRESHOLD = 100;

export interface ComparisonListWidgetProps {
  /** Initial page number (0-indexed) */
  initialPage?: number;
  /** Page size (items per page) */
  pageSize?: number;
  /** Initial status filter */
  initialStatus?: string;
}

/**
 * Widget for displaying paginated comparison list with filtering.
 *
 * Features:
 * - Status filtering (All/COMPLETED/FAILED/IN_PROGRESS)
 * - Pagination controls (prev/next)
 * - Loading states during data fetch
 * - Error handling with retry
 * - Navigation to comparison details page
 *
 * @param initialPage - Starting page number (default: 0)
 * @param pageSize - Items per page (default: 10)
 * @param initialStatus - Starting status filter (default: undefined = all)
 *
 * @example
 * ```tsx
 * function ComparisonsPage() {
 *   return (
 *     <div>
 *       <h1>My Comparisons</h1>
 *       <ComparisonListWidget pageSize={20} />
 *     </div>
 *   );
 * }
 * ```
 */
export function ComparisonListWidget({
  initialPage = 0,
  pageSize = 10,
  initialStatus,
}: ComparisonListWidgetProps) {
  const [page, setPage] = useState(initialPage);
  const [status, setStatus] = useState<string | undefined>(initialStatus);
  const navigate = useNavigate();

  // Fetch comparisons with TanStack Query
  const { data: rawData, isLoading, error, refetch } = useComparisons({
    page,
    size: pageSize,
    status,
  });

  // Type assertion for proper TypeScript inference
  const data = rawData as PagedComparisonResponse | undefined;

  const handleStatusFilterChange = (newStatus?: string) => {
    setStatus(newStatus);
    setPage(0); // Reset to first page when filter changes
  };

  const handleViewDetails = (comparisonId: number) => {
    navigate({ to: `/account/comparisons/${comparisonId}` });
  };

  const handlePreviousPage = () => {
    if (page > 0) {
      setPage(page - 1);
    }
  };

  const handleNextPage = () => {
    if (data && page < data.totalPages - 1) {
      setPage(page + 1);
    }
  };

  // T132: Determine if virtualization should be used
  const shouldUseVirtualization = data && data.content.length > VIRTUALIZATION_THRESHOLD;

  return (
    <div className="space-y-4">
      {/* Error State */}
      {error && (
        <Alert variant="destructive">
          <AlertDescription>
            Failed to load comparisons: {error.message}
            <Button variant="outline" size="sm" onClick={() => refetch()} className="ml-4">
              Retry
            </Button>
          </AlertDescription>
        </Alert>
      )}

      {/* T132: Conditional List View - Use virtualization for large lists */}
      {shouldUseVirtualization && data ? (
        <VirtualizedComparisonList
          comparisons={data.content}
          isLoading={isLoading}
          selectedStatus={status}
          onStatusFilterChange={handleStatusFilterChange}
          onViewDetails={handleViewDetails}
        />
      ) : (
        <ComparisonListView
          comparisons={data?.content || []}
          isLoading={isLoading}
          selectedStatus={status}
          onStatusFilterChange={handleStatusFilterChange}
          onViewDetails={handleViewDetails}
        />
      )}

      {/* Pagination Controls */}
      {!isLoading && data && data.totalPages > 1 && (
        <div className="flex items-center justify-between border-t border-separator pt-4">
          <div className="text-sm text-muted-foreground">
            Page {page + 1} of {data.totalPages} ({data.totalElements} total)
            {shouldUseVirtualization && ' (virtualized for performance)'}
          </div>
          <div className="flex gap-2">
            <Button
              variant="outline"
              size="sm"
              onClick={handlePreviousPage}
              disabled={page === 0}
            >
              <ChevronLeft className="h-4 w-4 mr-1" />
              Previous
            </Button>
            <Button
              variant="outline"
              size="sm"
              onClick={handleNextPage}
              disabled={page >= data.totalPages - 1}
            >
              Next
              <ChevronRight className="h-4 w-4 ml-1" />
            </Button>
          </div>
        </div>
      )}
    </div>
  );
}
