import * as React from 'react';

import { cn } from '@/shared/lib/utils';

export interface PageHeaderProps {
  title: string;
  /** Secondary line under the title (14px, ink-secondary). */
  subtitle?: string;
  /** Right-aligned actions slot (buttons, filters). */
  actions?: React.ReactNode;
  /** Breadcrumb slot rendered above the title (13px, ink-secondary). */
  breadcrumb?: React.ReactNode;
  className?: string;
}

/**
 * Monitoring page header (024, T012) — 22px/500 title with −0.33px tracking +
 * secondary subline, per pages/site-detail/SiteDetailShell.tsx.
 */
export function PageHeader({ title, subtitle, actions, breadcrumb, className }: PageHeaderProps) {
  return (
    <div className={cn('mb-6', className)}>
      {breadcrumb ? (
        <div className="mb-2.5 text-[13px] text-ink-secondary">{breadcrumb}</div>
      ) : null}
      <div className="flex flex-wrap items-center justify-between gap-3">
        <h1 className="text-[22px] font-medium leading-[1.1] tracking-[-0.33px] text-ink">
          {title}
        </h1>
        {actions ? <div className="flex items-center gap-2">{actions}</div> : null}
      </div>
      {subtitle ? <p className="mt-1.5 text-sm text-ink-secondary">{subtitle}</p> : null}
    </div>
  );
}
