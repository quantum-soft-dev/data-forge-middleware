import { render, screen } from '@testing-library/react';
import { describe, expect, it } from 'vitest';

import {
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableHeader,
  TableRow,
  tableCellNumeric,
} from '@/shared/ui/ui/table';

describe('Table (monitoring hairline style, T007)', () => {
  it('renders headers as 12px/500 secondary — never uppercase', () => {
    render(
      <Table>
        <TableHeader>
          <TableRow>
            <TableHead>Seq</TableHead>
          </TableRow>
        </TableHeader>
      </Table>,
    );
    const head = screen.getByText('Seq');
    expect(head.className).toContain('text-xs');
    expect(head.className).toContain('font-medium');
    expect(head.className).toContain('text-ink-secondary');
    expect(head.className).not.toContain('uppercase');
    expect(head.className).not.toContain('h-12');
  });

  it('renders rows with hairline separators and #FAFAFA hover', () => {
    render(
      <Table>
        <TableBody>
          <TableRow data-testid="row">
            <TableCell>42</TableCell>
          </TableRow>
        </TableBody>
      </Table>,
    );
    const row = screen.getByTestId('row');
    expect(row.className).toContain('border-separator');
    expect(row.className).toContain('hover:bg-surface-hover');
    expect(row.className).not.toContain('hover:bg-muted');
  });

  it('exports a numeric cell helper with tabular-nums', () => {
    expect(tableCellNumeric).toContain('tabular-nums');
    expect(tableCellNumeric).toContain('text-right');
  });
});
