/**
 * Client-side password generator for site credentials.
 *
 * Generates cryptographically secure random passwords matching backend algorithm:
 * - Length: 8-12 characters
 * - Character set: Alphanumeric (a-z, A-Z, 0-9)
 * - Uses crypto.getRandomValues() for browser-native secure randomness
 *
 * Feature: 007-adding-a-site (T018)
 *
 * @example
 * const password = generatePassword();
 * // Returns: "aB3xY9kL" (8-12 chars)
 */

const CHARACTERS = 'abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789';
const MIN_LENGTH = 8;
const MAX_LENGTH = 12;

/**
 * Generate a random password with secure randomness.
 *
 * @returns A random alphanumeric password (8-12 characters)
 * @throws Error if crypto.getRandomValues is not available
 */
export function generatePassword(): string {
  // Ensure crypto API is available
  if (typeof crypto === 'undefined' || !crypto.getRandomValues) {
    throw new Error('Crypto API not available. Secure random generation requires HTTPS or localhost.');
  }

  // Generate random length between MIN_LENGTH and MAX_LENGTH
  const length = MIN_LENGTH + Math.floor(Math.random() * (MAX_LENGTH - MIN_LENGTH + 1));

  // Generate password using cryptographically secure random values
  const randomValues = new Uint8Array(length);
  crypto.getRandomValues(randomValues);

  let password = '';
  for (let i = 0; i < length; i++) {
    // Map random byte to character index
    const charIndex = randomValues[i] % CHARACTERS.length;
    password += CHARACTERS[charIndex];
  }

  return password;
}

/**
 * Validate password meets requirements.
 *
 * @param password - Password to validate
 * @returns true if password meets requirements, false otherwise
 */
export function isValidPassword(password: string): boolean {
  if (!password || password.length < MIN_LENGTH || password.length > MAX_LENGTH) {
    return false;
  }

  // Must be alphanumeric only
  const alphanumericRegex = /^[a-zA-Z0-9]+$/;
  return alphanumericRegex.test(password);
}
