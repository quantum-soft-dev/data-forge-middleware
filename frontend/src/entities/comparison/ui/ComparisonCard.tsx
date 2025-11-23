/**
 * T127 + T116: ComparisonCard component with delete functionality
 *
 * List item component displaying comparison summary with:
 * - Status badge (COMPLETED/FAILED/IN_PROGRESS)
 * - Statistics preview (files compared, changed, added)
 * - Batch information
 * - Action buttons (view details, delete)
 *
 * Phase 10: List Comparisons (Supporting Feature)
 * T116: Delete button integration (Phase 9 deferred task)
 * Priority: P3
 *
 * @module entities/comparison/ui/ComparisonCard
 */

import { Card, CardHeader, CardTitle, CardDescription, CardContent, CardFooter } from '@/shared/ui/ui/card';
import { Badge } from '@/shared/ui/ui/badge';
import { Button } from '@/shared/ui/ui/button';
import { FileText, Calendar, Trash2 } from 'lucide-react';
import { Comparison } from '@/features/file-comparison/hooks/useComparisons';
import { useState } from 'react';
import { DeleteConfirmationDialog } from '@/features/file-comparison/ui/DeleteConfirmationDialog';
import { useDeleteComparison } from '@/features/file-comparison/hooks/useDeleteComparison';

export interface ComparisonCardProps {
  comparison: Comparison;
  onViewDetails?: (comparisonId: number) => void;
}

/**
 * Card component for displaying comparison in list view.
 *
 * Features:
 * - Color-coded status badges (green=COMPLETED, red=FAILED, yellow=IN_PROGRESS)
 * - File statistics summary
 * - Formatted timestamps
 * - View details button
 * - Delete button with confirmation dialog
 *
 * @param comparison - The comparison data to display
 * @param onViewDetails - Optional callback when "View Details" is clicked
 *
 * @example
 * ```tsx
 * <ComparisonCard
 *   comparison={comparison}
 *   onViewDetails={(id) => navigate(`/comparisons/${id}`)}
 * />
 * ```
 */
export function ComparisonCard({ comparison, onViewDetails }: ComparisonCardProps) {
  const [showDeleteDialog, setShowDeleteDialog] = useState(false);
  const { mutate: deleteComparison, isPending: isDeleting } = useDeleteComparison();

  const statusColors = {
    PENDING: 'bg-gray-100 text-gray-800 border-gray-200',
    COMPLETED: 'bg-green-100 text-green-800 border-green-200',
    FAILED: 'bg-red-100 text-red-800 border-red-200',
    IN_PROGRESS: 'bg-yellow-100 text-yellow-800 border-yellow-200',
  };

  const handleDelete = () => {
    deleteComparison(comparison.id.toString(), {
      onSuccess: () => {
        setShowDeleteDialog(false);
      },
    });
  };

  const formatDate = (dateString: string) => {
    return new Date(dateString).toLocaleString('en-US', {
      year: 'numeric',
      month: 'short',
      day: 'numeric',
      hour: '2-digit',
      minute: '2-digit',
    });
  };

  return (
    <>
      <Card className="hover:shadow-md transition-shadow">
        <CardHeader>
          <div className="flex items-start justify-between">
            <div>
              <CardTitle className="text-lg">
                Comparison #{comparison.id}
              </CardTitle>
              <CardDescription className="mt-1">
                <div className="flex items-center gap-2 text-sm">
                  <Calendar className="h-4 w-4" />
                  <span>{formatDate(comparison.createdAt)}</span>
                </div>
              </CardDescription>
            </div>
            <Badge className={statusColors[comparison.status]}>
              {comparison.status}
            </Badge>
          </div>
        </CardHeader>

        <CardContent>
          <div className="space-y-2">
            <div className="flex items-center gap-2 text-sm text-muted-foreground">
              <FileText className="h-4 w-4" />
              <span>
                {comparison.totalFilesCompared} files compared
              </span>
            </div>

            {comparison.status !== 'IN_PROGRESS' && (
              <div className="grid grid-cols-3 gap-4 mt-4">
                <div className="text-center">
                  <div className="text-2xl font-bold text-orange-600">
                    {comparison.filesChanged}
                  </div>
                  <div className="text-xs text-muted-foreground">Changed</div>
                </div>
                <div className="text-center">
                  <div className="text-2xl font-bold text-green-600">
                    {comparison.filesAdded}
                  </div>
                  <div className="text-xs text-muted-foreground">Added</div>
                </div>
                <div className="text-center">
                  <div className="text-2xl font-bold text-gray-600">
                    {comparison.filesUnchanged}
                  </div>
                  <div className="text-xs text-muted-foreground">Unchanged</div>
                </div>
              </div>
            )}

            {comparison.errorMessage && (
              <div className="mt-2 p-2 bg-red-50 border border-red-200 rounded text-sm text-red-800">
                Error: {comparison.errorMessage}
              </div>
            )}

            <div className="mt-4 space-y-1 text-sm text-muted-foreground">
              <div>Current: {comparison.currentBatchId.substring(0, 8)}...</div>
              <div>Target: {comparison.targetBatchId.substring(0, 8)}...</div>
            </div>
          </div>
        </CardContent>

        <CardFooter className="flex justify-between gap-2">
          <Button
            variant="outline"
            size="sm"
            onClick={() => onViewDetails?.(comparison.id)}
          >
            View Details
          </Button>

          {/* T116: Delete button with confirmation */}
          <Button
            variant="destructive"
            size="sm"
            onClick={() => setShowDeleteDialog(true)}
            disabled={isDeleting}
          >
            <Trash2 className="h-4 w-4 mr-2" />
            Delete
          </Button>
        </CardFooter>
      </Card>

      {/* Delete confirmation dialog */}
      <DeleteConfirmationDialog
        open={showDeleteDialog}
        title="Delete Comparison"
        description="Are you sure you want to delete this comparison? This action cannot be undone. All comparison results will be permanently deleted."
        onConfirm={handleDelete}
        onCancel={() => setShowDeleteDialog(false)}
        isLoading={isDeleting}
      />
    </>
  );
}
