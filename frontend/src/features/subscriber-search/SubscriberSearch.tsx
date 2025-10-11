/**
 * SubscriberSearch Composition
 *
 * Combines SearchInput and StatusFilter into a single search interface.
 */

import { SearchInput } from './SearchInput'

interface SubscriberSearchProps {
  onSearch: (query: string) => void
  defaultValue?: string
}

export function SubscriberSearch({ onSearch, defaultValue }: SubscriberSearchProps) {
  return <SearchInput onSearch={onSearch} defaultValue={defaultValue} />
}
