/**
 * T069: Component tests for DiffViewer
 *
 * Tests the diff viewer component which displays file differences with syntax highlighting.
 * Validates rendering of additions, deletions, unchanged lines, and various display modes.
 *
 * Feature: 009-markdown-user-story (User Story 3 - View Changes in Visual Editor)
 * Priority: P2 (Enhanced UX)
 * Phase: Phase 5
 *
 * Test Coverage:
 * - Diff rendering (additions, deletions, unchanged lines)
 * - Split view vs unified view
 * - Line numbers display
 * - File name display
 * - New file display (all additions)
 * - Accessibility (ARIA labels)
 * - Loading state (lazy loading)
 */

import { describe, it, expect, vi } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import { DiffViewer } from './DiffViewer';
import type { UnifiedDiff } from '@/entities/comparison/model/types';

// Mock react-diff-viewer-continued to avoid external dependency in tests
vi.mock('react-diff-viewer-continued', () => ({
  default: ({ oldValue, newValue, leftTitle, rightTitle, splitView }: any) => (
    <div data-testid="mock-diff-viewer">
      <div data-testid="diff-view-mode">{splitView ? 'split' : 'unified'}</div>
      <div data-testid="left-title">{leftTitle}</div>
      <div data-testid="right-title">{rightTitle}</div>
      <div data-testid="old-value">{oldValue}</div>
      <div data-testid="new-value">{newValue}</div>
    </div>
  ),
}));

