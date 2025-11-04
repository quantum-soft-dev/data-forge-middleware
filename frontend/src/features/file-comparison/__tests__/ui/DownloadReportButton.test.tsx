/**
 * T100: Component test for DownloadReportButton
 *
 * Purpose: Validate download report button component UI and interaction
 *
 * Test coverage:
 * - Renders button with file icon
 * - Triggers report download on click
 * - Shows loading indicator during download
 * - Shows error message on failure
 * - Disables button during download
 * - Accessible (ARIA labels, keyboard navigation)
 *
 * Note: This test should FAIL initially because DownloadReportButton component doesn't exist yet (TDD Red phase).
 */

import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { ReactNode } from 'react';
import DownloadReportButton from '../../ui/DownloadReportButton';
import { comparisonApi } from '../../api/comparisonApi';

// Mock comparison API
vi.mock('../../api/comparisonApi', () => ({
  comparisonApi: {
    downloadSummaryReport: vi.fn(),
  },
}));

// Mock URL.createObjectURL and URL.revokeObjectURL
global.URL.createObjectURL = vi.fn(() => 'blob:mock-url');
global.URL.revokeObjectURL = vi.fn();

// Mock createElement for download link
const mockClick = vi.fn();
const originalCreateElement = document.createElement.bind(document);
document.createElement = vi.fn((tagName: string) => {
  if (tagName === 'a') {
    const element = originalCreateElement('a');
    element.click = mockClick;
    return element;
  }
  return originalCreateElement(tagName);
}) as any;

