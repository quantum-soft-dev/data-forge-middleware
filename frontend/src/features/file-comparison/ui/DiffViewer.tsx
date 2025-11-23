/**
 * Component for displaying file diffs with syntax highlighting
 *
 * Feature: File Diff Comparison Between Upload Sessions
 * Task: T071 - Create DiffViewer component with react-diff-viewer-continued
 * Phase: Phase 5 - User Story 3 (View Changes in Visual Editor)
 *
 * This component wraps react-diff-viewer-continued to display unified diffs
 * with syntax highlighting, line numbers, and change indicators.
 *
 * @module features/file-comparison/ui/DiffViewer
 */

import React, { lazy, Suspense } from 'react';
import type { UnifiedDiff } from '@/entities/comparison/model/types';
import { parseDiff } from '../model/parseDiff';
import { Card, CardContent } from '@/shared/ui/ui/card';
import { Skeleton } from '@/shared/ui/ui/skeleton';

// Lazy load the diff viewer to reduce initial bundle size
const ReactDiffViewer = lazy(() => import('react-diff-viewer-continued'));

/**
 * Props for the DiffViewer component
 */
export interface DiffViewerProps {
  /**
   * The unified diff structure from the API
   */
  diff: UnifiedDiff;

  /**
   * Optional file name to display
   */
  fileName?: string;

  /**
   * Split view (side-by-side) or unified view
   * @default 'unified'
   */
  splitView?: boolean;

  /**
   * Show line numbers
   * @default true
   */
  showLineNumbers?: boolean;

  /**
   * Highlight inline changes
   * @default true
   */
  highlightInline?: boolean;

  /**
   * Optional CSS class name
   */
  className?: string;

  /**
   * Optional aria-label for accessibility
   */
  ariaLabel?: string;
}

/**
 * Loading skeleton displayed while diff viewer loads
 */
function DiffViewerSkeleton() {
  return (
    <Card>
      <CardContent className="p-4">
        <div className="space-y-2">
          <Skeleton className="h-8 w-full" />
          <Skeleton className="h-8 w-full" />
          <Skeleton className="h-8 w-full" />
          <Skeleton className="h-8 w-full" />
          <Skeleton className="h-8 w-full" />
        </div>
      </CardContent>
    </Card>
  );
}

/**
 * DiffViewer component for displaying file diffs
 *
 * Features:
 * - Lazy loading for bundle optimization
 * - Syntax highlighting
 * - Line numbers
 * - Split or unified view
 * - Accessibility support with ARIA labels
 *
 * @example
 * ```tsx
 * import { DiffViewer } from '@/features/file-comparison/ui/DiffViewer';
 *
 * function MyComponent() {
 *   const diff: UnifiedDiff = {
 *     hunks: [...],
 *     oldFileName: 'file.txt',
 *     newFileName: 'file.txt'
 *   };
 *
 *   return <DiffViewer diff={diff} fileName="file.txt" />;
 * }
 * ```
 */
export function DiffViewer({
  diff,
  fileName,
  splitView = false,
  showLineNumbers = true,
  highlightInline = true,
  className,
  ariaLabel,
}: DiffViewerProps): React.ReactElement {
  // Parse the unified diff into old and new values for react-diff-viewer
  const { oldValue, newValue } = parseDiff(diff);

  const effectiveAriaLabel = ariaLabel || `Diff for ${fileName || 'file'}`;

  return (
    <div className={className} role="region" aria-label={effectiveAriaLabel}>
      {fileName && (
        <div className="mb-2 rounded-t-lg bg-muted px-4 py-2">
          <span className="font-mono text-sm font-medium">{fileName}</span>
        </div>
      )}

      <Suspense fallback={<DiffViewerSkeleton />}>
        <ReactDiffViewer
          oldValue={oldValue}
          newValue={newValue}
          splitView={splitView}
          showDiffOnly={false}
          useDarkTheme={false}
          hideLineNumbers={!showLineNumbers}
          disableWordDiff={!highlightInline}
          leftTitle={diff.oldFileName || 'Original'}
          rightTitle={diff.newFileName || 'Modified'}
          styles={{
            variables: {
              light: {
                diffViewerBackground: '#ffffff',
                diffViewerColor: '#212529',
                addedBackground: '#e6ffed',
                addedColor: '#24292e',
                removedBackground: '#ffeef0',
                removedColor: '#24292e',
                wordAddedBackground: '#acf2bd',
                wordRemovedBackground: '#fdb8c0',
                addedGutterBackground: '#cdffd8',
                removedGutterBackground: '#ffdce0',
                gutterBackground: '#f6f8fa',
                gutterBackgroundDark: '#f3f4f6',
                highlightBackground: '#fffbdd',
                highlightGutterBackground: '#fff5b1',
              },
            },
            line: {
              padding: '0.25rem 0.5rem',
              fontSize: '0.875rem',
              lineHeight: '1.5',
              fontFamily: 'ui-monospace, SFMono-Regular, "SF Mono", Menlo, Consolas, "Liberation Mono", monospace',
            },
          }}
        />
      </Suspense>
    </div>
  );
}

/**
 * Type-safe component export
 */
export default DiffViewer;
