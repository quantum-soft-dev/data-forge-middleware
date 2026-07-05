/**
 * Monitoring design tokens (023, F4) — from design_handoff_delta_sync/README.md.
 *
 * The "monitoring treatment" reuses the product's status hexes with a distinct
 * presentation: 6px dot + 10–12% alpha pill background + darker full-color text.
 * Reusable for future monitoring surfaces.
 */

import type { SyncSeverity } from './severity';

export interface SeverityToken {
  /** Dot / fill color. */
  dot: string;
  /** Darker text color. */
  text: string;
  /** 10–12% alpha pill background. */
  bg: string;
  /** Human label for the severity chip. */
  label: string;
}

export const severityTokens: Record<SyncSeverity, SeverityToken> = {
  healthy: { dot: '#16A34A', text: '#15803D', bg: 'rgba(22,163,74,0.10)', label: 'Healthy' },
  elevated: { dot: '#F59E0B', text: '#B45309', bg: 'rgba(245,158,11,0.12)', label: 'Elevated' },
  critical: { dot: '#EF4444', text: '#B91C1C', bg: 'rgba(239,68,68,0.12)', label: 'Critical' },
  stalled: { dot: '#F97316', text: '#C2410C', bg: 'rgba(249,115,22,0.12)', label: 'Stalled' },
};

export const monitoringTokens = {
  primary: '#3C82D8',
  primaryHover: '#3676C4',
  blue50: '#EBF2FB',
  blue100: '#E0ECFA',
  text: '#2B2827',
  textSecondary: '#736F6D',
  textMuted: '#A3A3A3',
  title: '#403C3B',
  subtleBg: '#F5F5F4',
  hoverRow: '#FAFAFA',
  metricShell: '#EFEFEF',
  border: 'rgba(0,0,0,0.12)',
  separator: 'rgba(0,0,0,0.06)',
  iconWellBorder: 'rgba(24,22,22,0.08)',
  cardShadow: '0 20px 87.5px rgba(0,0,0,0.02), 0 0 1.75px rgba(0,0,0,0.16)',
  innerCardShadow: '0 1px 1.75px rgba(0,0,0,0.25), 0 0 0.5px rgba(0,0,0,0.04)',
  iconCircleShadow: '0 5px 4.375px rgba(0,0,0,0.01), 0 5px 6.125px rgba(0,0,0,0.05)',
  barGradient: 'linear-gradient(180deg, #3C82D8, #C9DCF4)',
} as const;
