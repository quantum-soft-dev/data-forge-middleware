import { render, screen } from '@testing-library/react';
import { describe, expect, it } from 'vitest';

import { Card, CardContent, CardHeader, CardTitle } from '@/shared/ui/ui/card';

describe('Card (monitoring treatment, T006)', () => {
  it('renders borderless white card with the layered monitoring shadow', () => {
    render(<Card data-testid="card">body</Card>);
    const card = screen.getByTestId('card');
    expect(card.className).toContain('shadow-card');
    expect(card.className).toContain('bg-card');
    expect(card.className).toContain('rounded-lg'); // --radius is 10px (T002)
    expect(card.className).not.toContain('shadow-sm');
    expect(card.className).not.toMatch(/(^|\s)border(\s|$)/);
  });

  it('renders CardTitle as 15px/500 monitoring section title', () => {
    render(<CardTitle>Checkpoints</CardTitle>);
    const title = screen.getByText('Checkpoints');
    expect(title.className).toContain('text-[15px]');
    expect(title.className).toContain('font-medium');
    expect(title.className).toContain('text-ink-title');
    expect(title.className).toContain('tracking-[-0.24px]');
    expect(title.className).not.toContain('text-2xl');
    expect(title.className).not.toContain('font-semibold');
  });

  it('uses the exemplar 16px padding scale', () => {
    render(
      <Card>
        <CardHeader data-testid="header">h</CardHeader>
        <CardContent data-testid="content">c</CardContent>
      </Card>,
    );
    expect(screen.getByTestId('header').className).toContain('p-4');
    expect(screen.getByTestId('content').className).toContain('p-4');
  });
});
