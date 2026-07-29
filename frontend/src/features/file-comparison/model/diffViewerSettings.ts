/**
 * Diff viewer settings context and accessor hook.
 *
 * Feature: File Diff Comparison Between Upload Sessions
 * Task: T073 - Add React Context for diff viewer settings
 * Phase: Phase 5 - User Story 3 (View Changes in Visual Editor)
 *
 * Kept apart from the provider component so that this module exports no
 * components — a mixed module breaks Fast Refresh for everything importing it.
 *
 * @module features/file-comparison/model/diffViewerSettings
 */

import { createContext, useContext } from 'react';

/**
 * Diff viewer display settings
 */
export interface DiffViewerSettings {
  /**
   * Show line numbers
   * @default true
   */
  showLineNumbers: boolean;

  /**
   * Split view (side-by-side) or unified view
   * @default false (unified)
   */
  splitView: boolean;

  /**
   * Highlight inline word-level changes
   * @default true
   */
  highlightInline: boolean;

  /**
   * Show only lines with changes (collapse unchanged sections)
   * @default false
   */
  showDiffOnly: boolean;

  /**
   * Dark theme
   * @default false
   */
  useDarkTheme: boolean;
}

/**
 * Default diff viewer settings
 */
const DEFAULT_SETTINGS: DiffViewerSettings = {
  showLineNumbers: true,
  splitView: false,
  highlightInline: true,
  showDiffOnly: false,
  useDarkTheme: false,
};

/**
 * Context value interface
 */
interface DiffViewerContextValue {
  /**
   * Current diff viewer settings
   */
  settings: DiffViewerSettings;

  /**
   * Update all settings at once
   */
  updateSettings: (settings: Partial<DiffViewerSettings>) => void;

  /**
   * Toggle split/unified view
   */
  toggleSplitView: () => void;

  /**
   * Toggle line numbers display
   */
  toggleLineNumbers: () => void;

  /**
   * Toggle inline highlighting
   */
  toggleInlineHighlight: () => void;

  /**
   * Toggle diff-only mode
   */
  toggleDiffOnly: () => void;

  /**
   * Toggle dark theme
   */
  toggleDarkTheme: () => void;

  /**
   * Reset all settings to defaults
   */
  resetSettings: () => void;
}

/**
 * Diff viewer context
 */

/**
 * Diff viewer context
 */
export const DiffViewerContext = createContext<DiffViewerContextValue | undefined>(undefined);

/**
 * Hook to access diff viewer settings
 *
 * Must be used within a DiffViewerProvider.
 *
 * @throws Error if used outside DiffViewerProvider
 *
 * @example
 * ```tsx
 * import { useDiffViewerSettings } from '@/features/file-comparison/model/DiffViewerContext';
 *
 * function DiffViewerControls() {
 *   const { settings, toggleSplitView, toggleLineNumbers } = useDiffViewerSettings();
 *
 *   return (
 *     <div>
 *       <button onClick={toggleSplitView}>
 *         {settings.splitView ? 'Unified View' : 'Split View'}
 *       </button>
 *       <button onClick={toggleLineNumbers}>
 *         {settings.showLineNumbers ? 'Hide Line Numbers' : 'Show Line Numbers'}
 *       </button>
 *     </div>
 *   );
 * }
 * ```
 */
export function useDiffViewerSettings(): DiffViewerContextValue {
  const context = useContext(DiffViewerContext);

  if (context === undefined) {
    throw new Error('useDiffViewerSettings must be used within a DiffViewerProvider');
  }

  return context;
}

export type { DiffViewerContextValue };
export { DEFAULT_SETTINGS };
