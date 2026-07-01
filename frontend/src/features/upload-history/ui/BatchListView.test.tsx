/**
 * T034: Component tests for BatchListView
 *
 * Tests batch list display component with infinite scroll, status indicators,
 * loading states, empty states, and error handling.
 *
 * Feature: 008-upload-history-user (User Story 1)
 */

import { describe, it, expect, vi } from 'vitest';
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { BatchListView } from './BatchListView';
import type { BatchSummary } from '@/entities/batch/model/types';

// Helper to generate ISO date string relative to now
const daysAgo = (days: number, hoursOffset = 0): string => {
  const date = new Date();
  date.setDate(date.getDate() - days);
  date.setHours(date.getHours() - hoursOffset);
  return date.toISOString();
};

describe('BatchListView', () => {
  // Use dynamic dates within last 7 days to pass default date filter
  const mockBatches: BatchSummary[] = [
    {
      id: '123e4567-e89b-12d3-a456-426614174000',
      siteId: '123e4567-e89b-12d3-a456-426614174001',
      status: 'COMPLETED',
      uploadedFilesCount: 5,
      totalSize: 1024000,
      hasErrors: false,
      startedAt: daysAgo(1, 0), // 1 day ago
      completedAt: daysAgo(1, -1), // 1 day ago + 1 hour
    },
    {
      id: '123e4567-e89b-12d3-a456-426614174002',
      siteId: '123e4567-e89b-12d3-a456-426614174001',
      status: 'FAILED',
      uploadedFilesCount: 3,
      totalSize: 512000,
      hasErrors: true,
      startedAt: daysAgo(2, 0), // 2 days ago
      completedAt: daysAgo(2, -1), // 2 days ago + 1 hour
    },
    {
      id: '123e4567-e89b-12d3-a456-426614174003',
      siteId: '123e4567-e89b-12d3-a456-426614174001',
      status: 'IN_PROGRESS',
      uploadedFilesCount: 2,
      totalSize: 256000,
      hasErrors: false,
      startedAt: daysAgo(0, 2), // today, 2 hours ago
      completedAt: null,
    },
    {
      id: '123e4567-e89b-12d3-a456-426614174004',
      siteId: '123e4567-e89b-12d3-a456-426614174002',
      status: 'COMPLETED_WITH_WARNINGS',
      uploadedFilesCount: 4,
      totalSize: 768000,
      hasErrors: false,
      startedAt: daysAgo(3, 0), // 3 days ago
      completedAt: daysAgo(3, -1), // 3 days ago + 1 hour
    },
  ];

  describe('Loading State', () => {
    it('should display loading spinner when isLoading is true', () => {
      render(
        <BatchListView
          batches={[]}
          isLoading={true}
          hasNextPage={false}
          isFetchingNextPage={false}
          onLoadMore={vi.fn()}
        />
      );

      expect(screen.getByText(/loading upload history/i)).toBeInTheDocument();
      // Check for spinner (Loader2 icon)
      const spinner = screen.getByText(/loading upload history/i).previousElementSibling;
      expect(spinner).toHaveClass('animate-spin');
    });

    it('should not display loading spinner when isLoading is false', () => {
      render(
        <BatchListView
          batches={mockBatches}
          isLoading={false}
          hasNextPage={false}
          isFetchingNextPage={false}
          onLoadMore={vi.fn()}
        />
      );

      expect(screen.queryByText(/loading upload history/i)).not.toBeInTheDocument();
    });
  });

  describe('Error State', () => {
    it('should display error message when error prop is provided', () => {
      const errorMessage = 'Failed to load batch history';

      render(
        <BatchListView
          batches={[]}
          isLoading={false}
          hasNextPage={false}
          isFetchingNextPage={false}
          onLoadMore={vi.fn()}
          error={errorMessage}
        />
      );

      expect(screen.getByText(errorMessage)).toBeInTheDocument();
      // Error should be in a red-styled container
      const errorContainer = screen.getByText(errorMessage).closest('div');
      expect(errorContainer).toHaveClass('border-red-200');
    });

    it('should not display batch list when error is present', () => {
      render(
        <BatchListView
          batches={mockBatches}
          isLoading={false}
          hasNextPage={false}
          isFetchingNextPage={false}
          onLoadMore={vi.fn()}
          error="Error occurred"
        />
      );

      // Batch data should not be rendered when there's an error
      expect(screen.queryByText(/5 files/i)).not.toBeInTheDocument();
    });
  });

  describe('Empty State', () => {
    it('should display empty state when no batches', () => {
      render(
        <BatchListView
          batches={[]}
          isLoading={false}
          hasNextPage={false}
          isFetchingNextPage={false}
          onLoadMore={vi.fn()}
        />
      );

      expect(screen.getByText(/no upload history found/i)).toBeInTheDocument();
      expect(
        screen.getByText(/upload sessions will appear here/i)
      ).toBeInTheDocument();
    });

    it('should not display empty state when batches exist', () => {
      render(
        <BatchListView
          batches={mockBatches}
          isLoading={false}
          hasNextPage={false}
          isFetchingNextPage={false}
          onLoadMore={vi.fn()}
        />
      );

      expect(screen.queryByText(/no upload history found/i)).not.toBeInTheDocument();
    });
  });

  describe('Batch List Display', () => {
    it('should render all batches', () => {
      render(
        <BatchListView
          batches={mockBatches}
          isLoading={false}
          hasNextPage={false}
          isFetchingNextPage={false}
          onLoadMore={vi.fn()}
        />
      );

      // Should display file counts for all batches
      expect(screen.getByText(/5 files/i)).toBeInTheDocument();
      expect(screen.getByText(/3 files/i)).toBeInTheDocument();
      expect(screen.getByText(/2 files/i)).toBeInTheDocument();
    });

    it('should display batch status badges', () => {
      render(
        <BatchListView
          batches={mockBatches}
          isLoading={false}
          hasNextPage={false}
          isFetchingNextPage={false}
          onLoadMore={vi.fn()}
        />
      );

      // mockBatches has: 1 COMPLETED, 1 FAILED, 1 IN_PROGRESS, 1 COMPLETED_WITH_WARNINGS
      expect(screen.getByText('COMPLETED')).toBeInTheDocument();
      expect(screen.getByText('FAILED')).toBeInTheDocument();
      expect(screen.getByText('IN_PROGRESS')).toBeInTheDocument();
      expect(screen.getByText('Completed (Warnings)')).toBeInTheDocument();
    });

    it('should display file size in human-readable format', () => {
      render(
        <BatchListView
          batches={mockBatches}
          isLoading={false}
          hasNextPage={false}
          isFetchingNextPage={false}
          onLoadMore={vi.fn()}
        />
      );

      // formatBytes should convert bytes to KB/MB (checking for "KB" or "MB" suffix)
      const sizeElements = screen.getAllByText(/\d+(\.\d+)?\s*(B|KB|MB|GB)/i);
      expect(sizeElements.length).toBeGreaterThan(0);
    });

    it('should display completed timestamp when available', () => {
      render(
        <BatchListView
          batches={mockBatches}
          isLoading={false}
          hasNextPage={false}
          isFetchingNextPage={false}
          onLoadMore={vi.fn()}
        />
      );

      // Should show "Completed:" for finished batches
      // mockBatches has 3 with completedAt: COMPLETED, FAILED, COMPLETED_WITH_WARNINGS
      const completedLabels = screen.getAllByText(/completed:/i);
      expect(completedLabels).toHaveLength(3);
    });

    it('should not display completed timestamp for in-progress batches', () => {
      const inProgressBatch: BatchSummary[] = [
        {
          ...mockBatches[2], // IN_PROGRESS batch
        },
      ];

      render(
        <BatchListView
          batches={inProgressBatch}
          isLoading={false}
          hasNextPage={false}
          isFetchingNextPage={false}
          onLoadMore={vi.fn()}
        />
      );

      expect(screen.queryByText(/completed:/i)).not.toBeInTheDocument();
    });
  });

  describe('Status Indicators', () => {
    it('should display green checkmark for batches without errors', () => {
      const successBatch: BatchSummary[] = [mockBatches[0]]; // hasErrors: false

      const { container } = render(
        <BatchListView
          batches={successBatch}
          isLoading={false}
          hasNextPage={false}
          isFetchingNextPage={false}
          onLoadMore={vi.fn()}
        />
      );

      // CheckCircle icon should be present
      const checkCircle = container.querySelector('.text-green-500');
      expect(checkCircle).toBeInTheDocument();
    });

    it('should display red X for FAILED status', () => {
      // Icon is based on status, not hasErrors
      const failedBatch: BatchSummary[] = [mockBatches[1]]; // status: 'FAILED'

      const { container } = render(
        <BatchListView
          batches={failedBatch}
          isLoading={false}
          hasNextPage={false}
          isFetchingNextPage={false}
          onLoadMore={vi.fn()}
        />
      );

      // XCircle icon should be present for FAILED status
      const xCircle = container.querySelector('.text-red-500');
      expect(xCircle).toBeInTheDocument();
    });

    it('should display green checkmark for COMPLETED_WITH_WARNINGS status', () => {
      const warningsBatch: BatchSummary[] = [mockBatches[3]]; // status: 'COMPLETED_WITH_WARNINGS'

      const { container } = render(
        <BatchListView
          batches={warningsBatch}
          isLoading={false}
          hasNextPage={false}
          isFetchingNextPage={false}
          onLoadMore={vi.fn()}
        />
      );

      // CheckCircle icon should be present for COMPLETED_WITH_WARNINGS status
      const checkCircle = container.querySelector('.text-green-500');
      expect(checkCircle).toBeInTheDocument();
    });

    it('should display blue spinner for IN_PROGRESS status', () => {
      const inProgressBatch: BatchSummary[] = [mockBatches[2]]; // status: 'IN_PROGRESS'

      const { container } = render(
        <BatchListView
          batches={inProgressBatch}
          isLoading={false}
          hasNextPage={false}
          isFetchingNextPage={false}
          onLoadMore={vi.fn()}
        />
      );

      // Loader2 icon should be present for IN_PROGRESS status
      const loader = container.querySelector('[class*="lucide-loader"]');
      expect(loader).toBeInTheDocument();
      expect(loader).toHaveClass('text-blue-500', 'animate-spin');
    });

    it('should apply correct badge color based on status', () => {
      render(
        <BatchListView
          batches={mockBatches}
          isLoading={false}
          hasNextPage={false}
          isFetchingNextPage={false}
          onLoadMore={vi.fn()}
        />
      );

      // COMPLETED status should have green badge
      const completedBadge = screen.getByText('COMPLETED');
      expect(completedBadge).toHaveClass('bg-green-100');
      expect(completedBadge).toHaveClass('text-green-800');

      // FAILED status should have red badge
      const failedBadge = screen.getByText('FAILED');
      expect(failedBadge).toHaveClass('bg-red-100');
      expect(failedBadge).toHaveClass('text-red-800');

      // IN_PROGRESS status should have blue badge
      const inProgressBadge = screen.getByText('IN_PROGRESS');
      expect(inProgressBadge).toHaveClass('bg-blue-100');
      expect(inProgressBadge).toHaveClass('text-blue-800');

      // COMPLETED_WITH_WARNINGS status should have yellow badge
      const warningsBadge = screen.getByText('Completed (Warnings)');
      expect(warningsBadge).toHaveClass('bg-yellow-100');
      expect(warningsBadge).toHaveClass('text-yellow-800');
    });
  });

  describe('Load More Button', () => {
    it('should display "Load More" button when hasNextPage is true', () => {
      render(
        <BatchListView
          batches={mockBatches}
          isLoading={false}
          hasNextPage={true}
          isFetchingNextPage={false}
          onLoadMore={vi.fn()}
        />
      );

      const loadMoreButton = screen.getByRole('button', { name: /load more/i });
      expect(loadMoreButton).toBeInTheDocument();
      expect(loadMoreButton).not.toBeDisabled();
    });

    it('should not display "Load More" button when hasNextPage is false', () => {
      render(
        <BatchListView
          batches={mockBatches}
          isLoading={false}
          hasNextPage={false}
          isFetchingNextPage={false}
          onLoadMore={vi.fn()}
        />
      );

      expect(screen.queryByRole('button', { name: /load more/i })).not.toBeInTheDocument();
    });

    it('should call onLoadMore when "Load More" button is clicked', async () => {
      const onLoadMore = vi.fn();
      const user = userEvent.setup();

      render(
        <BatchListView
          batches={mockBatches}
          isLoading={false}
          hasNextPage={true}
          isFetchingNextPage={false}
          onLoadMore={onLoadMore}
        />
      );

      const loadMoreButton = screen.getByRole('button', { name: /load more/i });
      await user.click(loadMoreButton);

      expect(onLoadMore).toHaveBeenCalledOnce();
    });

    it('should disable "Load More" button when isFetchingNextPage is true', () => {
      render(
        <BatchListView
          batches={mockBatches}
          isLoading={false}
          hasNextPage={true}
          isFetchingNextPage={true}
          onLoadMore={vi.fn()}
        />
      );

      const loadMoreButton = screen.getByRole('button', { name: /loading/i });
      expect(loadMoreButton).toBeDisabled();
    });

    it('should show loading text when fetching next page', () => {
      render(
        <BatchListView
          batches={mockBatches}
          isLoading={false}
          hasNextPage={true}
          isFetchingNextPage={true}
          onLoadMore={vi.fn()}
        />
      );

      expect(screen.getByText(/loading\.\.\./i)).toBeInTheDocument();
    });

    it('should show spinner when fetching next page', () => {
      const { container } = render(
        <BatchListView
          batches={mockBatches}
          isLoading={false}
          hasNextPage={true}
          isFetchingNextPage={true}
          onLoadMore={vi.fn()}
        />
      );

      // Loader2 icon should have animate-spin class
      const spinner = container.querySelector('.animate-spin');
      expect(spinner).toBeInTheDocument();
    });
  });

  describe('Hover Effects', () => {
    it('should apply hover styles to batch items', () => {
      const { container } = render(
        <BatchListView
          batches={[mockBatches[0]]}
          isLoading={false}
          hasNextPage={false}
          isFetchingNextPage={false}
          onLoadMore={vi.fn()}
        />
      );

      // Batch items should have hover:bg-gray-50 class
      const batchItem = container.querySelector('.hover\\:bg-gray-50');
      expect(batchItem).toBeInTheDocument();
    });
  });

  describe('Accessibility', () => {
    it('should have accessible button for Load More', () => {
      render(
        <BatchListView
          batches={mockBatches}
          isLoading={false}
          hasNextPage={true}
          isFetchingNextPage={false}
          onLoadMore={vi.fn()}
        />
      );

      const button = screen.getByRole('button', { name: /load more/i });
      expect(button).toBeInTheDocument();
    });

    it('should indicate disabled state properly', () => {
      render(
        <BatchListView
          batches={mockBatches}
          isLoading={false}
          hasNextPage={true}
          isFetchingNextPage={true}
          onLoadMore={vi.fn()}
        />
      );

      const button = screen.getByRole('button');
      expect(button).toBeDisabled();
      expect(button).toHaveClass('disabled:opacity-50');
      expect(button).toHaveClass('disabled:cursor-not-allowed');
    });
  });

  describe('Edge Cases', () => {
    it('should handle single batch', () => {
      render(
        <BatchListView
          batches={[mockBatches[0]]}
          isLoading={false}
          hasNextPage={false}
          isFetchingNextPage={false}
          onLoadMore={vi.fn()}
        />
      );

      expect(screen.getByText(/5 files/i)).toBeInTheDocument();
      expect(screen.queryByRole('button', { name: /load more/i })).not.toBeInTheDocument();
    });

    it('should handle batch with zero files', () => {
      const emptyBatch: BatchSummary = {
        ...mockBatches[0],
        uploadedFilesCount: 0,
        totalSize: 0,
      };

      render(
        <BatchListView
          batches={[emptyBatch]}
          isLoading={false}
          hasNextPage={false}
          isFetchingNextPage={false}
          onLoadMore={vi.fn()}
        />
      );

      expect(screen.getByText(/0 files/i)).toBeInTheDocument();
    });

    it('should handle very large file sizes', () => {
      const largeBatch: BatchSummary = {
        ...mockBatches[0],
        totalSize: 5368709120, // 5 GB
      };

      render(
        <BatchListView
          batches={[largeBatch]}
          isLoading={false}
          hasNextPage={false}
          isFetchingNextPage={false}
          onLoadMore={vi.fn()}
        />
      );

      // Should format large sizes appropriately (GB)
      expect(screen.getByText(/GB/i)).toBeInTheDocument();
    });

    it('should handle null error (no error state)', () => {
      render(
        <BatchListView
          batches={mockBatches}
          isLoading={false}
          hasNextPage={false}
          isFetchingNextPage={false}
          onLoadMore={vi.fn()}
          error={null}
        />
      );

      // Should display normal batch list, not error
      expect(screen.getByText(/5 files/i)).toBeInTheDocument();
    });
  });

  describe('Delta v2 batches (T6.6)', () => {
    it('should show changes/tables instead of files for a Delta batch', () => {
      const deltaBatch: BatchSummary = {
        ...mockBatches[0],
        uploadedFilesCount: 0,
        totalSize: 0,
        deltaRecordCount: 34,
        deltaTableCount: 6,
      };

      render(
        <BatchListView
          batches={[deltaBatch]}
          isLoading={false}
          hasNextPage={false}
          isFetchingNextPage={false}
          onLoadMore={vi.fn()}
        />
      );

      expect(screen.getByText(/34 changes • 6 tables/i)).toBeInTheDocument();
      expect(screen.queryByText(/files/i)).not.toBeInTheDocument();
    });

    it('should still show file count for v1 batches without a Delta signal', () => {
      render(
        <BatchListView
          batches={[mockBatches[0]]}
          isLoading={false}
          hasNextPage={false}
          isFetchingNextPage={false}
          onLoadMore={vi.fn()}
        />
      );

      expect(screen.getByText(/5 files/i)).toBeInTheDocument();
      expect(screen.queryByText(/changes/i)).not.toBeInTheDocument();
    });
  });

  describe('Site Filter', () => {
    const mockSites = [
      { id: '123e4567-e89b-12d3-a456-426614174001', accountId: 'acc-1', domain: 'site1.com', name: 'Site 1', isActive: true, createdAt: '2025-01-01' },
      { id: '123e4567-e89b-12d3-a456-426614174002', accountId: 'acc-1', domain: 'site2.com', name: 'Site 2', isActive: true, createdAt: '2025-01-01' },
    ];

    it('should render site filter dropdown when sites provided', () => {
      render(
        <BatchListView
          batches={mockBatches}
          isLoading={false}
          hasNextPage={false}
          isFetchingNextPage={false}
          onLoadMore={vi.fn()}
          sites={mockSites}
        />
      );

      expect(screen.getByText('All Sites')).toBeInTheDocument();
      expect(screen.getByText('Site 1')).toBeInTheDocument();
      expect(screen.getByText('Site 2')).toBeInTheDocument();
    });

    it('should not render site filter when no sites provided', () => {
      render(
        <BatchListView
          batches={mockBatches}
          isLoading={false}
          hasNextPage={false}
          isFetchingNextPage={false}
          onLoadMore={vi.fn()}
        />
      );

      expect(screen.queryByText('All Sites')).not.toBeInTheDocument();
    });

    it('should filter batches by site when site selected', async () => {
      const user = userEvent.setup();
      render(
        <BatchListView
          batches={mockBatches}
          isLoading={false}
          hasNextPage={false}
          isFetchingNextPage={false}
          onLoadMore={vi.fn()}
          sites={mockSites}
        />
      );

      // Initially should show all 4 batches - check by counting status badges
      expect(screen.getByText('COMPLETED')).toBeInTheDocument();
      expect(screen.getByText('FAILED')).toBeInTheDocument();
      expect(screen.getByText('IN_PROGRESS')).toBeInTheDocument();
      expect(screen.getByText('Completed (Warnings)')).toBeInTheDocument();

      // Select Site 2 (has only mockBatches[3])
      const siteSelect = screen.getByDisplayValue('All Sites');
      await user.selectOptions(siteSelect, '123e4567-e89b-12d3-a456-426614174002');

      // Should only show the batch from Site 2 (COMPLETED_WITH_WARNINGS)
      expect(screen.getByText('Completed (Warnings)')).toBeInTheDocument();
      // Other batches should not be visible
      expect(screen.queryByText('COMPLETED')).not.toBeInTheDocument();
      expect(screen.queryByText('FAILED')).not.toBeInTheDocument();
      expect(screen.queryByText('IN_PROGRESS')).not.toBeInTheDocument();
    });

    it('should include site filter in clear filters', async () => {
      const user = userEvent.setup();
      render(
        <BatchListView
          batches={mockBatches}
          isLoading={false}
          hasNextPage={false}
          isFetchingNextPage={false}
          onLoadMore={vi.fn()}
          sites={mockSites}
        />
      );

      // Select a site filter
      const siteSelect = screen.getByDisplayValue('All Sites');
      await user.selectOptions(siteSelect, '123e4567-e89b-12d3-a456-426614174002');

      // Clear filters button should appear
      const clearButton = screen.getByText('Clear filters');
      expect(clearButton).toBeInTheDocument();

      // Click clear filters
      await user.click(clearButton);

      // Site filter should be reset to 'All Sites'
      expect(screen.getByDisplayValue('All Sites')).toBeInTheDocument();
    });
  });

  describe('Refresh Button', () => {
    it('should render refresh button when onRefresh provided', () => {
      render(
        <BatchListView
          batches={mockBatches}
          isLoading={false}
          hasNextPage={false}
          isFetchingNextPage={false}
          onLoadMore={vi.fn()}
          onRefresh={vi.fn()}
        />
      );

      expect(screen.getByRole('button', { name: /refresh/i })).toBeInTheDocument();
    });

    it('should not render refresh button when onRefresh not provided', () => {
      render(
        <BatchListView
          batches={mockBatches}
          isLoading={false}
          hasNextPage={false}
          isFetchingNextPage={false}
          onLoadMore={vi.fn()}
        />
      );

      expect(screen.queryByRole('button', { name: /refresh/i })).not.toBeInTheDocument();
    });

    it('should call onRefresh when refresh button clicked', async () => {
      const user = userEvent.setup();
      const onRefresh = vi.fn();

      render(
        <BatchListView
          batches={mockBatches}
          isLoading={false}
          hasNextPage={false}
          isFetchingNextPage={false}
          onLoadMore={vi.fn()}
          onRefresh={onRefresh}
        />
      );

      const refreshButton = screen.getByRole('button', { name: /refresh/i });
      await user.click(refreshButton);

      expect(onRefresh).toHaveBeenCalledOnce();
    });

    it('should disable refresh button when isRefreshing is true', () => {
      render(
        <BatchListView
          batches={mockBatches}
          isLoading={false}
          hasNextPage={false}
          isFetchingNextPage={false}
          onLoadMore={vi.fn()}
          onRefresh={vi.fn()}
          isRefreshing={true}
        />
      );

      const refreshButton = screen.getByRole('button', { name: /refresh/i });
      expect(refreshButton).toBeDisabled();
    });

    it('should show spinning icon when isRefreshing is true', () => {
      const { container } = render(
        <BatchListView
          batches={mockBatches}
          isLoading={false}
          hasNextPage={false}
          isFetchingNextPage={false}
          onLoadMore={vi.fn()}
          onRefresh={vi.fn()}
          isRefreshing={true}
        />
      );

      // RefreshCw icon should have animate-spin class when refreshing
      const refreshIcon = container.querySelector('.lucide-refresh-cw');
      expect(refreshIcon).toHaveClass('animate-spin');
    });
  });
});
