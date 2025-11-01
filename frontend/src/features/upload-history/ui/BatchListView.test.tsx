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

describe('BatchListView', () => {
  const mockBatches: BatchSummary[] = [
    {
      id: '123e4567-e89b-12d3-a456-426614174000',
      siteId: '123e4567-e89b-12d3-a456-426614174001',
      status: 'COMPLETED',
      s3Path: 's3://bucket/path',
      uploadedFilesCount: 5,
      totalSize: 1024000,
      hasErrors: false,
      startedAt: '2025-11-01T10:00:00Z',
      completedAt: '2025-11-01T10:05:00Z',
    },
    {
      id: '123e4567-e89b-12d3-a456-426614174002',
      siteId: '123e4567-e89b-12d3-a456-426614174001',
      status: 'COMPLETED',
      s3Path: 's3://bucket/path2',
      uploadedFilesCount: 3,
      totalSize: 512000,
      hasErrors: true,
      startedAt: '2025-11-01T09:00:00Z',
      completedAt: '2025-11-01T09:05:00Z',
    },
    {
      id: '123e4567-e89b-12d3-a456-426614174003',
      siteId: '123e4567-e89b-12d3-a456-426614174001',
      status: 'IN_PROGRESS',
      s3Path: 's3://bucket/path3',
      uploadedFilesCount: 2,
      totalSize: 256000,
      hasErrors: false,
      startedAt: '2025-11-01T08:00:00Z',
      completedAt: null,
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

      const completedBadges = screen.getAllByText('COMPLETED');
      expect(completedBadges).toHaveLength(2);

      const inProgressBadge = screen.getByText('IN_PROGRESS');
      expect(inProgressBadge).toBeInTheDocument();
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
      const completedLabels = screen.getAllByText(/completed:/i);
      expect(completedLabels).toHaveLength(2); // Two batches have completedAt
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

    it('should display red X for batches with errors', () => {
      const errorBatch: BatchSummary[] = [mockBatches[1]]; // hasErrors: true

      const { container } = render(
        <BatchListView
          batches={errorBatch}
          isLoading={false}
          hasNextPage={false}
          isFetchingNextPage={false}
          onLoadMore={vi.fn()}
        />
      );

      // XCircle icon should be present
      const xCircle = container.querySelector('.text-red-500');
      expect(xCircle).toBeInTheDocument();
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
      const completedBadges = screen.getAllByText('COMPLETED');
      completedBadges.forEach((badge) => {
        expect(badge).toHaveClass('bg-green-100');
        expect(badge).toHaveClass('text-green-800');
      });

      // IN_PROGRESS status should have blue badge
      const inProgressBadge = screen.getByText('IN_PROGRESS');
      expect(inProgressBadge).toHaveClass('bg-blue-100');
      expect(inProgressBadge).toHaveClass('text-blue-800');
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
});
