import { StrictMode } from 'react'
import { createRoot } from 'react-dom/client'
import { Auth0Provider } from './providers/Auth0Provider'
import App from './App'
import '@/shared/styles/index.css'

/**
 * React application entry point
 *
 * - StrictMode enabled for development checks
 * - Auth0Provider wraps application for authentication
 * - Global styles imported
 * - Root element must exist in index.html
 */
const rootElement = document.getElementById('root')

if (!rootElement) {
  throw new Error(
    'Root element not found. Make sure index.html has <div id="root"></div>'
  )
}

createRoot(rootElement).render(
  <StrictMode>
    <Auth0Provider>
      <App />
    </Auth0Provider>
  </StrictMode>
)
