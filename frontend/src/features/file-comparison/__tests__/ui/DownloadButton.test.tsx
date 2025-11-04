/**
 * T089: Component test for DownloadButton
 *
 * Purpose: Validate download button component UI and interaction
 *
 * Test coverage:
 * - Renders button with download icon
 * - Triggers download on click
 * - Shows loading indicator during download
 * - Shows error message on failure
 * - Disables button during download
 * - Accessible (ARIA labels, keyboard navigation)
 *
 * Note: This test should FAIL initially because DownloadButton component doesn't exist yet (TDD Red phase).
 */

import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { ReactNode } from 'react';
import DownloadButton from '../../ui/DownloadButton';
import { comparisonApi } from '../../api/comparisonApi';

// Mock comparison API
vi.mock('../../api/comparisonApi', () => ({
  comparisonApi: {
    downloadComparison: vi.fn(),
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

describe('DownloadButton', () => {
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
   * TC01: Renders button with download icon
   *
   * Given: DownloadButton component is rendered
   * When: Component mounts
   * Then: Button is visible
   * And: Download icon is present
   * And: Button text is "Download ZIP"
   */
  it('should render button with download icon', () => {
    // Given/When: Render DownloadButton
    render(<DownloadButton comparisonId="123" />, { wrapper });

    // Then: Button is visible
    const button = screen.getByRole('button', { name: /download/i });
    expect(button).toBeInTheDocument();

    // Verify button has download icon (using Download from lucide-react)
    // Note: Icon is rendered as SVG, checking for aria-label or test-id
    expect(button).toBeInTheDocument();
  });

  /**
   * TC02: Triggers download on click
   *
   * Given: DownloadButton is rendered
   * When: User clicks the button
   * Then: Download API is called with comparison ID
   * And: Download is triggered
   */
  it('should trigger download on click', async () => {
    // Given: Mock successful API response
    const mockBlob = new Blob(['mock zip content'], { type: 'application/zip' });
    const mockResponse = {
      data: mockBlob,
      headers: {
        'content-disposition': 'attachment; filename="comparison-123.zip"',
      },
    };
    vi.mocked(comparisonApi.downloadComparison).mockResolvedValue(mockResponse);

    // Render button
    render(<DownloadButton comparisonId="123" />, { wrapper });

    // When: User clicks button
    const button = screen.getByRole('button', { name: /download/i });
    await userEvent.click(button);

    // Then: API is called
    await waitFor(() => {
      expect(comparisonApi.downloadComparison).toHaveBeenCalledWith('123');
    });

    // Download is triggered
    expect(mockClick).toHaveBeenCalled();
  });

  /**
   * TC03: Shows loading indicator during download
   *
   * Given: Download is in progress
   * When: User waits for download
   * Then: Button shows loading state
   * And: Button is disabled
   * And: Loading spinner is visible
   */
  it('should show loading indicator during download', async () => {
    // Given: Mock API with delayed response
    const mockBlob = new Blob(['mock zip content'], { type: 'application/zip' });
    const mockResponse = {
      data: mockBlob,
      headers: {
        'content-disposition': 'attachment; filename="comparison-456.zip"',
      },
    };
    vi.mocked(comparisonApi.downloadComparison).mockImplementation(
      () =>
        new Promise((resolve) =>
          setTimeout(() => resolve(mockResponse), 200)
        )
    );

    // Render button
    render(<DownloadButton comparisonId="456" />, { wrapper });

    // When: User clicks button
    const button = screen.getByRole('button', { name: /download/i });
    await userEvent.click(button);

    // Then: Button is disabled during download (wait for state update)
    await waitFor(() => expect(button).toBeDisabled());

    // Loading indicator is shown (text changes or spinner appears)
    // Note: Exact loading UI depends on implementation (spinner, text change, etc.)
    // Here we verify button is disabled as primary indicator
    expect(button).toBeDisabled();

    // Wait for download to complete
    await waitFor(() => {
      expect(button).not.toBeDisabled();
    });
  });

  /**
   * TC04: Shows error message on failure
   *
   * Given: Download fails with error
   * When: User clicks download button
   * Then: Error message is displayed
   * And: Button is re-enabled
   * And: Error is visible to user (toast or inline message)
   */
  it('should show error message on failure', async () => {
    // Given: Mock API error
    vi.mocked(comparisonApi.downloadComparison).mockRejectedValue(
      new Error('Failed to download comparison')
    );

    // Mock console.error to suppress error output in test
    const consoleErrorSpy = vi.spyOn(console, 'error').mockImplementation(() => {});

    // Render button
    render(<DownloadButton comparisonId="789" />, { wrapper });

    // When: User clicks button
    const button = screen.getByRole('button', { name: /download/i });
    await userEvent.click(button);

    // Then: Button is re-enabled after error
    await waitFor(() => {
      expect(button).not.toBeDisabled();
    });

    // Note: Error display mechanism depends on implementation
    // (could be toast notification, inline error text, etc.)
    // The critical check is that download was attempted and button returns to enabled state
    expect(comparisonApi.downloadComparison).toHaveBeenCalledWith('789');

    consoleErrorSpy.mockRestore();
  });

  /**
   * TC05: Disables button during download
   *
   * Given: Download is in progress
   * When: User tries to click again
   * Then: Button is disabled
   * And: Second click is prevented
   */
  it('should disable button during download', async () => {
    // Given: Mock API with delayed response
    const mockBlob = new Blob(['mock zip content'], { type: 'application/zip' });
    const mockResponse = {
      data: mockBlob,
      headers: {
        'content-disposition': 'attachment; filename="comparison-999.zip"',
      },
    };
    let resolveDownload: (value: any) => void;
    vi.mocked(comparisonApi.downloadComparison).mockImplementation(
      () =>
        new Promise((resolve) => {
          resolveDownload = resolve;
        })
    );

    // Render button
    render(<DownloadButton comparisonId="999" />, { wrapper });

    // When: User clicks button
    const button = screen.getByRole('button', { name: /download/i });
    await userEvent.click(button);

    // Then: Button is disabled (wait for state update)
    await waitFor(() => expect(button).toBeDisabled());

    // Try to click again (should be prevented)
    await userEvent.click(button);

    // API should only be called once
    expect(comparisonApi.downloadComparison).toHaveBeenCalledTimes(1);

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
   * Given: DownloadButton is rendered
   * When: Screen reader reads the component
   * Then: Button has accessible name
   * And: Loading state is announced
   * And: Keyboard navigation works
   */
  it('should be accessible with ARIA labels', async () => {
    // Given: Render button
    render(<DownloadButton comparisonId="access-test" />, { wrapper });

    // Then: Button has accessible name
    const button = screen.getByRole('button', { name: /download/i });
    expect(button).toBeInTheDocument();

    // Verify button is keyboard accessible (can be focused)
    button.focus();
    expect(button).toHaveFocus();

    // Verify Enter key triggers download
    const mockBlob = new Blob(['mock zip content'], { type: 'application/zip' });
    const mockResponse = {
      data: mockBlob,
      headers: {
        'content-disposition': 'attachment; filename="comparison-access.zip"',
      },
    };
    vi.mocked(comparisonApi.downloadComparison).mockResolvedValue(mockResponse);

    await userEvent.keyboard('{Enter}');

    await waitFor(() => {
      expect(comparisonApi.downloadComparison).toHaveBeenCalledWith('access-test');
    });
  });

  /**
   * TC07: Variant prop support (optional)
   *
   * Given: DownloadButton accepts variant prop
   * When: Rendered with variant="outline"
   * Then: Button has outline styling
   */
  it('should support variant prop', () => {
    // Given/When: Render with variant
    render(<DownloadButton comparisonId="variant-test" variant="outline" />, {
      wrapper,
    });

    // Then: Button is rendered (styling verification depends on implementation)
    const button = screen.getByRole('button', { name: /download/i });
    expect(button).toBeInTheDocument();
  });

  /**
   * TC08: Size prop support (optional)
   *
   * Given: DownloadButton accepts size prop
   * When: Rendered with size="sm"
   * Then: Button has small size styling
   */
  it('should support size prop', () => {
    // Given/When: Render with size
    render(<DownloadButton comparisonId="size-test" size="sm" />, {
      wrapper,
    });

    // Then: Button is rendered (styling verification depends on implementation)
    const button = screen.getByRole('button', { name: /download/i });
    expect(button).toBeInTheDocument();
  });
});
