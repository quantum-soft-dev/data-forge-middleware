/**
 * T122: Component test for ComparisonListView
 *
 * Tests the comparison list view component with:
 * - Rendering list of comparisons
 * - Empty state when no comparisons
 * - Status filter UI
 * - Loading states
 * - Error handling
 *
 * Phase 10: List Comparisons (Supporting Feature)
 * Priority: P3
 *
 * Note: This test should FAIL initially because the component doesn't exist yet (TDD Red phase).
 */

import { describe, it, expect, vi } from 'vitest';
import { render, screen, fireEvent } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { ComparisonListView } from '../../ui/ComparisonListView';

// Helper to wrap component with QueryClientProvider
function renderWithQueryClient(ui: React.ReactElement) {
  const queryClient = new QueryClient({
    defaultOptions: {
      queries: { retry: false },
      mutations: { retry: false },
    },
  });
  return render(<QueryClientProvider client={queryClient}>{ui}</QueryClientProvider>);
}

describe('ComparisonListView', () => {
  const mockComparisons = [
    {
      id: 1,
      currentBatchId: 'batch-1',
      targetBatchId: 'batch-2',
      status: 'COMPLETED' as const,
      totalFilesCompared: 10,
      filesChanged: 5,
      filesAdded: 2,
      filesUnchanged: 3,
      totalChangeSize: 1024,
      createdAt: '2025-11-04T12:00:00Z',
      startedAt: '2025-11-04T12:00:01Z',
      completedAt: '2025-11-04T12:00:30Z',
    },
    {
      id: 2,
      currentBatchId: 'batch-3',
      targetBatchId: 'batch-4',
      status: 'FAILED' as const,
      totalFilesCompared: 0,
      filesChanged: 0,
      filesAdded: 0,
      filesUnchanged: 0,
      totalChangeSize: 0,
      errorMessage: 'Test error',
      createdAt: '2025-11-04T11:00:00Z',
      startedAt: '2025-11-04T11:00:01Z',
    },
  ];

  const defaultProps = {
    comparisons: mockComparisons,
    isLoading: false,
    onStatusFilterChange: vi.fn(),
    selectedStatus: undefined as string | undefined,
  };

  it('should render list of comparisons', () => {
    // When: Render component with comparisons
    renderWithQueryClient(<ComparisonListView {...defaultProps} />);

    // Then: Should display all comparisons
    expect(screen.getByText(/batch-1/)).toBeInTheDocument();
    expect(screen.getByText(/batch-2/)).toBeInTheDocument();
    expect(screen.getByText(/batch-3/)).toBeInTheDocument();
    expect(screen.getByText(/batch-4/)).toBeInTheDocument();
  });

  it('should display comparison status badges', () => {
    // When: Render component with comparisons
    renderWithQueryClient(<ComparisonListView {...defaultProps} />);

    // Then: Should show status badges
    expect(screen.getByText('COMPLETED')).toBeInTheDocument();
    expect(screen.getByText('FAILED')).toBeInTheDocument();
  });

  it('should display file statistics', () => {
    // When: Render component with comparisons
    renderWithQueryClient(<ComparisonListView {...defaultProps} />);

    // Then: Should show file counts (using getAllByText since there are 2 comparisons)
    expect(screen.getByText(/10 files compared/i)).toBeInTheDocument();
    const changedLabels = screen.getAllByText('Changed');
    expect(changedLabels.length).toBeGreaterThan(0);
    const addedLabels = screen.getAllByText('Added');
    expect(addedLabels.length).toBeGreaterThan(0);
  });

  it('should render empty state when no comparisons', () => {
    // When: Render component with empty list
    renderWithQueryClient(<ComparisonListView {...defaultProps} comparisons={[]} />);

    // Then: Should show empty state message
    expect(screen.getByText(/no comparisons/i)).toBeInTheDocument();
  });

  it('should show loading skeleton when loading', () => {
    // When: Render component in loading state
    renderWithQueryClient(<ComparisonListView {...defaultProps} isLoading={true} comparisons={[]} />);

    // Then: Should show loading indicator
    expect(screen.getByRole('status')).toBeInTheDocument(); // Loading spinner or skeleton
  });

  it('should render status filter dropdown', () => {
    // When: Render component
    renderWithQueryClient(<ComparisonListView {...defaultProps} />);

    // Then: Should have status filter UI
    const filterButton = screen.getByRole('combobox', { name: /status/i });
    expect(filterButton).toBeInTheDocument();
  });

  it('should call onStatusFilterChange when filter selected', () => {
    // Given: Mock callback
    const onStatusFilterChange = vi.fn();

    // When: Render and select filter
    renderWithQueryClient(<ComparisonListView {...defaultProps} onStatusFilterChange={onStatusFilterChange} />);

    const filterButton = screen.getByRole('combobox', { name: /status/i });
    fireEvent.click(filterButton);

    // Select COMPLETED option
    const completedOption = screen.getByRole('option', { name: /completed/i });
    fireEvent.click(completedOption);

    // Then: Callback should be called
    expect(onStatusFilterChange).toHaveBeenCalledWith('COMPLETED');
  });

  it('should highlight selected filter', () => {
    // When: Render with selected status
    renderWithQueryClient(<ComparisonListView {...defaultProps} selectedStatus="COMPLETED" />);

    // Then: Should show active filter state
    const filterButton = screen.getByRole('combobox', { name: /status/i });
    expect(filterButton).toHaveTextContent(/completed/i);
  });

  it('should display comparison timestamps', () => {
    // When: Render component
    renderWithQueryClient(<ComparisonListView {...defaultProps} />);

    // Then: Should show formatted timestamps (both comparisons have Nov 4, 2025)
    const timestamps = screen.getAllByText(/Nov 4, 2025/i);
    expect(timestamps.length).toBeGreaterThan(0);
  });

  it('should handle comparisons with error messages', () => {
    // When: Render component with failed comparison
    renderWithQueryClient(<ComparisonListView {...defaultProps} />);

    // Then: Should display error message
    expect(screen.getByText(/test error/i)).toBeInTheDocument();
  });

  it('should have accessibility labels for screen readers', () => {
    // When: Render component
    renderWithQueryClient(<ComparisonListView {...defaultProps} />);

    // Then: Should have proper ARIA labels
    const list = screen.getByRole('list');
    expect(list).toBeInTheDocument();

    const listItems = screen.getAllByRole('listitem');
    expect(listItems.length).toBe(2);
  });

  it('should support clearing status filter', () => {
    // Given: Filter is selected
    const onStatusFilterChange = vi.fn();
    renderWithQueryClient(
      <ComparisonListView {...defaultProps} selectedStatus="COMPLETED" onStatusFilterChange={onStatusFilterChange} />
    );

    // When: User clears filter
    const filterButton = screen.getByRole('combobox', { name: /status/i });
    fireEvent.click(filterButton);

    const clearOption = screen.getByRole('option', { name: /all/i });
    fireEvent.click(clearOption);

    // Then: Should call with undefined/null
    expect(onStatusFilterChange).toHaveBeenCalledWith(undefined);
  });
});
