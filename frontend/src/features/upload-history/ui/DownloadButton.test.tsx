/**
 * T072: Component tests for DownloadButton
 *
 * Tests download button component with disabled state verification,
 * single/multiple file download logic, toast notifications, and error handling.
 *
 * Feature: 008-upload-history-user (User Story 3)
 */

import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { DownloadButton } from './DownloadButton';
import * as useFileDownloadHook from '../lib/useFileDownload';
import { toast } from 'sonner';

// Mock the useFileDownload hook
vi.mock('../lib/useFileDownload');

// Mock sonner toast
vi.mock('sonner', () => ({
  toast: {
    success: vi.fn(),
    error: vi.fn(),
  },
}));

describe('DownloadButton', () => {
  let queryClient: QueryClient;

  // Mock mutation function
  const mockMutate = vi.fn();
  const mockReset = vi.fn();

  const createMockMutation = (overrides = {}) => ({
    mutate: mockMutate,
    reset: mockReset,
    isPending: false,
    isSuccess: false,
    isError: false,
    error: null,
    data: undefined,
    ...overrides,
  });

  beforeEach(() => {
    queryClient = new QueryClient();
    vi.clearAllMocks();

    // Default mock implementation
    vi.spyOn(useFileDownloadHook, 'useFileDownload').mockReturnValue(
      createMockMutation() as ReturnType<typeof useFileDownloadHook.useFileDownload>
    );
  });

  const renderWithProviders = (ui: React.ReactElement) => {
    return render(
      <QueryClientProvider client={queryClient}>{ui}</QueryClientProvider>
    );
  };

  describe('Button Visibility and State', () => {
    it('should render download button', () => {
      renderWithProviders(
        <DownloadButton
          batchId="batch-123"
          batchStatus="COMPLETED"
          selectedFileIds={['file-001']}
        />
      );

      const button = screen.getByRole('button', { name: /download/i });
      expect(button).toBeDefined();
    });

    it('should be disabled when no files selected', () => {
      renderWithProviders(
        <DownloadButton
          batchId="batch-123"
          batchStatus="COMPLETED"
          selectedFileIds={[]}
        />
      );

      const button = screen.getByRole('button', { name: /download/i });
      expect(button).toBeDisabled();
    });

    it('should be disabled when batch is IN_PROGRESS', () => {
      renderWithProviders(
        <DownloadButton
          batchId="batch-123"
          batchStatus="IN_PROGRESS"
          selectedFileIds={['file-001']}
        />
      );

      const button = screen.getByRole('button', { name: /download/i });
      expect(button).toBeDisabled();
    });

    it('should be disabled when batch is FAILED', () => {
      renderWithProviders(
        <DownloadButton
          batchId="batch-123"
          batchStatus="FAILED"
          selectedFileIds={['file-001']}
        />
      );

      const button = screen.getByRole('button', { name: /download/i });
      expect(button).toBeDisabled();
    });

    it('should be enabled when batch is COMPLETED and files selected', () => {
      renderWithProviders(
        <DownloadButton
          batchId="batch-123"
          batchStatus="COMPLETED"
          selectedFileIds={['file-001']}
        />
      );

      const button = screen.getByRole('button', { name: /download/i });
      expect(button).not.toBeDisabled();
    });

    it('should be disabled during download', () => {
      vi.spyOn(useFileDownloadHook, 'useFileDownload').mockReturnValue(
        createMockMutation({ isPending: true }) as ReturnType<typeof useFileDownloadHook.useFileDownload>
      );

      renderWithProviders(
        <DownloadButton
          batchId="batch-123"
          batchStatus="COMPLETED"
          selectedFileIds={['file-001']}
        />
      );

      const button = screen.getByRole('button', { name: /downloading/i });
      expect(button).toBeDisabled();
    });
  });

  describe('Single File Download (Presigned URL)', () => {
    it('should trigger single file download when one file selected', async () => {
      const user = userEvent.setup();

      renderWithProviders(
        <DownloadButton
          batchId="batch-123"
          batchStatus="COMPLETED"
          selectedFileIds={['file-001']}
        />
      );

      const button = screen.getByRole('button', { name: /download/i });
      await user.click(button);

      // Verify mutation called with correct params (single file = fileIds array with 1 element)
      expect(mockMutate).toHaveBeenCalledWith(
        {
          batchId: 'batch-123',
          fileIds: ['file-001'],
          zipFilename: undefined, // Not provided as prop, so undefined
        },
        expect.objectContaining({
          onSuccess: expect.any(Function),
          onError: expect.any(Function),
        })
      );
    });

    it('should display loading state during download', () => {
      vi.spyOn(useFileDownloadHook, 'useFileDownload').mockReturnValue(
        createMockMutation({ isPending: true }) as ReturnType<typeof useFileDownloadHook.useFileDownload>
      );

      renderWithProviders(
        <DownloadButton
          batchId="batch-123"
          batchStatus="COMPLETED"
          selectedFileIds={['file-001']}
        />
      );

      // Button should show "Downloading..." with spinner
      expect(screen.getByRole('button', { name: /downloading/i })).toBeDefined();
    });
  });

  describe('Multiple File Download (ZIP)', () => {
    it('should trigger ZIP download when multiple files selected', async () => {
      const user = userEvent.setup();

      renderWithProviders(
        <DownloadButton
          batchId="batch-123"
          batchStatus="COMPLETED"
          selectedFileIds={['file-001', 'file-002', 'file-003']}
        />
      );

      const button = screen.getByRole('button', { name: /download/i });
      await user.click(button);

      expect(mockMutate).toHaveBeenCalledWith(
        {
          batchId: 'batch-123',
          fileIds: ['file-001', 'file-002', 'file-003'],
          zipFilename: undefined, // Default when not provided
        },
        expect.objectContaining({
          onSuccess: expect.any(Function),
          onError: expect.any(Function),
        })
      );
    });

    it('should use custom zipFilename if provided', async () => {
      const user = userEvent.setup();

      renderWithProviders(
        <DownloadButton
          batchId="batch-123"
          batchStatus="COMPLETED"
          selectedFileIds={['file-001', 'file-002']}
          zipFilename="custom-archive.zip"
        />
      );

      const button = screen.getByRole('button');
      await user.click(button);

      expect(mockMutate).toHaveBeenCalledWith(
        {
          batchId: 'batch-123',
          fileIds: ['file-001', 'file-002'],
          zipFilename: 'custom-archive.zip',
        },
        expect.objectContaining({
          onSuccess: expect.any(Function),
          onError: expect.any(Function),
        })
      );
    });

    it('should display correct button text for multiple files', () => {
      renderWithProviders(
        <DownloadButton
          batchId="batch-123"
          batchStatus="COMPLETED"
          selectedFileIds={['file-001', 'file-002', 'file-003']}
        />
      );

      // Button should show "Download (3)"
      const button = screen.getByRole('button', { name: /download \(3\)/i });
      expect(button).toBeDefined();
    });
  });

  describe('Toast Notifications', () => {
    it('should show success toast on successful download', async () => {
      const user = userEvent.setup();

      // Mock mutation to call onSuccess callback
      mockMutate.mockImplementation((_params, options) => {
        if (options && 'onSuccess' in options && options.onSuccess) {
          options.onSuccess({
            fileName: 'sales-2024.csv.gz',
            type: 'single',
          });
        }
      });

      renderWithProviders(
        <DownloadButton
          batchId="batch-123"
          batchStatus="COMPLETED"
          selectedFileIds={['file-001']}
        />
      );

      const button = screen.getByRole('button', { name: /download/i });
      await user.click(button);

      expect(toast.success).toHaveBeenCalledWith(
        'Download started: sales-2024.csv.gz',
        {
          description: '1 file(s) - Direct download',
        }
      );
    });

    it('should show success toast for ZIP download', async () => {
      const user = userEvent.setup();

      mockMutate.mockImplementation((_params, options) => {
        if (options && 'onSuccess' in options && options.onSuccess) {
          options.onSuccess({
            fileName: 'batch-123.zip',
            type: 'zip',
          });
        }
      });

      renderWithProviders(
        <DownloadButton
          batchId="batch-123"
          batchStatus="COMPLETED"
          selectedFileIds={['file-001', 'file-002', 'file-003']}
        />
      );

      const button = screen.getByRole('button');
      await user.click(button);

      expect(toast.success).toHaveBeenCalledWith(
        'Download started: batch-123.zip',
        {
          description: '3 file(s) - ZIP archive',
        }
      );
    });

    it('should show error toast on download failure', async () => {
      const user = userEvent.setup();
      const error = new Error('Network error: Failed to download file');

      mockMutate.mockImplementation((_params, options) => {
        if (options && 'onError' in options && options.onError) {
          options.onError(error);
        }
      });

      renderWithProviders(
        <DownloadButton
          batchId="batch-123"
          batchStatus="COMPLETED"
          selectedFileIds={['file-001']}
        />
      );

      const button = screen.getByRole('button');
      await user.click(button);

      expect(toast.error).toHaveBeenCalledWith('Download failed', {
        description: 'Network error: Failed to download file',
      });
    });

    it('should show generic error message for unknown errors', async () => {
      const user = userEvent.setup();
      const error = 'String error'; // Non-Error object

      mockMutate.mockImplementation((_params, options) => {
        if (options && 'onError' in options && options.onError) {
          options.onError(error);
        }
      });

      renderWithProviders(
        <DownloadButton
          batchId="batch-123"
          batchStatus="COMPLETED"
          selectedFileIds={['file-001']}
        />
      );

      const button = screen.getByRole('button');
      await user.click(button);

      expect(toast.error).toHaveBeenCalledWith('Download failed', {
        description: 'Failed to download files. Please try again.',
      });
    });
  });

  describe('Batch Status Validation', () => {
    const testCases = [
      { status: 'COMPLETED', shouldBeEnabled: true },
      { status: 'IN_PROGRESS', shouldBeEnabled: false },
      { status: 'FAILED', shouldBeEnabled: false },
    ];

    testCases.forEach(({ status, shouldBeEnabled }) => {
      it(`should ${shouldBeEnabled ? 'enable' : 'disable'} button when batch status is ${status}`, () => {
        renderWithProviders(
          <DownloadButton
            batchId="batch-123"
            batchStatus={status}
            selectedFileIds={['file-001']}
          />
        );

        const button = screen.getByRole('button', { name: /download/i });

        if (shouldBeEnabled) {
          expect(button).not.toBeDisabled();
        } else {
          expect(button).toBeDisabled();
        }
      });
    });
  });

  describe('Selection Count Display', () => {
    it('should display single file count in button text', () => {
      renderWithProviders(
        <DownloadButton
          batchId="batch-123"
          batchStatus="COMPLETED"
          selectedFileIds={['file-001']}
        />
      );

      const button = screen.getByRole('button', { name: /download \(1\)/i });
      expect(button).toBeDefined();
    });

    it('should display multiple file count in button text', () => {
      renderWithProviders(
        <DownloadButton
          batchId="batch-123"
          batchStatus="COMPLETED"
          selectedFileIds={['file-001', 'file-002', 'file-003']}
        />
      );

      const button = screen.getByRole('button', { name: /download \(3\)/i });
      expect(button).toBeDefined();
    });

    it('should not display count when no files selected', () => {
      renderWithProviders(
        <DownloadButton
          batchId="batch-123"
          batchStatus="COMPLETED"
          selectedFileIds={[]}
        />
      );

      const button = screen.getByRole('button', { name: /^download$/i });
      expect(button).toBeDisabled();
    });
  });

  describe('Custom Children Rendering', () => {
    it('should render custom children when provided', () => {
      renderWithProviders(
        <DownloadButton
          batchId="batch-123"
          batchStatus="COMPLETED"
          selectedFileIds={['file-001']}
        >
          Custom Download Text
        </DownloadButton>
      );

      const button = screen.getByRole('button', { name: /custom download text/i });
      expect(button).toBeDefined();
    });
  });

  describe('Error State Recovery', () => {
    it('should allow retry after error', async () => {
      const user = userEvent.setup();

      // Start with error state
      vi.spyOn(useFileDownloadHook, 'useFileDownload').mockReturnValue(
        createMockMutation({ isError: true, error: new Error('Download failed') }) as ReturnType<typeof useFileDownloadHook.useFileDownload>
      );

      renderWithProviders(
        <DownloadButton
          batchId="batch-123"
          batchStatus="COMPLETED"
          selectedFileIds={['file-001']}
        />
      );

      const button = screen.getByRole('button');
      expect(button).not.toBeDisabled(); // Should still be clickable for retry

      // Click to retry
      await user.click(button);

      expect(mockMutate).toHaveBeenCalled();
    });
  });
});
