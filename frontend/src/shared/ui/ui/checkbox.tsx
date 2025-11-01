import * as React from 'react'
import { cn } from '@/shared/lib/utils'

export interface CheckboxProps
  extends Omit<React.InputHTMLAttributes<HTMLInputElement>, 'type'> {
  indeterminate?: boolean
}

const Checkbox = React.forwardRef<HTMLInputElement, CheckboxProps>(
  ({ className, indeterminate, ...props }, ref) => {
    const checkboxRef = React.useRef<HTMLInputElement>(null)

    React.useImperativeHandle(ref, () => checkboxRef.current as HTMLInputElement)

    React.useEffect(() => {
      if (checkboxRef.current) {
        checkboxRef.current.indeterminate = indeterminate ?? false
      }
    }, [indeterminate])

    return (
      <input
        type="checkbox"
        className={cn(
          'peer h-4 w-4 shrink-0 rounded-sm border border-gray-300 ring-offset-white',
          'focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-blue-500 focus-visible:ring-offset-2',
          'disabled:cursor-not-allowed disabled:opacity-50',
          'checked:bg-blue-600 checked:border-blue-600',
          'indeterminate:bg-blue-600 indeterminate:border-blue-600',
          className
        )}
        ref={checkboxRef}
        {...props}
      />
    )
  }
)

Checkbox.displayName = 'Checkbox'

export { Checkbox }
