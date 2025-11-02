/**
 * T094: Unit tests for useExcelExport mutation hook
 *
 * Tests Excel export functionality with blob handling, URL cleanup,
 * error handling, and retry logic.
 *
 * Feature: 008-upload-history-user (User Story 4)
 */

import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { renderHook, waitFor } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { createElement, type ReactNode } from 'react';
import { useExcelExport } from './useExcelExport';
import * as batchApi from '@/entities/batch/api/batchApi';

// Mock the batchApi module
vi.mock('@/entities/batch/api/batchApi');

describe('useExcelExport', () => {
  let queryClient: QueryClient;

  // Mock DOM APIs
  const mockCreateObjectURL = vi.fn();
  const mockRevokeObjectURL = vi.fn();
  const mockClick = vi.fn();
  const mockLink = {
    href: '',
    download: '',
    style: { display: '' },
    click: mockClick,
  };

  beforeEach(() => {
    queryClient = new QueryClient({
      defaultOptions: {
        queries: { retry: false },
        mutations: { retry: false },
      },
    });

    // Mock URL APIs
    mockCreateObjectURL.mockReturnValue('blob:mock-url-12345');
    globalThis.URL.createObjectURL = mockCreateObjectURL;
    globalThis.URL.revokeObjectURL = mockRevokeObjectURL;

    // Spy on DOM methods
    vi.spyOn(document, 'createElement').mockImplementation((tagName: string) => {
      if (tagName === 'a') {
        return mockLink as unknown as HTMLAnchorElement;
      }
      // For other elements, we need to call the real implementation
      // Create actual DOM elements for React to use
      const element = Object.create(HTMLElement.prototype);
      element.tagName = tagName.toUpperCase();
      return element as HTMLElement;
    });

    vi.spyOn(document.body, 'appendChild').mockImplementation(() => mockLink as unknown as Node);
    vi.spyOn(document.body, 'removeChild').mockImplementation(() => mockLink as unknown as Node);

    vi.clearAllMocks();
    vi.useFakeTimers();
  });

  afterEach(() => {
    vi.restoreAllMocks();
    vi.useRealTimers();
  });

  const createWrapper = () => {
    return ({ children }: { children: ReactNode }) =>
      createElement(QueryClientProvider, { client: queryClient }, children);
  };

  const renderUseExcelExport = () => {
    return renderHook(() => useExcelExport(), { wrapper: createWrapper() });
  };

  describe('Excel Export Success', () => {
    it('should export CSV files to Excel blob', async () => {
      const mockBlob = new Blob(['excel data'], {
        type: 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet',
      });

      vi.spyOn(batchApi, 'exportToExcel').mockResolvedValue(mockBlob);

      const { result } = renderUseExcelExport();

      result.current.mutate({
        batchId: 'batch-123',
        fileIds: ['file-001', 'file-002'],
        excelFilename: 'export.xlsx',
      });

      await waitFor(() => expect(result.current.isSuccess).toBe(true));

      // Verify API call
      expect(batchApi.exportToExcel).toHaveBeenCalledWith('batch-123', [
        'file-001',
        'file-002',
      ]);

      // Verify blob URL creation
      expect(mockCreateObjectURL).toHaveBeenCalledWith(mockBlob);

      // Verify download trigger
      expect(document.createElement).toHaveBeenCalledWith('a');
      expect(mockClick).toHaveBeenCalled();
    });

    it('should use default filename when not provided', async () => {
      const mockBlob = new Blob(['excel data']);
      vi.spyOn(batchApi, 'exportToExcel').mockResolvedValue(mockBlob);

      const { result } = renderUseExcelExport();

      result.current.mutate({
        batchId: 'batch-456',
        fileIds: ['file-001'],
      });

      await waitFor(() => expect(result.current.isSuccess).toBe(true));

      // Verify default filename format
      expect(result.current.data).toEqual({
        fileName: 'batch-batch-456.xlsx',
        fileCount: 1,
      });
    });

    it('should return export metadata on success', async () => {
      const mockBlob = new Blob(['excel data']);
      vi.spyOn(batchApi, 'exportToExcel').mockResolvedValue(mockBlob);

      const { result } = renderUseExcelExport();

      result.current.mutate({
        batchId: 'batch-123',
        fileIds: ['file-001', 'file-002', 'file-003'],
        excelFilename: 'custom-export.xlsx',
      });

      await waitFor(() => expect(result.current.isSuccess).toBe(true));

      expect(result.current.data).toEqual({
        fileName: 'custom-export.xlsx',
        fileCount: 3,
      });
    });
  });

  describe('Blob URL Cleanup (T100)', () => {
    it('should revoke object URL after download', async () => {
      const mockBlob = new Blob(['excel data']);
      vi.spyOn(batchApi, 'exportToExcel').mockResolvedValue(mockBlob);

      const { result } = renderUseExcelExport();

      result.current.mutate({
        batchId: 'batch-123',
        fileIds: ['file-001'],
      });

      await waitFor(() => expect(result.current.isSuccess).toBe(true));

      // Object URL should not be revoked immediately
      expect(mockRevokeObjectURL).not.toHaveBeenCalled();

      // Fast-forward 1 second (cleanup delay)
      vi.advanceTimersByTime(1000);

      // Now object URL should be revoked
      expect(mockRevokeObjectURL).toHaveBeenCalledWith('blob:mock-url-12345');
    });

    it('should cleanup DOM link element after download', async () => {
      const mockBlob = new Blob(['excel data']);
      vi.spyOn(batchApi, 'exportToExcel').mockResolvedValue(mockBlob);

      const { result } = renderUseExcelExport();

      result.current.mutate({
        batchId: 'batch-123',
        fileIds: ['file-001'],
      });

      await waitFor(() => expect(result.current.isSuccess).toBe(true));

      // Link should be added and removed
      expect(document.body.appendChild).toHaveBeenCalled();

      // Fast-forward 100ms (link removal delay)
      vi.advanceTimersByTime(100);

      expect(document.body.removeChild).toHaveBeenCalled();
    });

    it('should revoke URL even if download fails', async () => {
      const mockBlob = new Blob(['excel data']);
      vi.spyOn(batchApi, 'exportToExcel').mockResolvedValue(mockBlob);

      // Mock click to throw error
      mockClick.mockImplementation(() => {
        throw new Error('Download failed');
      });

      const { result } = renderUseExcelExport();

      result.current.mutate({
        batchId: 'batch-123',
        fileIds: ['file-001'],
      });

      await waitFor(() => expect(result.current.isError).toBe(true));

      // URL should still be revoked (finally block)
      vi.advanceTimersByTime(1000);
      expect(mockRevokeObjectURL).toHaveBeenCalledWith('blob:mock-url-12345');
    });
  });

  describe('Error Handling', () => {
    it('should handle API errors', async () => {
      const error = new Error('Excel generation failed');
      vi.spyOn(batchApi, 'exportToExcel').mockRejectedValue(error);

      const { result } = renderUseExcelExport();

      result.current.mutate({
        batchId: 'batch-123',
        fileIds: ['file-001'],
      });

      await waitFor(() => expect(result.current.isError).toBe(true));

      expect(result.current.error).toEqual(error);
    });

    it('should not create blob URL on API failure', async () => {
      vi.spyOn(batchApi, 'exportToExcel').mockRejectedValue(
        new Error('Network error')
      );

      const { result } = renderUseExcelExport();

      result.current.mutate({
        batchId: 'batch-123',
        fileIds: ['file-001'],
      });

      await waitFor(() => expect(result.current.isError).toBe(true));

      // Blob URL should not be created if API fails
      expect(mockCreateObjectURL).not.toHaveBeenCalled();
    });
  });

  describe('Retry Logic', () => {
    it('should retry failed exports up to 2 times', async () => {
      const exportSpy = vi.spyOn(batchApi, 'exportToExcel');

      // Fail first 2 attempts, succeed on 3rd
      exportSpy
        .mockRejectedValueOnce(new Error('Network timeout'))
        .mockRejectedValueOnce(new Error('Network timeout'))
        .mockResolvedValueOnce(new Blob(['excel data']));

      // Enable retry for this test
      queryClient = new QueryClient({
        defaultOptions: {
          mutations: {
            retry: 2,
            retryDelay: 0, // No delay for faster test
          },
        },
      });

      const { result } = renderHook(() => useExcelExport(), {
        wrapper: ({ children }: { children: ReactNode }) =>
          createElement(QueryClientProvider, { client: queryClient }, children),
      });

      result.current.mutate({
        batchId: 'batch-123',
        fileIds: ['file-001'],
      });

      await waitFor(() => expect(result.current.isSuccess).toBe(true), {
        timeout: 3000,
      });

      // Should have attempted 3 times (initial + 2 retries)
      expect(exportSpy).toHaveBeenCalledTimes(3);
    });

    it('should fail after 2 retry attempts', async () => {
      const error = new Error('Persistent network error');
      vi.spyOn(batchApi, 'exportToExcel').mockRejectedValue(error);

      // Enable retry for this test
      queryClient = new QueryClient({
        defaultOptions: {
          mutations: {
            retry: 2,
            retryDelay: 0,
          },
        },
      });

      const { result } = renderHook(() => useExcelExport(), {
        wrapper: ({ children }: { children: ReactNode }) =>
          createElement(QueryClientProvider, { client: queryClient }, children),
      });

      result.current.mutate({
        batchId: 'batch-123',
        fileIds: ['file-001'],
      });

      await waitFor(() => expect(result.current.isError).toBe(true), {
        timeout: 3000,
      });

      // Should have attempted 3 times (initial + 2 retries)
      expect(batchApi.exportToExcel).toHaveBeenCalledTimes(3);
      expect(result.current.error).toEqual(error);
    });
  });

  describe('Loading State', () => {
    it('should show pending state during export', async () => {
      vi.spyOn(batchApi, 'exportToExcel').mockImplementation(
        () =>
          new Promise((resolve) => {
            setTimeout(() => resolve(new Blob(['excel data'])), 100);
          })
      );

      const { result } = renderUseExcelExport();

      result.current.mutate({
        batchId: 'batch-123',
        fileIds: ['file-001'],
      });

      // Should be pending immediately
      expect(result.current.isPending).toBe(true);

      await waitFor(() => expect(result.current.isSuccess).toBe(true));

      // Should no longer be pending
      expect(result.current.isPending).toBe(false);
    });
  });

  describe('Multiple File Export', () => {
    it('should handle exporting single file', async () => {
      const mockBlob = new Blob(['excel data']);
      vi.spyOn(batchApi, 'exportToExcel').mockResolvedValue(mockBlob);

      const { result } = renderUseExcelExport();

      result.current.mutate({
        batchId: 'batch-123',
        fileIds: ['file-001'],
      });

      await waitFor(() => expect(result.current.isSuccess).toBe(true));

      expect(batchApi.exportToExcel).toHaveBeenCalledWith('batch-123', [
        'file-001',
      ]);
      expect(result.current.data?.fileCount).toBe(1);
    });

    it('should handle exporting many files', async () => {
      const mockBlob = new Blob(['excel data']);
      vi.spyOn(batchApi, 'exportToExcel').mockResolvedValue(mockBlob);

      const { result } = renderUseExcelExport();

      const manyFileIds = Array.from({ length: 20 }, (_, i) => `file-${i + 1}`);

      result.current.mutate({
        batchId: 'batch-123',
        fileIds: manyFileIds,
      });

      await waitFor(() => expect(result.current.isSuccess).toBe(true));

      expect(batchApi.exportToExcel).toHaveBeenCalledWith(
        'batch-123',
        manyFileIds
      );
      expect(result.current.data?.fileCount).toBe(20);
    });
  });
});
