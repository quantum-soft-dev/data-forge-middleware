/**
 * AccountSearch Composition
 *
 * Combines SearchInput and StatusFilter into a single search interface.
 */

import { SearchInput } from './SearchInput'

interface AccountSearchProps {
  onSearch: (query: string) => void
  defaultValue?: string
}

export function AccountSearch({ onSearch, defaultValue }: AccountSearchProps) {
  return <SearchInput onSearch={onSearch} defaultValue={defaultValue} />
}
