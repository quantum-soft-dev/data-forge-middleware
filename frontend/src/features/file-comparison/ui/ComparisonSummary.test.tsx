/**
 * T055: Component tests for ComparisonSummary
 *
 * Tests comparison summary statistics display component with formatted values,
 * change percentage badge, and various data scenarios.
 *
 * Feature: 009-markdown-user-story (User Story 2 - Compare Files)
 * Priority: P1 (MVP)
 * Phase: Phase 4
 *
 * Test Coverage:
 * - Statistics display (total, changed, added, unchanged)
 * - Change size formatting (bytes, KB, MB, GB)
 * - Timestamp formatting
 * - Change percentage calculation and badge colors
 * - Batch ID display
 * - Edge cases (zero files, 100% changed, large numbers)
 */

import { describe, it, expect } from 'vitest';
import { render, screen } from '@testing-library/react';
import { ComparisonSummary } from './ComparisonSummary';
import type { ComparisonSummary as ComparisonSummaryType } from '@/entities/comparison/model/types';

describe('ComparisonSummary', () => {
  const mockSummary: ComparisonSummaryType = {
    totalFilesCompared: 50,
    filesChanged: 12,
    filesAdded: 3,
    filesUnchanged: 35,
    totalChangeSize: 125000,
    comparisonTimestamp: '2025-11-03T10:31:25Z',
    currentBatchId: 'batch-123',
    targetBatchId: 'batch-120',
  };

  describe('Statistics Display', () => {
    it('should display total files compared', () => {
      render(<ComparisonSummary summary={mockSummary} />);

      expect(screen.getByText('50')).toBeInTheDocument();
      expect(screen.getByText('Total Files')).toBeInTheDocument();
    });

    it('should display files changed count', () => {
      render(<ComparisonSummary summary={mockSummary} />);

      expect(screen.getByText('12')).toBeInTheDocument();
      expect(screen.getByText('Modified')).toBeInTheDocument();
    });

    it('should display files added count', () => {
      render(<ComparisonSummary summary={mockSummary} />);

      expect(screen.getByText('3')).toBeInTheDocument();
      expect(screen.getByText('Added')).toBeInTheDocument();
    });

    it('should display files unchanged count', () => {
      render(<ComparisonSummary summary={mockSummary} />);

      expect(screen.getByText('35')).toBeInTheDocument();
      expect(screen.getByText('Unchanged')).toBeInTheDocument();
    });

    it('should display all statistics together', () => {
      render(<ComparisonSummary summary={mockSummary} />);

      // Verify all cards are present
      expect(screen.getByText('Total Files')).toBeInTheDocument();
      expect(screen.getByText('Modified')).toBeInTheDocument();
      expect(screen.getByText('Added')).toBeInTheDocument();
      expect(screen.getByText('Unchanged')).toBeInTheDocument();
      expect(screen.getByText('Change Size')).toBeInTheDocument();
      expect(screen.getByText('Compared At')).toBeInTheDocument();
    });
  });

  describe('Change Size Formatting', () => {
    it('should format bytes (< 1 KB)', () => {
      const summary = {
        ...mockSummary,
        totalChangeSize: 512, // 512 bytes
      };

      render(<ComparisonSummary summary={summary} />);

      expect(screen.getByText('512 Bytes')).toBeInTheDocument();
    });

    it('should format kilobytes (1 KB to 1 MB)', () => {
      const summary = {
        ...mockSummary,
        totalChangeSize: 5120, // 5 KB
      };

      render(<ComparisonSummary summary={summary} />);

      expect(screen.getByText('5 KB')).toBeInTheDocument();
    });

    it('should format megabytes (1 MB to 1 GB)', () => {
      const summary = {
        ...mockSummary,
        totalChangeSize: 1572864, // ~1.5 MB
      };

      render(<ComparisonSummary summary={summary} />);

      expect(screen.getByText('1.5 MB')).toBeInTheDocument();
    });

    it('should format gigabytes (> 1 GB)', () => {
      const summary = {
        ...mockSummary,
        totalChangeSize: 2147483648, // 2 GB
      };

      render(<ComparisonSummary summary={summary} />);

      expect(screen.getByText('2 GB')).toBeInTheDocument();
    });

    it('should format zero bytes', () => {
      const summary = {
        ...mockSummary,
        totalChangeSize: 0,
      };

      render(<ComparisonSummary summary={summary} />);

      expect(screen.getByText('0 Bytes')).toBeInTheDocument();
    });

    it('should format default summary change size (122.07 KB)', () => {
      render(<ComparisonSummary summary={mockSummary} />);

      // 125000 bytes = 122.07 KB
      expect(screen.getByText('122.07 KB')).toBeInTheDocument();
    });
  });

  describe('Timestamp Formatting', () => {
    it('should format ISO timestamp to readable date', () => {
      render(<ComparisonSummary summary={mockSummary} />);

      // Should contain date components (exact format depends on locale)
      expect(screen.getByText(/Nov|11|2025/)).toBeInTheDocument();
      expect(screen.getByText('Compared At')).toBeInTheDocument();
    });

    it('should handle different timestamp formats', () => {
      const summary = {
        ...mockSummary,
        comparisonTimestamp: '2025-12-25T15:30:45Z', // Christmas
      };

      render(<ComparisonSummary summary={summary} />);

      // Should contain date components - check for formatted date string
      expect(screen.getByText(/Dec.*25.*2025/)).toBeInTheDocument();
    });
  });

  describe('Change Percentage Badge', () => {
    it('should calculate change percentage correctly', () => {
      // 12 changed + 3 added = 15 total changes out of 50 files = 30%
      render(<ComparisonSummary summary={mockSummary} />);

      expect(screen.getByText('30% Changed')).toBeInTheDocument();
    });

    it('should show 0% when no files changed', () => {
      const summary = {
        ...mockSummary,
        filesChanged: 0,
        filesAdded: 0,
        filesUnchanged: 50,
      };

      render(<ComparisonSummary summary={summary} />);

      expect(screen.getByText('0% Changed')).toBeInTheDocument();
    });

    it('should show 100% when all files changed', () => {
      const summary = {
        ...mockSummary,
        totalFilesCompared: 50,
        filesChanged: 50,
        filesAdded: 0,
        filesUnchanged: 0,
      };

      render(<ComparisonSummary summary={summary} />);

      expect(screen.getByText('100% Changed')).toBeInTheDocument();
      // "50" appears twice (Total Files and Modified), use getAllByText
      const fifties = screen.getAllByText('50');
      expect(fifties.length).toBeGreaterThanOrEqual(2);
    });

    it('should round change percentage to nearest integer', () => {
      const summary = {
        ...mockSummary,
        totalFilesCompared: 99, // Changed to avoid "100" matching multiple elements
        filesChanged: 33,
        filesAdded: 0,
        filesUnchanged: 66,
      };

      render(<ComparisonSummary summary={summary} />);

      // 33/99 = 33.33% rounded to 33%
      expect(screen.getByText('33% Changed')).toBeInTheDocument();
    });

    it('should handle zero total files gracefully', () => {
      const summary = {
        ...mockSummary,
        totalFilesCompared: 0,
        filesChanged: 0,
        filesAdded: 0,
        filesUnchanged: 0,
      };

      render(<ComparisonSummary summary={summary} />);

      // Should show 0% when dividing by zero
      expect(screen.getByText('0% Changed')).toBeInTheDocument();
    });
  });

  describe('Batch ID Display', () => {
    it('should display current and target batch IDs', () => {
      render(<ComparisonSummary summary={mockSummary} />);

      expect(screen.getByText(/batch-123/)).toBeInTheDocument();
      expect(screen.getByText(/batch-120/)).toBeInTheDocument();
      expect(screen.getByText(/Comparing batch/)).toBeInTheDocument();
      expect(screen.getByText(/with/)).toBeInTheDocument();
    });

    it('should display batch IDs with monospace font', () => {
      const { container } = render(<ComparisonSummary summary={mockSummary} />);

      // Find elements containing batch IDs
      const batchElements = container.querySelectorAll('.font-mono');
      expect(batchElements.length).toBeGreaterThan(0);
    });
  });

  describe('Card Structure', () => {
    it('should render card with header and content', () => {
      render(<ComparisonSummary summary={mockSummary} />);

      expect(screen.getByText('Comparison Summary')).toBeInTheDocument();
      expect(screen.getByText('Overview of changes between upload sessions')).toBeInTheDocument();
    });

    it('should render with custom className', () => {
      const { container } = render(
        <ComparisonSummary summary={mockSummary} className="custom-class" />
      );

      const card = container.querySelector('.custom-class');
      expect(card).toBeInTheDocument();
    });
  });

  describe('Edge Cases', () => {
    it('should handle very large file counts', () => {
      const summary = {
        ...mockSummary,
        totalFilesCompared: 999999,
        filesChanged: 500000,
        filesAdded: 100000,
        filesUnchanged: 399999,
      };

      render(<ComparisonSummary summary={summary} />);

      expect(screen.getByText('999999')).toBeInTheDocument();
      expect(screen.getByText('500000')).toBeInTheDocument();
      expect(screen.getByText('100000')).toBeInTheDocument();
      expect(screen.getByText('399999')).toBeInTheDocument();
    });

    it('should handle very large change size (> 1 GB)', () => {
      const summary = {
        ...mockSummary,
        totalChangeSize: 5368709120, // 5 GB
      };

      render(<ComparisonSummary summary={summary} />);

      expect(screen.getByText('5 GB')).toBeInTheDocument();
    });

    it('should handle all files being new (100% added)', () => {
      const summary = {
        ...mockSummary,
        totalFilesCompared: 30,
        filesChanged: 0,
        filesAdded: 30,
        filesUnchanged: 0,
      };

      render(<ComparisonSummary summary={summary} />);

      // "30" appears twice (Total Files and Added), use getAllByText
      const thirties = screen.getAllByText('30');
      expect(thirties.length).toBeGreaterThanOrEqual(2);
      expect(screen.getByText('100% Changed')).toBeInTheDocument();
    });

    it('should handle all files being unchanged (0% changed)', () => {
      const summary = {
        ...mockSummary,
        totalFilesCompared: 100,
        filesChanged: 0,
        filesAdded: 0,
        filesUnchanged: 100,
      };

      render(<ComparisonSummary summary={summary} />);

      // "100" appears twice (Total Files and Unchanged), use getAllByText
      const hundreds = screen.getAllByText('100');
      expect(hundreds.length).toBeGreaterThanOrEqual(2);
      expect(screen.getByText('0% Changed')).toBeInTheDocument();
    });

    it('should handle single file comparison', () => {
      const summary = {
        ...mockSummary,
        totalFilesCompared: 1,
        filesChanged: 1,
        filesAdded: 0,
        filesUnchanged: 0,
        totalChangeSize: 1024,
      };

      render(<ComparisonSummary summary={summary} />);

      // Use getAllByText and check count since "1" appears multiple times (Total Files, Modified)
      const ones = screen.getAllByText('1');
      expect(ones.length).toBeGreaterThanOrEqual(2); // At least Total Files and Modified
      expect(screen.getByText('100% Changed')).toBeInTheDocument();
      expect(screen.getByText('1 KB')).toBeInTheDocument();
    });
  });

  describe('Icons Display', () => {
    it('should render all statistic icons', () => {
      const { container } = render(<ComparisonSummary summary={mockSummary} />);

      // Count SVG elements (icons from lucide-react)
      const icons = container.querySelectorAll('svg');

      // Should have at least 7 icons:
      // - Card header icon (FileText)
      // - 6 statistic icons (FileText, FileX, FilePlus, FileCheck, Database, Calendar)
      expect(icons.length).toBeGreaterThanOrEqual(7);
    });
  });

  describe('Responsive Grid Layout', () => {
    it('should render statistics in grid layout', () => {
      const { container } = render(<ComparisonSummary summary={mockSummary} />);

      // Check for grid classes (Tailwind CSS)
      const grid = container.querySelector('.grid');
      expect(grid).toBeInTheDocument();
      expect(grid).toHaveClass('gap-4');
    });
  });
});
