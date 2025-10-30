import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { ReactQueryDevtools } from '@tanstack/react-query-devtools'
import type { ReactNode } from 'react'

interface QueryProviderProps {
  children: ReactNode
}

/**
 * TanStack Query provider for server state management
 *
 * Configuration:
 * - Stale time: 5 minutes (data considered fresh for 5 min)
 * - Cache time: 10 minutes (unused data garbage collected after 10 min)
 * - Retry: 3 attempts with exponential backoff
 * - Refetch on window focus: enabled
 *
 * See Constitution Principle X for React Query best practices
 */
const queryClient = new QueryClient({
  defaultOptions: {
    queries: {
      staleTime: 5 * 60 * 1000, // 5 minutes
      gcTime: 10 * 60 * 1000, // 10 minutes (formerly cacheTime)
      retry: 3,
      retryDelay: (attemptIndex) => Math.min(1000 * 2 ** attemptIndex, 30000),
      refetchOnWindowFocus: true,
    },
    mutations: {
      retry: 1,
    },
  },
})

export function QueryProvider({ children }: QueryProviderProps) {
  return (
    <QueryClientProvider client={queryClient}>
      {children}
      {/* DevTools only in development */}
      {import.meta.env.DEV && <ReactQueryDevtools initialIsOpen={false} />}
    </QueryClientProvider>
  )
}
