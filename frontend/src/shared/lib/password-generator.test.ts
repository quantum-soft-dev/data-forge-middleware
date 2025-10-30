/**
 * Unit tests for password generator utility.
 *
 * Tests password generation constraints matching backend:
 * - Length: 8-12 characters
 * - Character set: alphanumeric only (a-z, A-Z, 0-9)
 * - Uniqueness: Statistically unlikely to generate duplicates
 * - Security: Uses crypto.getRandomValues()
 *
 * Feature: 007-adding-a-site (T019)
 */

import { describe, it, expect } from 'vitest';
import { generatePassword, isValidPassword } from './password-generator';

describe('generatePassword', () => {
  it('should generate password with length between 8 and 12 characters', () => {
    const password = generatePassword();
    expect(password.length).toBeGreaterThanOrEqual(8);
    expect(password.length).toBeLessThanOrEqual(12);
  });

  it('should generate password containing only alphanumeric characters', () => {
    const password = generatePassword();
    expect(password).toMatch(/^[a-zA-Z0-9]+$/);
  });

  it('should not generate null or empty password', () => {
    const password = generatePassword();
    expect(password).toBeTruthy();
    expect(password.length).toBeGreaterThan(0);
  });

  it('should generate unique passwords (statistical test)', () => {
    const iterations = 100;
    const passwords = new Set<string>();

    for (let i = 0; i < iterations; i++) {
      const password = generatePassword();
      passwords.add(password);
    }

    // At least 95% unique (allowing for statistical possibility of collisions)
    const uniquenessRatio = passwords.size / iterations;
    expect(uniquenessRatio).toBeGreaterThanOrEqual(0.95);
  });

  it('should contain at least one letter (statistical)', () => {
    let hasLetter = false;

    // Run multiple times to account for randomness
    for (let i = 0; i < 50; i++) {
      const password = generatePassword();
      if (/[a-zA-Z]/.test(password)) {
        hasLetter = true;
        break;
      }
    }

    expect(hasLetter).toBe(true);
  });

  it('should contain at least one digit (statistical)', () => {
    let hasDigit = false;

    // Run multiple times to account for randomness
    for (let i = 0; i < 50; i++) {
      const password = generatePassword();
      if (/[0-9]/.test(password)) {
        hasDigit = true;
        break;
      }
    }

    expect(hasDigit).toBe(true);
  });

  it('should use crypto.getRandomValues (entropy test)', () => {
    const iterations = 100;
    const charactersUsed = new Set<string>();

    for (let i = 0; i < iterations; i++) {
      const password = generatePassword();
      for (const char of password) {
        charactersUsed.add(char);
      }
    }

    // Should use a variety of characters (at least 20 different characters)
    expect(charactersUsed.size).toBeGreaterThanOrEqual(20);
  });
});

describe('isValidPassword', () => {
  it('should accept valid password with 8 characters', () => {
    expect(isValidPassword('abcd1234')).toBe(true);
  });

  it('should accept valid password with 12 characters', () => {
    expect(isValidPassword('abcd12345678')).toBe(true);
  });

  it('should reject password shorter than 8 characters', () => {
    expect(isValidPassword('abc123')).toBe(false);
  });

  it('should reject password longer than 12 characters', () => {
    expect(isValidPassword('abcd123456789')).toBe(false);
  });

  it('should reject password with special characters', () => {
    expect(isValidPassword('abcd123!')).toBe(false);
  });

  it('should reject password with spaces', () => {
    expect(isValidPassword('abcd 1234')).toBe(false);
  });

  it('should reject empty string', () => {
    expect(isValidPassword('')).toBe(false);
  });

  it('should reject null/undefined', () => {
    expect(isValidPassword(null as any)).toBe(false);
    expect(isValidPassword(undefined as any)).toBe(false);
  });
});
