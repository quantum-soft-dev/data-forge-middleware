import { render, screen } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';

import { Header } from '@/widgets/header/Header';
import { monitoringTokens } from '@/shared/ui/tokens';

const mockUseAuth = vi.fn();

vi.mock('@/entities/user-session/api/useAuth', () => ({
  useAuth: () => mockUseAuth(),
}));

vi.mock('@auth0/auth0-react', () => ({
  useAuth0: () => ({ logout: vi.fn(), isAuthenticated: true }),
}));

vi.mock('@tanstack/react-router', () => ({
  // eslint-disable-next-line @typescript-eslint/no-explicit-any
  Link: ({ children, activeProps: _activeProps, to, ...props }: any) => (
    <a href={to} {...props}>
      {children}
    </a>
  ),
}));

function asUser() {
  mockUseAuth.mockReturnValue({
    user: { name: 'Boris' },
    hasRole: () => false,
    isRolesLoading: false,
  });
}

function asAdmin() {
  mockUseAuth.mockReturnValue({
    user: { name: 'Boris' },
    hasRole: (r: string) => r === 'ROLE_ADMIN',
    isRolesLoading: false,
  });
}

describe('Header (monitoring shell, T013)', () => {
  it('renders with a hairline bottom border and no legacy shadow', () => {
    asUser();
    render(<Header />);
    const header = screen.getByRole('banner');
    expect(header.className).toContain('border-separator');
    expect(header.className).not.toContain('border-gray-200');
    expect(header.className).not.toContain('shadow-sm');
  });

  it('renders nav links in ink-secondary with brand active treatment', () => {
    asUser();
    render(<Header />);
    const link = screen.getByRole('link', { name: 'Dashboard' });
    expect(link.className).toContain('text-ink-secondary');
    expect(link.className).toContain('hover:text-ink');
    expect(link.className).not.toContain('text-gray-700');
  });

  it('renders the Admin chip as an info alpha pill', () => {
    asAdmin();
    render(<Header />);
    const chip = screen.getByText('Admin');
    expect(chip.className).toContain('rounded-full');
    expect(chip).toHaveStyle({ background: monitoringTokens.blue50 });
    expect(chip.className).not.toContain('bg-blue-100');
  });

  it('keeps role-based navigation behavior unchanged', () => {
    asAdmin();
    render(<Header />);
    expect(screen.getByRole('link', { name: 'User Management' })).toBeInTheDocument();
    expect(screen.queryByRole('link', { name: 'Site Management' })).not.toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Sign Out' })).toBeInTheDocument();
  });
});
