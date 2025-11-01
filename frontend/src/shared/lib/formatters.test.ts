import { describe, it, expect } from 'vitest';
import {
  formatBytes,
  formatDateTime,
  formatDate,
  formatTime,
  formatDuration,
} from './formatters';

describe('formatBytes', () => {
  it('should format 0 bytes', () => {
    expect(formatBytes(0)).toBe('0 B');
  });

  it('should format bytes (< 1KB)', () => {
    expect(formatBytes(500)).toBe('500.0 B');
    expect(formatBytes(1023)).toBe('1023.0 B');
  });

  it('should format kilobytes', () => {
    expect(formatBytes(1024)).toBe('1.0 KB');
    expect(formatBytes(2048)).toBe('2.0 KB');
    expect(formatBytes(5120)).toBe('5.0 KB');
  });

  it('should format megabytes', () => {
    expect(formatBytes(1048576)).toBe('1.0 MB'); // 1MB
    expect(formatBytes(2621440)).toBe('2.5 MB'); // 2.5MB
    expect(formatBytes(10485760)).toBe('10.0 MB'); // 10MB
  });

  it('should format gigabytes', () => {
    expect(formatBytes(1073741824)).toBe('1.0 GB'); // 1GB
    expect(formatBytes(5368709120)).toBe('5.0 GB'); // 5GB
  });

  it('should format terabytes', () => {
    expect(formatBytes(1099511627776)).toBe('1.0 TB'); // 1TB
  });
});

describe('formatDateTime', () => {
  it('should format ISO-8601 datetime string', () => {
    const result = formatDateTime('2025-01-15T10:30:45Z');
    expect(result).toContain('2025');
    expect(result).toMatch(/\d{2}:\d{2}:\d{2}/); // Contains time in HH:MM:SS format
  });

  it('should handle different months', () => {
    const result = formatDateTime('2025-11-01T12:00:00Z');
    expect(result).toContain('2025');
    expect(result).toMatch(/\d{2}:\d{2}:\d{2}/);
  });
});

describe('formatDate', () => {
  it('should format ISO-8601 to date only', () => {
    const result = formatDate('2025-01-15T10:30:45Z');
    expect(result).toContain('2025');
    expect(result).not.toMatch(/\d{2}:\d{2}:\d{2}/); // Should not contain time
  });
});

describe('formatTime', () => {
  it('should format ISO-8601 to time only', () => {
    const result = formatTime('2025-01-15T10:30:45Z');
    expect(result).toMatch(/\d{2}:\d{2}:\d{2}/); // Contains time in HH:MM:SS format
    expect(result).not.toContain('Jan');
    expect(result).not.toContain('2025');
  });
});

describe('formatDuration', () => {
  it('should format milliseconds', () => {
    expect(formatDuration(500)).toBe('500ms');
    expect(formatDuration(999)).toBe('999ms');
  });

  it('should format seconds', () => {
    expect(formatDuration(1000)).toBe('1.0s');
    expect(formatDuration(5200)).toBe('5.2s');
    expect(formatDuration(59999)).toBe('60.0s');
  });

  it('should format minutes', () => {
    expect(formatDuration(60000)).toBe('1.0m');
    expect(formatDuration(180000)).toBe('3.0m');
    expect(formatDuration(3599999)).toBe('60.0m');
  });

  it('should format hours', () => {
    expect(formatDuration(3600000)).toBe('1.0h');
    expect(formatDuration(7200000)).toBe('2.0h');
  });
});
