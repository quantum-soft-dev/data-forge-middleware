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
    user: { name: 'Boris Pliss' },
    hasRole: () => false,
    isRolesLoading: false,
  });
}

function asAdmin() {
  mockUseAuth.mockReturnValue({
    user: { name: 'Boris Pliss' },
    hasRole: (r: string) => r === 'ROLE_ADMIN',
    isRolesLoading: false,
  });
}

describe('Header (floating monitoring shell per prototype, T013)', () => {
  it('renders as a floating rounded card, not a full-width bar', () => {
    asUser();
    render(<Header />);
    const header = screen.getByRole('banner');
    // outer: centered 1120px container with top margin
    expect(header.className).toContain('max-w-[1120px]');
    expect(header.className).toContain('mx-auto');
    expect(header.className).not.toContain('border-b');
    // inner: white r10 card with the panel shadow (prototype: radius 10, shadow 0.02/0.16)
    const card = header.firstElementChild as HTMLElement;
    expect(card.className).toContain('rounded-[10px]');
    expect(card.className).toContain('shadow-panel');
    expect(card.className).toContain('bg-white');
  });

  it('renders the logo title 17px/600 with tight tracking', () => {
    asUser();
    render(<Header />);
    const title = screen.getByText('DataForge Middleware');
    expect(title.className).toContain('text-[17px]');
    expect(title.className).toContain('font-semibold');
    expect(title.className).toContain('tracking-[-0.24px]');
  });

  it('renders nav links as hover pills (14px/400, ink-secondary)', () => {
    asUser();
    render(<Header />);
    const link = screen.getByRole('link', { name: 'Dashboard' });
    expect(link.className).toContain('rounded-lg');
    expect(link.className).toContain('px-3');
    expect(link.className).toContain('text-sm');
    expect(link.className).toContain('text-ink-secondary');
    expect(link.className).toContain('hover:bg-surface-subtle');
    expect(link.className).toContain('hover:text-ink');
    expect(link.className).not.toContain('font-medium');
  });

  it('renders the user avatar with initials on brand-50', () => {
    asUser();
    render(<Header />);
    const avatar = screen.getByTitle('Boris Pliss');
    expect(avatar.textContent).toBe('BP');
    expect(avatar.className).toContain('rounded-full');
    expect(avatar).toHaveStyle({
      background: monitoringTokens.blue50,
      color: monitoringTokens.primary,
    });
  });

  it('renders the Admin chip as an info alpha pill for admins', () => {
    asAdmin();
    render(<Header />);
    const chip = screen.getByText('Admin');
    expect(chip.className).toContain('rounded-full');
    expect(chip).toHaveStyle({ background: monitoringTokens.blue50 });
  });

  it('keeps role-based navigation behavior unchanged', () => {
    asAdmin();
    render(<Header />);
    expect(screen.getByRole('link', { name: 'User Management' })).toBeInTheDocument();
    expect(screen.queryByRole('link', { name: 'Site Management' })).not.toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Sign Out' })).toBeInTheDocument();
  });
});
