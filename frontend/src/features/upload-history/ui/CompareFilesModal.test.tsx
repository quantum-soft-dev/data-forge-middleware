/**
 * CompareFilesModal batch pills must render through the shared batch status
 * mapping. Regression guard for the 024-review crash: the modal passed the
 * removed shadcn variants ('default'/'secondary') to the restyled Badge and
 * threw on an undefined variantColors lookup for every batch row.
 */

import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it, vi } from 'vitest';

import { CompareFilesModal } from './CompareFilesModal';

// jsdom lacks these; Radix Popover / cmdk need them
class ResizeObserverStub {
  observe() {}
  unobserve() {}
  disconnect() {}
}
globalThis.ResizeObserver = globalThis.ResizeObserver ?? (ResizeObserverStub as never);
Element.prototype.scrollIntoView = Element.prototype.scrollIntoView ?? (() => {});

vi.mock('@tanstack/react-router', () => ({
  useNavigate: () => vi.fn(),
}));

vi.mock('@/features/file-comparison/lib/useCreateComparison', () => ({
  useCreateComparison: () => ({ mutate: vi.fn(), isPending: false }),
}));

const SITE = 'site-1';
const batch = (id: string, status: string) => ({
  id,
  siteId: SITE,
  status,
  uploadedFilesCount: 2,
  totalSize: 2048,
  hasErrors: false,
  startedAt: '2026-07-05T10:00:00Z',
  completedAt: '2026-07-05T11:00:00Z',
});

vi.mock('@/entities/batch/api/queries', () => ({
  useBatchHistory: () => ({
    data: {
      pages: [
        {
          items: [
            batch('current', 'COMPLETED'),
            batch('target-ok', 'COMPLETED'),
            batch('target-stalled', 'NOT_COMPLETED'),
          ],
        },
      ],
    },
    isLoading: false,
    fetchNextPage: vi.fn(),
    hasNextPage: false,
    isFetchingNextPage: false,
  }),
}));

describe('CompareFilesModal', () => {
  it('renders batch status pills via the shared mapping without crashing', async () => {
    const user = userEvent.setup();
    render(
      <CompareFilesModal
        isOpen
        onClose={vi.fn()}
        currentBatchId="current"
        currentSiteId={SITE}
        selectedFileIds={[]}
      />,
    );

    // Open the target batch combobox — each option renders a status Badge.
    await user.click(screen.getByRole('combobox'));

    expect(screen.getByText('Completed')).toBeInTheDocument();
    // NOT_COMPLETED renders its human label, not the raw enum string.
    expect(screen.getByText('Not completed')).toBeInTheDocument();
    expect(screen.queryByText('NOT_COMPLETED')).not.toBeInTheDocument();
  });
});
