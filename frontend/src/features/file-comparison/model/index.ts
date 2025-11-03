/**
 * Public API exports for file-comparison model utilities
 *
 * Feature: File Diff Comparison Between Upload Sessions
 * Phase: Phase 5 - User Story 3 (View Changes in Visual Editor)
 */

export {
  parseDiff,
  isNewFile,
  isDeletedFile,
  getDiffStats,
  formatDiffSummary,
  type ParsedDiff,
} from './parseDiff';

export {
  DiffViewerProvider,
  useDiffViewerSettings,
  DEFAULT_SETTINGS,
  type DiffViewerSettings,
  type DiffViewerContextValue,
} from './DiffViewerContext';
