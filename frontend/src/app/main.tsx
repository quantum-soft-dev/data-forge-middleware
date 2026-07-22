import { StrictMode } from 'react'
import { createRoot } from 'react-dom/client'
import { Auth0Provider } from './providers/Auth0Provider'
import App from './App'
// Geist 400/500/600 self-hosted (023, F13 — design handoff: weights 400/500/600 only, never 700)
import '@fontsource/geist-sans/400.css'
import '@fontsource/geist-sans/500.css'
import '@fontsource/geist-sans/600.css'
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
