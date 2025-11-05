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
    <div className="container mx-auto py-8 px-4">
      {/* Page Header */}
      <div className="mb-8">
        <h1 className="text-3xl font-bold tracking-tight">File Comparisons</h1>
        <p className="text-muted-foreground mt-2">
          View and manage all your file comparison results
        </p>
      </div>

      {/* Comparison List Widget */}
      <ComparisonListWidget pageSize={10} />
    </div>
  );
}
