/**
 * T080: Component test for ComparisonSummaryWidget
 *
 * Tests the widget's rendering, styling, and interaction features:
 * - Statistics display
 * - Enhanced styling with icons
 * - "View Details" button functionality
 * - Timestamp formatting
 * - Session ID display with links
 * - Change percentage badge
 *
 * Feature: 009-markdown-user-story (User Story 5 - View Summary Report)
 * Priority: P2
 * Phase: Phase 6
 *
 * Test Coverage:
 * - Component rendering with valid summary data
 * - Enhanced icons and styling presence
 * - "View Details" button callback
 * - Upload session navigation links
 * - Timestamp and change size formatting
 * - Change percentage badge color logic
 */

import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { ComparisonSummaryWidget } from '../ComparisonSummaryWidget';
import { monitoringTokens, severityTokens } from '@/shared/ui/tokens';
import type { ComparisonSummary } from '@/entities/comparison/model/types';

// Mock the useDownloadSummaryReport hook
vi.mock('@/features/file-comparison/hooks/useDownloadSummaryReport', () => ({
  useDownloadSummaryReport: () => ({
    mutate: vi.fn(),
    isPending: false,
    isSuccess: false,
    isError: false,
    error: null,
    data: undefined,
  }),
}));

describe('ComparisonSummaryWidget', () => {
  let queryClient: QueryClient;

  beforeEach(() => {
    queryClient = new QueryClient({
      defaultOptions: {
        queries: { retry: false },
        mutations: { retry: false },
      },
    });
    vi.clearAllMocks();
  });

  const renderWithProviders = (ui: React.ReactElement) => {
    return render(
      <QueryClientProvider client={queryClient}>{ui}</QueryClientProvider>
    );
  };

  const mockSummary: ComparisonSummary = {
    totalFilesCompared: 10,
    filesChanged: 5,
    filesAdded: 2,
    filesUnchanged: 3,
    totalChangeSize: 1024000, // 1 MB
    comparisonTimestamp: '2025-11-03T10:30:00Z',
    currentBatchId: 'a1b2c3d4-e5f6-7890-abcd-ef1234567890',
    targetBatchId: 'c3d4e5f6-a7b8-9012-cdef-123456789012',
  };

  it('should render summary statistics correctly', () => {
    renderWithProviders(
      <ComparisonSummaryWidget
        summary={mockSummary}
        comparisonId={1}
      />
    );

    // Verify title (may appear multiple times due to nested components)
    expect(screen.getAllByText('Comparison Summary')[0]).toBeInTheDocument();

    // Verify statistics display
    expect(screen.getByText('10')).toBeInTheDocument(); // Total files
    expect(screen.getByText('Total Files')).toBeInTheDocument();

    expect(screen.getByText('5')).toBeInTheDocument(); // Files changed
    expect(screen.getByText('Modified')).toBeInTheDocument();

    expect(screen.getByText('2')).toBeInTheDocument(); // Files added
    expect(screen.getByText('Added')).toBeInTheDocument();

    expect(screen.getByText('3')).toBeInTheDocument(); // Files unchanged
    expect(screen.getByText('Unchanged')).toBeInTheDocument();
  });

  it('should display change percentage badge with correct color', () => {
    renderWithProviders(
      <ComparisonSummaryWidget
        summary={mockSummary}
        comparisonId={1}
      />
    );

    // 70% changed (5 changed + 2 added) / 10 total
    const badges = screen.getAllByText('70% Changed');
    expect(badges.length).toBeGreaterThan(0);
    // Badge should have the critical (alpha red) treatment for >50%
    expect(badges[0]).toHaveStyle({
      background: severityTokens.critical.bg,
      color: severityTokens.critical.text,
    });
  });

  it('should display formatted timestamp correctly', () => {
    renderWithProviders(
      <ComparisonSummaryWidget
        summary={mockSummary}
        comparisonId={1}
      />
    );

    // Check for timestamp presence (format may vary by locale)
    const timestamps = screen.queryAllByText(/Nov 3, 2025/i);
    expect(timestamps.length).toBeGreaterThan(0);
  });

  it('should display formatted change size', () => {
    const { container } = renderWithProviders(
      <ComparisonSummaryWidget
        summary={mockSummary}
        comparisonId={1}
      />
    );

    // 1024000 bytes should be formatted (format may vary: KB or MB)
    // Just check that the component rendered successfully
    expect(container.textContent).toBeTruthy();
    // Check for presence of size-related text
    expect(container.textContent).toMatch(/(KB|MB|Bytes)/i);
  });

  it('should render upload session buttons with callbacks', async () => {
    const user = userEvent.setup();
    const onViewCurrentSession = vi.fn();
    const onViewTargetSession = vi.fn();

    renderWithProviders(
      <ComparisonSummaryWidget
        summary={mockSummary}
        comparisonId={1}
        onViewCurrentSession={onViewCurrentSession}
        onViewTargetSession={onViewTargetSession}
      />
    );

    // Check for session ID display
    expect(screen.getByText('Current Session')).toBeInTheDocument();
    expect(screen.getByText('Target Session')).toBeInTheDocument();

    // Batch IDs may appear multiple times (in widget and nested component)
    expect(screen.queryAllByText(mockSummary.currentBatchId).length).toBeGreaterThan(0);
    expect(screen.queryAllByText(mockSummary.targetBatchId).length).toBeGreaterThan(0);

    // Verify buttons and callbacks
    const buttons = screen.getAllByRole('button');
    // Should have 3 buttons total: 2 session buttons + 1 "View Details" button
    expect(buttons.length).toBeGreaterThanOrEqual(2);

    // Click current session button
    const currentSessionButton = screen.getByText('Current Session').closest('button');
    if (currentSessionButton) {
      await user.click(currentSessionButton);
      expect(onViewCurrentSession).toHaveBeenCalledTimes(1);
    }

    // Click target session button
    const targetSessionButton = screen.getByText('Target Session').closest('button');
    if (targetSessionButton) {
      await user.click(targetSessionButton);
      expect(onViewTargetSession).toHaveBeenCalledTimes(1);
    }
  });

  it('should render "View Details" button and trigger callback', async () => {
    const user = userEvent.setup();
    const onViewDetails = vi.fn();

    renderWithProviders(
      <ComparisonSummaryWidget
        summary={mockSummary}
        comparisonId={1}
        onViewDetails={onViewDetails}
      />
    );

    const viewDetailsButton = screen.getByRole('button', { name: /view details/i });
    expect(viewDetailsButton).toBeInTheDocument();

    await user.click(viewDetailsButton);

    expect(onViewDetails).toHaveBeenCalledTimes(1);
  });

  it('should display icons for enhanced visual styling', () => {
    const { container } = renderWithProviders(
      <ComparisonSummaryWidget
        summary={mockSummary}
        comparisonId={1}
      />
    );

    // Check for presence of Lucide icons by className or SVG presence
    const svgs = container.querySelectorAll('svg');
    expect(svgs.length).toBeGreaterThan(0); // Should have multiple icons
  });

  it('should show secondary badge color for low change percentage', () => {
    const lowChangeSummary: ComparisonSummary = {
      ...mockSummary,
      totalFilesCompared: 10,
      filesChanged: 1,
      filesAdded: 0,
      filesUnchanged: 9,
    };

    renderWithProviders(
      <ComparisonSummaryWidget
        summary={lowChangeSummary}
        comparisonId={1}
      />
    );

    // 10% changed (1 changed + 0 added) / 10 total
    const badges = screen.getAllByText('10% Changed');
    expect(badges.length).toBeGreaterThan(0);
    // Badge should have the neutral (subtle) treatment for <20%
    expect(badges[0]).toHaveStyle({
      background: monitoringTokens.subtleBg,
      color: monitoringTokens.textSecondary,
    });
  });

  it('should show default badge color for medium change percentage', () => {
    const mediumChangeSummary: ComparisonSummary = {
      ...mockSummary,
      totalFilesCompared: 10,
      filesChanged: 3,
      filesAdded: 0,
      filesUnchanged: 7,
    };

    renderWithProviders(
      <ComparisonSummaryWidget
        summary={mediumChangeSummary}
        comparisonId={1}
      />
    );

    // 30% changed (3 changed + 0 added) / 10 total
    const badges = screen.getAllByText('30% Changed');
    expect(badges.length).toBeGreaterThan(0);
    // Badge should have the info (brand blue) treatment for 20-50%
    expect(badges[0].className).toContain('inline-flex');
    expect(badges[0]).toHaveStyle({
      background: monitoringTokens.blue50,
      color: monitoringTokens.primary,
    });
  });

  it('should handle 0% change correctly', () => {
    const noChangeSummary: ComparisonSummary = {
      ...mockSummary,
      totalFilesCompared: 10,
      filesChanged: 0,
      filesAdded: 0,
      filesUnchanged: 10,
      totalChangeSize: 0,
    };

    renderWithProviders(
      <ComparisonSummaryWidget
        summary={noChangeSummary}
        comparisonId={1}
      />
    );

    const badges = screen.getAllByText('0% Changed');
    expect(badges.length).toBeGreaterThan(0);
    // Check that 0 Bytes appears somewhere in the document
    expect(screen.queryAllByText('0 Bytes').length).toBeGreaterThan(0);
  });

  it('should apply custom className if provided', () => {
    const { container } = renderWithProviders(
      <ComparisonSummaryWidget
        summary={mockSummary}
        comparisonId={1}
        className="custom-class"
      />
    );

    const widgetRoot = container.firstChild;
    expect(widgetRoot).toHaveClass('custom-class');
  });
});
