/**
 * SiteList — admin vs. user wiring.
 *
 * The widget picks between the admin and the user mutation depending on the
 * `accountId` prop. Both sets of hooks must be called on every render (issue
 * #77, react-hooks/rules-of-hooks): calling them behind a ternary makes the
 * hook order depend on a prop.
 */

import { describe, it, expect, beforeEach, vi } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { SiteList } from './SiteList';
import * as siteApi from '@/entities/site/api/siteApi';
import type { Site } from '@/entities/site/model/types';

vi.mock('@/entities/site/api/siteApi');

vi.mock('@tanstack/react-router', () => ({
  useNavigate: () => vi.fn(),
}));

vi.mock('@/features/delta-sync/api/queries', () => ({
  useDeltaSyncHealth: () => ({ data: [], isLoading: false }),
}));

vi.mock('sonner', () => ({
  toast: { success: vi.fn(), error: vi.fn() },
}));

const site: Site = {
  id: 'site-1',
  accountId: 'account-1',
  siteName: 'example.com',
  name: 'Example Site',
  isActive: true,
  retentionDays: 45,
  createdAt: '2024-01-01T00:00:00Z',
  siteType: 'DBF',
  clientApiVersion: 'V2',
};

function renderList(props: { accountId?: string }) {
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false } },
  });
  return render(
    <QueryClientProvider client={queryClient}>
      <SiteList {...props} />
    </QueryClientProvider>
  );
}

describe('SiteList', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    vi.mocked(siteApi.listUserSites).mockResolvedValue([site]);
    vi.mocked(siteApi.listAdminSites).mockResolvedValue([site]);
    vi.mocked(siteApi.deactivateUserSite).mockResolvedValue({ ...site, isActive: false });
    vi.mocked(siteApi.deactivateAdminSite).mockResolvedValue({ ...site, isActive: false });
    vi.mocked(siteApi.deleteUserSite).mockResolvedValue(undefined);
    vi.mocked(siteApi.deleteAdminSite).mockResolvedValue(undefined);
    vi.mocked(siteApi.updateAdminSiteRetention).mockResolvedValue({ ...site, retentionDays: 30 });
  });

  describe('user context (no accountId)', () => {
    it('deactivates through the user endpoint', async () => {
      renderList({});
      await screen.findByText('Example Site');

      await userEvent.click(screen.getByRole('button', { name: 'Deactivate' }));
      await userEvent.click(await screen.findByRole('button', { name: 'Deactivate Site' }));

      await waitFor(() => expect(siteApi.deactivateUserSite).toHaveBeenCalledWith('site-1'));
      expect(siteApi.deactivateAdminSite).not.toHaveBeenCalled();
    });

    it('hides the retention controls', async () => {
      renderList({});
      await screen.findByText('Example Site');

      expect(screen.queryByRole('spinbutton')).not.toBeInTheDocument();
    });
  });

  describe('admin context (accountId provided)', () => {
    it('deactivates through the admin endpoint', async () => {
      renderList({ accountId: 'account-1' });
      await screen.findByText('Example Site');

      await userEvent.click(screen.getByRole('button', { name: 'Deactivate' }));
      await userEvent.click(await screen.findByRole('button', { name: 'Deactivate Site' }));

      await waitFor(() =>
        expect(siteApi.deactivateAdminSite).toHaveBeenCalledWith('account-1', 'site-1')
      );
      expect(siteApi.deactivateUserSite).not.toHaveBeenCalled();
    });

    it('saves the retention policy through the admin endpoint', async () => {
      renderList({ accountId: 'account-1' });
      await screen.findByText('Example Site');

      const input = await screen.findByRole('spinbutton');
      await userEvent.clear(input);
      await userEvent.type(input, '30');
      await userEvent.click(screen.getByRole('button', { name: /save/i }));

      await waitFor(() => expect(siteApi.updateAdminSiteRetention).toHaveBeenCalledWith('site-1', 30));
    });
  });
});
