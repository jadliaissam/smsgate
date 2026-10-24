import { StrictMode } from 'react'
import { createRoot } from 'react-dom/client'
import '@fontsource-variable/inter'
import './index.css'
import { App } from './App'
import { createApp } from './router'

const app = createApp()

createRoot(document.getElementById('root')!).render(
  <StrictMode>
    <App app={app} />
  </StrictMode>,
)
