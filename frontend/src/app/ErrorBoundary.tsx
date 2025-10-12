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
        <div className="flex min-h-screen items-center justify-center bg-gradient-to-br from-red-50 to-orange-50 p-4">
          <div className="w-full max-w-2xl">
            <div className="rounded-2xl border border-red-200 bg-white p-8 shadow-xl">
              {/* Error Icon */}
              <div className="mb-6 flex justify-center">
                <div className="flex h-16 w-16 items-center justify-center rounded-full bg-red-100">
                  <svg
                    className="h-8 w-8 text-red-600"
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
                <h1 className="mb-2 text-2xl font-bold text-gray-900">
                  Oops! Something went wrong
                </h1>
                <p className="mb-6 text-gray-600">
                  We're sorry for the inconvenience. An unexpected error has occurred.
                </p>
              </div>

              {/* Error Details (Development Only) */}
              {process.env.NODE_ENV === 'development' && this.state.error && (
                <div className="mb-6 space-y-4">
                  <details className="rounded-lg border border-gray-200 bg-gray-50 p-4">
                    <summary className="cursor-pointer font-semibold text-gray-700">
                      Error Details (Dev Only)
                    </summary>
                    <div className="mt-4 space-y-2">
                      <div>
                        <p className="text-sm font-medium text-gray-700">Error Message:</p>
                        <pre className="mt-1 overflow-x-auto rounded bg-red-50 p-2 text-xs text-red-900">
                          {this.state.error.message}
                        </pre>
                      </div>
                      {this.state.errorInfo && (
                        <div>
                          <p className="text-sm font-medium text-gray-700">Component Stack:</p>
                          <pre className="mt-1 max-h-48 overflow-auto rounded bg-gray-100 p-2 text-xs text-gray-800">
                            {this.state.errorInfo.componentStack}
                          </pre>
                        </div>
                      )}
                      {this.state.error.stack && (
                        <div>
                          <p className="text-sm font-medium text-gray-700">Stack Trace:</p>
                          <pre className="mt-1 max-h-48 overflow-auto rounded bg-gray-100 p-2 text-xs text-gray-800">
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
                  className="rounded-lg bg-blue-600 px-6 py-3 text-sm font-medium text-white transition-colors hover:bg-blue-700 focus:outline-none focus:ring-2 focus:ring-blue-500 focus:ring-offset-2"
                >
                  Try Again
                </button>
                <button
                  onClick={() => window.location.reload()}
                  className="rounded-lg border border-gray-300 bg-white px-6 py-3 text-sm font-medium text-gray-700 transition-colors hover:bg-gray-50 focus:outline-none focus:ring-2 focus:ring-gray-500 focus:ring-offset-2"
                >
                  Reload Page
                </button>
                <a
                  href="/"
                  className="rounded-lg border border-gray-300 bg-white px-6 py-3 text-center text-sm font-medium text-gray-700 transition-colors hover:bg-gray-50 focus:outline-none focus:ring-2 focus:ring-gray-500 focus:ring-offset-2"
                >
                  Go to Home
                </a>
              </div>

              {/* Support Message */}
              <div className="mt-6 border-t border-gray-200 pt-6 text-center">
                <p className="text-sm text-gray-500">
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
