import { describe, expect, it } from 'vitest';
import resolveConfig from 'tailwindcss/resolveConfig';

// eslint-disable-next-line import/no-relative-packages -- tailwind config lives at package root
import tailwindConfig from '../../../tailwind.config.js';
import { monitoringTokens } from '@/shared/ui/tokens';

const theme = resolveConfig(tailwindConfig).theme;

describe('tailwind monitoring theme (T003)', () => {
  it('exposes the ink text scale', () => {
    expect(theme.colors.ink).toEqual({
      DEFAULT: monitoringTokens.text,
      secondary: monitoringTokens.textSecondary,
      muted: monitoringTokens.textMuted,
      title: monitoringTokens.title,
    });
  });

  it('exposes the brand scale', () => {
    expect(theme.colors.brand).toEqual({
      DEFAULT: monitoringTokens.primary,
      hover: monitoringTokens.primaryHover,
      50: monitoringTokens.blue50,
      100: monitoringTokens.blue100,
    });
  });

  it('exposes hairline and separator colors', () => {
    expect(theme.colors.hairline).toBe(monitoringTokens.border);
    expect(theme.colors.separator).toBe(monitoringTokens.separator);
  });

  it('exposes the monitoring shadows (keys must not collide with color names)', () => {
    expect(theme.boxShadow.panel).toBe(monitoringTokens.cardShadow);
    expect(theme.boxShadow['panel-inner']).toBe(monitoringTokens.innerCardShadow);
    expect(theme.boxShadow['icon-circle']).toBe(monitoringTokens.iconCircleShadow);
    // "shadow-card" would resolve as a *shadow color* utility (colors.card = white)
    expect(theme.boxShadow.card).toBeUndefined();
  });
});
