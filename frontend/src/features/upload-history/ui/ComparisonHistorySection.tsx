/**
 * Comparison history section for batch detail page
 *
 * Shows list of all comparisons created for this batch.
 * Allows users to navigate to comparison results.
 *
 * Feature: 009-markdown-user-story (Added 2025-11-03)
 */

import { useNavigate } from '@tanstack/react-router';
import { useBatchComparisons } from '@/features/file-comparison/hooks/useBatchComparisons';
import { Card, CardContent, CardHeader, CardTitle } from '@/shared/ui/ui/card';
import { Badge } from '@/shared/ui/ui/badge';
import { Button } from '@/shared/ui/ui/button';
import { GitCompare, Calendar, Eye, Loader2 } from 'lucide-react';
import { formatDateTime } from '@/shared/lib/formatters';
import { COMPARISON_STATUS_LABELS, COMPARISON_STATUS_VARIANT } from '@/entities/comparison/model/comparisonStatus';

interface ComparisonHistorySectionProps {
  /** Current batch ID */
  batchId: string;
}


/**
 * Displays comparison history for a batch
 */
export function ComparisonHistorySection({ batchId }: ComparisonHistorySectionProps) {
  const navigate = useNavigate();
  const { data: comparisons, isLoading, error } = useBatchComparisons({ batchId });

  const handleViewComparison = (comparisonId: number) => {
    navigate({ to: `/account/comparisons/${comparisonId}` });
  };

  if (isLoading) {
    return (
      <Card>
        <CardHeader>
          <CardTitle className="flex items-center gap-2">
            <GitCompare className="h-5 w-5" />
            Comparison History
          </CardTitle>
        </CardHeader>
        <CardContent>
          <div className="flex items-center justify-center py-8">
            <Loader2 className="h-5 w-5 animate-spin text-muted-foreground" />
            <span className="ml-2 text-sm text-muted-foreground">Loading comparisons...</span>
          </div>
        </CardContent>
      </Card>
    );
  }

  if (error) {
    return (
      <Card>
        <CardHeader>
          <CardTitle className="flex items-center gap-2">
            <GitCompare className="h-5 w-5" />
            Comparison History
          </CardTitle>
        </CardHeader>
        <CardContent>
          <p className="text-sm text-red-600">
            Failed to load comparison history: {error.message}
          </p>
        </CardContent>
      </Card>
    );
  }

  if (!comparisons || comparisons.length === 0) {
    return (
      <Card>
        <CardHeader>
          <CardTitle className="flex items-center gap-2">
            <GitCompare className="h-5 w-5" />
            Comparison History
          </CardTitle>
        </CardHeader>
        <CardContent>
          <p className="text-sm text-muted-foreground">
            No comparisons created yet. Select files and click "Compare Files" to get started.
          </p>
        </CardContent>
      </Card>
    );
  }

  return (
    <Card>
      <CardHeader>
        <CardTitle className="flex items-center gap-2">
          <GitCompare className="h-5 w-5" />
          Comparison History ({comparisons.length})
        </CardTitle>
      </CardHeader>
      <CardContent>
        <div className="space-y-3">
          {comparisons.map((comparison) => (
            <div
              key={comparison.id}
              className="flex items-center justify-between rounded-lg border p-3 hover:bg-muted/50 transition-colors"
            >
              <div className="flex-1 space-y-1">
                <div className="flex items-center gap-2">
                  <span className="text-sm font-medium">
                    Comparison #{comparison.id}
                  </span>
                  <Badge variant={COMPARISON_STATUS_VARIANT[comparison.status]}>
                    {COMPARISON_STATUS_LABELS[comparison.status]}
                  </Badge>
                </div>
                <div className="flex items-center gap-4 text-xs text-muted-foreground">
                  <span className="flex items-center gap-1">
                    <Calendar className="h-3 w-3" />
                    {formatDateTime(comparison.createdAt)}
                  </span>
                  {comparison.totalFilesCompared > 0 && (
                    <>
                      <span>{comparison.totalFilesCompared} files compared</span>
                      {comparison.filesChanged !== undefined && comparison.filesChanged > 0 && (
                        <span className="text-orange-600 font-medium">
                          {comparison.filesChanged} changed
                        </span>
                      )}
                    </>
                  )}
                </div>
              </div>
              <Button
                variant="ghost"
                size="sm"
                onClick={() => handleViewComparison(comparison.id)}
                className="gap-2"
              >
                <Eye className="h-4 w-4" />
                View Results
              </Button>
            </div>
          ))}
        </div>
      </CardContent>
    </Card>
  );
}
