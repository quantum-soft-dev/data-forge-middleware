import { StrictMode } from 'react'
import { createRoot } from 'react-dom/client'
import App from './App'
import '@/shared/styles/index.css'

/**
 * React application entry point
 *
 * - StrictMode enabled for development checks
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
    <App />
  </StrictMode>
)
