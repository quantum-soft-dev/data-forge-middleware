import * as React from "react"
import { cva, type VariantProps } from "class-variance-authority"

import { cn } from "@/shared/lib/utils"
import { monitoringTokens, severityTokens } from "@/shared/ui/tokens"

/**
 * Monitoring status pill (024, T004): rounded-full, 12px/500, 10–12% alpha
 * background + darker full-color text, optional 6px leading dot.
 * Exemplar: features/delta-sync/ui/SyncHealthPill.tsx.
 */
const badgeVariants = cva(
  "inline-flex items-center gap-1.5 rounded-full px-2.5 py-1 text-xs font-medium transition-colors",
  {
    variants: {
      variant: {
        info: "",
        neutral: "",
        success: "",
        warning: "",
        critical: "",
        stalled: "",
        outline: "border border-hairline text-ink-secondary",
      },
    },
    defaultVariants: {
      variant: "info",
    },
  }
)

type CanonicalVariant =
  | "info"
  | "neutral"
  | "success"
  | "warning"
  | "critical"
  | "stalled"
  | "outline"

interface VariantColors {
  background?: string
  color?: string
  dot?: string
}

const variantColors: Record<CanonicalVariant, VariantColors> = {
  info: {
    background: monitoringTokens.blue50,
    color: monitoringTokens.primary,
    dot: monitoringTokens.primary,
  },
  neutral: {
    background: monitoringTokens.subtleBg,
    color: monitoringTokens.textSecondary,
    dot: monitoringTokens.textMuted,
  },
  success: {
    background: severityTokens.healthy.bg,
    color: severityTokens.healthy.text,
    dot: severityTokens.healthy.dot,
  },
  warning: {
    background: severityTokens.elevated.bg,
    color: severityTokens.elevated.text,
    dot: severityTokens.elevated.dot,
  },
  critical: {
    background: severityTokens.critical.bg,
    color: severityTokens.critical.text,
    dot: severityTokens.critical.dot,
  },
  stalled: {
    background: severityTokens.stalled.bg,
    color: severityTokens.stalled.text,
    dot: severityTokens.stalled.dot,
  },
  outline: { dot: monitoringTokens.textMuted },
}

export interface BadgeProps
  extends React.HTMLAttributes<HTMLDivElement>,
    VariantProps<typeof badgeVariants> {
  /** Render a 6px status dot in the variant's dot color. */
  dot?: boolean
}

function Badge({ className, variant, dot, style, children, ...props }: BadgeProps) {
  const colors = variantColors[variant ?? "info"]

  return (
    <div
      className={cn(badgeVariants({ variant }), className)}
      style={{ background: colors.background, color: colors.color, ...style }}
      {...props}
    >
      {dot ? (
        <span
          aria-hidden="true"
          className="h-1.5 w-1.5 shrink-0 rounded-full"
          style={{ background: colors.dot }}
        />
      ) : null}
      {children}
    </div>
  )
}

export { Badge, badgeVariants }
