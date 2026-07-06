import { describe, expect, it } from 'vitest';

import { monitoringTokens, severityTokens } from '@/shared/ui/tokens';
import * as legacyTokens from '@/features/delta-sync/model/tokens';

describe('shared monitoring tokens', () => {
  it('exposes the monitoring palette', () => {
    expect(monitoringTokens.primary).toBe('#3C82D8');
    expect(monitoringTokens.primaryHover).toBe('#3676C4');
    expect(monitoringTokens.text).toBe('#2B2827');
    expect(monitoringTokens.textSecondary).toBe('#736F6D');
    expect(monitoringTokens.textMuted).toBe('#A3A3A3');
    expect(monitoringTokens.border).toBe('rgba(0,0,0,0.12)');
    expect(monitoringTokens.separator).toBe('rgba(0,0,0,0.06)');
    expect(monitoringTokens.cardShadow).toBe(
      '0 20px 87.5px rgba(0,0,0,0.02), 0 0 1.75px rgba(0,0,0,0.16)',
    );
  });

  it('exposes all four severity tokens with alpha backgrounds', () => {
    expect(Object.keys(severityTokens).sort()).toEqual([
      'critical',
      'elevated',
      'healthy',
      'stalled',
    ]);
    expect(severityTokens.healthy).toEqual({
      dot: '#16A34A',
      text: '#15803D',
      bg: 'rgba(22,163,74,0.10)',
      label: 'Healthy',
    });
    expect(severityTokens.critical.text).toBe('#B91C1C');
  });

  it('keeps the legacy delta-sync path as a re-export of the shared module', () => {
    expect(legacyTokens.monitoringTokens).toBe(monitoringTokens);
    expect(legacyTokens.severityTokens).toBe(severityTokens);
  });
});
