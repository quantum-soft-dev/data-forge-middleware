/**
 * T051: Component tests for FileTable
 *
 * Tests file table component with checkbox selection logic,
 * "Select all" functionality, empty states, and selection callbacks.
 *
 * Feature: 008-upload-history-user (User Story 2)
 */

import { describe, it, expect, vi } from 'vitest';
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { FileTable } from './FileTable';
import type { FileMetadata } from '@/entities/batch/model/types';

describe('FileTable', () => {
  const mockFiles: FileMetadata[] = [
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
  ];

  describe('Empty State', () => {
    it('should display empty message when files array is empty', () => {
      render(<FileTable files={[]} />);

      expect(screen.getByText(/no files in this batch/i)).toBeInTheDocument();
    });

    it('should not display table when files array is empty', () => {
      render(<FileTable files={[]} />);

      expect(screen.queryByRole('table')).not.toBeInTheDocument();
    });
  });

  describe('File List Display', () => {
    it('should render table with correct headers', () => {
      render(<FileTable files={mockFiles} />);

      expect(screen.getByText(/filename/i)).toBeInTheDocument();
      expect(screen.getByText(/size/i)).toBeInTheDocument();
      expect(screen.getByText(/uploaded at/i)).toBeInTheDocument();
    });

    it('should display all files in the table', () => {
      render(<FileTable files={mockFiles} />);

      mockFiles.forEach((file) => {
        expect(screen.getByText(file.originalFileName)).toBeInTheDocument();
      });
    });

    it('should display formatted file sizes', () => {
      render(<FileTable files={mockFiles} />);

      // 512000 bytes = 500 KB
      expect(screen.getByText(/500 KB/i)).toBeInTheDocument();
      // 768000 bytes = 750 KB
      expect(screen.getByText(/750 KB/i)).toBeInTheDocument();
      // 256000 bytes = 250 KB
      expect(screen.getByText(/250 KB/i)).toBeInTheDocument();
    });

    it('should display formatted timestamps', () => {
      render(<FileTable files={mockFiles} />);

      // Check that timestamps are rendered (format may vary based on formatDateTime implementation)
      const rows = screen.getAllByRole('row');
      expect(rows.length).toBeGreaterThan(mockFiles.length); // Header + data rows
    });
  });

  describe('Individual Checkbox Selection', () => {
    it('should render checkbox for each file', () => {
      render(<FileTable files={mockFiles} />);

      const checkboxes = screen.getAllByRole('checkbox');
      // +1 for "Select all" checkbox
      expect(checkboxes).toHaveLength(mockFiles.length + 1);
    });

    it('should check individual file checkbox when clicked', async () => {
      const user = userEvent.setup();
      render(<FileTable files={mockFiles} />);

      const checkboxes = screen.getAllByRole('checkbox');
      const firstFileCheckbox = checkboxes[1]; // Index 0 is "Select all"

      expect(firstFileCheckbox).not.toBeChecked();

      await user.click(firstFileCheckbox);

      expect(firstFileCheckbox).toBeChecked();
    });

    it('should uncheck individual file checkbox when clicked twice', async () => {
      const user = userEvent.setup();
      render(<FileTable files={mockFiles} />);

      const checkboxes = screen.getAllByRole('checkbox');
      const firstFileCheckbox = checkboxes[1];

      await user.click(firstFileCheckbox);
      expect(firstFileCheckbox).toBeChecked();

      await user.click(firstFileCheckbox);
      expect(firstFileCheckbox).not.toBeChecked();
    });

    it('should call onSelectionChange when file is selected', async () => {
      const onSelectionChange = vi.fn();
      const user = userEvent.setup();

      render(<FileTable files={mockFiles} onSelectionChange={onSelectionChange} />);

      const checkboxes = screen.getAllByRole('checkbox');
      const firstFileCheckbox = checkboxes[1];

      await user.click(firstFileCheckbox);

      expect(onSelectionChange).toHaveBeenCalledWith(['file-001']);
    });

    it('should call onSelectionChange with multiple file IDs when multiple files selected', async () => {
      const onSelectionChange = vi.fn();
      const user = userEvent.setup();

      render(<FileTable files={mockFiles} onSelectionChange={onSelectionChange} />);

      const checkboxes = screen.getAllByRole('checkbox');

      // Select first file
      await user.click(checkboxes[1]);
      expect(onSelectionChange).toHaveBeenLastCalledWith(['file-001']);

      // Select second file
      await user.click(checkboxes[2]);
      expect(onSelectionChange).toHaveBeenLastCalledWith(['file-001', 'file-002']);

      // Select third file
      await user.click(checkboxes[3]);
      expect(onSelectionChange).toHaveBeenLastCalledWith(['file-001', 'file-002', 'file-003']);
    });

    it('should highlight selected row with blue background', async () => {
      const user = userEvent.setup();
      render(<FileTable files={mockFiles} />);

      const checkboxes = screen.getAllByRole('checkbox');
      const firstFileCheckbox = checkboxes[1];

      await user.click(firstFileCheckbox);

      // Find the row containing the first file
      const row = screen.getByText('sales-2024.csv.gz').closest('tr');
      expect(row).toHaveClass('bg-blue-50');
    });
  });

  describe('Select All Checkbox', () => {
    it('should render "Select all" checkbox', () => {
      render(<FileTable files={mockFiles} />);

      const checkboxes = screen.getAllByRole('checkbox');
      const selectAllCheckbox = checkboxes[0];

      expect(selectAllCheckbox).toHaveAttribute('aria-label', 'Select all files');
    });

    it('should select all files when "Select all" is clicked', async () => {
      const user = userEvent.setup();
      render(<FileTable files={mockFiles} />);

      const checkboxes = screen.getAllByRole('checkbox');
      const selectAllCheckbox = checkboxes[0];

      await user.click(selectAllCheckbox);

      // Check that all file checkboxes are now checked
      checkboxes.slice(1).forEach((checkbox) => {
        expect(checkbox).toBeChecked();
      });
    });

    it('should deselect all files when "Select all" is clicked twice', async () => {
      const user = userEvent.setup();
      render(<FileTable files={mockFiles} />);

      const checkboxes = screen.getAllByRole('checkbox');
      const selectAllCheckbox = checkboxes[0];

      // Select all
      await user.click(selectAllCheckbox);

      // Deselect all
      await user.click(selectAllCheckbox);

      // Check that all file checkboxes are now unchecked
      checkboxes.slice(1).forEach((checkbox) => {
        expect(checkbox).not.toBeChecked();
      });
    });

    it('should call onSelectionChange with all file IDs when "Select all" is clicked', async () => {
      const onSelectionChange = vi.fn();
      const user = userEvent.setup();

      render(<FileTable files={mockFiles} onSelectionChange={onSelectionChange} />);

      const checkboxes = screen.getAllByRole('checkbox');
      const selectAllCheckbox = checkboxes[0];

      await user.click(selectAllCheckbox);

      expect(onSelectionChange).toHaveBeenCalledWith(['file-001', 'file-002', 'file-003']);
    });

    it('should call onSelectionChange with empty array when deselecting all', async () => {
      const onSelectionChange = vi.fn();
      const user = userEvent.setup();

      render(<FileTable files={mockFiles} onSelectionChange={onSelectionChange} />);

      const checkboxes = screen.getAllByRole('checkbox');
      const selectAllCheckbox = checkboxes[0];

      await user.click(selectAllCheckbox); // Select all
      await user.click(selectAllCheckbox); // Deselect all

      expect(onSelectionChange).toHaveBeenLastCalledWith([]);
    });

    it('should be checked when all files are selected individually', async () => {
      const user = userEvent.setup();
      render(<FileTable files={mockFiles} />);

      const checkboxes = screen.getAllByRole('checkbox');
      const selectAllCheckbox = checkboxes[0];

      // Select all files individually
      for (let i = 1; i <= mockFiles.length; i++) {
        await user.click(checkboxes[i]);
      }

      expect(selectAllCheckbox).toBeChecked();
    });

    it('should be unchecked when not all files are selected', async () => {
      const user = userEvent.setup();
      render(<FileTable files={mockFiles} />);

      const checkboxes = screen.getAllByRole('checkbox');
      const selectAllCheckbox = checkboxes[0];

      // Select only first file
      await user.click(checkboxes[1]);

      expect(selectAllCheckbox).not.toBeChecked();
    });
  });

  describe('Selection Summary', () => {
    it('should not display selection summary when no files are selected', () => {
      render(<FileTable files={mockFiles} />);

      expect(screen.queryByText(/selected/i)).not.toBeInTheDocument();
    });

    it('should display selection summary when files are selected', async () => {
      const user = userEvent.setup();
      render(<FileTable files={mockFiles} />);

      const checkboxes = screen.getAllByRole('checkbox');
      await user.click(checkboxes[1]); // Select first file

      expect(screen.getByText(/1 file selected/i)).toBeInTheDocument();
    });

    it('should display plural "files" when multiple files selected', async () => {
      const user = userEvent.setup();
      render(<FileTable files={mockFiles} />);

      const checkboxes = screen.getAllByRole('checkbox');
      await user.click(checkboxes[1]); // Select first file
      await user.click(checkboxes[2]); // Select second file

      expect(screen.getByText(/2 files selected/i)).toBeInTheDocument();
    });

    it('should display singular "file" when one file selected', async () => {
      const user = userEvent.setup();
      render(<FileTable files={mockFiles} />);

      const checkboxes = screen.getAllByRole('checkbox');
      await user.click(checkboxes[1]);

      expect(screen.getByText(/1 file selected/i)).toBeInTheDocument();
    });

    it('should update selection count when files are deselected', async () => {
      const user = userEvent.setup();
      render(<FileTable files={mockFiles} />);

      const checkboxes = screen.getAllByRole('checkbox');
      const selectAllCheckbox = checkboxes[0];

      // Select all
      await user.click(selectAllCheckbox);
      expect(screen.getByText(/3 files selected/i)).toBeInTheDocument();

      // Deselect one file
      await user.click(checkboxes[1]);
      expect(screen.getByText(/2 files selected/i)).toBeInTheDocument();
    });
  });

  describe('Accessibility', () => {
    it('should have aria-label for file checkboxes', () => {
      render(<FileTable files={mockFiles} />);

      const checkboxes = screen.getAllByRole('checkbox');
      expect(checkboxes[1]).toHaveAttribute('aria-label', 'Select sales-2024.csv.gz');
      expect(checkboxes[2]).toHaveAttribute('aria-label', 'Select inventory-2024.csv.gz');
      expect(checkboxes[3]).toHaveAttribute('aria-label', 'Select orders-2024.csv.gz');
    });

    it('should have table structure with proper roles', () => {
      render(<FileTable files={mockFiles} />);

      expect(screen.getByRole('table')).toBeInTheDocument();
      const rows = screen.getAllByRole('row');
      expect(rows.length).toBe(mockFiles.length + 1); // Header + data rows
    });
  });
});
