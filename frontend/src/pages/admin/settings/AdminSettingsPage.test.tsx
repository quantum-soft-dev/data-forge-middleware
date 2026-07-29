/**
 * AdminSettingsPage — the cron editor is seeded from the loaded schedule.
 *
 * The seeding used to run in an effect (issue #77,
 * react-hooks/set-state-in-effect); it now happens during render, so the field
 * must never be shown with the stale value once the query resolves.
 */

import { describe, it, expect, beforeEach, vi } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import AdminSettingsPage from './AdminSettingsPage';
import * as settingsApi from '@/entities/settings/api/settingsApi';

vi.mock('@/entities/settings/api/settingsApi');

vi.mock('@/widgets/header/Header', () => ({
  Header: () => <div data-testid="mock-header">Header</div>,
}));

vi.mock('sonner', () => ({
  toast: { success: vi.fn(), error: vi.fn() },
}));

function renderPage() {
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
  });
  return render(
    <QueryClientProvider client={queryClient}>
      <AdminSettingsPage />
    </QueryClientProvider>
  );
}

describe('AdminSettingsPage', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    vi.mocked(settingsApi.getBatchRetentionSchedule).mockResolvedValue({
      cron: '0 0 2 * * *',
      source: 'DB',
      updatedAt: '2026-01-01T00:00:00Z',
    });
    vi.mocked(settingsApi.updateBatchRetentionSchedule).mockResolvedValue({
      cron: '0 0 5 * * *',
      source: 'DB',
      updatedAt: '2026-01-02T00:00:00Z',
    });
  });

  /** Waits for the query to resolve and the field to be seeded from it. */
  async function seededCronInput() {
    const input = screen.getByLabelText('Cron');
    await waitFor(() => expect(input).toHaveValue('0 0 2 * * *'));
    return input;
  }

  it('seeds the cron field from the loaded schedule', async () => {
    renderPage();

    await seededCronInput();
    expect(screen.getByText(/Source: DB/)).toBeInTheDocument();
  });

  it('keeps the edited value while the loaded schedule stays the same', async () => {
    renderPage();

    const input = await seededCronInput();
    await userEvent.clear(input);
    await userEvent.type(input, '0 0 5 * * *');

    await userEvent.click(screen.getByRole('button', { name: 'Refresh' }));

    await waitFor(() =>
      expect(settingsApi.getBatchRetentionSchedule).toHaveBeenCalledTimes(2)
    );
    expect(screen.getByLabelText('Cron')).toHaveValue('0 0 5 * * *');
  });

  it('saves the trimmed cron expression', async () => {
    renderPage();

    const input = await seededCronInput();
    await userEvent.clear(input);
    await userEvent.type(input, '  0 0 5 * * *  ');
    await userEvent.click(screen.getByRole('button', { name: 'Save' }));

    await waitFor(() =>
      expect(settingsApi.updateBatchRetentionSchedule).toHaveBeenCalledWith('0 0 5 * * *')
    );
  });

  it('refuses to save an empty cron expression', async () => {
    renderPage();

    const input = await seededCronInput();
    await userEvent.clear(input);
    await userEvent.click(screen.getByRole('button', { name: 'Save' }));

    expect(settingsApi.updateBatchRetentionSchedule).not.toHaveBeenCalled();
  });
});
