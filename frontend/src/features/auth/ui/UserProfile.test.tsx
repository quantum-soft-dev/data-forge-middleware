import { fireEvent, render, screen } from '@testing-library/react'
import { describe, expect, it } from 'vitest'

import { UserProfile } from './UserProfile'

const defaultProps = {
  name: 'Boris Pliss',
  email: 'boris@example.com',
  initials: 'BP',
  isAdmin: false,
  isRolesLoading: false,
  titleId: 'test-profile-title',
}

describe('UserProfile', () => {
  it('renders safe personal fields and a privacy-preserving profile image', () => {
    render(
      <UserProfile
        {...defaultProps}
        picture="https://example.com/boris.png"
      />,
    )

    expect(screen.getByRole('heading', { name: 'Your profile' })).toHaveAttribute(
      'id',
      'test-profile-title',
    )
    expect(screen.getByText('Boris Pliss')).toBeInTheDocument()
    expect(screen.getByText('boris@example.com')).toBeInTheDocument()
    expect(screen.getByText('Member')).toBeInTheDocument()
    expect(screen.getByRole('img', { name: 'Boris Pliss profile picture' })).toHaveAttribute(
      'referrerpolicy',
      'no-referrer',
    )
  })

  it('does not assert an account type before roles resolve', () => {
    render(<UserProfile {...defaultProps} isRolesLoading />)

    expect(screen.getByText('Account type loading')).toBeInTheDocument()
    expect(screen.queryByText('Member')).not.toBeInTheDocument()
    expect(screen.queryByText('Administrator')).not.toBeInTheDocument()
  })

  it('falls back to visual-only initials when the profile image fails', () => {
    render(
      <UserProfile
        {...defaultProps}
        picture="https://example.com/expired.png"
      />,
    )

    fireEvent.error(screen.getByRole('img', { name: 'Boris Pliss profile picture' }))

    expect(screen.queryByRole('img', { name: /profile picture/i })).not.toBeInTheDocument()
    const initials = screen.getByText('BP')
    expect(initials).toHaveAttribute('aria-hidden', 'true')
    expect(initials).not.toHaveAttribute('aria-label')
  })

  it('uses explicit fallbacks for whitespace-only personal fields', () => {
    render(
      <UserProfile
        {...defaultProps}
        name=" "
        email={'\t'}
        picture=" "
      />,
    )

    expect(screen.getByText('Name not provided')).toBeInTheDocument()
    expect(screen.getByText('Email not provided')).toBeInTheDocument()
    expect(screen.queryByRole('img')).not.toBeInTheDocument()
  })

  it('uses a generic image description when picture exists without a name', () => {
    render(
      <UserProfile
        {...defaultProps}
        name={undefined}
        picture="https://example.com/anonymous.png"
      />,
    )

    expect(screen.getByRole('img', { name: 'User profile picture' })).toBeInTheDocument()
  })

  it('renders the resolved Administrator account type', () => {
    render(<UserProfile {...defaultProps} isAdmin />)

    expect(screen.getByText('Administrator')).toBeInTheDocument()
  })
})
