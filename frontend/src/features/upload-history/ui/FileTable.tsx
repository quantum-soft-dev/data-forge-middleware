/**
 * T054: File table component with checkbox selection
 *
 * Displays list of files in a batch with individual and "Select all" checkboxes.
 * Used in batch detail view for file selection before download/export operations.
 *
 * Feature: 008-upload-history-user (User Story 2)
 */

import { useState, useCallback, useMemo } from 'react';
import { Checkbox } from '@/shared/ui/ui/checkbox';
import type { FileMetadata } from '@/entities/batch/model/types';
import { formatBytes, formatDateTime } from '@/shared/lib/formatters';
import { ArrowUpDown, ArrowUp, ArrowDown, Search } from 'lucide-react';

interface FileTableProps {
  /** List of files in batch */
  files: FileMetadata[];
  /** Callback when selection changes */
  onSelectionChange?: (selectedFileIds: string[]) => void;
}

type SortField = 'name' | 'size' | 'date';
type SortDirection = 'asc' | 'desc';

/**
 * T054: File table with checkbox selection (individual + "Select all")
 */
export function FileTable({ files, onSelectionChange }: FileTableProps) {
  const [selectedFileIds, setSelectedFileIds] = useState<Set<string>>(new Set());
  const [searchQuery, setSearchQuery] = useState('');
  const [sortField, setSortField] = useState<SortField>('name');
  const [sortDirection, setSortDirection] = useState<SortDirection>('asc');

  // Filter and sort files
  const filteredAndSortedFiles = useMemo(() => {
    let filtered = files;

    // Filter by search query
    if (searchQuery.trim()) {
      const query = searchQuery.toLowerCase();
      filtered = files.filter(file =>
        file.originalFileName.toLowerCase().includes(query)
      );
    }

    // Sort
    const sorted = [...filtered].sort((a, b) => {
      let comparison = 0;

      switch (sortField) {
        case 'name':
          comparison = a.originalFileName.localeCompare(b.originalFileName);
          break;
        case 'size':
          comparison = a.fileSize - b.fileSize;
          break;
        case 'date':
          comparison = new Date(a.uploadedAt).getTime() - new Date(b.uploadedAt).getTime();
          break;
      }

      return sortDirection === 'asc' ? comparison : -comparison;
    });

    return sorted;
  }, [files, searchQuery, sortField, sortDirection]);

  // Update selection status based on filtered files
  const allSelected = filteredAndSortedFiles.length > 0 &&
    filteredAndSortedFiles.every(f => selectedFileIds.has(f.id));
  const indeterminate = filteredAndSortedFiles.some(f => selectedFileIds.has(f.id)) &&
    !allSelected;

  // Toggle sort
  const toggleSort = (field: SortField) => {
    if (sortField === field) {
      setSortDirection(prev => prev === 'asc' ? 'desc' : 'asc');
    } else {
      setSortField(field);
      setSortDirection('asc');
    }
  };

  // Get sort icon
  const getSortIcon = (field: SortField) => {
    if (sortField !== field) {
      return <ArrowUpDown className="ml-1 h-3 w-3 inline opacity-40" />;
    }
    return sortDirection === 'asc'
      ? <ArrowUp className="ml-1 h-3 w-3 inline" />
      : <ArrowDown className="ml-1 h-3 w-3 inline" />;
  };

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

      // Notify parent after state update using queueMicrotask
      const selectedArray = Array.from(newSet);
      console.log('[FileTable] Selection changed, calling parent with:', selectedArray);
      queueMicrotask(() => {
        onSelectionChange?.(selectedArray);
      });

      return newSet;
    });
  }, [onSelectionChange]);

  /**
   * Toggle all files selection (filtered files only)
   */
  const toggleAll = useCallback(() => {
    setSelectedFileIds((prev) => {
      const newSet = allSelected
        ? new Set<string>() // Deselect all visible
        : new Set([...prev, ...filteredAndSortedFiles.map(f => f.id)]); // Select all visible

      // Notify parent after state update using queueMicrotask
      const selectedArray = Array.from(newSet);
      console.log('[FileTable] Selection changed (toggle all), calling parent with:', selectedArray);
      queueMicrotask(() => {
        onSelectionChange?.(selectedArray);
      });

      return newSet;
    });
  }, [filteredAndSortedFiles, allSelected, onSelectionChange]);

  // Empty state
  if (files.length === 0) {
    return (
      <div className="rounded-lg border border-gray-200 bg-gray-50 p-8 text-center">
        <p className="text-gray-600">No files in this batch.</p>
      </div>
    );
  }

  return (
    <div className="space-y-3">
      {/* Search input */}
      <div className="relative">
        <Search className="absolute left-3 top-1/2 transform -translate-y-1/2 h-4 w-4 text-gray-400" />
        <input
          type="text"
          placeholder="Filter by filename..."
          value={searchQuery}
          onChange={(e) => setSearchQuery(e.target.value)}
          className="w-full pl-10 pr-4 py-2 text-sm border border-gray-300 rounded-md focus:outline-none focus:ring-2 focus:ring-blue-500"
        />
      </div>

      {/* Table with scrolling */}
      <div className="overflow-hidden rounded-lg border border-gray-200">
        <div className="max-h-[400px] overflow-y-auto">
          <table className="min-w-full divide-y divide-gray-200">
            <thead className="bg-gray-50 sticky top-0 z-10">
              <tr>
                <th scope="col" className="w-10 px-3 py-2">
                  <Checkbox
                    checked={allSelected}
                    indeterminate={indeterminate}
                    onCheckedChange={toggleAll}
                    aria-label="Select all files"
                  />
                </th>
                <th
                  scope="col"
                  className="px-3 py-2 text-left text-xs font-medium uppercase tracking-wider text-gray-500 cursor-pointer hover:bg-gray-100"
                  onClick={() => toggleSort('name')}
                >
                  Filename
                  {getSortIcon('name')}
                </th>
                <th
                  scope="col"
                  className="px-3 py-2 text-left text-xs font-medium uppercase tracking-wider text-gray-500 cursor-pointer hover:bg-gray-100"
                  onClick={() => toggleSort('size')}
                >
                  Size
                  {getSortIcon('size')}
                </th>
                <th
                  scope="col"
                  className="px-3 py-2 text-left text-xs font-medium uppercase tracking-wider text-gray-500 cursor-pointer hover:bg-gray-100"
                  onClick={() => toggleSort('date')}
                >
                  Uploaded
                  {getSortIcon('date')}
                </th>
              </tr>
            </thead>
            <tbody className="divide-y divide-gray-200 bg-white">
              {filteredAndSortedFiles.length === 0 ? (
                <tr>
                  <td colSpan={4} className="px-3 py-8 text-center text-sm text-gray-500">
                    No files match your search
                  </td>
                </tr>
              ) : (
                filteredAndSortedFiles.map((file) => (
                  <tr
                    key={file.id}
                    className={`hover:bg-gray-50 transition-colors ${
                      selectedFileIds.has(file.id) ? 'bg-blue-50' : ''
                    }`}
                  >
                    <td className="w-10 px-3 py-2">
                      <Checkbox
                        checked={selectedFileIds.has(file.id)}
                        onCheckedChange={() => toggleFile(file.id)}
                        aria-label={`Select ${file.originalFileName}`}
                      />
                    </td>
                    <td className="px-3 py-2 text-sm font-medium text-gray-900">
                      {file.originalFileName}
                    </td>
                    <td className="px-3 py-2 text-sm text-gray-500">
                      {formatBytes(file.fileSize)}
                    </td>
                    <td className="px-3 py-2 text-sm text-gray-500">
                      {formatDateTime(file.uploadedAt)}
                    </td>
                  </tr>
                ))
              )}
            </tbody>
          </table>
        </div>
      </div>

      {/* Selection summary */}
      {selectedFileIds.size > 0 && (
        <div className="border-t border-gray-200 bg-blue-50 px-4 py-2 text-sm text-blue-800">
          {selectedFileIds.size} file{selectedFileIds.size !== 1 ? 's' : ''} selected
        </div>
      )}
    </div>
  );
}
