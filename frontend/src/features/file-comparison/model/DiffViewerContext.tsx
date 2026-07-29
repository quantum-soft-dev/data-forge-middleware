/**
 * React Context provider for diff viewer settings
 *
 * Feature: File Diff Comparison Between Upload Sessions
 * Task: T073 - Add React Context for diff viewer settings
 * Phase: Phase 5 - User Story 3 (View Changes in Visual Editor)
 *
 * Provides global settings for diff viewer display preferences including
 * theme, line numbers, split/unified view, and syntax highlighting.
 * The context object, its types and the accessor hook live in
 * ./diffViewerSettings — this module exports the provider only.
 *
 * @module features/file-comparison/model/DiffViewerContext
 */

import React, { useState, useCallback, ReactNode } from 'react';
import {
  DiffViewerContext,
  DEFAULT_SETTINGS,
  type DiffViewerContextValue,
  type DiffViewerSettings,
} from './diffViewerSettings';


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
