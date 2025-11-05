/**
 * Tests for parseDiff utility functions
 *
 * Feature: File Diff Comparison Between Upload Sessions
 * Task: T072 - Create parseDiff utility function
 * Phase: Phase 5 - User Story 3 (View Changes in Visual Editor)
 */

import { describe, it, expect } from 'vitest';
import type { UnifiedDiff } from '@/entities/comparison/model/types';
import {
  parseDiff,
  isNewFile,
  isDeletedFile,
  getDiffStats,
  formatDiffSummary,
} from './parseDiff';

describe('parseDiff', () => {
  it('should parse a diff with additions and deletions', () => {
    const diff: UnifiedDiff = {
      hunks: [
        {
          oldStart: 1,
          oldLines: 2,
          newStart: 1,
          newLines: 3,
          changes: [
            { type: 'UNCHANGED', lineNumber: 1, content: 'header' },
            { type: 'REMOVED', lineNumber: 2, content: 'old line' },
            { type: 'ADDED', lineNumber: 2, content: 'new line' },
            { type: 'ADDED', lineNumber: 3, content: 'added line' },
          ],
        },
      ],
      oldFileName: 'file.txt',
      newFileName: 'file.txt',
    };

    const result = parseDiff(diff);

    expect(result.oldValue).toBe('header\nold line');
    expect(result.newValue).toBe('header\nnew line\nadded line');
  });

  it('should parse a diff with only unchanged lines', () => {
    const diff: UnifiedDiff = {
      hunks: [
        {
          oldStart: 1,
          oldLines: 2,
          newStart: 1,
          newLines: 2,
          changes: [
            { type: 'UNCHANGED', lineNumber: 1, content: 'line 1' },
            { type: 'UNCHANGED', lineNumber: 2, content: 'line 2' },
          ],
        },
      ],
      oldFileName: 'file.txt',
      newFileName: 'file.txt',
    };

    const result = parseDiff(diff);

    expect(result.oldValue).toBe('line 1\nline 2');
    expect(result.newValue).toBe('line 1\nline 2');
  });

  it('should parse a new file with only additions', () => {
    const diff: UnifiedDiff = {
      hunks: [
        {
          oldStart: 0,
          oldLines: 0,
          newStart: 1,
          newLines: 2,
          changes: [
            { type: 'ADDED', lineNumber: 1, content: 'new line 1' },
            { type: 'ADDED', lineNumber: 2, content: 'new line 2' },
          ],
        },
      ],
      oldFileName: null,
      newFileName: 'new_file.txt',
    };

    const result = parseDiff(diff);

    expect(result.oldValue).toBe('');
    expect(result.newValue).toBe('new line 1\nnew line 2');
  });

  it('should return empty strings for empty diff', () => {
    const diff: UnifiedDiff = {
      hunks: [],
      oldFileName: 'file.txt',
      newFileName: 'file.txt',
    };

    const result = parseDiff(diff);

    expect(result.oldValue).toBe('');
    expect(result.newValue).toBe('');
  });

  it('should handle multiple hunks correctly', () => {
    const diff: UnifiedDiff = {
      hunks: [
        {
          oldStart: 1,
          oldLines: 2,
          newStart: 1,
          newLines: 2,
          changes: [
            { type: 'UNCHANGED', lineNumber: 1, content: 'start' },
            { type: 'REMOVED', lineNumber: 2, content: 'old middle' },
          ],
        },
        {
          oldStart: 10,
          oldLines: 2,
          newStart: 10,
          newLines: 3,
          changes: [
            { type: 'ADDED', lineNumber: 10, content: 'new middle' },
            { type: 'UNCHANGED', lineNumber: 11, content: 'end' },
          ],
        },
      ],
      oldFileName: 'file.txt',
      newFileName: 'file.txt',
    };

    const result = parseDiff(diff);

    expect(result.oldValue).toBe('start\nold middle\nend');
    expect(result.newValue).toBe('start\nnew middle\nend');
  });
});

