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
    <div className="min-h-screen bg-white">
      <Header />

      <div className="container mx-auto max-w-[1120px] px-6 py-6">
        <PageHeader
          title="Upload History"
          subtitle="View your upload sessions and their status"
        />

        <BatchListWidget />
      </div>
    </div>
  );
}
