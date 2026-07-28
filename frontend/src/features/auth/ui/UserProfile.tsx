import { Mail } from 'lucide-react'
import { useState } from 'react'

import { Badge } from '@/shared/ui/ui/badge'

interface UserProfileProps {
  name?: string
  email?: string
  picture?: string
  initials: string
  isAdmin: boolean
  isRolesLoading: boolean
  titleId: string
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
  isRolesLoading,
  titleId,
}: UserProfileProps) {
  const [failedPicture, setFailedPicture] = useState<string>()
  const normalizedName = name?.trim() || undefined
  const normalizedEmail = email?.trim() || undefined
  const normalizedPicture = picture?.trim() || undefined
  const displayName = normalizedName || 'Name not provided'
  const displayEmail = normalizedEmail || 'Email not provided'
  const accountType = isAdmin ? 'Administrator' : 'Member'
  const showPicture = normalizedPicture && failedPicture !== normalizedPicture

  return (
    <div className="p-5">
      <h2
        id={titleId}
        className="text-[15px] font-semibold tracking-[-0.24px] text-ink"
      >
        Your profile
      </h2>

      <div className="mt-4 flex items-center gap-3">
        {showPicture ? (
          <img
            src={normalizedPicture}
            alt={`${normalizedName || 'User'} profile picture`}
            referrerPolicy="no-referrer"
            onError={() => setFailedPicture(normalizedPicture)}
            className="h-12 w-12 shrink-0 rounded-full border border-separator object-cover"
          />
        ) : (
          <div
            aria-hidden="true"
            className="flex h-12 w-12 shrink-0 items-center justify-center rounded-full bg-brand-50 text-sm font-medium text-brand"
          >
            {initials}
          </div>
        )}

        <div className="min-w-0">
          <p className="break-words text-sm font-medium text-ink">{displayName}</p>
          <Badge
            variant={isRolesLoading ? 'neutral' : isAdmin ? 'info' : 'neutral'}
            className="mt-1"
          >
            {isRolesLoading ? (
              <>
                <span aria-hidden="true">—</span>
                <span className="sr-only">Account type loading</span>
              </>
            ) : (
              accountType
            )}
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
    </div>
  )
}
