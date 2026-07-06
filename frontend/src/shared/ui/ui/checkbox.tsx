import * as React from 'react'
import { cn } from '@/shared/lib/utils'

export interface CheckboxProps
  extends Omit<React.InputHTMLAttributes<HTMLInputElement>, 'type' | 'onChange'> {
  indeterminate?: boolean
  onCheckedChange?: (checked: boolean) => void
}

const Checkbox = React.forwardRef<HTMLInputElement, CheckboxProps>(
  ({ className, indeterminate, onCheckedChange, ...props }, ref) => {
    const checkboxRef = React.useRef<HTMLInputElement>(null)

    React.useImperativeHandle(ref, () => checkboxRef.current as HTMLInputElement)

    React.useEffect(() => {
      if (checkboxRef.current) {
        checkboxRef.current.indeterminate = indeterminate ?? false
      }
    }, [indeterminate])

    const handleChange = (e: React.ChangeEvent<HTMLInputElement>) => {
      onCheckedChange?.(e.target.checked)
    }

    return (
      <input
        type="checkbox"
        className={cn(
          'peer h-4 w-4 shrink-0 rounded-sm border border-hairline ring-offset-white',
          'focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-brand focus-visible:ring-offset-2',
          'disabled:cursor-not-allowed disabled:opacity-50',
          'checked:bg-brand checked:border-brand',
          'indeterminate:bg-brand indeterminate:border-brand',
          className
        )}
        onChange={handleChange}
        ref={checkboxRef}
        {...props}
      />
    )
  }
)

Checkbox.displayName = 'Checkbox'

export { Checkbox }
