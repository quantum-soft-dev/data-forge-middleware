/**
 * PluginSecretDialog Component
 *
 * Shows a freshly issued plugin secret exactly once — the backend stores only a
 * hash, so an unread value is lost for good. Rendered after an activation or a
 * rotation that returned credentials.
 */

import { useState } from 'react'
import { AlertTriangle, Check, Copy } from 'lucide-react'
import { toast } from 'sonner'
import {
  AlertDialog,
  AlertDialogContent,
  AlertDialogDescription,
  AlertDialogHeader,
  AlertDialogTitle,
} from '@/shared/ui/ui/alert-dialog'
import { Alert, AlertDescription } from '@/shared/ui/ui/alert'
import { Button } from '@/shared/ui/ui/button'
import { severityTokens } from '@/shared/ui/tokens'
import type { PluginSecret } from '../model/pluginSecret'

export interface PluginSecretDialogProps {
  /** Secret to reveal; null keeps the dialog closed */
  secret: PluginSecret | null
  /** Human-readable plugin name, falls back to the plugin id */
  pluginName?: string
  /** Called once the owner acknowledges having saved the secret */
  onClose: () => void
}

interface SecretFieldProps {
  label: string
  value: string
  /** Accessible name of the copy button, e.g. "Copy API key" */
  copyLabel: string
}

function SecretField({ label, value, copyLabel }: SecretFieldProps) {
  const [copied, setCopied] = useState(false)

  const handleCopy = async () => {
    try {
      await navigator.clipboard.writeText(value)
      setCopied(true)
      toast.success(`${label} copied to clipboard`)
      setTimeout(() => setCopied(false), 2000)
    } catch {
      toast.error(`Failed to copy ${label.toLowerCase()}`)
    }
  }

  return (
    <div>
      <div className="mb-1 flex items-center justify-between">
        <p className="text-sm font-medium text-ink-secondary">{label}</p>
        <Button variant="outline" size="sm" className="h-7" onClick={handleCopy} aria-label={copyLabel}>
          {copied ? (
            <>
              <Check className="mr-1 h-3 w-3" style={{ color: severityTokens.healthy.dot }} />
              Copied
            </>
          ) : (
            <>
              <Copy className="mr-1 h-3 w-3" />
              Copy
            </>
          )}
        </Button>
      </div>
      <p className="break-all rounded bg-surface-subtle p-2 font-mono text-sm">{value}</p>
    </div>
  )
}

/**
 * One-shot reveal of a plugin secret.
 *
 * The value lives in component state only while the dialog is open: it is never
 * written to the query cache, a toast or the console.
 */
export function PluginSecretDialog({ secret, pluginName, onClose }: PluginSecretDialogProps) {
  if (!secret) return null

  const displayName = pluginName ?? secret.pluginId
  const isBasicAuth = secret.kind === 'basic-auth'

  return (
    <AlertDialog open onOpenChange={(isOpen) => !isOpen && onClose()}>
      <AlertDialogContent className="max-h-[90vh] max-w-lg overflow-y-auto">
        <AlertDialogHeader>
          <AlertDialogTitle>
            {isBasicAuth ? 'Save your credentials' : 'Save your API key'}
          </AlertDialogTitle>
          <AlertDialogDescription>
            {displayName} issued {isBasicAuth ? 'these credentials' : 'this API key'} for your
            account.
          </AlertDialogDescription>
        </AlertDialogHeader>

        <div className="mt-4 space-y-4">
          <Alert variant="destructive">
            <AlertTriangle className="h-4 w-4" />
            <AlertDescription>
              <strong>Copy it now:</strong> it won't be shown again. If it is lost, rotate the
              secret to issue a new one — the old value stops working immediately.
            </AlertDescription>
          </Alert>

          {secret.kind === 'basic-auth' ? (
            <>
              <SecretField label="Login" value={secret.login} copyLabel="Copy login" />
              <SecretField label="Password" value={secret.password} copyLabel="Copy password" />
            </>
          ) : (
            <SecretField label="API key" value={secret.value} copyLabel="Copy API key" />
          )}

          <div className="flex justify-end border-t border-hairline pt-4">
            <Button onClick={onClose}>I've saved it</Button>
          </div>
        </div>
      </AlertDialogContent>
    </AlertDialog>
  )
}
