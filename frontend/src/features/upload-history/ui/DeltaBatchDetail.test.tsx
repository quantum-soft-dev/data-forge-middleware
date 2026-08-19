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

  it('defensively aggregates duplicate table entries into one row and one download', () => {
    render(
      <DeltaBatchDetail
        batch={makeBatch({
          deltaStats: [
            { table: 'orders', inserts: 10, updates: 2, deletes: 1 },
            { table: 'orders', inserts: 5, updates: 4, deletes: 2 },
          ],
        })}
      />,
    );

    expect(screen.getAllByTestId('delta-stats-row-orders')).toHaveLength(1);
    expect(screen.getAllByRole('button', { name: 'Parquet' })).toHaveLength(1);
    expect(screen.getByTestId('delta-stats-row-orders')).toHaveTextContent('+15');
    expect(screen.getByTestId('delta-stats-row-orders')).toHaveTextContent('6');
    expect(screen.getByTestId('delta-stats-row-orders')).toHaveTextContent('−3');
    expect(screen.getByTestId('delta-stats-total-row')).toHaveTextContent('24');
  });

  it('colors the status circle from shared tokens per severity (stalled vs critical)', () => {
    // CSSOM normalizes color strings — run tokens through the same normalization
    const css = (color: string) => {
      const el = document.createElement('div');
      el.style.background = color;
      return el.style.background;
    };
    const circle = (batch: BatchDetail) => {
      const { container, unmount } = render(<DeltaBatchDetail batch={batch} />);
      const el = container.querySelector('.h-10.w-10') as HTMLElement;
      const bg = el.style.background;
      unmount();
      return bg;
    };

    expect(circle(makeBatch())).toBe(css(severityTokens.healthy.bg));
    expect(circle(makeBatch({ status: 'IN_PROGRESS', completedAt: null }))).toBe(css(monitoringTokens.blue50));
    expect(circle(makeBatch({ status: 'FAILED' }))).toBe(css(severityTokens.critical.bg));
    expect(circle(makeBatch({ status: 'NOT_COMPLETED' }))).toBe(css(severityTokens.stalled.bg));
    expect(circle(makeBatch({ status: 'CANCELLED' }))).toBe(css(severityTokens.stalled.bg));
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

  it('says the file is not due yet while the session is in progress (issue #213)', () => {
    // The File column reads the unified completed-batch artifact (036), which is enqueued on
    // BATCH_COMPLETED — so for the whole life of a CONTINUOUS session there is nothing to link to.
    // A bare em dash said "missing" for "not built yet", and a QA operator read it as a lost file.
    render(<DeltaBatchDetail batch={makeBatch({ status: 'IN_PROGRESS', completedAt: null })} />);

    const pending = screen.getAllByTestId('delta-file-pending');
    expect(pending).toHaveLength(2);
    expect(pending[0]).toHaveTextContent('After session');
    expect(pending[0].title).toMatch(/when the session ends/i);
    expect(screen.getByText(/built when the session ends/i)).toBeInTheDocument();
  });

  it('does not promise a file for a session that ended without completing', () => {
    // Nothing enqueues an artifact for a failed session, so "after session" would be a promise
    // nothing keeps.
    render(<DeltaBatchDetail batch={makeBatch({ status: 'FAILED' })} />);

    expect(screen.queryByTestId('delta-file-pending')).not.toBeInTheDocument();
    expect(screen.queryByText(/built when the session ends/i)).not.toBeInTheDocument();
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
