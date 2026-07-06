import { render, screen } from '@testing-library/react';
import { describe, expect, it } from 'vitest';

import { Button } from '@/shared/ui/ui/button';

describe('Button (monitoring variants, T005)', () => {
  it('uses rounded-lg and keeps a visible focus ring', () => {
    render(<Button>Go</Button>);
    const btn = screen.getByRole('button', { name: 'Go' });
    expect(btn.className).toContain('rounded-lg');
    expect(btn.className).not.toContain('rounded-md');
    expect(btn.className).toContain('focus-visible:ring-2');
  });

  it('default variant renders solid brand with brand hover', () => {
    render(<Button>Save</Button>);
    const btn = screen.getByRole('button', { name: 'Save' });
    expect(btn.className).toContain('bg-brand');
    expect(btn.className).toContain('hover:bg-brand-hover');
    expect(btn.className).toContain('text-white');
    expect(btn.className).not.toContain('bg-primary');
  });

  it('outline variant renders hairline border with subtle hover', () => {
    render(<Button variant="outline">Edit</Button>);
    const btn = screen.getByRole('button', { name: 'Edit' });
    expect(btn.className).toContain('border-hairline');
    expect(btn.className).toContain('text-ink');
    expect(btn.className).toContain('hover:bg-secondary');
    expect(btn.className).not.toContain('border-input');
  });

  it('destructive variant stays solid red for dialog confirmations', () => {
    render(<Button variant="destructive">Delete</Button>);
    const btn = screen.getByRole('button', { name: 'Delete' });
    expect(btn.className).toContain('bg-danger-solid');
    expect(btn.className).toContain('hover:bg-danger-solid-hover');
  });

  it('destructive-outline variant renders red hairline treatment', () => {
    render(<Button variant="destructive-outline">Deactivate</Button>);
    const btn = screen.getByRole('button', { name: 'Deactivate' });
    expect(btn.className).toContain('border-danger-border');
    expect(btn.className).toContain('text-danger-text');
    expect(btn.className).toContain('hover:bg-danger-bg');
  });

  it('supports the compact size (h-8)', () => {
    render(
      <Button variant="outline" size="compact">
        Rebuild now
      </Button>,
    );
    expect(screen.getByRole('button', { name: 'Rebuild now' }).className).toContain('h-8');
  });

  it('keeps ghost and link variants on monitoring palette', () => {
    render(
      <>
        <Button variant="ghost">G</Button>
        <Button variant="link">L</Button>
      </>,
    );
    expect(screen.getByRole('button', { name: 'G' }).className).toContain('hover:bg-secondary');
    expect(screen.getByRole('button', { name: 'L' }).className).toContain('text-brand');
  });
});
