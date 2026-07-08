/**
 * T130: ComparisonListPage with delete functionality (T117)
 *
 * Main page component for viewing all file comparisons.
 * Displays a list of comparisons with pagination and filtering.
 *
 * Features:
 * - Page title and description
 * - ComparisonListWidget integration
 * - Delete functionality via ComparisonCard (T117)
 * - Responsive layout
 *
 * Phase 10: List Comparisons (Supporting Feature)
 * T117: Delete button functionality integrated via ComparisonCard component
 * Priority: P3
 *
 * @module pages/comparison/ComparisonListPage
 */

import { PageHeader } from '@/shared/ui/page-header';
import { ComparisonListWidget } from '@/widgets/comparison/ComparisonListWidget';

/**
 * Page displaying the list of all file comparisons.
 *
 * This page serves as the main entry point for viewing comparison history.
 * Users can:
 * - View all comparisons with pagination
 * - Filter by status (COMPLETED/FAILED/IN_PROGRESS)
 * - Delete comparisons (via ComparisonCard's delete button - T116, T117)
 * - Navigate to comparison details
 *
 * @example
 * ```tsx
 * // Route configuration
 * <Route path="/comparisons" element={<ComparisonListPage />} />
 * ```
 */
export function ComparisonListPage() {
  return (
    <div className="container mx-auto max-w-[1120px] px-6 py-6">
      {/* Page Header */}
      <PageHeader
        className="mb-6"
        title="File Comparisons"
        subtitle="View and manage all your file comparison results"
      />

      {/* Comparison List Widget */}
      <ComparisonListWidget pageSize={10} />
    </div>
  );
}
