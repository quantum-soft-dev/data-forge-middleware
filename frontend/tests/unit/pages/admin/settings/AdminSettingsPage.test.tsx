import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import AdminSettingsPage from '@/pages/admin/settings/AdminSettingsPage';
import { toast } from 'sonner';
import * as settings from '@/entities/settings';

vi.mock('@/widgets/header/Header', () => ({
  Header: () => <header data-testid="header">Header</header>,
}));

vi.mock('sonner', () => ({
  toast: {
    success: vi.fn(),
    error: vi.fn(),
  },
}));

vi.mock('@/entities/settings', () => ({
  getBatchRetentionSchedule: vi.fn(),
  updateBatchRetentionSchedule: vi.fn(),
}));

const renderWithQueryClient = (ui: React.ReactElement) => {
  const queryClient = new QueryClient({
    defaultOptions: {
      queries: { retry: false },
      mutations: { retry: false },
    },
  });

  return render(
    <QueryClientProvider client={queryClient}>
      {ui}
    </QueryClientProvider>
  );
};

describe('AdminSettingsPage', () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });

  it('loads and displays current cron value', async () => {
    vi.mocked(settings.getBatchRetentionSchedule).mockResolvedValue({
      cron: '0 0 2 * * *',
      source: 'CONFIG',
      updatedAt: null,
    } as any);

    renderWithQueryClient(<AdminSettingsPage />);

    expect(screen.getByTestId('header')).toBeInTheDocument();

    const input = await screen.findByLabelText('Cron');
    await waitFor(() => {
      expect(input).toHaveValue('0 0 2 * * *');
    });
  });

  it('submits updated cron and shows success toast', async () => {
    vi.mocked(settings.getBatchRetentionSchedule).mockResolvedValue({
      cron: '0 0 2 * * *',
      source: 'CONFIG',
      updatedAt: null,
    } as any);

    vi.mocked(settings.updateBatchRetentionSchedule).mockResolvedValue({
      cron: '0 0 3 * * *',
      source: 'DB',
      updatedAt: '2026-02-08T00:00:00Z',
    } as any);

    renderWithQueryClient(<AdminSettingsPage />);

    const input = await screen.findByLabelText('Cron');
    await waitFor(() => {
      expect(input).not.toBeDisabled();
      expect(input).toHaveValue('0 0 2 * * *');
    });
    await userEvent.clear(input);
    await userEvent.type(input, '0 0 3 * * *');

    await userEvent.click(screen.getByRole('button', { name: 'Save' }));

    await waitFor(() => {
      expect(settings.updateBatchRetentionSchedule).toHaveBeenCalledWith('0 0 3 * * *');
      expect(toast.success).toHaveBeenCalled();
    });
  });
});
