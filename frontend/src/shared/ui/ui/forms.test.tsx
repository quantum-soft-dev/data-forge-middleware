import { render, screen } from '@testing-library/react';
import { describe, expect, it } from 'vitest';

import { Checkbox } from '@/shared/ui/ui/checkbox';
import { Input } from '@/shared/ui/ui/input';

describe('form primitives (monitoring style, T009)', () => {
  it('Input uses rounded-lg and exposes an aria-invalid error treatment', () => {
    render(<Input aria-invalid placeholder="Site name" />);
    const input = screen.getByPlaceholderText('Site name');
    expect(input.className).toContain('rounded-lg');
    expect(input.className).not.toContain('rounded-md');
    expect(input.className).toContain('aria-invalid:border-danger-border');
    expect(input.className).toContain('aria-invalid:text-danger-text');
  });

  it('Checkbox uses hairline border and brand checked state', () => {
    render(<Checkbox aria-label="pick" />);
    const box = screen.getByRole('checkbox', { name: 'pick' });
    expect(box.className).toContain('border-hairline');
    expect(box.className).toContain('checked:bg-brand');
    expect(box.className).toContain('focus-visible:ring-brand');
    expect(box.className).not.toContain('blue-600');
    expect(box.className).not.toContain('border-gray-300');
  });
});
