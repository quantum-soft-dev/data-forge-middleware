import { render, screen } from '@testing-library/react';
import { describe, expect, it } from 'vitest';

import { PageHeader } from '@/shared/ui/page-header';

describe('PageHeader (T012)', () => {
  it('renders the 22px/500 monitoring title with tight tracking', () => {
    render(<PageHeader title="Dashboard" />);
    const title = screen.getByRole('heading', { name: 'Dashboard' });
    expect(title.className).toContain('text-[22px]');
    expect(title.className).toContain('font-medium');
    expect(title.className).toContain('tracking-[-0.33px]');
    expect(title.className).toContain('text-ink');
  });

  it('renders the secondary subtitle when provided', () => {
    render(<PageHeader title="Sites" subtitle="Manage your registered sites" />);
    const sub = screen.getByText('Manage your registered sites');
    expect(sub.className).toContain('text-sm');
    expect(sub.className).toContain('text-ink-secondary');
  });

  it('renders actions and breadcrumb slots', () => {
    render(
      <PageHeader
        title="Site"
        breadcrumb={<a href="/sites">All sites</a>}
        actions={<button type="button">Create</button>}
      />,
    );
    expect(screen.getByRole('link', { name: 'All sites' })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Create' })).toBeInTheDocument();
  });

  it('renders no subtitle node when omitted', () => {
    const { container } = render(<PageHeader title="Plain" />);
    expect(container.querySelectorAll('p')).toHaveLength(0);
  });
});
