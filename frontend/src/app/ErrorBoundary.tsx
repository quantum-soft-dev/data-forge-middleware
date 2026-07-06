import { Component, ErrorInfo, ReactNode } from 'react'

interface Props {
  children: ReactNode
  fallback?: (error: Error, errorInfo: ErrorInfo) => ReactNode
}

interface State {
  hasError: boolean
  error: Error | null
  errorInfo: ErrorInfo | null
}

/**
 * Error Boundary component to catch React render errors.
 *
 * Catches errors during rendering, in lifecycle methods, and in constructors
 * of the whole tree below them. Does NOT catch errors in:
 * - Event handlers (use try-catch)
 * - Asynchronous code (setTimeout, promises)
 * - Server-side rendering
 * - Errors thrown in the error boundary itself
 *
 * Usage:
 * ```tsx
 * <ErrorBoundary>
 *   <App />
 * </ErrorBoundary>
 * ```
 *
 * @see https://react.dev/reference/react/Component#catching-rendering-errors-with-an-error-boundary
 */
export class ErrorBoundary extends Component<Props, State> {
  constructor(props: Props) {
    super(props)
    this.state = {
      hasError: false,
      error: null,
      errorInfo: null,
    }
  }

  static getDerivedStateFromError(error: Error): Partial<State> {
    // Update state so the next render will show the fallback UI
    return {
      hasError: true,
      error,
    }
  }

  componentDidCatch(error: Error, errorInfo: ErrorInfo): void {
    // Log error to console in development
    if (process.env.NODE_ENV === 'development') {
      console.error('ErrorBoundary caught an error:', error, errorInfo)
    }

    // Update state with error info for detailed fallback UI
    this.setState({
      errorInfo,
    })

    // TODO: Send error to logging service (e.g., Sentry, LogRocket)
    // Example:
    // logErrorToService(error, errorInfo)
  }

  handleReset = () => {
    this.setState({
      hasError: false,
      error: null,
      errorInfo: null,
    })
  }

  render() {
    if (this.state.hasError) {
      // Custom fallback UI if provided
      if (this.props.fallback && this.state.error && this.state.errorInfo) {
        return this.props.fallback(this.state.error, this.state.errorInfo)
      }

      // Default fallback UI
      return (
        <div className="flex min-h-screen items-center justify-center bg-white p-4">
          <div className="w-full max-w-2xl">
            <div className="rounded-2xl bg-white p-8 shadow-panel">
              {/* Error Icon */}
              <div className="mb-6 flex justify-center">
                <div className="flex h-16 w-16 items-center justify-center rounded-full bg-danger-bg">
                  <svg
                    className="h-8 w-8 text-danger-text"
                    fill="none"
                    stroke="currentColor"
                    viewBox="0 0 24 24"
                  >
                    <path
                      strokeLinecap="round"
                      strokeLinejoin="round"
                      strokeWidth={2}
                      d="M12 9v2m0 4h.01m-6.938 4h13.856c1.54 0 2.502-1.667 1.732-3L13.732 4c-.77-1.333-2.694-1.333-3.464 0L3.34 16c-.77 1.333.192 3 1.732 3z"
                    />
                  </svg>
                </div>
              </div>

              {/* Error Message */}
              <div className="text-center">
                <h1 className="mb-2 text-[22px] font-medium tracking-[-0.33px] text-ink">
                  Oops! Something went wrong
                </h1>
                <p className="mb-6 text-ink-secondary">
                  We're sorry for the inconvenience. An unexpected error has occurred.
                </p>
              </div>

              {/* Error Details (Development Only) */}
              {process.env.NODE_ENV === 'development' && this.state.error && (
                <div className="mb-6 space-y-4">
                  <details className="rounded-lg bg-surface-subtle p-4">
                    <summary className="cursor-pointer font-medium text-ink-secondary">
                      Error Details (Dev Only)
                    </summary>
                    <div className="mt-4 space-y-2">
                      <div>
                        <p className="text-sm font-medium text-ink-secondary">Error Message:</p>
                        <pre className="mt-1 overflow-x-auto rounded-lg bg-danger-bg p-2 text-xs text-danger-text">
                          {this.state.error.message}
                        </pre>
                      </div>
                      {this.state.errorInfo && (
                        <div>
                          <p className="text-sm font-medium text-ink-secondary">Component Stack:</p>
                          <pre className="mt-1 max-h-48 overflow-auto rounded-lg bg-surface-subtle p-2 text-xs text-ink-secondary">
                            {this.state.errorInfo.componentStack}
                          </pre>
                        </div>
                      )}
                      {this.state.error.stack && (
                        <div>
                          <p className="text-sm font-medium text-ink-secondary">Stack Trace:</p>
                          <pre className="mt-1 max-h-48 overflow-auto rounded-lg bg-surface-subtle p-2 text-xs text-ink-secondary">
                            {this.state.error.stack}
                          </pre>
                        </div>
                      )}
                    </div>
                  </details>
                </div>
              )}

              {/* Action Buttons */}
              <div className="flex flex-col gap-3 sm:flex-row sm:justify-center">
                <button
                  onClick={this.handleReset}
                  className="rounded-lg bg-brand px-6 py-3 text-sm font-medium text-white transition-colors hover:bg-brand-hover focus:outline-none focus:ring-2 focus:ring-ring focus:ring-offset-2"
                >
                  Try Again
                </button>
                <button
                  onClick={() => window.location.reload()}
                  className="rounded-lg border border-hairline bg-white px-6 py-3 text-sm font-medium text-ink-secondary transition-colors hover:bg-surface-subtle focus:outline-none focus:ring-2 focus:ring-gray-500 focus:ring-offset-2"
                >
                  Reload Page
                </button>
                <a
                  href="/"
                  className="rounded-lg border border-hairline bg-white px-6 py-3 text-center text-sm font-medium text-ink-secondary transition-colors hover:bg-surface-subtle focus:outline-none focus:ring-2 focus:ring-gray-500 focus:ring-offset-2"
                >
                  Go to Home
                </a>
              </div>

              {/* Support Message */}
              <div className="mt-6 border-t border-separator pt-6 text-center">
                <p className="text-sm text-ink-muted">
                  If this problem persists, please contact support.
                </p>
              </div>
            </div>
          </div>
        </div>
      )
    }

    return this.props.children
  }
}
