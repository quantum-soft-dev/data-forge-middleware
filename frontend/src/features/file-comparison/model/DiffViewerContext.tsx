/**
 * React Context for diff viewer settings
 *
 * Feature: File Diff Comparison Between Upload Sessions
 * Task: T073 - Add React Context for diff viewer settings
 * Phase: Phase 5 - User Story 3 (View Changes in Visual Editor)
 *
 * Provides global settings for diff viewer display preferences including
 * theme, line numbers, split/unified view, and syntax highlighting.
 *
 * @module features/file-comparison/model/DiffViewerContext
 */

import React, { createContext, useContext, useState, useCallback, ReactNode } from 'react';

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
const DiffViewerContext = createContext<DiffViewerContextValue | undefined>(undefined);

/**
 * Props for DiffViewerProvider
 */
interface DiffViewerProviderProps {
  /**
   * Child components
   */
  children: ReactNode;

  /**
   * Optional initial settings (for testing or customization)
   */
  initialSettings?: Partial<DiffViewerSettings>;
}

/**
 * Diff viewer settings provider
 *
 * Wraps the comparison feature components to provide shared diff viewer settings.
 * Settings are persisted in component state (could be extended to localStorage).
 *
 * @example
 * ```tsx
 * import { DiffViewerProvider } from '@/features/file-comparison/model/DiffViewerContext';
 *
 * function App() {
 *   return (
 *     <DiffViewerProvider>
 *       <ComparisonDetailPage />
 *     </DiffViewerProvider>
 *   );
 * }
 * ```
 */
export function DiffViewerProvider({
  children,
  initialSettings = {},
}: DiffViewerProviderProps): React.ReactElement {
  const [settings, setSettings] = useState<DiffViewerSettings>({
    ...DEFAULT_SETTINGS,
    ...initialSettings,
  });

  const updateSettings = useCallback((newSettings: Partial<DiffViewerSettings>) => {
    setSettings((prev) => ({ ...prev, ...newSettings }));
  }, []);

  const toggleSplitView = useCallback(() => {
    setSettings((prev) => ({ ...prev, splitView: !prev.splitView }));
  }, []);

  const toggleLineNumbers = useCallback(() => {
    setSettings((prev) => ({ ...prev, showLineNumbers: !prev.showLineNumbers }));
  }, []);

  const toggleInlineHighlight = useCallback(() => {
    setSettings((prev) => ({ ...prev, highlightInline: !prev.highlightInline }));
  }, []);

  const toggleDiffOnly = useCallback(() => {
    setSettings((prev) => ({ ...prev, showDiffOnly: !prev.showDiffOnly }));
  }, []);

  const toggleDarkTheme = useCallback(() => {
    setSettings((prev) => ({ ...prev, useDarkTheme: !prev.useDarkTheme }));
  }, []);

  const resetSettings = useCallback(() => {
    setSettings(DEFAULT_SETTINGS);
  }, []);

  const value: DiffViewerContextValue = {
    settings,
    updateSettings,
    toggleSplitView,
    toggleLineNumbers,
    toggleInlineHighlight,
    toggleDiffOnly,
    toggleDarkTheme,
    resetSettings,
  };

  return <DiffViewerContext.Provider value={value}>{children}</DiffViewerContext.Provider>;
}

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

/**
 * Type-safe exports
 */
export type { DiffViewerContextValue };
export { DEFAULT_SETTINGS };
