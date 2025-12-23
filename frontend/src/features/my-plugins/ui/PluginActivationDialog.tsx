/**
 * PluginActivationDialog Component
 *
 * Dialog for activating a plugin with required configuration fields.
 * Currently supports Bit BI plugin with tenantId field.
 */

import { useState } from 'react'
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from '@/shared/ui/ui/dialog'
import { Button } from '@/shared/ui/ui/button'
import { Input } from '@/shared/ui/ui/input'
import { Label } from '@/shared/ui/ui/label'
import type { AvailablePlugin, ActivatePluginRequest } from '../model/types'

export interface PluginActivationDialogProps {
  /** Whether the dialog is open */
  open: boolean
  /** Plugin to activate */
  plugin: AvailablePlugin | null
  /** Callback when dialog is closed (without activation) */
  onClose: () => void
  /** Callback when plugin is activated with data */
  onSubmit: (pluginId: string, request: ActivatePluginRequest) => void
  /** Whether activation is in progress */
  isLoading?: boolean
}

/**
 * Dialog for plugin activation with configuration form.
 *
 * For bit-bi plugin, shows tenantId field.
 * For other plugins, shows a generic activation confirmation.
 */
export function PluginActivationDialog({
  open,
  plugin,
  onClose,
  onSubmit,
  isLoading = false,
}: PluginActivationDialogProps) {
  const [tenantId, setTenantId] = useState('')
  const [error, setError] = useState<string | null>(null)

  const isBitBi = plugin?.pluginId === 'bit-bi'

  const handleSubmit = (e: React.FormEvent) => {
    e.preventDefault()
    setError(null)

    if (!plugin) return

    // Validate required fields for bit-bi
    if (isBitBi && !tenantId.trim()) {
      setError('Tenant ID is required')
      return
    }

    const pluginData: Record<string, unknown> = isBitBi
      ? { tenantId: tenantId.trim() }
      : {}

    onSubmit(plugin.pluginId, { pluginData })
  }

  const handleClose = () => {
    setTenantId('')
    setError(null)
    onClose()
  }

  if (!plugin) return null

  return (
    <Dialog open={open} onOpenChange={(isOpen) => !isOpen && handleClose()}>
      <DialogContent className="sm:max-w-md">
        <DialogHeader>
          <DialogTitle>Activate {plugin.displayName}</DialogTitle>
          <DialogDescription>
            {isBitBi
              ? 'Enter your Bit BI tenant ID to connect your account.'
              : `Activate ${plugin.displayName} for your account.`}
          </DialogDescription>
        </DialogHeader>

        <form onSubmit={handleSubmit}>
          {isBitBi && (
            <div className="space-y-4 py-4">
              <div className="space-y-2">
                <Label htmlFor="tenantId">
                  Tenant ID <span className="text-red-500">*</span>
                </Label>
                <Input
                  id="tenantId"
                  value={tenantId}
                  onChange={(e) => {
                    setTenantId(e.target.value)
                    if (error) setError(null)
                  }}
                  placeholder="Enter your Bit BI tenant ID"
                  disabled={isLoading}
                  aria-invalid={!!error}
                  aria-describedby={error ? 'tenantId-error' : undefined}
                />
                {error && (
                  <p id="tenantId-error" className="text-sm text-red-500">
                    {error}
                  </p>
                )}
              </div>
            </div>
          )}

          <DialogFooter className="gap-2 sm:gap-0">
            <Button
              type="button"
              variant="outline"
              onClick={handleClose}
              disabled={isLoading}
            >
              Cancel
            </Button>
            <Button type="submit" disabled={isLoading}>
              {isLoading ? 'Activating...' : 'Activate'}
            </Button>
          </DialogFooter>
        </form>
      </DialogContent>
    </Dialog>
  )
}
