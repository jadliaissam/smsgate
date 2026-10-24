import { StrictMode } from 'react'
import { createRoot } from 'react-dom/client'
import '@fontsource-variable/inter'
import './index.css'
import { ToastProvider } from './ui/Toast'
import { Placeholder } from './pages/Placeholder'

// Temporary entry until the router lands (W3 replaces this with router.tsx).
createRoot(document.getElementById('root')!).render(
  <StrictMode>
    <ToastProvider>
      <Placeholder />
    </ToastProvider>
  </StrictMode>,
)
