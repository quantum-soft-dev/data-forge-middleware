/**
 * Download report button component for comparison summary.
 *
 * Provides a button to download comparison summary report as a text file,
 * with loading state and error handling.
 *
 * @module features/file-comparison/ui/DownloadReportButton
 */

import { FileText } from 'lucide-react';
import { Button } from '@/shared/ui/ui/button';
import { useDownloadSummaryReport } from '../hooks/useDownloadSummaryReport';

/**
 * Props for DownloadReportButton component.
 */
export interface DownloadReportButtonProps {
  /** ID of the comparison to download summary for */
  comparisonId: string;

  /** Button variant (default: 'outline') */
  variant?: 'default' | 'outline' | 'secondary' | 'ghost' | 'link';

  /** Button size (default: 'default') */
  size?: 'default' | 'sm' | 'lg' | 'icon';

  /** Additional CSS class name */
  className?: string;
}

/**
 * Download report button component for comparison summary report.
 *
 * Features:
 * - Downloads comparison summary as text file
 * - Shows loading spinner during download
 * - Disables button during download
 * - Accessible with ARIA labels
 *
 * @param props - Component props
 * @returns Download report button component
 *
 * @example
 * ```tsx
 * <DownloadReportButton comparisonId="123" />
 * ```
 *
 * @example
 * ```tsx
 * <DownloadReportButton
 *   comparisonId="456"
 *   variant="secondary"
 *   size="sm"
 * />
 * ```
 *
 * Phase 8 (User Story 6) - Task T103
 */
export default function DownloadReportButton({
  comparisonId,
  variant = 'outline',
  size = 'default',
  className,
}: DownloadReportButtonProps) {
  const { mutate, isPending } = useDownloadSummaryReport();

  const handleDownload = () => {
    mutate(comparisonId, {
      onSuccess: (data) => {
        console.log('Summary report download started:', data.filename);
      },
      onError: (error) => {
        console.error('Summary report download failed:', error instanceof Error ? error.message : 'Failed to download summary report');
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
      aria-label={isPending ? 'Downloading summary report' : 'Download summary report'}
    >
      <FileText className="mr-2 h-4 w-4" />
      {isPending ? 'Downloading...' : 'Download Report'}
    </Button>
  );
}
