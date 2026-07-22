/**
 * SearchInput Component
 *
 * Debounced search input for account filtering.
 * Per spec: 400ms debounce delay.
 */

import { useState, useEffect, useRef } from 'react'
import { Search, X } from 'lucide-react'

interface SearchInputProps {
  onSearch: (query: string) => void
  placeholder?: string
  defaultValue?: string
}

export function SearchInput({
  onSearch,
  placeholder = 'Search accounts...',
  defaultValue = '',
}: SearchInputProps) {
  const [value, setValue] = useState(defaultValue)
  const isFirstRender = useRef(true)

  useEffect(() => {
    // Skip the initial render: the effect would otherwise report the untouched
    // default value back as a search the user never performed.
    if (isFirstRender.current) {
      isFirstRender.current = false
      return
    }

    const handler = setTimeout(() => {
      onSearch(value)
    }, 400) // 400ms debounce per spec

    return () => clearTimeout(handler)
  }, [value, onSearch])

  const handleClear = () => {
    setValue('')
  }

  return (
    <div className="relative">
      <div className="pointer-events-none absolute inset-y-0 left-0 flex items-center pl-3">
        <Search className="h-5 w-5 text-ink-muted" data-testid="search-icon" />
      </div>
      <input
        type="text"
        value={value}
        onChange={(e) => setValue(e.target.value)}
        placeholder={placeholder}
        className="block w-full rounded-lg border border-hairline bg-white py-2 pl-10 pr-10 text-sm placeholder-gray-400 focus:border-primary focus:outline-none focus:ring-1 focus:ring-primary"
      />
      {value && (
        <button
          type="button"
          onClick={handleClear}
          aria-label="Clear search"
          className="absolute inset-y-0 right-0 flex items-center pr-3 text-ink-muted hover:text-ink-secondary"
        >
          <X className="h-5 w-5" />
        </button>
      )}
    </div>
  )
}
