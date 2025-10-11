/**
 * SearchInput Component
 *
 * Debounced search input for subscriber filtering.
 * Per spec: 400ms debounce delay.
 */

import { useState, useEffect } from 'react'
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

  useEffect(() => {
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
        <Search className="h-5 w-5 text-gray-400" data-testid="search-icon" />
      </div>
      <input
        type="text"
        value={value}
        onChange={(e) => setValue(e.target.value)}
        placeholder={placeholder}
        className="block w-full rounded-lg border border-gray-300 bg-white py-2 pl-10 pr-10 text-sm placeholder-gray-400 focus:border-primary focus:outline-none focus:ring-1 focus:ring-primary"
      />
      {value && (
        <button
          type="button"
          onClick={handleClear}
          aria-label="Clear search"
          className="absolute inset-y-0 right-0 flex items-center pr-3 text-gray-400 hover:text-gray-600"
        >
          <X className="h-5 w-5" />
        </button>
      )}
    </div>
  )
}