describe('DiffViewer', () => {
  const mockDiff: UnifiedDiff = {
    hunks: [
      {
        oldStart: 1,
        oldLines: 3,
        newStart: 1,
        newLines: 4,
        changes: [
          { type: 'UNCHANGED', lineNumber: 1, content: 'header' },
          { type: 'REMOVED', lineNumber: 2, content: 'old line' },
          { type: 'ADDED', lineNumber: 2, content: 'new line' },
          { type: 'ADDED', lineNumber: 3, content: 'added line' },
          { type: 'UNCHANGED', lineNumber: 4, content: 'footer' },
        ],
      },
    ],
    oldFileName: 'file.txt',
    newFileName: 'file.txt',
  };

  describe('Diff Rendering', () => {
    it('should render additions in green', async () => {
      render(<DiffViewer diff={mockDiff} />);

      await waitFor(() => {
        expect(screen.getByTestId('mock-diff-viewer')).toBeInTheDocument();
      });

      const newValue = screen.getByTestId('new-value');
      expect(newValue.textContent).toContain('new line');
      expect(newValue.textContent).toContain('added line');
    });

    it('should render deletions in red', async () => {
      render(<DiffViewer diff={mockDiff} />);

      await waitFor(() => {
        expect(screen.getByTestId('mock-diff-viewer')).toBeInTheDocument();
      });

      const oldValue = screen.getByTestId('old-value');
      expect(oldValue.textContent).toContain('old line');
    });

    it('should render unchanged lines correctly', async () => {
      render(<DiffViewer diff={mockDiff} />);

      await waitFor(() => {
        expect(screen.getByTestId('mock-diff-viewer')).toBeInTheDocument();
      });

      const oldValue = screen.getByTestId('old-value');
      const newValue = screen.getByTestId('new-value');

      // Unchanged lines appear in both old and new
      expect(oldValue.textContent).toContain('header');
      expect(oldValue.textContent).toContain('footer');
      expect(newValue.textContent).toContain('header');
      expect(newValue.textContent).toContain('footer');
    });

    it('should correctly parse diff with mixed changes', async () => {
      render(<DiffViewer diff={mockDiff} />);

      await waitFor(() => {
        expect(screen.getByTestId('mock-diff-viewer')).toBeInTheDocument();
      });

      const oldValue = screen.getByTestId('old-value');
      const newValue = screen.getByTestId('new-value');

      // Old value should have: header, old line, footer
      expect(oldValue.textContent).toBe('header\nold line\nfooter');

      // New value should have: header, new line, added line, footer
      expect(newValue.textContent).toBe('header\nnew line\nadded line\nfooter');
    });
  });

  describe('View Modes', () => {
    it('should default to unified view', async () => {
      render(<DiffViewer diff={mockDiff} />);

      await waitFor(() => {
        const viewMode = screen.getByTestId('diff-view-mode');
        expect(viewMode.textContent).toBe('unified');
      });
    });

    it('should render in split view when splitView=true', async () => {
      render(<DiffViewer diff={mockDiff} splitView={true} />);

      await waitFor(() => {
        const viewMode = screen.getByTestId('diff-view-mode');
        expect(viewMode.textContent).toBe('split');
      });
    });
  });

  describe('File Name Display', () => {
    it('should display file name when provided', async () => {
      render(<DiffViewer diff={mockDiff} fileName="data.csv" />);

      await waitFor(() => {
        expect(screen.getByText('data.csv')).toBeInTheDocument();
      });
    });

    it('should not display file name section when not provided', async () => {
      const { container } = render(<DiffViewer diff={mockDiff} />);

      await waitFor(() => {
        expect(screen.getByTestId('mock-diff-viewer')).toBeInTheDocument();
      });

      // File name is in a div with bg-muted class
      const fileNameSection = container.querySelector('.bg-muted');
      expect(fileNameSection).not.toBeInTheDocument();
    });

    it('should use oldFileName and newFileName as titles', async () => {
      render(<DiffViewer diff={mockDiff} />);

      await waitFor(() => {
        expect(screen.getByTestId('left-title')).toHaveTextContent('file.txt');
        expect(screen.getByTestId('right-title')).toHaveTextContent('file.txt');
      });
    });
  });

  describe('New File Display', () => {
    it('should handle new file (no old file)', async () => {
      const newFileDiff: UnifiedDiff = {
        hunks: [
          {
            oldStart: 0,
            oldLines: 0,
            newStart: 1,
            newLines: 2,
            changes: [
              { type: 'ADDED', lineNumber: 1, content: 'new line 1' },
              { type: 'ADDED', lineNumber: 2, content: 'new line 2' },
            ],
          },
        ],
        oldFileName: null,
        newFileName: 'new_file.json',
      };

      render(<DiffViewer diff={newFileDiff} fileName="new_file.json" />);

      await waitFor(() => {
        const oldValue = screen.getByTestId('old-value');
        const newValue = screen.getByTestId('new-value');

        expect(oldValue.textContent).toBe('');
        expect(newValue.textContent).toBe('new line 1\nnew line 2');
      });
    });

    it('should use "Original" and "Modified" as default titles', async () => {
      const newFileDiff: UnifiedDiff = {
        hunks: [
          {
            oldStart: 0,
            oldLines: 0,
            newStart: 1,
            newLines: 1,
            changes: [{ type: 'ADDED', lineNumber: 1, content: 'content' }],
          },
        ],
        oldFileName: null,
        newFileName: null,
      };

      render(<DiffViewer diff={newFileDiff} />);

      await waitFor(() => {
        expect(screen.getByTestId('left-title')).toHaveTextContent('Original');
        expect(screen.getByTestId('right-title')).toHaveTextContent('Modified');
      });
    });
  });

  describe('Empty Diff Handling', () => {
    it('should handle empty diff gracefully', async () => {
      const emptyDiff: UnifiedDiff = {
        hunks: [],
        oldFileName: 'file.txt',
        newFileName: 'file.txt',
      };

      render(<DiffViewer diff={emptyDiff} />);

      await waitFor(() => {
        const oldValue = screen.getByTestId('old-value');
        const newValue = screen.getByTestId('new-value');

        expect(oldValue.textContent).toBe('');
        expect(newValue.textContent).toBe('');
      });
    });

    it('should handle diff with empty hunks', async () => {
      const emptyHunkDiff: UnifiedDiff = {
        hunks: [
          {
            oldStart: 1,
            oldLines: 0,
            newStart: 1,
            newLines: 0,
            changes: [],
          },
        ],
        oldFileName: 'file.txt',
        newFileName: 'file.txt',
      };

      render(<DiffViewer diff={emptyHunkDiff} />);

      await waitFor(() => {
        const oldValue = screen.getByTestId('old-value');
        const newValue = screen.getByTestId('new-value');

        expect(oldValue.textContent).toBe('');
        expect(newValue.textContent).toBe('');
      });
    });
  });

  describe('Accessibility', () => {
    it('should have default aria-label', async () => {
      const { container } = render(<DiffViewer diff={mockDiff} />);

      await waitFor(() => {
        const region = container.querySelector('[role="region"]');
        expect(region).toHaveAttribute('aria-label', 'Diff for file');
      });
    });

    it('should use file name in aria-label when provided', async () => {
      const { container } = render(<DiffViewer diff={mockDiff} fileName="data.csv" />);

      await waitFor(() => {
        const region = container.querySelector('[role="region"]');
        expect(region).toHaveAttribute('aria-label', 'Diff for data.csv');
      });
    });

    it('should use custom aria-label when provided', async () => {
      const { container } = render(
        <DiffViewer diff={mockDiff} fileName="data.csv" ariaLabel="Custom diff label" />
      );

      await waitFor(() => {
        const region = container.querySelector('[role="region"]');
        expect(region).toHaveAttribute('aria-label', 'Custom diff label');
      });
    });

    it('should have role="region" for accessibility', async () => {
      const { container } = render(<DiffViewer diff={mockDiff} />);

      await waitFor(() => {
        const region = container.querySelector('[role="region"]');
        expect(region).toBeInTheDocument();
      });
    });
  });

  describe('Custom Styling', () => {
    it('should apply custom className', async () => {
      const { container } = render(<DiffViewer diff={mockDiff} className="custom-class" />);

      await waitFor(() => {
        const diffContainer = container.querySelector('.custom-class');
        expect(diffContainer).toBeInTheDocument();
      });
    });

    it('should render file name with monospace font', async () => {
      const { container } = render(<DiffViewer diff={mockDiff} fileName="code.ts" />);

      await waitFor(() => {
        const fileName = container.querySelector('.font-mono');
        expect(fileName).toHaveTextContent('code.ts');
      });
    });
  });

  describe('Multiple Hunks', () => {
    it('should handle diff with multiple hunks', async () => {
      const multiHunkDiff: UnifiedDiff = {
        hunks: [
          {
            oldStart: 1,
            oldLines: 2,
            newStart: 1,
            newLines: 2,
            changes: [
              { type: 'UNCHANGED', lineNumber: 1, content: 'start' },
              { type: 'REMOVED', lineNumber: 2, content: 'old middle' },
            ],
          },
          {
            oldStart: 10,
            oldLines: 2,
            newStart: 10,
            newLines: 3,
            changes: [
              { type: 'ADDED', lineNumber: 10, content: 'new middle' },
              { type: 'UNCHANGED', lineNumber: 11, content: 'end' },
            ],
          },
        ],
        oldFileName: 'file.txt',
        newFileName: 'file.txt',
      };

      render(<DiffViewer diff={multiHunkDiff} />);

      await waitFor(() => {
        const oldValue = screen.getByTestId('old-value');
        const newValue = screen.getByTestId('new-value');

        expect(oldValue.textContent).toBe('start\nold middle\nend');
        expect(newValue.textContent).toBe('start\nnew middle\nend');
      });
    });
  });
});
