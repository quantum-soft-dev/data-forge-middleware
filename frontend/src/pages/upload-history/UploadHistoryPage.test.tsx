import { render, screen } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';

import UploadHistoryPage from '@/pages/upload-history/UploadHistoryPage';

vi.mock('@/widgets/header/Header', () => ({ Header: () => <div data-testid="header" /> }));
vi.mock('@/widgets/upload-history/BatchListWidget', () => ({
  BatchListWidget: () => <div data-testid="batch-list" />,
}));

describe('UploadHistoryPage (monitoring scaffold, T021)', () => {
  it('renders the monitoring scaffold with PageHeader', () => {
    const { container } = render(<UploadHistoryPage />);
    const scaffold = container.firstElementChild as HTMLElement;
    expect(scaffold.className).toContain('bg-white');
    expect(scaffold.className).not.toContain('bg-gray-50');

    const title = screen.getByRole('heading', { name: 'Upload History' });
    expect(title.className).toContain('text-[22px]');
    expect(title.className).not.toContain('text-3xl');
    expect(screen.getByTestId('batch-list')).toBeInTheDocument();
  });
});
