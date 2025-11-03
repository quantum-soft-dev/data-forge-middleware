/**
 * Widget for displaying file diffs with settings controls
 *
 * Feature: File Diff Comparison Between Upload Sessions
 * Task: T074 - Create DiffViewerWidget wrapper component
 * Phase: Phase 5 - User Story 3 (View Changes in Visual Editor)
 *
 * This widget wraps the DiffViewer component and adds controls for
 * adjusting display settings (split/unified view, line numbers, etc.)
 *
 * @module widgets/comparison/DiffViewerWidget
 */

import React from 'react';
import { DiffViewer } from '@/features/file-comparison/ui/DiffViewer';
import { useDiffViewerSettings } from '@/features/file-comparison/model/DiffViewerContext';
import type { UnifiedDiff } from '@/entities/comparison/model/types';
import { Card, CardContent, CardHeader, CardTitle } from '@/shared/ui/ui/card';
import { Button } from '@/shared/ui/ui/button';
import { Badge } from '@/shared/ui/ui/badge';
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuLabel,
  DropdownMenuSeparator,
  DropdownMenuCheckboxItem,
  DropdownMenuTrigger,
} from '@/shared/ui/ui/dropdown-menu';
import { Settings, Split, Maximize2, Eye, EyeOff } from 'lucide-react';
import { formatDiffSummary, getDiffStats } from '@/features/file-comparison/model/parseDiff';

/**
 * Props for DiffViewerWidget
 */
export interface DiffViewerWidgetProps {
  /**
   * The unified diff to display
   */
  diff: UnifiedDiff;

  /**
   * File name to display
   */
  fileName: string;

  /**
   * Optional change type badge (ADDED, MODIFIED, etc.)
   */
  changeType?: 'ADDED' | 'MODIFIED' | 'UNCHANGED' | 'REMOVED';

  /**
   * Optional CSS class name
   */
  className?: string;
}

/**
 * DiffViewerWidget component
 *
 * Provides a complete diff viewing experience with:
 * - Diff visualization using DiffViewer
 * - Settings controls (split/unified, line numbers, etc.)
 * - File metadata display
 * - Change statistics
 *
 * @example
 * ```tsx
 * import { DiffViewerWidget } from '@/widgets/comparison/DiffViewerWidget';
 * import { DiffViewerProvider } from '@/features/file-comparison/model/DiffViewerContext';
 *
 * function ComparisonPage() {
 *   const diff: UnifiedDiff = { ... };
 *
 *   return (
 *     <DiffViewerProvider>
 *       <DiffViewerWidget
 *         diff={diff}
 *         fileName="data.csv"
 *         changeType="MODIFIED"
 *       />
 *     </DiffViewerProvider>
 *   );
 * }
 * ```
 */
export function DiffViewerWidget({
  diff,
  fileName,
  changeType,
  className,
}: DiffViewerWidgetProps): React.ReactElement {
  const {
    settings,
    toggleSplitView,
    toggleLineNumbers,
    toggleInlineHighlight,
    toggleDiffOnly,
    resetSettings,
  } = useDiffViewerSettings();

  const stats = getDiffStats(diff);
  const summary = formatDiffSummary(stats.additions, stats.deletions);

  // Change type badge colors
  const getChangeTypeBadgeVariant = (type?: string) => {
    switch (type) {
      case 'ADDED':
        return 'default'; // green
      case 'MODIFIED':
        return 'secondary'; // orange/amber
      case 'REMOVED':
        return 'destructive'; // red
      case 'UNCHANGED':
        return 'outline'; // gray
      default:
        return 'default';
    }
  };

  return (
    <Card className={className}>
      <CardHeader className="space-y-1">
        <div className="flex items-center justify-between">
          <CardTitle className="flex items-center gap-2 text-lg">
            <span className="font-mono">{fileName}</span>
            {changeType && (
              <Badge variant={getChangeTypeBadgeVariant(changeType)} className="ml-2">
                {changeType}
              </Badge>
            )}
          </CardTitle>

          <div className="flex items-center gap-2">
            {/* Change summary */}
            <span className="text-sm text-muted-foreground">{summary}</span>

            {/* View mode toggle */}
            <Button
              variant="outline"
              size="sm"
              onClick={toggleSplitView}
              title={settings.splitView ? 'Switch to unified view' : 'Switch to split view'}
            >
              {settings.splitView ? (
                <>
                  <Maximize2 className="mr-1 h-4 w-4" />
                  Unified
                </>
              ) : (
                <>
                  <Split className="mr-1 h-4 w-4" />
                  Split
                </>
              )}
            </Button>

            {/* Settings dropdown */}
            <DropdownMenu>
              <DropdownMenuTrigger asChild>
                <Button variant="outline" size="sm" title="Diff viewer settings">
                  <Settings className="h-4 w-4" />
                </Button>
              </DropdownMenuTrigger>
              <DropdownMenuContent align="end" className="w-56">
                <DropdownMenuLabel>Display Settings</DropdownMenuLabel>
                <DropdownMenuSeparator />

                <DropdownMenuCheckboxItem
                  checked={settings.showLineNumbers}
                  onCheckedChange={toggleLineNumbers}
                >
                  Show Line Numbers
                </DropdownMenuCheckboxItem>

                <DropdownMenuCheckboxItem
                  checked={settings.highlightInline}
                  onCheckedChange={toggleInlineHighlight}
                >
                  Highlight Inline Changes
                </DropdownMenuCheckboxItem>

                <DropdownMenuCheckboxItem
                  checked={settings.showDiffOnly}
                  onCheckedChange={toggleDiffOnly}
                >
                  Show Only Changes
                </DropdownMenuCheckboxItem>

                <DropdownMenuSeparator />

                <DropdownMenuItem onClick={resetSettings}>
                  Reset to Defaults
                </DropdownMenuItem>
              </DropdownMenuContent>
            </DropdownMenu>
          </div>
        </div>

        {/* File metadata */}
        <div className="flex items-center gap-4 text-sm text-muted-foreground">
          <span>
            {diff.oldFileName || 'New file'} → {diff.newFileName || 'Deleted'}
          </span>
          <span>
            {stats.additions > 0 && <span className="text-green-600">+{stats.additions}</span>}
            {stats.additions > 0 && stats.deletions > 0 && ' / '}
            {stats.deletions > 0 && <span className="text-red-600">-{stats.deletions}</span>}
          </span>
        </div>
      </CardHeader>

      <CardContent className="p-0">
        <DiffViewer
          diff={diff}
          splitView={settings.splitView}
          showLineNumbers={settings.showLineNumbers}
          highlightInline={settings.highlightInline}
          ariaLabel={`Diff for ${fileName}`}
        />
      </CardContent>
    </Card>
  );
}

/**
 * Type-safe component export
 */
export default DiffViewerWidget;
