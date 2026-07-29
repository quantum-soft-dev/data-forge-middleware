import { describe, it, expect } from 'vitest';
import tailwindConfig from '../../../tailwind.config.js';

/**
 * F13 (023) — product decision P1: Geist is the global product font.
 * Guards the Tailwind sans stack (self-hosted via @fontsource/geist-sans,
 * weights 400/500/600 imported in app/main.tsx).
 */
describe('Geist font adoption (F13)', () => {
  it('puts Geist first in the Tailwind sans stack', () => {
    const sans = (tailwindConfig as { theme: { extend: { fontFamily: { sans: string[] } } } })
      .theme.extend.fontFamily.sans;
    expect(sans[0]).toMatch(/Geist/);
  });
});
