import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { beforeEach, describe, expect, it, vi } from 'vitest';

import { DeltaBatchDetail } from './DeltaBatchDetail';
import { monitoringTokens, severityTokens } from '@/shared/ui/tokens';
import type { BatchDetail } from '@/entities/batch/model/types';

const presignBatchTableParquet = vi.fn();
vi.mock('@/features/delta-sync/api/deltaSyncApi', () => ({
  presignBatchTableParquet: (...args: unknown[]) => presignBatchTableParquet(...args),
}));

const toastError = vi.fn();
const toastSuccess = vi.fn();
vi.mock('sonner', () => ({
  toast: {
    error: (...args: unknown[]) => toastError(...args),
    success: (...args: unknown[]) => toastSuccess(...args),
  },
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

const statusPill = (label: string) =>
  screen.getAllByText(label).find((el) => el.className.includes('rounded-full'))!;

describe('DeltaBatchDetail — delta Parquet downloads (025)', () => {
  beforeEach(() => {
    presignBatchTableParquet.mockReset();
    toastError.mockReset();
    toastSuccess.mockReset();
  });

  it('renders a Parquet download pill per table row for a completed session', () => {
    render(<DeltaBatchDetail batch={makeBatch()} />);
    expect(screen.getAllByRole('button', { name: 'Parquet' })).toHaveLength(2);
  });

  it('presigns through the owner route and starts a same-tab anchor download', async () => {
    presignBatchTableParquet.mockResolvedValue({
      downloadUrl: 'https://s3/egress/orders.parquet',
      fileName: 'orders_seq879446-879482.parquet',
      expiresAt: '2026-07-05T18:00:00Z',
    });
    const anchorClick = vi.spyOn(HTMLAnchorElement.prototype, 'click').mockImplementation(() => {});
    const user = userEvent.setup();
    render(<DeltaBatchDetail batch={makeBatch()} />);

    await user.click(screen.getByTestId('delta-stats-row-orders').querySelector('button')!);

    expect(presignBatchTableParquet).toHaveBeenCalledWith(
      '139dc0a0-7743-4d6c-9823-d6925a4c9f74',
      'c4bdde2b-7105-42d7-944d-06fd13b0c66d',
      'orders',
    );
    await waitFor(() => expect(anchorClick).toHaveBeenCalled());
    expect(toastSuccess).toHaveBeenCalled();
    anchorClick.mockRestore();
  });

  it('shows the schema explanation only for a 404', async () => {
    presignBatchTableParquet.mockRejectedValue({
      isAxiosError: true,
      response: { status: 404 },
    });
    const user = userEvent.setup();
    render(<DeltaBatchDetail batch={makeBatch()} />);

    await user.click(screen.getByTestId('delta-stats-row-items').querySelector('button')!);

    await waitFor(() => expect(toastError).toHaveBeenCalled());
    expect(String(toastError.mock.calls[0][0])).toMatch(/schema|egress/i);
  });

  it('shows a generic retry toast for non-404 failures (e.g. S3 outage → 503)', async () => {
    presignBatchTableParquet.mockRejectedValue({
      isAxiosError: true,
      response: { status: 503 },
    });
    const user = userEvent.setup();
    render(<DeltaBatchDetail batch={makeBatch()} />);

    await user.click(screen.getByTestId('delta-stats-row-items').querySelector('button')!);

    await waitFor(() => expect(toastError).toHaveBeenCalled());
    expect(String(toastError.mock.calls[0][0])).not.toMatch(/schema/i);
  });

  it('disables the pill while a presign request is in flight', async () => {
    let resolve!: (v: unknown) => void;
    presignBatchTableParquet.mockReturnValue(new Promise((r) => (resolve = r)));
    const anchorClick = vi.spyOn(HTMLAnchorElement.prototype, 'click').mockImplementation(() => {});
    const user = userEvent.setup();
    render(<DeltaBatchDetail batch={makeBatch()} />);

    const button = screen.getByTestId('delta-stats-row-orders').querySelector('button')!;
    await user.click(button);
    expect(button).toBeDisabled();

    resolve({ downloadUrl: 'u', fileName: 'f', expiresAt: 'e' });
    await waitFor(() => expect(button).not.toBeDisabled());
    anchorClick.mockRestore();
  });

  it('renders no download pills while the session is in progress', () => {
    render(<DeltaBatchDetail batch={makeBatch({ status: 'IN_PROGRESS', completedAt: null })} />);
    expect(screen.queryByRole('button', { name: 'Parquet' })).not.toBeInTheDocument();
  });

  it('renders the status pill via the shared mapping (Completed = healthy)', () => {
    render(<DeltaBatchDetail batch={makeBatch()} />);
    expect(statusPill('Completed')).toHaveStyle({
      background: severityTokens.healthy.bg,
      color: severityTokens.healthy.text,
    });
  });

  it('renders COMPLETED_WITH_WARNINGS amber, consistent with the batch list', () => {
    render(<DeltaBatchDetail batch={makeBatch({ status: 'COMPLETED_WITH_WARNINGS' })} />);
    expect(statusPill('Completed (Warnings)')).toHaveStyle({
      background: severityTokens.elevated.bg,
      color: severityTokens.elevated.text,
    });
  });

  it('renders IN_PROGRESS as an info pill', () => {
    render(<DeltaBatchDetail batch={makeBatch({ status: 'IN_PROGRESS', completedAt: null })} />);
    expect(statusPill('In progress')).toHaveStyle({
      background: monitoringTokens.blue50,
      color: monitoringTokens.primary,
    });
  });
});
