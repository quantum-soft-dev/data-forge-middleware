/**
 * Download button component for comparison results.
 *
 * Provides a button to download comparison results as a ZIP archive,
 * with loading state and error handling.
 *
 * @module features/file-comparison/ui/DownloadButton
 */

import { Download } from 'lucide-react';
import { Button } from '@/shared/ui/ui/button';
import { useDownloadComparison } from '../hooks/useDownloadComparison';

/**
 * Props for DownloadButton component.
 */
export interface DownloadButtonProps {
  /** ID of the comparison to download */
  comparisonId: string;

  /** Button variant (default: 'default') */
  variant?: 'default' | 'outline' | 'secondary' | 'ghost' | 'link';

  /** Button size (default: 'default') */
  size?: 'default' | 'sm' | 'lg' | 'icon';

  /** Additional CSS class name */
  className?: string;
}

/**
 * Download button component for comparison ZIP archives.
 *
 * Features:
 * - Downloads comparison results as ZIP
 * - Shows loading spinner during download
 * - Disables button during download
 * - Accessible with ARIA labels
 *
 * @param props - Component props
 * @returns Download button component
 *
 * @example
 * ```tsx
 * <DownloadButton comparisonId="123" />
 * ```
 *
 * @example
 * ```tsx
 * <DownloadButton
 *   comparisonId="456"
 *   variant="outline"
 *   size="sm"
 * />
 * ```
 *
 * Phase 7 (User Story 4) - Task T096
 */
export default function DownloadButton({
  comparisonId,
  variant = 'default',
  size = 'default',
  className,
}: DownloadButtonProps) {
  const { mutate, isPending } = useDownloadComparison();

  const handleDownload = () => {
    mutate(comparisonId, {
      onSuccess: (data) => {
        console.log('Download started:', data.filename);
      },
      onError: (error) => {
        console.error('Download failed:', error instanceof Error ? error.message : 'Failed to download comparison');
      },
    });
  };

  return (
    <Button
      onClick={handleDownload}
      disabled={isPending}
      variant={variant}
      size={size}
      className={className}
      aria-label={isPending ? 'Downloading comparison' : 'Download comparison as ZIP'}
    >
      <Download className="mr-2 h-4 w-4" />
      {isPending ? 'Downloading...' : 'Download ZIP'}
    </Button>
  );
}
