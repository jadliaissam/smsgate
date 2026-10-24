import type { ReactNode } from 'react'
import { Link, Outlet } from '@tanstack/react-router'
import { cx, focusRing } from '../ui/cx'
import { ThemeToggle } from '../ui/ThemeToggle'
import { Wordmark } from './Logo'

/**
 * Signed-out frame: a glass card centred on a soft brand gradient mesh
 * (radial gradients from tokens only, no images). Renders the matched auth
 * page, or `children` when used directly (404, session error).
 */
export function AuthLayout({ children }: { children?: ReactNode }) {
  return (
    <div className="relative isolate flex min-h-dvh flex-col overflow-hidden bg-canvas">
      <MeshBackground />

      <header className="flex items-center justify-between px-4 pt-4 sm:px-8 sm:pt-6">
        <Link to="/" className={cx('rounded-lg', focusRing)} aria-label="SMSGate home">
          <Wordmark />
        </Link>
        <ThemeToggle />
      </header>

      <main className="flex flex-1 items-center justify-center px-4 py-10 sm:py-16">
        <div
          className={cx(
            'w-full max-w-[26rem] rounded-2xl border border-border bg-surface/80 p-6 shadow-raised backdrop-blur-xl sm:p-8',
            'dark:bg-surface/70',
            'animate-pop-in motion-reduce:animate-none',
          )}
        >
          {children ?? <Outlet />}
        </div>
      </main>

      <footer className="px-4 pb-6 text-center text-xs text-fg-subtle">
        Send SMS through your own Android phones with one HTTP call.
      </footer>
    </div>
  )
}

function MeshBackground() {
  return (
    <div aria-hidden="true" className="pointer-events-none absolute inset-0 -z-10">
      <div className="absolute -top-[18%] -left-[12%] size-[42rem] rounded-full bg-radial from-brand-300/45 to-transparent to-70% dark:from-brand-700/30" />
      <div className="absolute -top-[10%] -right-[18%] size-[38rem] rounded-full bg-radial from-brand-500/25 to-transparent to-70% dark:from-brand-500/20" />
      <div className="absolute -bottom-[25%] left-[20%] size-[46rem] rounded-full bg-radial from-brand-200/50 to-transparent to-70% dark:from-brand-900/45" />
      {/* Faint dot grid, fading out towards the edges. */}
      <div className="absolute inset-0 bg-[radial-gradient(var(--color-border-strong)_1px,transparent_1px)] bg-size-[22px_22px] mask-radial-from-20% mask-radial-to-75% opacity-60" />
    </div>
  )
}

interface AuthHeaderProps {
  title: ReactNode
  description?: ReactNode
  icon?: ReactNode
}

/** Title block at the top of an auth card. */
export function AuthHeader({ title, description, icon }: AuthHeaderProps) {
  return (
    <div className="mb-6 text-center">
      {icon && <div className="mb-4 flex justify-center">{icon}</div>}
      <h1 className="text-xl font-semibold tracking-tight text-balance text-fg">{title}</h1>
      {description && <p className="mt-1.5 text-sm text-pretty text-fg-muted">{description}</p>}
    </div>
  )
}
