import { render, screen } from '@testing-library/react';
import { describe, expect, it } from 'vitest';

import { Alert, AlertDescription } from '@/shared/ui/ui/alert';
import { Separator } from '@/shared/ui/ui/separator';
import { Skeleton } from '@/shared/ui/ui/skeleton';

describe('feedback primitives (monitoring style, T011)', () => {
  it('Skeleton pulses on the subtle surface', () => {
    render(<Skeleton data-testid="sk" className="h-24 w-full" />);
    const sk = screen.getByTestId('sk');
    expect(sk.className).toContain('animate-pulse');
    expect(sk.className).toContain('bg-surface-subtle');
    expect(sk.className).toContain('rounded-lg');
    expect(sk.className).not.toContain('bg-muted');
  });

  it('Alert default renders as a borderless subtle panel', () => {
    render(
      <Alert data-testid="alert">
        <AlertDescription>No sites yet</AlertDescription>
      </Alert>,
    );
    const alert = screen.getByTestId('alert');
    expect(alert.className).toContain('bg-surface-subtle');
    expect(alert.className).not.toMatch(/(^|\s)border(\s|$)/);
  });

  it('Alert destructive renders as the red warning panel', () => {
    render(
      <Alert data-testid="alert" variant="destructive">
        <AlertDescription>Failed to load</AlertDescription>
      </Alert>,
    );
    const alert = screen.getByTestId('alert');
    expect(alert.className).toContain('border-danger-border');
    expect(alert.className).toContain('bg-danger-bg');
    expect(alert.className).toContain('text-danger-text');
    expect(alert.className).not.toContain('text-destructive');
  });

  it('Separator renders as a hairline', () => {
    render(<Separator data-testid="sep" />);
    expect(screen.getByTestId('sep').className).toContain('bg-separator');
  });
});
