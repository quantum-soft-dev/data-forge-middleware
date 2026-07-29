import { render, screen } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';

import { AreaChartWidget } from './AreaChartWidget';
import { BarChartWidget } from './BarChartWidget';
import { PieChartWidget } from './PieChartWidget';
import { TopCompaniesWidget } from './TopCompaniesWidget';

vi.mock('recharts', () => {
  const Stub = ({ children }: any) => <div>{children}</div>;
  return {
    ResponsiveContainer: Stub,
    AreaChart: Stub,
    Area: Stub,
    BarChart: Stub,
    Bar: Stub,
    PieChart: Stub,
    Pie: Stub,
    Cell: Stub,
    XAxis: Stub,
    YAxis: Stub,
    CartesianGrid: Stub,
    Tooltip: Stub,
    Legend: Stub,
  };
});

describe('dashboard chart widgets (monitoring cards, T015)', () => {
  const cases: Array<[string, () => void]> = [
    ['Account Growth Trend', () => render(<AreaChartWidget data={[]} />)],
    ['Monthly Growth', () => render(<BarChartWidget data={[]} />)],
    ['Status Distribution', () => render(<PieChartWidget data={[]} />)],
    ['Top 5 Companies', () => render(<TopCompaniesWidget data={[]} />)],
  ];

  it.each(cases)('%s renders as a monitoring card with 15px/500 title', (title, renderFn) => {
    renderFn();
    const heading = screen.getByText(title);
    expect(heading.className).toContain('text-[15px]');
    expect(heading.className).toContain('font-medium');
    expect(heading.className).toContain('text-ink-title');
    expect(heading.className).not.toContain('text-lg');
    expect(heading.className).not.toContain('font-semibold');

    const card = heading.closest('div');
    expect(card?.className).toContain('shadow-panel');
    expect(card?.className).not.toContain('border-gray-200');
    expect(card?.className).not.toContain('shadow-sm');
  });
});
