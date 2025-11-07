import '@testing-library/jest-dom'
import { cleanup } from '@testing-library/react'
import { afterEach, vi } from 'vitest'
import { mockAuth0 } from './mocks/mockAuth0'

// Mock Auth0 React SDK globally for all tests
vi.mock('@auth0/auth0-react', () => mockAuth0)

// Cleanup after each test
afterEach(() => {
  cleanup()
})
