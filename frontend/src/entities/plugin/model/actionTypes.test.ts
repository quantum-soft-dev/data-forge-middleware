import { describe, expect, it } from 'vitest'
import { actionTypeLabel, actionTypeVariant } from './actionTypes'

describe('plugin actionTypes shared mapping (review r3)', () => {
  it('labels and colors known action types', () => {
    expect(actionTypeLabel('ACTIVATE')).toBe('Activate')
    expect(actionTypeVariant('ACTIVATE')).toBe('success')
    expect(actionTypeVariant('EVENT_TIMEOUT')).toBe('stalled')
  })

  it('degrades to the raw type on a neutral pill for unknown action types', () => {
    // The maps were duplicated in ActionTypeBadge and AuditLogFilters — a new backend
    // action type updated in one copy silently diverged in the other.
    expect(actionTypeLabel('SQL_GENERATION_STARTED')).toBe('SQL_GENERATION_STARTED')
    expect(actionTypeVariant('SQL_GENERATION_STARTED')).toBe('neutral')
  })
})
