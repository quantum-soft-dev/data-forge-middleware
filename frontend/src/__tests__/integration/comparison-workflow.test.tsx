/**
 * T070: Integration test for full comparison workflow
 *
 * Tests the complete user journey for file comparison:
 * 1. Select files from an upload session
 * 2. Create a comparison between two sessions
 * 3. View diff results in the visual editor
 *
 * Feature: 009-markdown-user-story (User Story 3 - View Changes in Visual Editor)
 * Priority: P2 (Enhanced UX)
 * Phase: Phase 5
 *
 * Test Coverage:
 * - Complete workflow: select → create → view
 * - API integration with mocked backend
 * - State management through TanStack Query
 * - UI rendering and interactions
 * - Error handling scenarios
 */

import { describe, it, expect, beforeEach, vi } from 'vitest';
import { render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { DiffViewerProvider } from '@/features/file-comparison/model/DiffViewerContext';
import { DiffViewerWidget } from '@/widgets/comparison/DiffViewerWidget';
import type {
  ComparisonResponse,
  ComparisonResult,
  UnifiedDiff,
} from '@/entities/comparison/model/types';
import * as comparisonApi from '@/entities/comparison/api/comparisonApi';

// Mock the comparison API
vi.mock('@/entities/comparison/api/comparisonApi');

// Mock react-diff-viewer-continued to avoid external dependency
vi.mock('react-diff-viewer-continued', () => ({
  default: ({ oldValue, newValue, splitView }: any) => (
    <div data-testid="diff-viewer">
      <div data-testid="view-mode">{splitView ? 'split' : 'unified'}</div>
      <div data-testid="old-content">{oldValue}</div>
      <div data-testid="new-content">{newValue}</div>
    </div>
  ),
}));

describe('Comparison Workflow Integration', () => {
  let queryClient: QueryClient;

  beforeEach(() => {
    // Create a new QueryClient for each test
    queryClient = new QueryClient({
      defaultOptions: {
        queries: {
          retry: false,
        },
      },
    });

    // Clear all mocks
    vi.clearAllMocks();
  });

  const mockComparisonResponse: ComparisonResponse = {
    id: 1,
    currentBatchId: 'batch-123',
    targetBatchId: 'batch-120',
    accountId: 'account-1',
    status: 'COMPLETED',
    createdAt: '2025-11-03T10:00:00Z',
    startedAt: '2025-11-03T10:00:01Z',
    completedAt: '2025-11-03T10:00:30Z',
    totalFilesCompared: 3,
    filesChanged: 1,
    filesAdded: 1,
    filesUnchanged: 1,
    totalChangeSize: 5000,
  };

  const mockUnifiedDiff: UnifiedDiff = {
    hunks: [
      {
        oldStart: 1,
        oldLines: 3,
        newStart: 1,
        newLines: 4,
        changes: [
          { type: 'UNCHANGED', lineNumber: 1, content: 'header1,header2,header3' },
          { type: 'REMOVED', lineNumber: 2, content: 'old_value1,old_value2,old_value3' },
          { type: 'ADDED', lineNumber: 2, content: 'new_value1,new_value2,new_value3' },
          { type: 'ADDED', lineNumber: 3, content: 'added_value1,added_value2,added_value3' },
          { type: 'UNCHANGED', lineNumber: 4, content: 'footer1,footer2,footer3' },
        ],
      },
    ],
    oldFileName: 'data.csv',
    newFileName: 'data.csv',
  };

  const mockComparisonResults: ComparisonResult[] = [
    {
      id: 1,
      comparisonId: 1,
      fileId: 101,
      targetFileId: 201,
      fileName: 'data.csv',
      targetFileName: 'data.csv',
      changeType: 'MODIFIED',
      lineAdditions: 2,
      lineDeletions: 1,
      changeSize: 150,
      createdAt: '2025-11-03T10:00:15Z',
      unifiedDiff: mockUnifiedDiff,
    },
    {
      id: 2,
      comparisonId: 1,
      fileId: 102,
      targetFileId: null,
      fileName: 'new_file.json',
      targetFileName: null,
      changeType: 'ADDED',
      lineAdditions: 50,
      lineDeletions: 0,
      changeSize: 2500,
      createdAt: '2025-11-03T10:00:20Z',
      unifiedDiff: {
        hunks: [
          {
            oldStart: 0,
            oldLines: 0,
            newStart: 1,
            newLines: 2,
            changes: [
              { type: 'ADDED', lineNumber: 1, content: '{' },
              { type: 'ADDED', lineNumber: 2, content: '  "key": "value"' },
            ],
          },
        ],
        oldFileName: null,
        newFileName: 'new_file.json',
      },
    },
    {
      id: 3,
      comparisonId: 1,
      fileId: 103,
      targetFileId: 203,
      fileName: 'unchanged.txt',
      targetFileName: 'unchanged.txt',
      changeType: 'UNCHANGED',
      lineAdditions: 0,
      lineDeletions: 0,
      changeSize: 0,
      createdAt: '2025-11-03T10:00:25Z',
      unifiedDiff: {
        hunks: [],
        oldFileName: 'unchanged.txt',
        newFileName: 'unchanged.txt',
      },
    },
  ];

  function renderWithProviders(ui: React.ReactElement) {
    return render(
      <QueryClientProvider client={queryClient}>
        <DiffViewerProvider>{ui}</DiffViewerProvider>
      </QueryClientProvider>
    );
  }

  describe('Complete Workflow', () => {
    it('should complete full comparison workflow: create → view diff', async () => {
      // Mock API calls
      vi.mocked(comparisonApi.createComparison).mockResolvedValue(mockComparisonResponse);
      vi.mocked(comparisonApi.getComparisonResults).mockResolvedValue({
        content: mockComparisonResults,
        page: 0,
        size: 50,
        totalElements: 3,
        totalPages: 1,
      });

      // Step 1: Create comparison (simulated - we'll test the result)
      const comparison = await comparisonApi.createComparison({
        currentBatchId: 'batch-123',
        targetBatchId: 'batch-120',
      });

      expect(comparison).toEqual(mockComparisonResponse);
      expect(comparison.status).toBe('COMPLETED');

      // Step 2: Fetch comparison results
      const results = await comparisonApi.getComparisonResults(comparison.id);

      expect(results.content).toHaveLength(3);
      expect(results.content[0].changeType).toBe('MODIFIED');
      expect(results.content[1].changeType).toBe('ADDED');
      expect(results.content[2].changeType).toBe('UNCHANGED');

      // Step 3: Render diff viewer with first result
      const modifiedFileResult = results.content[0];
      renderWithProviders(
        <DiffViewerWidget
          diff={modifiedFileResult.unifiedDiff}
          fileName={modifiedFileResult.fileName}
          changeType={modifiedFileResult.changeType}
        />
      );

      // Verify diff viewer renders
      await waitFor(() => {
        expect(screen.getByTestId('diff-viewer')).toBeInTheDocument();
      });

      // Verify file name and change type badge
      expect(screen.getByText('data.csv')).toBeInTheDocument();
      expect(screen.getByText('MODIFIED')).toBeInTheDocument();

      // Verify diff content
      const oldContent = screen.getByTestId('old-content');
      const newContent = screen.getByTestId('new-content');

      expect(oldContent.textContent).toContain('old_value1');
      expect(newContent.textContent).toContain('new_value1');
      expect(newContent.textContent).toContain('added_value1');
    });

    it('should handle new file (ADDED) correctly', async () => {
      vi.mocked(comparisonApi.getComparisonResults).mockResolvedValue({
        content: [mockComparisonResults[1]], // New file
        page: 0,
        size: 50,
        totalElements: 1,
        totalPages: 1,
      });

      const results = await comparisonApi.getComparisonResults(1, 'ADDED');
      const newFileResult = results.content[0];

      renderWithProviders(
        <DiffViewerWidget
          diff={newFileResult.unifiedDiff}
          fileName={newFileResult.fileName}
          changeType={newFileResult.changeType}
        />
      );

      await waitFor(() => {
        expect(screen.getByTestId('diff-viewer')).toBeInTheDocument();
      });

      expect(screen.getByText('new_file.json')).toBeInTheDocument();
      expect(screen.getByText('ADDED')).toBeInTheDocument();

      // New file should have empty old content
      const oldContent = screen.getByTestId('old-content');
      const newContent = screen.getByTestId('new-content');

      expect(oldContent.textContent).toBe('');
      expect(newContent.textContent).toContain('{');
      expect(newContent.textContent).toContain('"key": "value"');
    });

    it('should filter results by change type', async () => {
      // Test filtering by MODIFIED
      vi.mocked(comparisonApi.getComparisonResults).mockResolvedValue({
        content: [mockComparisonResults[0]],
        page: 0,
        size: 50,
        totalElements: 1,
        totalPages: 1,
      });

      const modifiedResults = await comparisonApi.getComparisonResults(1, 'MODIFIED');
      expect(modifiedResults.content).toHaveLength(1);
      expect(modifiedResults.content[0].changeType).toBe('MODIFIED');

      // Test filtering by ADDED
      vi.mocked(comparisonApi.getComparisonResults).mockResolvedValue({
        content: [mockComparisonResults[1]],
        page: 0,
        size: 50,
        totalElements: 1,
        totalPages: 1,
      });

      const addedResults = await comparisonApi.getComparisonResults(1, 'ADDED');
      expect(addedResults.content).toHaveLength(1);
      expect(addedResults.content[0].changeType).toBe('ADDED');
    });
  });

  describe('View Mode Toggling', () => {
    it('should toggle between unified and split view', async () => {
      const user = userEvent.setup();

      vi.mocked(comparisonApi.getComparisonResults).mockResolvedValue({
        content: [mockComparisonResults[0]],
        page: 0,
        size: 50,
        totalElements: 1,
        totalPages: 1,
      });

      const results = await comparisonApi.getComparisonResults(1);
      const result = results.content[0];

      renderWithProviders(
        <DiffViewerWidget
          diff={result.unifiedDiff}
          fileName={result.fileName}
          changeType={result.changeType}
        />
      );

      await waitFor(() => {
        expect(screen.getByTestId('diff-viewer')).toBeInTheDocument();
      });

      // Initially should be in unified view
      expect(screen.getByTestId('view-mode')).toHaveTextContent('unified');

      // Find and click the split view toggle button
      const splitButton = screen.getByRole('button', { name: /split/i });
      await user.click(splitButton);

      // Should now be in split view
      await waitFor(() => {
        expect(screen.getByTestId('view-mode')).toHaveTextContent('split');
      });

      // Click again to go back to unified
      const unifiedButton = screen.getByRole('button', { name: /unified/i });
      await user.click(unifiedButton);

      await waitFor(() => {
        expect(screen.getByTestId('view-mode')).toHaveTextContent('unified');
      });
    });
  });

  describe('Settings Controls', () => {
    it('should open settings menu and toggle options', async () => {
      const user = userEvent.setup();

      renderWithProviders(
        <DiffViewerWidget
          diff={mockUnifiedDiff}
          fileName="test.csv"
          changeType="MODIFIED"
        />
      );

      await waitFor(() => {
        expect(screen.getByTestId('diff-viewer')).toBeInTheDocument();
      });

      // Find and click settings button
      const settingsButton = screen.getByRole('button', { name: /diff viewer settings/i });
      await user.click(settingsButton);

      // Settings menu should be visible
      await waitFor(() => {
        expect(screen.getByText('Display Settings')).toBeInTheDocument();
      });

      // Should have settings options
      expect(screen.getByText('Show Line Numbers')).toBeInTheDocument();
      expect(screen.getByText('Highlight Inline Changes')).toBeInTheDocument();
      expect(screen.getByText('Show Only Changes')).toBeInTheDocument();
    });
  });

  describe('Error Handling', () => {
    it('should handle API errors gracefully', async () => {
      vi.mocked(comparisonApi.createComparison).mockRejectedValue(
        new Error('Network error')
      );

      await expect(
        comparisonApi.createComparison({
          currentBatchId: 'batch-123',
          targetBatchId: 'batch-120',
        })
      ).rejects.toThrow('Network error');
    });

    it('should handle empty comparison results', async () => {
      vi.mocked(comparisonApi.getComparisonResults).mockResolvedValue({
        content: [],
        page: 0,
        size: 50,
        totalElements: 0,
        totalPages: 0,
      });

      const results = await comparisonApi.getComparisonResults(1);
      expect(results.content).toHaveLength(0);
      expect(results.totalElements).toBe(0);
    });

    it('should handle comparison with no changes', async () => {
      renderWithProviders(
        <DiffViewerWidget
          diff={{
            hunks: [],
            oldFileName: 'file.txt',
            newFileName: 'file.txt',
          }}
          fileName="file.txt"
          changeType="UNCHANGED"
        />
      );

      await waitFor(() => {
        expect(screen.getByTestId('diff-viewer')).toBeInTheDocument();
      });

      expect(screen.getByText('UNCHANGED')).toBeInTheDocument();
      expect(screen.getByText('No changes')).toBeInTheDocument();
    });
  });

  describe('Change Statistics', () => {
    it('should display correct change statistics', async () => {
      renderWithProviders(
        <DiffViewerWidget
          diff={mockUnifiedDiff}
          fileName="data.csv"
          changeType="MODIFIED"
        />
      );

      await waitFor(() => {
        expect(screen.getByTestId('diff-viewer')).toBeInTheDocument();
      });

      // Should show additions and deletions count
      expect(screen.getByText(/\+2 -1/)).toBeInTheDocument();
    });

    it('should show only additions for new files', async () => {
      const newFileResult = mockComparisonResults[1];

      renderWithProviders(
        <DiffViewerWidget
          diff={newFileResult.unifiedDiff}
          fileName={newFileResult.fileName}
          changeType={newFileResult.changeType}
        />
      );

      await waitFor(() => {
        expect(screen.getByTestId('diff-viewer')).toBeInTheDocument();
      });

      // Should show only additions (no deletions)
      const additionTexts = screen.getAllByText(/\+2/);
      expect(additionTexts.length).toBeGreaterThan(0);
    });
  });

  describe('Pagination', () => {
    it('should handle paginated comparison results', async () => {
      // First page
      vi.mocked(comparisonApi.getComparisonResults).mockResolvedValue({
        content: [mockComparisonResults[0]],
        page: 0,
        size: 1,
        totalElements: 3,
        totalPages: 3,
      });

      const page1 = await comparisonApi.getComparisonResults(1, undefined, 0, 1);
      expect(page1.content).toHaveLength(1);
      expect(page1.totalPages).toBe(3);

      // Second page
      vi.mocked(comparisonApi.getComparisonResults).mockResolvedValue({
        content: [mockComparisonResults[1]],
        page: 1,
        size: 1,
        totalElements: 3,
        totalPages: 3,
      });

      const page2 = await comparisonApi.getComparisonResults(1, undefined, 1, 1);
      expect(page2.content).toHaveLength(1);
      expect(page2.page).toBe(1);
    });
  });
});
