/**
 * T054: File table component with checkbox selection
 *
 * Displays list of files in a batch with individual and "Select all" checkboxes.
 * Used in batch detail view for file selection before download/export operations.
 *
 * Feature: 008-upload-history-user (User Story 2)
 */

import { useState, useCallback } from 'react';
import { Checkbox } from '@/shared/ui/checkbox';
import type { FileMetadata } from '@/entities/batch/model/types';
import { formatBytes, formatDateTime } from '@/shared/lib/formatters';

interface FileTableProps {
  /** List of files in batch */
  files: FileMetadata[];
  /** Callback when selection changes */
  onSelectionChange?: (selectedFileIds: string[]) => void;
}

/**
 * T054: File table with checkbox selection (individual + "Select all")
 */
export function FileTable({ files, onSelectionChange }: FileTableProps) {
  const [selectedFileIds, setSelectedFileIds] = useState<Set<string>>(new Set());

  // Check if all files are selected
  const allSelected = files.length > 0 && selectedFileIds.size === files.length;

  // Check if some (but not all) files are selected
  const indeterminate = selectedFileIds.size > 0 && selectedFileIds.size < files.length;

  /**
   * Toggle individual file selection
   */
  const toggleFile = useCallback((fileId: string) => {
    setSelectedFileIds((prev) => {
      const newSet = new Set(prev);
      if (newSet.has(fileId)) {
        newSet.delete(fileId);
      } else {
        newSet.add(fileId);
      }

      // Notify parent component
      onSelectionChange?.(Array.from(newSet));

      return newSet;
    });
  }, [onSelectionChange]);

  /**
   * Toggle all files selection
   */
  const toggleAll = useCallback(() => {
    setSelectedFileIds((prev) => {
      const newSet = prev.size === files.length
        ? new Set<string>() // Deselect all
        : new Set(files.map(f => f.id)); // Select all

      // Notify parent component
      onSelectionChange?.(Array.from(newSet));

      return newSet;
    });
  }, [files, onSelectionChange]);

  // Empty state
  if (files.length === 0) {
    return (
      <div className="rounded-lg border border-gray-200 bg-gray-50 p-8 text-center">
        <p className="text-gray-600">No files in this batch.</p>
      </div>
    );
  }

  return (
    <div className="overflow-hidden rounded-lg border border-gray-200">
      <table className="min-w-full divide-y divide-gray-200">
        <thead className="bg-gray-50">
          <tr>
            <th scope="col" className="w-12 px-4 py-3">
              <Checkbox
                checked={allSelected}
                indeterminate={indeterminate}
                onCheckedChange={toggleAll}
                aria-label="Select all files"
              />
            </th>
            <th
              scope="col"
              className="px-4 py-3 text-left text-xs font-medium uppercase tracking-wider text-gray-500"
            >
              Filename
            </th>
            <th
              scope="col"
              className="px-4 py-3 text-left text-xs font-medium uppercase tracking-wider text-gray-500"
            >
              Size
            </th>
            <th
              scope="col"
              className="px-4 py-3 text-left text-xs font-medium uppercase tracking-wider text-gray-500"
            >
              Uploaded At
            </th>
          </tr>
        </thead>
        <tbody className="divide-y divide-gray-200 bg-white">
          {files.map((file) => (
            <tr
              key={file.id}
              className={`hover:bg-gray-50 transition-colors ${
                selectedFileIds.has(file.id) ? 'bg-blue-50' : ''
              }`}
            >
              <td className="w-12 px-4 py-3">
                <Checkbox
                  checked={selectedFileIds.has(file.id)}
                  onCheckedChange={() => toggleFile(file.id)}
                  aria-label={`Select ${file.originalFileName}`}
                />
              </td>
              <td className="px-4 py-3 text-sm font-medium text-gray-900">
                {file.originalFileName}
              </td>
              <td className="px-4 py-3 text-sm text-gray-500">
                {formatBytes(file.fileSize)}
              </td>
              <td className="px-4 py-3 text-sm text-gray-500">
                {formatDateTime(file.uploadedAt)}
              </td>
            </tr>
          ))}
        </tbody>
      </table>

      {/* Selection summary */}
      {selectedFileIds.size > 0 && (
        <div className="border-t border-gray-200 bg-blue-50 px-4 py-2 text-sm text-blue-800">
          {selectedFileIds.size} file{selectedFileIds.size !== 1 ? 's' : ''} selected
        </div>
      )}
    </div>
  );
}
