/**
 * DeviceVerifyPage — page-state transitions.
 *
 * The transitions (URL code → confirm, query error → error, loaded info →
 * confirm) used to live in effects (issue #77, react-hooks/set-state-in-effect);
 * they now happen during render. These tests pin the observable behaviour.
 */

import { describe, it, expect, beforeEach, vi } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import DeviceVerifyPage from './DeviceVerifyPage';
import * as deviceAuthApi from '@/features/device-auth/api/deviceAuthApi';

vi.mock('@/features/device-auth/api/deviceAuthApi');

const search: { code?: string } = {};
vi.mock('@tanstack/react-router', () => ({
  useSearch: () => search,
}));

vi.mock('@/widgets/header/Header', () => ({
  Header: () => <div data-testid="mock-header">Header</div>,
}));

const verifyInfo = {
  userCode: 'ABCD-1234',
  siteName: 'warehouse-01',
  siteDescription: 'Warehouse terminal',
  expiresAt: new Date(Date.now() + 10 * 60 * 1000).toISOString(),
};

function renderPage() {
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
  });
  return render(
    <QueryClientProvider client={queryClient}>
      <DeviceVerifyPage />
    </QueryClientProvider>
  );
}

function lookedUpCodes(): string[] {
  return vi.mocked(deviceAuthApi.getVerifyInfo).mock.calls.map(([code]) => code);
}

function httpError(status: number, description?: string) {
  return {
    response: { status, data: description ? { error_description: description } : {} },
  };
}

describe('DeviceVerifyPage', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    delete search.code;
    vi.mocked(deviceAuthApi.getVerifyInfo).mockResolvedValue(verifyInfo as never);
    vi.mocked(deviceAuthApi.approveAuthorization).mockResolvedValue({
      siteName: 'warehouse-01',
    } as never);
    vi.mocked(deviceAuthApi.denyAuthorization).mockResolvedValue(undefined as never);
  });

  it('asks for a code when the URL carries none', () => {
    renderPage();

    expect(screen.getByText('Enter Device Code')).toBeInTheDocument();
    expect(deviceAuthApi.getVerifyInfo).not.toHaveBeenCalled();
  });

  it('goes straight to confirmation when the URL carries a code', async () => {
    search.code = 'ABCD-1234';

    renderPage();

    expect(await screen.findByText('Authorize Device')).toBeInTheDocument();
    expect(screen.getByText('warehouse-01')).toBeInTheDocument();
    expect(deviceAuthApi.getVerifyInfo).toHaveBeenCalledWith('ABCD-1234');
  });

  // The direct URL is printed by a client TUI and pasted by hand, so the code
  // in it need not arrive in the presentation the backend stores (#211).
  it('normalizes a code that arrives in the URL', async () => {
    search.code = 'm9q24aml';

    renderPage();

    expect(await screen.findByText('Authorize Device')).toBeInTheDocument();
    expect(lookedUpCodes()).toEqual(['M9Q2-4AML']);
  });

  // A malformed direct URL must not leave the page on a confirmation card with
  // nothing to confirm: the lookup cannot fire for an incomplete code, so that
  // state renders neither the details nor the spinner (#211).
  it('falls back to the input state when the URL code is incomplete', () => {
    search.code = 'M9Q24AM';

    renderPage();

    expect(screen.getByText('Enter Device Code')).toBeInTheDocument();
    expect(screen.getByLabelText('Device Code')).toHaveValue('M9Q2-4AM');
    expect(lookedUpCodes()).toEqual([]);
  });

  it('moves to confirmation once a typed code resolves', async () => {
    renderPage();

    // The query fires as soon as the code is complete; the page switches to
    // the confirm state when the info arrives, without pressing Continue.
    await userEvent.click(screen.getByLabelText('Device Code'));
    await userEvent.paste('ABCD1234');

    expect(await screen.findByText('Authorize Device')).toBeInTheDocument();
    expect(lookedUpCodes()).toEqual(['ABCD-1234']);
  });

  // A user code is eight characters rendered as XXXX-XXXX, so the formatted
  // value is nine characters long. Looking one up at eight meant the last
  // keystroke was preceded by a lookup of a seven-character code, which the
  // backend answers 404 — and the global interceptor toasts "Resource not
  // found." for it while the flow goes on to succeed (issue #211).
  it('does not look up a code that is still being typed', async () => {
    renderPage();

    const field = screen.getByLabelText('Device Code');
    await userEvent.type(field, 'M9Q24AM');

    expect(field).toHaveValue('M9Q2-4AM');
    expect(lookedUpCodes()).toEqual([]);
    expect(screen.getByRole('button', { name: 'Continue' })).toBeDisabled();
  });

  it('looks a typed code up exactly once, when its last character arrives', async () => {
    renderPage();

    await userEvent.type(screen.getByLabelText('Device Code'), 'M9Q24AML');

    expect(await screen.findByText('Authorize Device')).toBeInTheDocument();
    expect(lookedUpCodes()).toEqual(['M9Q2-4AML']);
  });

  it('reports an already-processed authorization (HTTP 400)', async () => {
    search.code = 'ABCD-1234';
    vi.mocked(deviceAuthApi.getVerifyInfo).mockRejectedValue(httpError(400));

    renderPage();

    expect(await screen.findByRole('heading', { name: 'Error', level: 2 })).toBeInTheDocument();
    expect(screen.getByText(/already been processed/i)).toBeInTheDocument();
  });

  it('reports an unknown or expired code (HTTP 404)', async () => {
    search.code = 'ABCD-1234';
    vi.mocked(deviceAuthApi.getVerifyInfo).mockRejectedValue(
      httpError(404, 'Device code not found')
    );

    renderPage();

    expect(await screen.findByRole('heading', { name: 'Error', level: 2 })).toBeInTheDocument();
    expect(screen.getByText('Device code not found')).toBeInTheDocument();
  });

  it('returns to the input state from the error state', async () => {
    search.code = 'ABCD-1234';
    vi.mocked(deviceAuthApi.getVerifyInfo).mockRejectedValue(httpError(400));

    renderPage();
    await screen.findByRole('heading', { name: 'Error', level: 2 });

    await userEvent.click(screen.getByRole('button', { name: 'Try Again' }));

    expect(screen.getByText('Enter Device Code')).toBeInTheDocument();
  });

  it('approves the authorization and shows the created site', async () => {
    search.code = 'ABCD-1234';

    renderPage();
    await screen.findByText('Authorize Device');

    await userEvent.click(screen.getByRole('button', { name: 'Approve & Create Site' }));

    expect(
      await screen.findByRole('heading', { name: 'Site Created Successfully', level: 2 })
    ).toBeInTheDocument();
    await waitFor(() =>
      expect(deviceAuthApi.approveAuthorization).toHaveBeenCalledWith('ABCD-1234')
    );
  });

  it('denies the authorization', async () => {
    search.code = 'ABCD-1234';

    renderPage();
    await screen.findByText('Authorize Device');

    await userEvent.click(screen.getByRole('button', { name: 'Deny' }));

    expect(
      await screen.findByRole('heading', { name: 'Authorization Denied', level: 2 })
    ).toBeInTheDocument();
  });
});