describe('isNewFile', () => {
  it('should return true for new file (oldFileName is null)', () => {
    const diff: UnifiedDiff = {
      hunks: [],
      oldFileName: null,
      newFileName: 'new_file.txt',
    };

    expect(isNewFile(diff)).toBe(true);
  });

  it('should return false for modified file', () => {
    const diff: UnifiedDiff = {
      hunks: [],
      oldFileName: 'file.txt',
      newFileName: 'file.txt',
    };

    expect(isNewFile(diff)).toBe(false);
  });
});

describe('isDeletedFile', () => {
  it('should return true for deleted file (newFileName is null)', () => {
    const diff: UnifiedDiff = {
      hunks: [],
      oldFileName: 'deleted_file.txt',
      newFileName: null,
    };

    expect(isDeletedFile(diff)).toBe(true);
  });

  it('should return false for modified file', () => {
    const diff: UnifiedDiff = {
      hunks: [],
      oldFileName: 'file.txt',
      newFileName: 'file.txt',
    };

    expect(isDeletedFile(diff)).toBe(false);
  });
});

describe('getDiffStats', () => {
  it('should calculate stats for a mixed diff', () => {
    const diff: UnifiedDiff = {
      hunks: [
        {
          oldStart: 1,
          oldLines: 5,
          newStart: 1,
          newLines: 6,
          changes: [
            { type: 'UNCHANGED', lineNumber: 1, content: 'line 1' },
            { type: 'UNCHANGED', lineNumber: 2, content: 'line 2' },
            { type: 'REMOVED', lineNumber: 3, content: 'old line' },
            { type: 'ADDED', lineNumber: 3, content: 'new line 1' },
            { type: 'ADDED', lineNumber: 4, content: 'new line 2' },
            { type: 'UNCHANGED', lineNumber: 5, content: 'line 3' },
          ],
        },
      ],
      oldFileName: 'file.txt',
      newFileName: 'file.txt',
    };

    const stats = getDiffStats(diff);

    expect(stats.additions).toBe(2);
    expect(stats.deletions).toBe(1);
    expect(stats.unchanged).toBe(3);
  });

  it('should return zeros for empty diff', () => {
    const diff: UnifiedDiff = {
      hunks: [],
      oldFileName: 'file.txt',
      newFileName: 'file.txt',
    };

    const stats = getDiffStats(diff);

    expect(stats.additions).toBe(0);
    expect(stats.deletions).toBe(0);
    expect(stats.unchanged).toBe(0);
  });

  it('should handle multiple hunks', () => {
    const diff: UnifiedDiff = {
      hunks: [
        {
          oldStart: 1,
          oldLines: 2,
          newStart: 1,
          newLines: 3,
          changes: [
            { type: 'ADDED', lineNumber: 1, content: 'added 1' },
            { type: 'ADDED', lineNumber: 2, content: 'added 2' },
            { type: 'UNCHANGED', lineNumber: 3, content: 'unchanged' },
          ],
        },
        {
          oldStart: 10,
          oldLines: 2,
          newStart: 11,
          newLines: 1,
          changes: [
            { type: 'REMOVED', lineNumber: 10, content: 'removed 1' },
            { type: 'REMOVED', lineNumber: 11, content: 'removed 2' },
          ],
        },
      ],
      oldFileName: 'file.txt',
      newFileName: 'file.txt',
    };

    const stats = getDiffStats(diff);

    expect(stats.additions).toBe(2);
    expect(stats.deletions).toBe(2);
    expect(stats.unchanged).toBe(1);
  });
});

describe('formatDiffSummary', () => {
  it('should format additions and deletions', () => {
    expect(formatDiffSummary(10, 5)).toBe('+10 -5');
  });

  it('should format only additions', () => {
    expect(formatDiffSummary(5, 0)).toBe('+5');
  });

  it('should format only deletions', () => {
    expect(formatDiffSummary(0, 3)).toBe('-3');
  });

  it('should format no changes', () => {
    expect(formatDiffSummary(0, 0)).toBe('No changes');
  });
});
