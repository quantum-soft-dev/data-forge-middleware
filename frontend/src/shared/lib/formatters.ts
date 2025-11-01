/**
 * Formatting utility functions for Upload History feature
 */

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
