import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { beforeEach, describe, expect, it, vi } from 'vitest';

import { DeltaBatchDetail } from './DeltaBatchDetail';
import { severityTokens } from '@/shared/ui/tokens';
import type { BatchDetail } from '@/entities/batch/model/types';

const presignBatchTableParquet = vi.fn();
vi.mock('@/features/delta-sync/api/deltaSyncApi', () => ({
  presignBatchTableParquet: (...args: unknown[]) => presignBatchTableParquet(...args),
}));

const toastError = vi.fn();
vi.mock('sonner', () => ({
  toast: { error: (...args: unknown[]) => toastError(...args) },
}));

function makeBatch(overrides: Partial<BatchDetail> = {}): BatchDetail {
  return {
    id: 'c4bdde2b-7105-42d7-944d-06fd13b0c66d',
    siteId: '139dc0a0-7743-4d6c-9823-d6925a4c9f74',
    status: 'COMPLETED',
    startedAt: '2026-07-05T17:30:04Z',
    completedAt: '2026-07-05T17:30:04Z',
    uploadedFilesCount: 0,
    totalSize: 0,
    hasErrors: false,
    files: [],
    deltaTableCount: 2,
    deltaRecordCount: 37,
    deltaStats: [
      { table: 'orders', inserts: 2, updates: 0, deletes: 1 },
      { table: 'items', inserts: 16, updates: 0, deletes: 16 },
    ],
    seqRange: { first: 879446, last: 879482 },
    ...overrides,
  } as BatchDetail;
}

describe('DeltaBatchDetail — delta Parquet downloads (025)', () => {
  beforeEach(() => {
    presignBatchTableParquet.mockReset();
    toastError.mockReset();
    vi.spyOn(window, 'open').mockImplementation(() => null);
  });

  it('renders a Parquet download pill per table row for a completed session', () => {
    render(<DeltaBatchDetail batch={makeBatch()} />);
    const pills = screen.getAllByRole('button', { name: 'Parquet' });
    expect(pills).toHaveLength(2);
  });

  it('presigns and opens the file when a pill is clicked', async () => {
    presignBatchTableParquet.mockResolvedValue({
      downloadUrl: 'https://s3/egress/orders.parquet',
      fileName: 'orders_seq879446-879482.parquet',
      expiresAt: '2026-07-05T18:00:00Z',
    });
    const user = userEvent.setup();
    render(<DeltaBatchDetail batch={makeBatch()} />);

    const row = screen.getByTestId('delta-stats-row-orders');
    await user.click(row.querySelector('button')!);

    expect(presignBatchTableParquet).toHaveBeenCalledWith(
      '139dc0a0-7743-4d6c-9823-d6925a4c9f74',
      'c4bdde2b-7105-42d7-944d-06fd13b0c66d',
      'orders',
      { admin: false },
    );
    await waitFor(() =>
      expect(window.open).toHaveBeenCalledWith('https://s3/egress/orders.parquet', '_blank', 'noopener'),
    );
  });

  it('shows an error toast when the file was never egressed (404)', async () => {
    presignBatchTableParquet.mockRejectedValue({ response: { status: 404 } });
    const user = userEvent.setup();
    render(<DeltaBatchDetail batch={makeBatch()} />);

    await user.click(screen.getByTestId('delta-stats-row-items').querySelector('button')!);

    await waitFor(() => expect(toastError).toHaveBeenCalled());
    expect(window.open).not.toHaveBeenCalled();
  });

  it('renders no download pills while the session is in progress', () => {
    render(<DeltaBatchDetail batch={makeBatch({ status: 'IN_PROGRESS', completedAt: null })} />);
    expect(screen.queryByRole('button', { name: 'Parquet' })).not.toBeInTheDocument();
  });

  it('renders the status pill with severity colors (regression: transparent pill)', () => {
    render(<DeltaBatchDetail batch={makeBatch()} />);
    const pill = screen
      .getAllByText('Completed')
      .find((el) => el.className.includes('rounded-full'))!;
    expect(pill).toHaveStyle({
      background: severityTokens.healthy.bg,
      color: severityTokens.healthy.text,
    });
  });
});
