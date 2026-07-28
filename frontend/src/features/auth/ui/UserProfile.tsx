import { Mail } from 'lucide-react'

import { Badge } from '@/shared/ui/ui/badge'

interface UserProfileProps {
  name?: string
  email?: string
  picture?: string
  initials: string
  isAdmin: boolean
}

/**
 * Read-only, presentational view of the safe personal fields exposed by Auth0.
 *
 * Authentication identifiers and raw claims are deliberately not accepted as
 * props, which keeps them outside the profile rendering boundary.
 */
export function UserProfile({
  name,
  email,
  picture,
  initials,
  isAdmin,
}: UserProfileProps) {
  const normalizedName = name?.trim() || undefined
  const normalizedEmail = email?.trim() || undefined
  const normalizedPicture = picture?.trim() || undefined
  const displayName = normalizedName || 'Name not provided'
  const displayEmail = normalizedEmail || 'Email not provided'
  const accountType = isAdmin ? 'Administrator' : 'Member'

  return (
    <section aria-labelledby="current-user-profile-title" className="p-5">
      <h2
        id="current-user-profile-title"
        className="text-[15px] font-semibold tracking-[-0.24px] text-ink"
      >
        Your profile
      </h2>

      <div className="mt-4 flex items-center gap-3">
        {normalizedPicture ? (
          <img
            src={normalizedPicture}
            alt={`${normalizedName || 'User'} profile picture`}
            className="h-12 w-12 shrink-0 rounded-full border border-separator object-cover"
          />
        ) : (
          <div
            aria-label="Profile initials"
            className="flex h-12 w-12 shrink-0 items-center justify-center rounded-full bg-brand-50 text-sm font-medium text-brand"
          >
            {initials}
          </div>
        )}

        <div className="min-w-0">
          <p className="break-words text-sm font-medium text-ink">{displayName}</p>
          <Badge variant={isAdmin ? 'info' : 'neutral'} className="mt-1">
            {accountType}
          </Badge>
        </div>
      </div>

      <div className="mt-4 border-t border-separator pt-4">
        <div className="flex items-start gap-2.5">
          <Mail
            aria-hidden="true"
            className="mt-0.5 h-4 w-4 shrink-0 text-ink-muted"
            strokeWidth={1.5}
          />
          <div className="min-w-0">
            <p className="text-xs text-ink-muted">Email</p>
            <p className="break-all text-sm text-ink">{displayEmail}</p>
          </div>
        </div>
      </div>
    </section>
  )
}
