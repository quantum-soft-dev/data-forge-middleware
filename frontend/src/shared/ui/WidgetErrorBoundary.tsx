/**
 * Widget-specific Error Boundary component.
 *
 * Provides a compact error display suitable for dashboard widgets,
 * unlike the full-page ErrorBoundary.
 */

import { Component, ErrorInfo, ReactNode } from 'react'
import { AlertCircle, RefreshCw } from 'lucide-react'
import { Button } from '@/shared/ui/ui/button'

interface Props {
  children: ReactNode
  widgetName?: string
}

interface State {
  hasError: boolean
  error: Error | null
}

export class WidgetErrorBoundary extends Component<Props, State> {
  constructor(props: Props) {
    super(props)
    this.state = {
      hasError: false,
      error: null,
    }
  }

  static getDerivedStateFromError(error: Error): Partial<State> {
    return {
      hasError: true,
      error,
    }
  }

  componentDidCatch(error: Error, errorInfo: ErrorInfo): void {
    console.error(
      `WidgetErrorBoundary [${this.props.widgetName ?? 'Unknown'}] caught an error:`,
      error,
      errorInfo
    )
  }

  handleReset = () => {
    this.setState({
      hasError: false,
      error: null,
    })
  }

  render() {
    if (this.state.hasError) {
      return (
        <div className="flex flex-col items-center justify-center rounded-lg border border-red-200 bg-red-50 p-6 text-center">
          <AlertCircle className="h-8 w-8 text-red-500 mb-3" />
          <h3 className="text-sm font-medium text-red-800 mb-1">
            {this.props.widgetName ? `${this.props.widgetName} Error` : 'Widget Error'}
          </h3>
          <p className="text-xs text-red-600 mb-4">
            Something went wrong loading this widget.
          </p>
          {process.env.NODE_ENV === 'development' && this.state.error && (
            <p className="text-xs text-red-500 mb-4 font-mono break-all max-w-full">
              {this.state.error.message}
            </p>
          )}
          <Button
            variant="outline"
            size="sm"
            onClick={this.handleReset}
            className="text-red-700 border-red-300 hover:bg-red-100"
          >
            <RefreshCw className="h-3 w-3 mr-1" />
            Try Again
          </Button>
        </div>
      )
    }

    return this.props.children
  }
}
