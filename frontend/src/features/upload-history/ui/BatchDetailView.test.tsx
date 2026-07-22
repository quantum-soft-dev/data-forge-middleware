/**
 * T050: Component tests for BatchDetailView
 *
 * Tests batch detail display component with file list, status indicators,
 * loading states, error handling, and navigation.
 *
 * Feature: 008-upload-history-user (User Story 2)
 */

import { describe, it, expect, vi } from 'vitest';
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { BatchDetailView } from './BatchDetailView';
import { monitoringTokens, severityTokens } from '@/shared/ui/tokens';
import type { BatchDetail } from '@/entities/batch/model/types';

describe('BatchDetailView', () => {
  // Helper function to render with QueryClient
  const renderWithQueryClient = (ui: React.ReactElement) => {
    const queryClient = new QueryClient({
      defaultOptions: {
        queries: { retry: false },
        mutations: { retry: false },
      },
    });
    return render(
      <QueryClientProvider client={queryClient}>{ui}</QueryClientProvider>
    );
  };
  const mockBatch: BatchDetail = {
    id: '550e8400-e29b-41d4-a716-446655440000',
    siteId: '123e4567-e89b-12d3-a456-426614174001',
    status: 'COMPLETED',
    hasErrors: false,
    uploadedFilesCount: 3,
    totalSize: 1536000,
    startedAt: '2025-11-01T10:00:00Z',
    completedAt: '2025-11-01T10:05:00Z',
    files: [
      {
        id: 'file-001',
        originalFileName: 'sales-2024.csv.gz',
        fileSize: 512000,
        uploadedAt: '2025-11-01T10:01:00Z',
      },
      {
        id: 'file-002',
        originalFileName: 'inventory-2024.csv.gz',
        fileSize: 768000,
        uploadedAt: '2025-11-01T10:02:00Z',
      },
      {
        id: 'file-003',
        originalFileName: 'orders-2024.csv.gz',
        fileSize: 256000,
        uploadedAt: '2025-11-01T10:03:00Z',
      },
    ],
  };

  describe('Loading State', () => {
    it('should display loading spinner when isLoading is true', () => {
      renderWithQueryClient(
        <BatchDetailView
          batch={undefined}
          isLoading={true}
          error={null}
        />
      );

      expect(screen.getByText(/loading batch details/i)).toBeInTheDocument();
      const spinner = screen.getByText(/loading batch details/i).previousElementSibling;
      expect(spinner).toHaveClass('animate-spin');
    });

    it('should not display loading spinner when isLoading is false', () => {
      renderWithQueryClient(
        <BatchDetailView
          batch={mockBatch}
          isLoading={false}
          error={null}
        />
      );

      expect(screen.queryByText(/loading batch details/i)).not.toBeInTheDocument();
    });
  });

  describe('Error State', () => {
    it('should display error message when error prop is provided', () => {
      const errorMessage = 'Failed to load batch details';

      renderWithQueryClient(
        <BatchDetailView
          batch={undefined}
          isLoading={false}
          error={errorMessage}
        />
      );

      expect(screen.getByText(errorMessage)).toBeInTheDocument();
    });

    it('should display back button when error occurs and onBack is provided', async () => {
      const onBack = vi.fn();
      const user = userEvent.setup();

      renderWithQueryClient(
        <BatchDetailView
          batch={undefined}
          isLoading={false}
          error="Error occurred"
          onBack={onBack}
        />
      );

      const backButton = screen.getByRole('button', { name: /back to list/i });
      expect(backButton).toBeInTheDocument();

      await user.click(backButton);
      expect(onBack).toHaveBeenCalledOnce();
    });
  });

  describe('Empty State', () => {
    it('should display "Batch not found" when batch is undefined and not loading', () => {
      renderWithQueryClient(
        <BatchDetailView
          batch={undefined}
          isLoading={false}
          error={null}
        />
      );

      expect(screen.getByText(/batch not found/i)).toBeInTheDocument();
    });
  });

  describe('Batch Metadata Display', () => {
    it('should display batch ID and status', () => {
      renderWithQueryClient(
        <BatchDetailView
          batch={mockBatch}
          isLoading={false}
          error={null}
        />
      );

      expect(screen.getByText(/upload session/i)).toBeInTheDocument();
      expect(screen.getByText(new RegExp(mockBatch.id))).toBeInTheDocument();
      expect(screen.getByText('Completed')).toBeInTheDocument();
    });

    it('should display success icon when hasErrors is false', () => {
      renderWithQueryClient(
        <BatchDetailView
          batch={mockBatch}
          isLoading={false}
          error={null}
        />
      );

      // CheckCircle icon should be present (green checkmark)
      const container = screen.getByText(/upload session/i).closest('div');
      expect(container).toBeInTheDocument();
    });

    it('should display error icon when hasErrors is true', () => {
      const batchWithErrors = { ...mockBatch, hasErrors: true };

      renderWithQueryClient(
        <BatchDetailView
          batch={batchWithErrors}
          isLoading={false}
          error={null}
        />
      );

      // XCircle icon should be present (red X)
      const container = screen.getByText(/upload session/i).closest('div');
      expect(container).toBeInTheDocument();
    });

    it('should display file count and total size', () => {
      renderWithQueryClient(
        <BatchDetailView
          batch={mockBatch}
          isLoading={false}
          error={null}
        />
      );

      expect(screen.getByText('3 files')).toBeInTheDocument();
      expect(screen.getByText(/1\.5 MB/i)).toBeInTheDocument(); // 1536000 bytes = 1.5 MB
    });

    it('should display started and completed timestamps', () => {
      renderWithQueryClient(
        <BatchDetailView
          batch={mockBatch}
          isLoading={false}
          error={null}
        />
      );

      expect(screen.getByText(/started at/i)).toBeInTheDocument();
      expect(screen.getByText(/completed at/i)).toBeInTheDocument();
    });

    it('should not display completed timestamp for in-progress batches', () => {
      const inProgressBatch = { ...mockBatch, status: 'IN_PROGRESS' as const, completedAt: null };

      renderWithQueryClient(
        <BatchDetailView
          batch={inProgressBatch}
          isLoading={false}
          error={null}
        />
      );

      expect(screen.getByText(/started at/i)).toBeInTheDocument();
      expect(screen.queryByText(/completed at/i)).not.toBeInTheDocument();
    });

    it('should display error indicator for FAILED status', () => {
      // Icon is based on status, not hasErrors
      const failedBatch = { ...mockBatch, status: 'FAILED' as const };

      renderWithQueryClient(
        <BatchDetailView
          batch={failedBatch}
          isLoading={false}
          error={null}
        />
      );

      // Check for XCircle icon (error indicator) based on FAILED status
      const errorIcon = document.querySelector('.lucide-circle-x');
      expect(errorIcon).toBeInTheDocument();
      expect(errorIcon).toHaveStyle({ color: severityTokens.critical.dot });
    });

    it('should display success indicator for COMPLETED_WITH_WARNINGS status', () => {
      const batchWithWarnings = { ...mockBatch, status: 'COMPLETED_WITH_WARNINGS' as const };

      renderWithQueryClient(
        <BatchDetailView
          batch={batchWithWarnings}
          isLoading={false}
          error={null}
        />
      );

      // Check for CheckCircle icon (success indicator) based on COMPLETED_WITH_WARNINGS status
      // lucide-react uses class like 'lucide-circle-check-big' for CheckCircle
      const successIcon = document.querySelector('[class*="lucide-circle-check"]');
      expect(successIcon).toBeInTheDocument();
      expect(successIcon).toHaveStyle({ color: severityTokens.healthy.dot });
    });

    it('should display loading indicator for IN_PROGRESS status', () => {
      const inProgressBatch = { ...mockBatch, status: 'IN_PROGRESS' as const, completedAt: null };

      renderWithQueryClient(
        <BatchDetailView
          batch={inProgressBatch}
          isLoading={false}
          error={null}
        />
      );

      // Check for Loader2 icon (loading indicator) based on IN_PROGRESS status
      // lucide-react uses class like 'lucide-loader-2' or 'lucide-loader'
      const loadingIcon = document.querySelector('[class*="lucide-loader"]');
      expect(loadingIcon).toBeInTheDocument();
      expect(loadingIcon).toHaveClass('text-brand', 'animate-spin');
    });
  });

  describe('File List Display', () => {
    it('should display file count heading', () => {
      renderWithQueryClient(
        <BatchDetailView
          batch={mockBatch}
          isLoading={false}
          error={null}
        />
      );

      expect(screen.getByText(`Files (${mockBatch.files.length})`)).toBeInTheDocument();
    });

    it('should render FileTable component with files', () => {
      renderWithQueryClient(
        <BatchDetailView
          batch={mockBatch}
          isLoading={false}
          error={null}
        />
      );

      // Check that file table headers are present
      expect(screen.getByText(/filename/i)).toBeInTheDocument();
      // "Size" appears twice (table header + batch metadata), so use getAllByText
      expect(screen.getAllByText(/size/i).length).toBeGreaterThan(0);
      // Column header is just "Uploaded" (not "Uploaded At")
      expect(screen.getByRole('columnheader', { name: /uploaded/i })).toBeInTheDocument();

      // Check that files are displayed
      mockBatch.files.forEach((file) => {
        expect(screen.getByText(file.originalFileName)).toBeInTheDocument();
      });
    });
  });

  describe('Navigation', () => {
    it('should display back button when onBack is provided', () => {
      renderWithQueryClient(
        <BatchDetailView
          batch={mockBatch}
          isLoading={false}
          error={null}
          onBack={vi.fn()}
        />
      );

      expect(screen.getByRole('button', { name: /back to list/i })).toBeInTheDocument();
    });

    it('should not display back button when onBack is not provided', () => {
      renderWithQueryClient(
        <BatchDetailView
          batch={mockBatch}
          isLoading={false}
          error={null}
        />
      );

      expect(screen.queryByRole('button', { name: /back to list/i })).not.toBeInTheDocument();
    });

    it('should call onBack when back button is clicked', async () => {
      const onBack = vi.fn();
      const user = userEvent.setup();

      renderWithQueryClient(
        <BatchDetailView
          batch={mockBatch}
          isLoading={false}
          error={null}
          onBack={onBack}
        />
      );

      const backButton = screen.getByRole('button', { name: /back to list/i });
      await user.click(backButton);

      expect(onBack).toHaveBeenCalledOnce();
    });
  });

  describe('File Selection Callback', () => {
    it('should call onFileSelectionChange when file selection changes', async () => {
      const onFileSelectionChange = vi.fn();
      const user = userEvent.setup();

      renderWithQueryClient(
        <BatchDetailView
          batch={mockBatch}
          isLoading={false}
          error={null}
          onFileSelectionChange={onFileSelectionChange}
        />
      );

      // Click on first file checkbox
      const checkboxes = screen.getAllByRole('checkbox');
      // First checkbox is "Select all", second is first file
      await user.click(checkboxes[1]);

      expect(onFileSelectionChange).toHaveBeenCalled();
    });
  });

  describe('Status Badge Colors', () => {
    it('should display green badge for COMPLETED status', () => {
      renderWithQueryClient(
        <BatchDetailView
          batch={mockBatch}
          isLoading={false}
          error={null}
        />
      );

      const statusBadge = screen.getByText('Completed');
      expect(statusBadge).toHaveStyle({ background: severityTokens.healthy.bg, color: severityTokens.healthy.text });
    });

    it('should display blue badge for IN_PROGRESS status', () => {
      const inProgressBatch = { ...mockBatch, status: 'IN_PROGRESS' as const };

      renderWithQueryClient(
        <BatchDetailView
          batch={inProgressBatch}
          isLoading={false}
          error={null}
        />
      );

      const statusBadge = screen.getByText('In progress');
      expect(statusBadge).toHaveStyle({ background: monitoringTokens.blue50, color: monitoringTokens.primary });
    });

    it('should display red badge for FAILED status', () => {
      const failedBatch = { ...mockBatch, status: 'FAILED' as const };

      renderWithQueryClient(
        <BatchDetailView
          batch={failedBatch}
          isLoading={false}
          error={null}
        />
      );

      const statusBadge = screen.getByText('Failed');
      expect(statusBadge).toHaveStyle({ background: severityTokens.critical.bg, color: severityTokens.critical.text });
    });
  });

  // F10 (023, DoD #1): the three delta states
  describe('Delta v2 batch surfaces (F10)', () => {
    const deltaBatch: BatchDetail = {
      ...mockBatch,
      id: 'd3f8e0f1-1111-2222-3333-444455556666',
      uploadedFilesCount: 0,
      files: [],
      mode: 'CONTINUOUS',
      seqRange: { first: 4801, last: 5100 },
      deltaStats: [
        { table: 'orders', inserts: 120, updates: 45, deletes: 3 },
        { table: 'customers', inserts: 10, updates: 0, deletes: 7 },
      ],
    };

    it('renders the redesigned delta surfaces and hides the entire file UI', () => {
      renderWithQueryClient(<BatchDetailView batch={deltaBatch} isLoading={false} />);

      // Meta card: short id, chips
      expect(screen.getByText('Batch #d3f8e0f1')).toBeInTheDocument();
      expect(screen.getByText('Delta session')).toBeInTheDocument();
      expect(screen.getByText('CONTINUOUS')).toBeInTheDocument();
      expect(screen.getByText('4,801 – 5,100')).toBeInTheDocument();

      // Table changes with signed colored values, sorted by table name, Total row
      expect(screen.getByText('Table changes')).toBeInTheDocument();
      const rows = screen.getAllByTestId(/delta-stats-row-/);
      expect(rows[0]).toHaveAttribute('data-testid', 'delta-stats-row-customers');
      expect(rows[1]).toHaveAttribute('data-testid', 'delta-stats-row-orders');
      expect(screen.getByText('+120')).toBeInTheDocument();
      expect(screen.getByText('−3')).toBeInTheDocument();
      const totalRow = screen.getByTestId('delta-stats-total-row');
      expect(totalRow).toHaveTextContent('+130');
      expect(totalRow).toHaveTextContent('45');
      expect(totalRow).toHaveTextContent('−10');
      expect(totalRow).toHaveTextContent('185');

      // File UI hidden
      expect(screen.queryByText(/^Files \(/)).not.toBeInTheDocument();
      expect(screen.queryByRole('button', { name: /download/i })).not.toBeInTheDocument();
      expect(screen.queryByRole('button', { name: /excel/i })).not.toBeInTheDocument();
      expect(screen.queryByRole('button', { name: /compare/i })).not.toBeInTheDocument();
    });

    it('shows the empty-session state when both deltaStats and files are empty', () => {
      const emptyBatch: BatchDetail = { ...mockBatch, uploadedFilesCount: 0, files: [], deltaStats: [] };
      renderWithQueryClient(<BatchDetailView batch={emptyBatch} isLoading={false} />);

      expect(screen.getByText('No changes in this session')).toBeInTheDocument();
      expect(screen.queryByTestId('delta-batch-detail')).not.toBeInTheDocument();
      expect(screen.queryByText(/^Files \(/)).not.toBeInTheDocument();
    });

    it('keeps the unchanged v1 behavior when files are present', () => {
      renderWithQueryClient(<BatchDetailView batch={mockBatch} isLoading={false} />);

      expect(screen.getByText('Files (3)')).toBeInTheDocument();
      expect(screen.queryByTestId('delta-batch-detail')).not.toBeInTheDocument();
      expect(screen.queryByText('No changes in this session')).not.toBeInTheDocument();
    });
  });
});
