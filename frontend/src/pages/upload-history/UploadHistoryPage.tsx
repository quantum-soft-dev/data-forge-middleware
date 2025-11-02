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

/**
 * T039: Upload history page
 */
export default function UploadHistoryPage() {
  return (
    <div className="min-h-screen bg-gray-50">
      <Header />

      <div className="container mx-auto px-4 py-8">
        <div className="mb-8">
          <h1 className="text-3xl font-bold text-gray-900">Upload History</h1>
          <p className="mt-2 text-gray-600">
            View your upload sessions and their status
          </p>
        </div>

        <BatchListWidget />
      </div>
    </div>
  );
}
