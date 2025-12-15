/**
 * T095: Component tests for ExcelButton
 *
 * Tests Excel export button component with disabled state verification,
 * toast notifications, loading states, and error handling.
 *
 * Feature: 008-upload-history-user (User Story 4)
 */

import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { ExcelButton } from './ExcelButton';
import * as useExcelExportHook from '../lib/useExcelExport';
import { toast } from 'sonner';

// Mock the useExcelExport hook
vi.mock('../lib/useExcelExport');

// Mock sonner toast
vi.mock('sonner', () => ({
  toast: {
    success: vi.fn(),
    error: vi.fn(),
  },
}));

describe('ExcelButton', () => {
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
    vi.spyOn(useExcelExportHook, 'useExcelExport').mockReturnValue(
      createMockMutation() as ReturnType<typeof useExcelExportHook.useExcelExport>
    );
  });

  const renderWithProviders = (ui: React.ReactElement) => {
    return render(
      <QueryClientProvider client={queryClient}>{ui}</QueryClientProvider>
    );
  };

  describe('Button Visibility and State', () => {
    it('should render excel export button', () => {
      renderWithProviders(
        <ExcelButton
          batchId="batch-123"
          batchStatus="COMPLETED"
          selectedFileIds={['file-001']}
        />
      );

      const button = screen.getByRole('button', { name: /create excel/i });
      expect(button).toBeDefined();
    });

    it('should be disabled when no files selected', () => {
      renderWithProviders(
        <ExcelButton
          batchId="batch-123"
          batchStatus="COMPLETED"
          selectedFileIds={[]}
        />
      );

      const button = screen.getByRole('button', { name: /create excel/i });
      expect(button).toBeDisabled();
    });

    it('should be disabled when batch is IN_PROGRESS', () => {
      renderWithProviders(
        <ExcelButton
          batchId="batch-123"
          batchStatus="IN_PROGRESS"
          selectedFileIds={['file-001']}
        />
      );

      const button = screen.getByRole('button', { name: /create excel/i });
      expect(button).toBeDisabled();
    });

    it('should be disabled when batch is FAILED', () => {
      renderWithProviders(
        <ExcelButton
          batchId="batch-123"
          batchStatus="FAILED"
          selectedFileIds={['file-001']}
        />
      );

      const button = screen.getByRole('button', { name: /create excel/i });
      expect(button).toBeDisabled();
    });

    it('should be enabled when batch is COMPLETED and files selected', () => {
      renderWithProviders(
        <ExcelButton
          batchId="batch-123"
          batchStatus="COMPLETED"
          selectedFileIds={['file-001']}
        />
      );

      const button = screen.getByRole('button', { name: /create excel/i });
      expect(button).not.toBeDisabled();
    });

    it('should be enabled when batch is COMPLETED_WITH_WARNINGS and files selected', () => {
      renderWithProviders(
        <ExcelButton
          batchId="batch-123"
          batchStatus="COMPLETED_WITH_WARNINGS"
          selectedFileIds={['file-001']}
        />
      );

      const button = screen.getByRole('button', { name: /create excel/i });
      expect(button).not.toBeDisabled();
    });

    it('should be disabled during export', () => {
      vi.spyOn(useExcelExportHook, 'useExcelExport').mockReturnValue(
        createMockMutation({ isPending: true }) as ReturnType<typeof useExcelExportHook.useExcelExport>
      );

      renderWithProviders(
        <ExcelButton
          batchId="batch-123"
          batchStatus="COMPLETED"
          selectedFileIds={['file-001']}
        />
      );

      const button = screen.getByRole('button', { name: /generating excel/i });
      expect(button).toBeDisabled();
    });
  });

  describe('Excel Export', () => {
    it('should trigger excel export when clicked', async () => {
      const user = userEvent.setup();

      renderWithProviders(
        <ExcelButton
          batchId="batch-123"
          batchStatus="COMPLETED"
          selectedFileIds={['file-001', 'file-002']}
        />
      );

      const button = screen.getByRole('button', { name: /create excel/i });
      await user.click(button);

      // Verify mutation called with correct params
      expect(mockMutate).toHaveBeenCalledWith(
        {
          batchId: 'batch-123',
          fileIds: ['file-001', 'file-002'],
          excelFilename: undefined, // Not provided as prop
        },
        expect.objectContaining({
          onSuccess: expect.any(Function),
          onError: expect.any(Function),
        })
      );
    });

    it('should use custom excelFilename if provided', async () => {
      const user = userEvent.setup();

      renderWithProviders(
        <ExcelButton
          batchId="batch-123"
          batchStatus="COMPLETED"
          selectedFileIds={['file-001', 'file-002']}
          excelFilename="custom-export.xlsx"
        />
      );

      const button = screen.getByRole('button');
      await user.click(button);

      expect(mockMutate).toHaveBeenCalledWith(
        {
          batchId: 'batch-123',
          fileIds: ['file-001', 'file-002'],
          excelFilename: 'custom-export.xlsx',
        },
        expect.objectContaining({
          onSuccess: expect.any(Function),
          onError: expect.any(Function),
        })
      );
    });

    it('should display loading state during export', () => {
      vi.spyOn(useExcelExportHook, 'useExcelExport').mockReturnValue(
        createMockMutation({ isPending: true }) as ReturnType<typeof useExcelExportHook.useExcelExport>
      );

      renderWithProviders(
        <ExcelButton
          batchId="batch-123"
          batchStatus="COMPLETED"
          selectedFileIds={['file-001']}
        />
      );

      // Button should show "Generating Excel..." with spinner
      expect(screen.getByRole('button', { name: /generating excel/i })).toBeDefined();
    });

    it('should display correct button text with file count', () => {
      renderWithProviders(
        <ExcelButton
          batchId="batch-123"
          batchStatus="COMPLETED"
          selectedFileIds={['file-001', 'file-002', 'file-003']}
        />
      );

      // Button should show "Create Excel (3)"
      const button = screen.getByRole('button', { name: /create excel \(3\)/i });
      expect(button).toBeDefined();
    });
  });

  describe('Toast Notifications', () => {
    it('should show success toast on successful export', async () => {
      const user = userEvent.setup();

      // Mock mutation to call onSuccess callback
      mockMutate.mockImplementation((_params, options) => {
        if (options && 'onSuccess' in options && options.onSuccess) {
          options.onSuccess({
            fileName: 'batch-export.xlsx',
            fileCount: 2,
          });
        }
      });

      renderWithProviders(
        <ExcelButton
          batchId="batch-123"
          batchStatus="COMPLETED"
          selectedFileIds={['file-001', 'file-002']}
        />
      );

      const button = screen.getByRole('button', { name: /create excel/i });
      await user.click(button);

      expect(toast.success).toHaveBeenCalledWith(
        'Excel export complete: batch-export.xlsx',
        {
          description: '2 CSV file(s) converted to Excel sheets',
        }
      );
    });

    it('should show success toast with single file count', async () => {
      const user = userEvent.setup();

      mockMutate.mockImplementation((_params, options) => {
        if (options && 'onSuccess' in options && options.onSuccess) {
          options.onSuccess({
            fileName: 'single-file.xlsx',
            fileCount: 1,
          });
        }
      });

      renderWithProviders(
        <ExcelButton
          batchId="batch-123"
          batchStatus="COMPLETED"
          selectedFileIds={['file-001']}
        />
      );

      const button = screen.getByRole('button');
      await user.click(button);

      expect(toast.success).toHaveBeenCalledWith(
        'Excel export complete: single-file.xlsx',
        {
          description: '1 CSV file(s) converted to Excel sheets',
        }
      );
    });

    it('should show error toast on export failure', async () => {
      const user = userEvent.setup();
      const error = new Error('CSV parsing error: Invalid format');

      mockMutate.mockImplementation((_params, options) => {
        if (options && 'onError' in options && options.onError) {
          options.onError(error);
        }
      });

      renderWithProviders(
        <ExcelButton
          batchId="batch-123"
          batchStatus="COMPLETED"
          selectedFileIds={['file-001']}
        />
      );

      const button = screen.getByRole('button');
      await user.click(button);

      expect(toast.error).toHaveBeenCalledWith('Excel export failed', {
        description: 'CSV parsing error: Invalid format',
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
        <ExcelButton
          batchId="batch-123"
          batchStatus="COMPLETED"
          selectedFileIds={['file-001']}
        />
      );

      const button = screen.getByRole('button');
      await user.click(button);

      expect(toast.error).toHaveBeenCalledWith('Excel export failed', {
        description: 'Failed to generate Excel file. Please try again.',
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
          <ExcelButton
            batchId="batch-123"
            batchStatus={status}
            selectedFileIds={['file-001']}
          />
        );

        const button = screen.getByRole('button', { name: /create excel/i });

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
        <ExcelButton
          batchId="batch-123"
          batchStatus="COMPLETED"
          selectedFileIds={['file-001']}
        />
      );

      const button = screen.getByRole('button', { name: /create excel \(1\)/i });
      expect(button).toBeDefined();
    });

    it('should display multiple file count in button text', () => {
      renderWithProviders(
        <ExcelButton
          batchId="batch-123"
          batchStatus="COMPLETED"
          selectedFileIds={['file-001', 'file-002', 'file-003']}
        />
      );

      const button = screen.getByRole('button', { name: /create excel \(3\)/i });
      expect(button).toBeDefined();
    });

    it('should not display count when no files selected', () => {
      renderWithProviders(
        <ExcelButton
          batchId="batch-123"
          batchStatus="COMPLETED"
          selectedFileIds={[]}
        />
      );

      const button = screen.getByRole('button', { name: /^create excel$/i });
      expect(button).toBeDisabled();
    });
  });

  describe('Custom Children Rendering', () => {
    it('should render custom children when provided', () => {
      renderWithProviders(
        <ExcelButton
          batchId="batch-123"
          batchStatus="COMPLETED"
          selectedFileIds={['file-001']}
        >
          Export to Excel
        </ExcelButton>
      );

      const button = screen.getByRole('button', { name: /export to excel/i });
      expect(button).toBeDefined();
    });
  });

  describe('Error State Recovery', () => {
    it('should allow retry after error', async () => {
      const user = userEvent.setup();

      // Start with error state
      vi.spyOn(useExcelExportHook, 'useExcelExport').mockReturnValue(
        createMockMutation({ isError: true, error: new Error('Export failed') }) as ReturnType<typeof useExcelExportHook.useExcelExport>
      );

      renderWithProviders(
        <ExcelButton
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

  describe('Multiple CSV Files Export', () => {
    it('should handle exporting many CSV files', async () => {
      const user = userEvent.setup();

      const manyFileIds = Array.from({ length: 20 }, (_, i) => `file-${i + 1}`);

      renderWithProviders(
        <ExcelButton
          batchId="batch-123"
          batchStatus="COMPLETED"
          selectedFileIds={manyFileIds}
        />
      );

      const button = screen.getByRole('button', { name: /create excel \(20\)/i });
      await user.click(button);

      expect(mockMutate).toHaveBeenCalledWith(
        {
          batchId: 'batch-123',
          fileIds: manyFileIds,
          excelFilename: undefined,
        },
        expect.objectContaining({
          onSuccess: expect.any(Function),
          onError: expect.any(Function),
        })
      );
    });
  });
});
