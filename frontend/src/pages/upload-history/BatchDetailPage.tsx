/**
 * Batch Detail page component
 *
 * Page for viewing detailed batch information and file list.
 * Route: /account/upload-history/:batchId
 *
 * Feature: 008-upload-history-user (User Story 2)
 */

import { useParams, useNavigate } from '@tanstack/react-router';
import { Header } from '@/widgets/header/Header';
import { BatchDetailWidget } from '@/widgets/upload-history/BatchDetailWidget';

/**
 * Batch detail page
 */
export default function BatchDetailPage() {
  const { batchId } = useParams({ from: '/account/upload-history/$batchId' });
  const navigate = useNavigate();

  if (!batchId) {
    return (
      <div className="min-h-screen bg-white">
        <Header />
        <div className="container mx-auto px-4 py-8">
          <div className="rounded-lg border border-red-200 bg-red-50 p-4">
            <p className="text-sm text-red-800">Batch ID is required.</p>
          </div>
        </div>
      </div>
    );
  }

  return (
    <div className="min-h-screen bg-white">
      <Header />

      <div className="container mx-auto px-4 py-8">
        <BatchDetailWidget
          batchId={batchId}
          onBack={() => navigate({ to: '/account/upload-history' })}
        />
      </div>
    </div>
  );
}
