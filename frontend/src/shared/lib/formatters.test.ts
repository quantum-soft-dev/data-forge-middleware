import { describe, it, expect, vi, afterEach } from 'vitest';
import {
  formatBytes,
  formatDateTime,
  formatDate,
  formatTime,
  formatDuration,
  formatNumber,
  formatRelativeTime,
  formatShortDate,
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

describe('formatNumber', () => {
  it('formats with en-US thousands separators', () => {
    expect(formatNumber(0)).toBe('0');
    expect(formatNumber(999)).toBe('999');
    expect(formatNumber(4821)).toBe('4,821');
    expect(formatNumber(1204500)).toBe('1,204,500');
  });

  it('handles negative values', () => {
    expect(formatNumber(-1500)).toBe('-1,500');
  });
});

describe('formatRelativeTime', () => {
  afterEach(() => {
    vi.useRealTimers();
  });

  it('renders a relative phrase with suffix', () => {
    vi.useFakeTimers();
    vi.setSystemTime(new Date('2026-07-05T12:00:00Z'));

    expect(formatRelativeTime('2026-07-05T09:00:00Z')).toBe('about 3 hours ago');
    expect(formatRelativeTime('2026-07-05T11:59:40Z')).toBe('less than a minute ago');
  });
});

describe('formatShortDate', () => {
  it('formats as "MMM DD, HH:mm" for dense table cells', () => {
    // Zero-padded day and 24h time per the design spec ("Jul 05, 12:41").
    // Timezone-agnostic assertions, consistent with the formatDateTime tests above.
    expect(formatShortDate('2026-07-05T12:41:00Z')).toMatch(/^Jul \d{2}, \d{2}:\d{2}$/);
    expect(formatShortDate('2026-01-09T09:05:00Z')).toMatch(/^Jan \d{2}, \d{2}:\d{2}$/);
  });

  it('never renders seconds', () => {
    expect(formatShortDate('2026-07-05T12:41:33Z')).not.toMatch(/:\d{2}:\d{2}/);
  });
});
