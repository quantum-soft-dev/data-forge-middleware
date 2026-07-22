import { render, screen } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';

import { GlobalErrorItem } from './GlobalErrorItem';
import { severityTokens } from '@/shared/ui/tokens';
import type { GlobalErrorSummary } from '../model/global-error.types';

function makeError(overrides: Partial<GlobalErrorSummary> = {}): GlobalErrorSummary {
  return {
    id: 'e1',
    title: 'Sync failed',
    type: 'SYNC',
    severity: 'ERROR',
    siteName: 'store-1',
    occurredAt: new Date().toISOString(),
    isRead: false,
    ...overrides,
  } as GlobalErrorSummary;
}

const noop = vi.fn();

describe('GlobalErrorItem (monitoring severity pills, T016)', () => {
  it.each([
    ['CRITICAL', severityTokens.critical.bg],
    ['ERROR', severityTokens.critical.bg],
    ['WARNING', severityTokens.elevated.bg],
  ] as const)('renders %s as an alpha severity pill', (severity, bg) => {
    render(
      <GlobalErrorItem
        error={makeError({ severity })}
        selected={false}
        onSelect={noop}
        onClick={noop}
      />,
    );
    expect(screen.getByText(severity)).toHaveStyle({ background: bg });
  });

  it('renders rows with hairline separators and monitoring hover/unread treatment', () => {
    const { container } = render(
      <GlobalErrorItem error={makeError()} selected={false} onSelect={noop} onClick={noop} />,
    );
    const row = container.firstElementChild as HTMLElement;
    expect(row.className).toContain('border-separator');
    expect(row.className).toContain('hover:bg-surface-hover');
    expect(row.className).toContain('bg-brand-50/50'); // unread tint
    expect(row.className).not.toContain('hover:bg-gray-50');
  });

  it('renders the unread dot in brand color', () => {
    render(<GlobalErrorItem error={makeError()} selected={false} onSelect={noop} onClick={noop} />);
    expect(screen.getByLabelText('Unread').className).toContain('bg-brand');
  });
});
