import { QueryClientProvider } from '@tanstack/react-query'
import { RouterProvider } from '@tanstack/react-router'
import type { AppInstance } from './router'
import { ToastProvider } from './ui/Toast'

/** Providers + router. `app` comes from `createApp()` (main.tsx, tests). */
export function App({ app }: { app: AppInstance }) {
  return (
    <QueryClientProvider client={app.queryClient}>
      <ToastProvider>
        <RouterProvider router={app.router} />
      </ToastProvider>
    </QueryClientProvider>
  )
}
