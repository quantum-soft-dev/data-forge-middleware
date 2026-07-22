import { render, screen } from '@testing-library/react';
import { describe, expect, it } from 'vitest';

import { Tabs, TabsList, TabsTrigger } from '@/shared/ui/ui/tabs';

describe('Tabs (monitoring treatment as default, T008)', () => {
  const renderTabs = () =>
    render(
      <Tabs defaultValue="a">
        <TabsList data-testid="list">
          <TabsTrigger value="a">Upload history</TabsTrigger>
          <TabsTrigger value="b">Delta Sync</TabsTrigger>
        </TabsList>
      </Tabs>,
    );

  it('renders a transparent, unboxed TabsList', () => {
    renderTabs();
    const list = screen.getByTestId('list');
    expect(list.className).toContain('bg-transparent');
    expect(list.className).toContain('p-0');
    expect(list.className).toContain('gap-1.5');
    expect(list.className).not.toContain('bg-muted');
    expect(list.className).not.toContain('h-10');
  });

  it('renders triggers with the site-detail pill treatment', () => {
    renderTabs();
    const trigger = screen.getByRole('tab', { name: 'Upload history' });
    expect(trigger.className).toContain('rounded-lg');
    expect(trigger.className).toContain('px-4');
    expect(trigger.className).toContain('text-ink-secondary');
    expect(trigger.className).toContain('hover:bg-surface-subtle');
    expect(trigger.className).toContain('data-[state=active]:border-brand');
    expect(trigger.className).toContain('data-[state=active]:text-brand');
    expect(trigger.className).toContain('data-[state=active]:bg-surface-active');
    expect(trigger.className).not.toContain('data-[state=active]:shadow-sm');
  });
});
