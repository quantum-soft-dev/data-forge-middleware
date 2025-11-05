/**
 * T128: ComparisonListView component with status filters
 *
 * Displays a list of comparisons with:
 * - Status filter dropdown (All, COMPLETED, FAILED, IN_PROGRESS)
 * - Loading skeleton state
 * - Empty state
 * - Grid layout of ComparisonCard components
 * - Accessibility support (ARIA labels, semantic HTML)
 *
 * Phase 10: List Comparisons (Supporting Feature)
 * Priority: P3
 *
 * @module features/file-comparison/ui/ComparisonListView
 */

import { ComparisonCard } from '@/entities/comparison/ui/ComparisonCard';
import { Comparison } from '../hooks/useComparisons';
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from '@/shared/ui/ui/select';
import { Skeleton } from '@/shared/ui/ui/skeleton';
import { Filter } from 'lucide-react';

export interface ComparisonListViewProps {
  /** List of comparisons to display */
  comparisons: Comparison[];
  /** Loading state indicator */
  isLoading: boolean;
  /** Callback when status filter changes */
  onStatusFilterChange: (status?: string) => void;
  /** Currently selected status filter */
  selectedStatus?: string;
  /** Optional callback when View Details is clicked */
  onViewDetails?: (comparisonId: number) => void;
}

/**
 * List view component for displaying comparisons with filtering.
 *
 * Features:
 * - Status filter dropdown (All/COMPLETED/FAILED/IN_PROGRESS)
 * - Loading skeletons during data fetch
 * - Empty state when no comparisons
 * - Responsive grid layout
 * - Accessibility with proper ARIA roles
 *
 * @param comparisons - Array of comparison objects to display
 * @param isLoading - Shows loading skeletons when true
 * @param onStatusFilterChange - Called when user changes status filter
 * @param selectedStatus - Currently active status filter
 * @param onViewDetails - Optional callback for View Details button
 *
 * @example
 * ```tsx
 * const { data, isLoading } = useComparisons({ page: 0, status: statusFilter });
 *
 * <ComparisonListView
 *   comparisons={data?.content || []}
 *   isLoading={isLoading}
 *   selectedStatus={statusFilter}
 *   onStatusFilterChange={setStatusFilter}
 *   onViewDetails={(id) => navigate(`/comparisons/${id}`)}
 * />
 * ```
 */
export function ComparisonListView({
  comparisons,
  isLoading,
  onStatusFilterChange,
  selectedStatus,
  onViewDetails,
}: ComparisonListViewProps) {
  return (
    <div className="space-y-4">
      {/* Filter Bar */}
      <div className="flex items-center gap-2">
        <Filter className="h-4 w-4 text-muted-foreground" />
        <Select
          value={selectedStatus || 'all'}
          onValueChange={(value) => onStatusFilterChange(value === 'all' ? undefined : value)}
        >
          <SelectTrigger className="w-[200px]" aria-label="Status filter">
            <SelectValue placeholder="Filter by status" />
          </SelectTrigger>
          <SelectContent>
            <SelectItem value="all">All Statuses</SelectItem>
            <SelectItem value="COMPLETED">Completed</SelectItem>
            <SelectItem value="FAILED">Failed</SelectItem>
            <SelectItem value="IN_PROGRESS">In Progress</SelectItem>
          </SelectContent>
        </Select>
      </div>

      {/* Loading State */}
      {isLoading && (
        <div role="status" aria-label="Loading comparisons">
          <div className="space-y-4">
            {[1, 2, 3].map((i) => (
              <div key={i} className="border rounded-lg p-4 space-y-3">
                <div className="flex justify-between items-start">
                  <div className="space-y-2 flex-1">
                    <Skeleton className="h-5 w-32" />
                    <Skeleton className="h-4 w-48" />
                  </div>
                  <Skeleton className="h-6 w-24" />
                </div>
                <Skeleton className="h-4 w-full" />
                <div className="flex gap-4">
                  <Skeleton className="h-8 w-20" />
                  <Skeleton className="h-8 w-20" />
                  <Skeleton className="h-8 w-20" />
                </div>
              </div>
            ))}
          </div>
        </div>
      )}

      {/* Empty State */}
      {!isLoading && comparisons.length === 0 && (
        <div className="text-center py-12 border rounded-lg bg-muted/20">
          <p className="text-muted-foreground text-lg">
            No comparisons found
          </p>
          <p className="text-sm text-muted-foreground mt-2">
            {selectedStatus
              ? `No comparisons with status "${selectedStatus}"`
              : 'Start a comparison to see it here'}
          </p>
        </div>
      )}

      {/* Comparisons List */}
      {!isLoading && comparisons.length > 0 && (
        <ul role="list" className="space-y-4">
          {comparisons.map((comparison) => (
            <li key={comparison.id} role="listitem">
              <ComparisonCard
                comparison={comparison}
                onViewDetails={onViewDetails}
              />
            </li>
          ))}
        </ul>
      )}
    </div>
  );
}