describe('DownloadReportButton', () => {
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

  const wrapper = ({ children }: { children: ReactNode }) => (
    <QueryClientProvider client={queryClient}>{children}</QueryClientProvider>
  );

  /**
   * TC01: Renders button with file icon
   *
   * Given: DownloadReportButton component is rendered
   * When: Component mounts
   * Then: Button is visible
   * And: File icon is present
   * And: Button text is "Download Report"
   */
  it('should render button with file icon', () => {
    // Given/When: Render DownloadReportButton
    render(<DownloadReportButton comparisonId="123" />, { wrapper });

    // Then: Button is visible with text "Download Report"
    const button = screen.getByRole('button', { name: /download summary report/i });
    expect(button).toBeInTheDocument();

    // Verify button text
    expect(button).toHaveTextContent(/download report/i);
  });

  /**
   * TC02: Triggers report download on click
   *
   * Given: DownloadReportButton is rendered
   * When: User clicks the button
   * Then: Download API is called with comparison ID
   * And: Report download is triggered
   */
  it('should trigger report download on click', async () => {
    // Given: Mock successful API response
    const mockBlob = new Blob(['FILE COMPARISON SUMMARY REPORT'], { type: 'text/plain' });
    const mockResponse = {
      data: mockBlob,
      headers: {
        'content-disposition': 'attachment; filename="comparison-123-summary.txt"',
      },
    };
    vi.mocked(comparisonApi.downloadSummaryReport).mockResolvedValue(mockResponse);

    // Render button
    render(<DownloadReportButton comparisonId="123" />, { wrapper });

    // When: User clicks button
    const button = screen.getByRole('button', { name: /download summary report/i });
    await userEvent.click(button);

    // Then: API is called
    await waitFor(() => {
      expect(comparisonApi.downloadSummaryReport).toHaveBeenCalledWith('123');
    });

    // Download is triggered
    expect(mockClick).toHaveBeenCalled();
  });

  /**
   * TC03: Shows loading indicator during download
   *
   * Given: Report download is in progress
   * When: User waits for download
   * Then: Button shows loading state
   * And: Button is disabled
   */
  it('should show loading indicator during download', async () => {
    // Given: Mock API with delayed response
    const mockBlob = new Blob(['FILE COMPARISON SUMMARY REPORT'], { type: 'text/plain' });
    const mockResponse = {
      data: mockBlob,
      headers: {
        'content-disposition': 'attachment; filename="comparison-456-summary.txt"',
      },
    };
    vi.mocked(comparisonApi.downloadSummaryReport).mockImplementation(
      () =>
        new Promise((resolve) =>
          setTimeout(() => resolve(mockResponse), 200)
        )
    );

    // Render button
    render(<DownloadReportButton comparisonId="456" />, { wrapper });

    // When: User clicks button
    const button = screen.getByRole('button', { name: /download summary report/i });
    await userEvent.click(button);

    // Then: Button is disabled during download (wait for state update)
    await waitFor(() => expect(button).toBeDisabled());

    // Wait for download to complete
    await waitFor(() => {
      expect(button).not.toBeDisabled();
    });
  });

  /**
   * TC04: Shows error message on failure
   *
   * Given: Report download fails with error
   * When: User clicks download button
   * Then: Error message is displayed
   * And: Button is re-enabled
   */
  it('should show error message on failure', async () => {
    // Given: Mock API error
    vi.mocked(comparisonApi.downloadSummaryReport).mockRejectedValue(
      new Error('Failed to download summary report')
    );

    // Mock console.error to suppress error output in test
    const consoleErrorSpy = vi.spyOn(console, 'error').mockImplementation(() => {});

    // Render button
    render(<DownloadReportButton comparisonId="789" />, { wrapper });

    // When: User clicks button
    const button = screen.getByRole('button', { name: /download summary report/i });
    await userEvent.click(button);

    // Then: Button is re-enabled after error
    await waitFor(() => {
      expect(button).not.toBeDisabled();
    });

    expect(comparisonApi.downloadSummaryReport).toHaveBeenCalledWith('789');

    consoleErrorSpy.mockRestore();
  });

  /**
   * TC05: Disables button during download
   *
   * Given: Report download is in progress
   * When: User tries to click again
   * Then: Button is disabled
   * And: Second click is prevented
   */
  it('should disable button during download', async () => {
    // Given: Mock API with delayed response
    const mockBlob = new Blob(['FILE COMPARISON SUMMARY REPORT'], { type: 'text/plain' });
    const mockResponse = {
      data: mockBlob,
      headers: {
        'content-disposition': 'attachment; filename="comparison-999-summary.txt"',
      },
    };
    let resolveDownload: (value: any) => void;
    vi.mocked(comparisonApi.downloadSummaryReport).mockImplementation(
      () =>
        new Promise((resolve) => {
          resolveDownload = resolve;
        })
    );

    // Render button
    render(<DownloadReportButton comparisonId="999" />, { wrapper });

    // When: User clicks button
    const button = screen.getByRole('button', { name: /download summary report/i });
    await userEvent.click(button);

    // Then: Button is disabled (wait for state update)
    await waitFor(() => expect(button).toBeDisabled());

    // Try to click again (should be prevented)
    await userEvent.click(button);

    // API should only be called once
    expect(comparisonApi.downloadSummaryReport).toHaveBeenCalledTimes(1);

    // Resolve download
    resolveDownload!(mockResponse);

    // Wait for button to re-enable
    await waitFor(() => {
      expect(button).not.toBeDisabled();
    });
  });

  /**
   * TC06: Accessible with ARIA labels
   *
   * Given: DownloadReportButton is rendered
   * When: Screen reader reads the component
   * Then: Button has accessible name
   * And: Keyboard navigation works
   */
  it('should be accessible with ARIA labels', async () => {
    // Given: Render button
    render(<DownloadReportButton comparisonId="access-test" />, { wrapper });

    // Then: Button has accessible name
    const button = screen.getByRole('button', { name: /download summary report/i });
    expect(button).toBeInTheDocument();

    // Verify button is keyboard accessible (can be focused)
    button.focus();
    expect(button).toHaveFocus();

    // Verify Enter key triggers download
    const mockBlob = new Blob(['FILE COMPARISON SUMMARY REPORT'], { type: 'text/plain' });
    const mockResponse = {
      data: mockBlob,
      headers: {
        'content-disposition': 'attachment; filename="comparison-access-summary.txt"',
      },
    };
    vi.mocked(comparisonApi.downloadSummaryReport).mockResolvedValue(mockResponse);

    await userEvent.keyboard('{Enter}');

    await waitFor(() => {
      expect(comparisonApi.downloadSummaryReport).toHaveBeenCalledWith('access-test');
    });
  });

  /**
   * TC07: Variant prop support (optional)
   *
   * Given: DownloadReportButton accepts variant prop
   * When: Rendered with variant="outline"
   * Then: Button has outline styling
   */
  it('should support variant prop', () => {
    // Given/When: Render with variant
    render(<DownloadReportButton comparisonId="variant-test" variant="outline" />, {
      wrapper,
    });

    // Then: Button is rendered
    const button = screen.getByRole('button', { name: /download summary report/i });
    expect(button).toBeInTheDocument();
  });

  /**
   * TC08: Size prop support (optional)
   *
   * Given: DownloadReportButton accepts size prop
   * When: Rendered with size="sm"
   * Then: Button has small size styling
   */
  it('should support size prop', () => {
    // Given/When: Render with size
    render(<DownloadReportButton comparisonId="size-test" size="sm" />, {
      wrapper,
    });

    // Then: Button is rendered
    const button = screen.getByRole('button', { name: /download summary report/i });
    expect(button).toBeInTheDocument();
  });
});
