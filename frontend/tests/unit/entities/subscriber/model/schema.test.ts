import { describe, it, expect } from 'vitest'
import {
  subscriberSchema,
  subscriberFiltersSchema,
  subscriberListResponseSchema,
  createSubscriberSchema,
} from '@/entities/subscriber/model/schema'

describe('Subscriber Schemas', () => {
  describe('subscriberSchema', () => {
    it('should validate valid subscriber data', () => {
      const validData = {
        id: '123',
        name: 'John Doe',
        email: 'john@example.com',
        phone: '+1234567890',
        company: 'Acme Corp',
        status: 'active',
        createdAt: '2024-01-15T10:00:00Z',
      }

      const result = subscriberSchema.safeParse(validData)
      expect(result.success).toBe(true)
    })

    it('should reject missing required fields', () => {
      const invalidData = {
        id: '123',
        name: 'John Doe',
        // missing email
      }

      const result = subscriberSchema.safeParse(invalidData)
      expect(result.success).toBe(false)
    })

    it('should reject invalid email format', () => {
      const invalidData = {
        id: '123',
        name: 'John Doe',
        email: 'invalid-email',
        phone: '+1234567890',
        company: 'Acme',
        status: 'active',
        createdAt: '2024-01-01T00:00:00Z',
      }

      const result = subscriberSchema.safeParse(invalidData)
      expect(result.success).toBe(false)
    })

    it('should accept valid status values', () => {
      const activeData = { ...validSubscriber(), status: 'active' }
      const inactiveData = { ...validSubscriber(), status: 'inactive' }

      expect(subscriberSchema.safeParse(activeData).success).toBe(true)
      expect(subscriberSchema.safeParse(inactiveData).success).toBe(true)
    })

    it('should reject invalid status values', () => {
      const invalidData = { ...validSubscriber(), status: 'pending' }
      const result = subscriberSchema.safeParse(invalidData)
      expect(result.success).toBe(false)
    })
  })

  describe('subscriberFiltersSchema', () => {
    it('should validate valid filters', () => {
      const validFilters = {
        search: 'john',
        status: 'active',
        page: 1,
        size: 20,
      }

      const result = subscriberFiltersSchema.safeParse(validFilters)
      expect(result.success).toBe(true)
    })

    it('should accept empty filters', () => {
      const result = subscriberFiltersSchema.safeParse({})
      expect(result.success).toBe(true)
    })

    it('should reject invalid page numbers', () => {
      const invalidFilters = { page: 0 } // page must be >= 1
      const result = subscriberFiltersSchema.safeParse(invalidFilters)
      expect(result.success).toBe(false)
    })

    it('should reject invalid page size', () => {
      const invalidFilters = { size: 0 } // size must be >= 1
      const result = subscriberFiltersSchema.safeParse(invalidFilters)
      expect(result.success).toBe(false)
    })
  })

  describe('subscriberListResponseSchema', () => {
    it('should validate valid list response', () => {
      const validResponse = {
        content: [validSubscriber()],
        page: 0,
        size: 10,
        totalElements: 1,
        totalPages: 1,
      }

      const result = subscriberListResponseSchema.safeParse(validResponse)
      expect(result.success).toBe(true)
    })

    it('should accept empty content array', () => {
      const emptyResponse = {
        content: [],
        page: 0,
        size: 10,
        totalElements: 0,
        totalPages: 0,
      }

      const result = subscriberListResponseSchema.safeParse(emptyResponse)
      expect(result.success).toBe(true)
    })

    it('should reject missing pagination fields', () => {
      const invalidResponse = {
        content: [validSubscriber()],
        // missing page, size, etc.
      }

      const result = subscriberListResponseSchema.safeParse(invalidResponse)
      expect(result.success).toBe(false)
    })
  })

  describe('createSubscriberSchema', () => {
    it('should validate valid create subscriber data', () => {
      const validData = {
        name: 'John Doe',
        email: 'john@example.com',
        phone: '+1234567890',
        company: 'Acme Corp',
      }

      const result = createSubscriberSchema.safeParse(validData)
      expect(result.success).toBe(true)
    })

    it('should require name field', () => {
      const invalidData = {
        email: 'john@example.com',
        // missing name
      }

      const result = createSubscriberSchema.safeParse(invalidData)
      expect(result.success).toBe(false)
    })

    it('should require email field', () => {
      const invalidData = {
        name: 'John Doe',
        // missing email
      }

      const result = createSubscriberSchema.safeParse(invalidData)
      expect(result.success).toBe(false)
    })

    it('should validate email format', () => {
      const invalidData = {
        name: 'John Doe',
        email: 'invalid-email',
      }

      const result = createSubscriberSchema.safeParse(invalidData)
      expect(result.success).toBe(false)
    })

    it('should accept empty phone string', () => {
      const data = {
        name: 'John Doe',
        email: 'john@example.com',
        phone: '',
        company: 'Acme',
      }

      const result = createSubscriberSchema.safeParse(data)
      expect(result.success).toBe(true)
      if (result.success) {
        expect(result.data.phone).toBe('')
      }
    })

    it('should accept empty company string', () => {
      const data = {
        name: 'John Doe',
        email: 'john@example.com',
        phone: '+1234567890',
        company: '',
      }

      const result = createSubscriberSchema.safeParse(data)
      expect(result.success).toBe(true)
      if (result.success) {
        expect(result.data.company).toBe('')
      }
    })

    it('should allow optional phone and company fields', () => {
      const minimalData = {
        name: 'Jane Smith',
        email: 'jane@example.com',
      }

      const result = createSubscriberSchema.safeParse(minimalData)
      expect(result.success).toBe(true)
      if (result.success) {
        expect(result.data.phone).toBeUndefined()
        expect(result.data.company).toBeUndefined()
      }
    })
  })
})

// Helper function
function validSubscriber() {
  return {
    id: '123',
    name: 'John Doe',
    email: 'john@example.com',
    phone: '+1234567890',
    company: 'Acme Corp',
    status: 'active' as const,
    createdAt: '2024-01-15T10:00:00Z',
  }
}
