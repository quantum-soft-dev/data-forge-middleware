/**
 * Formatting utility functions for Upload History feature
 */

import { formatDistanceToNow } from 'date-fns';

/**
 * Format bytes to human-readable size string
 *
 * Converts bytes to appropriate unit (B, KB, MB, GB, TB)
 * with 1 decimal place precision
 *
 * @param bytes - File size in bytes
 * @returns Formatted string (e.g., "50.3 MB", "1.2 GB")
 *
 * @example
 * formatBytes(1024) // "1.0 KB"
 * formatBytes(1048576) // "1.0 MB"
 * formatBytes(2621440) // "2.5 MB"
 * formatBytes(0) // "0 B"
 */
export function formatBytes(bytes: number): string {
  if (bytes === 0) return '0 B';

  const units = ['B', 'KB', 'MB', 'GB', 'TB'];
  const k = 1024;
  const i = Math.floor(Math.log(bytes) / Math.log(k));
  const size = bytes / Math.pow(k, i);

  return `${size.toFixed(1)} ${units[i]}`;
}

/**
 * Format ISO-8601 datetime string to human-readable format
 *
 * Converts ISO timestamp to localized date and time
 * Format: "MMM DD, YYYY HH:MM:SS"
 *
 * @param iso - ISO-8601 datetime string
 * @returns Formatted datetime string (e.g., "Jan 15, 2025 10:30:45")
 *
 * @example
 * formatDateTime("2025-01-15T10:30:45Z") // "Jan 15, 2025 10:30:45"
 * formatDateTime("2025-11-01T12:00:00Z") // "Nov 1, 2025 12:00:00"
 */
export function formatDateTime(iso: string): string {
  const date = new Date(iso);

  // Format date part: "Jan 15, 2025"
  const dateOptions: Intl.DateTimeFormatOptions = {
    year: 'numeric',
    month: 'short',
    day: 'numeric',
  };
  const datePart = date.toLocaleDateString('en-US', dateOptions);

  // Format time part: "10:30:45"
  const timeOptions: Intl.DateTimeFormatOptions = {
    hour: '2-digit',
    minute: '2-digit',
    second: '2-digit',
    hour12: false,
  };
  const timePart = date.toLocaleTimeString('en-US', timeOptions);

  return `${datePart} ${timePart}`;
}

/**
 * Format ISO-8601 date string to short date format
 *
 * Converts ISO timestamp to localized date only
 * Format: "MMM DD, YYYY"
 *
 * @param iso - ISO-8601 datetime string
 * @returns Formatted date string (e.g., "Jan 15, 2025")
 *
 * @example
 * formatDate("2025-01-15T10:30:45Z") // "Jan 15, 2025"
 */
export function formatDate(iso: string): string {
  const date = new Date(iso);
  const options: Intl.DateTimeFormatOptions = {
    year: 'numeric',
    month: 'short',
    day: 'numeric',
  };
  return date.toLocaleDateString('en-US', options);
}

/**
 * Format ISO-8601 time string to time only
 *
 * Converts ISO timestamp to localized time only
 * Format: "HH:MM:SS"
 *
 * @param iso - ISO-8601 datetime string
 * @returns Formatted time string (e.g., "10:30:45")
 *
 * @example
 * formatTime("2025-01-15T10:30:45Z") // "10:30:45"
 */
export function formatTime(iso: string): string {
  const date = new Date(iso);
  const options: Intl.DateTimeFormatOptions = {
    hour: '2-digit',
    minute: '2-digit',
    second: '2-digit',
    hour12: false,
  };
  return date.toLocaleTimeString('en-US', options);
}

/**
 * Format a number with en-US thousands separators (Delta Sync UI, F1)
 *
 * All Delta counters (row counts, sequences, lag) are potentially large numbers
 * and must always render with grouping separators.
 *
 * @param value - Number to format
 * @returns Formatted string (e.g., "1,204,500")
 *
 * @example
 * formatNumber(4821) // "4,821"
 * formatNumber(1204500) // "1,204,500"
 */
export function formatNumber(value: number): string {
  return value.toLocaleString('en-US');
}

/**
 * Format an ISO-8601 timestamp as a relative phrase (Delta Sync UI, F1)
 *
 * @param iso - ISO-8601 datetime string
 * @returns Relative phrase with suffix (e.g., "about 3 hours ago")
 *
 * @example
 * formatRelativeTime("2026-07-05T09:00:00Z") // "about 3 hours ago" (at 12:00Z)
 */
export function formatRelativeTime(iso: string): string {
  return formatDistanceToNow(new Date(iso), { addSuffix: true });
}

/**
 * Format an ISO-8601 timestamp as a short date for dense table cells (Delta Sync UI, F1)
 *
 * Format: "MMM DD, HH:mm" with a zero-padded day and 24h time, no seconds.
 *
 * @param iso - ISO-8601 datetime string
 * @returns Short date string (e.g., "Jul 05, 12:41")
 */
export function formatShortDate(iso: string): string {
  const date = new Date(iso);
  const datePart = date.toLocaleDateString('en-US', { month: 'short', day: '2-digit' });
  const timePart = date.toLocaleTimeString('en-US', { hour: '2-digit', minute: '2-digit', hour12: false });
  return `${datePart}, ${timePart}`;
}

/**
 * Format duration in milliseconds to human-readable string
 *
 * Converts milliseconds to appropriate unit (ms, s, m, h)
 *
 * @param milliseconds - Duration in milliseconds
 * @returns Formatted duration (e.g., "5.2s", "3.5m", "1.2h")
 *
 * @example
 * formatDuration(500) // "500ms"
 * formatDuration(5200) // "5.2s"
 * formatDuration(180000) // "3.0m"
 */
export function formatDuration(milliseconds: number): string {
  if (milliseconds < 1000) {
    return `${milliseconds}ms`;
  }

  if (milliseconds < 60000) {
    return `${(milliseconds / 1000).toFixed(1)}s`;
  }

  if (milliseconds < 3600000) {
    return `${(milliseconds / 60000).toFixed(1)}m`;
  }

  return `${(milliseconds / 3600000).toFixed(1)}h`;
}
