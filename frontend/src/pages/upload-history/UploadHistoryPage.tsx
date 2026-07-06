/**
 * T039: Upload History page component
 *
 * Main page for viewing upload history.
 * Route: /account/upload-history
 *
 * Feature: 008-upload-history-user (User Story 1)
 */

import { Header } from '@/widgets/header/Header';
import { BatchListWidget } from '@/widgets/upload-history/BatchListWidget';
import { PageHeader } from '@/shared/ui/page-header';

/**
 * T039: Upload history page
 */
export default function UploadHistoryPage() {
  return (
    <div className="min-h-screen bg-surface-hover">
      <Header />

      <div className="container mx-auto px-4 py-8">
        <PageHeader
          title="Upload History"
          subtitle="View your upload sessions and their status"
        />

        <BatchListWidget />
      </div>
    </div>
  );
}
