import { render, screen } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';

import DashboardPage from '@/pages/dashboard/DashboardPage';

vi.mock('@/widgets/header/Header', () => ({ Header: () => <div data-testid="header" /> }));
vi.mock('@/widgets/dashboard-charts', () => ({
  DashboardCharts: () => <div data-testid="charts" />,
}));
vi.mock('@/widgets/global-errors/GlobalErrorsWidget', () => ({
  GlobalErrorsWidget: () => <div data-testid="global-errors" />,
}));
vi.mock('@/entities/user-session/api/useAuth', () => ({
  useAuth: () => ({ hasRole: () => false, isRolesLoading: false }),
}));

describe('DashboardPage (monitoring scaffold, T014)', () => {
  it('renders the monitoring page scaffold and PageHeader', () => {
    const { container } = render(<DashboardPage />);
    const scaffold = container.firstElementChild as HTMLElement;
    expect(scaffold.className).toContain('bg-surface-hover');
    expect(scaffold.className).not.toContain('bg-gray-50');

    const title = screen.getByRole('heading', { name: 'Dashboard' });
    expect(title.className).toContain('text-[22px]');
    expect(title.className).toContain('font-medium');
    expect(title.className).not.toContain('text-3xl');
  });

  it('keeps widget composition unchanged for regular users', () => {
    render(<DashboardPage />);
    expect(screen.getByTestId('header')).toBeInTheDocument();
    expect(screen.getByTestId('charts')).toBeInTheDocument();
    expect(screen.getByTestId('global-errors')).toBeInTheDocument();
  });
});
