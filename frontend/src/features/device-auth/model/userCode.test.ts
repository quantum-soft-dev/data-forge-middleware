/**
 * The `XXXX-XXXX` user code shape — the boundary issue #211 turned on.
 */

import { describe, it, expect } from 'vitest';
import {
  USER_CODE_CHARS,
  USER_CODE_LENGTH,
  formatUserCode,
  isCompleteUserCode,
} from './userCode';

describe('formatUserCode', () => {
  it('upper-cases and drops anything that is not alphanumeric', () => {
    expect(formatUserCode('m9q2 4a-ml')).toBe('M9Q2-4AML');
  });

  it('adds the separator only once the fifth character arrives', () => {
    expect(formatUserCode('M9Q2')).toBe('M9Q2');
    expect(formatUserCode('M9Q24')).toBe('M9Q2-4');
  });

  it('ignores characters past the eighth', () => {
    expect(formatUserCode('M9Q24AMLXYZ')).toBe('M9Q2-4AML');
  });
});

describe('formatUserCode on a value that is not a string', () => {
  // TanStack Router parses search values with JSON.parse, so a code that is all
  // digits (the alphabet allows 2-9) arrives as a number once the separator is
  // dropped, and a repeated parameter arrives as an array (#211 review).
  it('accepts a number', () => {
    expect(formatUserCode(23456789 as unknown as string)).toBe('2345-6789');
  });

  it('accepts null and undefined as an empty code', () => {
    expect(formatUserCode(null as unknown as string)).toBe('');
    expect(formatUserCode(undefined as unknown as string)).toBe('');
    expect(isCompleteUserCode(undefined as unknown as string)).toBe(false);
  });

  it('accepts an array without throwing', () => {
    expect(() => formatUserCode(['A', 'B'] as unknown as string)).not.toThrow();
  });
});

describe('isCompleteUserCode', () => {
  it('rejects the keystroke before last', () => {
    // Seven characters render as eight — the length the lookup used to fire on.
    expect(formatUserCode('M9Q24AM')).toHaveLength(USER_CODE_CHARS);
    expect(isCompleteUserCode('M9Q24AM')).toBe(false);
  });

  it('accepts a full code, typed or pasted, formatted or raw', () => {
    expect(formatUserCode('M9Q24AML')).toHaveLength(USER_CODE_LENGTH);
    expect(isCompleteUserCode('M9Q24AML')).toBe(true);
    expect(isCompleteUserCode('M9Q2-4AML')).toBe(true);
    expect(isCompleteUserCode('m9q2-4aml')).toBe(true);
  });

  it('rejects an empty code', () => {
    expect(isCompleteUserCode('')).toBe(false);
  });
});
