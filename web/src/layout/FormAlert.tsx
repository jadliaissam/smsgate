import type { ReactNode } from 'react'
import { AlertIcon } from '../ui/icons'

/** Form-level error under a form's fields; announced when it appears. */
export function FormAlert({ children }: { children: ReactNode }) {
  return (
    <div
      role="alert"
      className="flex items-start gap-2.5 rounded-xl border border-danger-200 bg-danger-50 px-3.5 py-3 text-sm text-danger-800 animate-fade-in motion-reduce:animate-none dark:border-danger-500/25 dark:bg-danger-500/10 dark:text-danger-200"
    >
      <AlertIcon className="mt-0.5 size-4 shrink-0" />
      <p>{children}</p>
    </div>
  )
}
