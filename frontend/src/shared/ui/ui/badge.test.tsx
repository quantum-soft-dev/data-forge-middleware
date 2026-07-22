import { render, screen } from '@testing-library/react';
import { describe, expect, it } from 'vitest';

import { Badge } from '@/shared/ui/ui/badge';
import { monitoringTokens, severityTokens } from '@/shared/ui/tokens';

describe('Badge (monitoring pills, T004)', () => {
  it('renders the pill layout: rounded-full, 12px/500, no focus ring', () => {
    render(<Badge>Active</Badge>);
    const badge = screen.getByText('Active');
    expect(badge.className).toContain('rounded-full');
    expect(badge.className).toContain('text-xs');
    expect(badge.className).toContain('font-medium');
    expect(badge.className).not.toContain('font-semibold');
    expect(badge.className).not.toContain('focus:ring');
  });

  it.each([
    ['success', severityTokens.healthy.bg, severityTokens.healthy.text],
    ['warning', severityTokens.elevated.bg, severityTokens.elevated.text],
    ['critical', severityTokens.critical.bg, severityTokens.critical.text],
    ['stalled', severityTokens.stalled.bg, severityTokens.stalled.text],
  ] as const)('renders %s as alpha pill from severityTokens', (variant, bg, text) => {
    render(<Badge variant={variant}>{variant}</Badge>);
    const badge = screen.getByText(variant);
    expect(badge.style.background).not.toBe('');
    expect(badge).toHaveStyle({ background: bg, color: text });
  });

  it('renders info with brand-blue alpha treatment', () => {
    render(<Badge variant="info">v2</Badge>);
    expect(screen.getByText('v2')).toHaveStyle({
      background: monitoringTokens.blue50,
      color: monitoringTokens.primary,
    });
  });

  it('renders neutral with subtle background and secondary text', () => {
    render(<Badge variant="neutral">Inactive</Badge>);
    expect(screen.getByText('Inactive')).toHaveStyle({
      background: monitoringTokens.subtleBg,
      color: monitoringTokens.textSecondary,
    });
  });

  it('renders outline with hairline border and no fill', () => {
    render(<Badge variant="outline">DBF</Badge>);
    const badge = screen.getByText('DBF');
    expect(badge.className).toContain('border-hairline');
    expect(badge.style.background).toBe('');
  });

  it('renders a 6px leading dot when dot is set', () => {
    render(<Badge variant="success" dot data-testid="pill" />);
    const dot = screen.getByTestId('pill').querySelector('span');
    expect(dot).not.toBeNull();
    expect(dot!.className).toContain('h-1.5');
    expect(dot!.className).toContain('w-1.5');
    expect(dot!.className).toContain('rounded-full');
    expect(dot).toHaveStyle({ background: severityTokens.healthy.dot });
  });

  it('renders no dot by default', () => {
    render(<Badge data-testid="plain">x</Badge>);
    expect(screen.getByTestId('plain').querySelector('span')).toBeNull();
  });
});
