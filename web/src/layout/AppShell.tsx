import { useEffect, useId, useRef, useState } from 'react'
import { Link, Outlet } from '@tanstack/react-router'
import { useLogout } from '../api/queries'
import { cx, focusRing } from '../ui/cx'
import { XIcon } from '../ui/icons'
import { Spinner } from '../ui/Spinner'
import { ThemeToggle } from '../ui/ThemeToggle'
import { ChevronDownIcon, LogOutIcon, MailIcon } from './icons'
import { LogoMark } from './Logo'
import { ResendButton } from './ResendButton'
import { useSessionUser } from './session'
import { MobileTabBar, Sidebar } from './Sidebar'

/** Protected frame: sidebar (tab bar on mobile), top bar, page outlet. */
export function AppShell() {
  const me = useSessionUser()
  const [bannerDismissed, setBannerDismissed] = useState(false)

  return (
    <div className="min-h-dvh bg-canvas">
      <Sidebar plan={me.plan} />

      <div className="md:pl-64">
        <header className="sticky top-0 z-20 border-b border-border bg-canvas/80 backdrop-blur-xl">
          <div className="mx-auto flex h-14 max-w-5xl items-center gap-3 px-4 sm:px-6 lg:px-8">
            <Link to="/" className={cx('rounded-lg md:hidden', focusRing)} aria-label="SMSGate overview">
              <LogoMark />
            </Link>
            <div className="ml-auto flex items-center gap-1.5">
              <ThemeToggle />
              <AccountMenu email={me.email} />
            </div>
          </div>
        </header>

        <main className="mx-auto max-w-5xl px-4 pt-6 pb-28 sm:px-6 sm:pt-8 md:pb-12 lg:px-8">
          {!me.email_verified && !bannerDismissed && (
            <UnverifiedBanner email={me.email} onDismiss={() => setBannerDismissed(true)} />
          )}
          <Outlet />
        </main>
      </div>

      <MobileTabBar />
    </div>
  )
}

function UnverifiedBanner({ email, onDismiss }: { email: string; onDismiss: () => void }) {
  return (
    <section
      aria-label="Verify your email"
      className="mb-6 flex flex-col gap-3 rounded-2xl border border-warning-200 bg-warning-50/80 p-4 shadow-card animate-fade-in motion-reduce:animate-none sm:flex-row sm:items-center dark:border-warning-500/20 dark:bg-warning-500/8"
    >
      <div className="flex min-w-0 flex-1 items-start gap-3">
        <span className="flex size-9 shrink-0 items-center justify-center rounded-xl bg-warning-100 text-lg text-warning-700 dark:bg-warning-500/15 dark:text-warning-300">
          <MailIcon />
        </span>
        <div className="min-w-0 text-sm">
          <p className="font-medium text-fg">Verify your email to create API keys</p>
          <p className="mt-0.5 text-fg-muted">
            We sent a link to <span className="font-medium break-all text-fg">{email}</span>.
          </p>
        </div>
      </div>
      <div className="flex items-center gap-1.5 self-end sm:self-auto">
        <ResendButton />
        <button
          type="button"
          onClick={onDismiss}
          aria-label="Dismiss"
          className={cx(
            'flex size-8 items-center justify-center rounded-lg text-fg-muted transition-colors duration-150 hover:bg-warning-100 hover:text-fg motion-reduce:transition-none dark:hover:bg-warning-500/15',
            focusRing,
          )}
        >
          <XIcon />
        </button>
      </div>
    </section>
  )
}

function AccountMenu({ email }: { email: string }) {
  const [open, setOpen] = useState(false)
  const logout = useLogout()
  const menuId = useId()
  const root = useRef<HTMLDivElement>(null)
  const trigger = useRef<HTMLButtonElement>(null)
  const firstItem = useRef<HTMLButtonElement>(null)

  useEffect(() => {
    if (!open) return
    firstItem.current?.focus()
    const onPointer = (e: PointerEvent) => {
      if (!root.current?.contains(e.target as Node)) setOpen(false)
    }
    const onKey = (e: KeyboardEvent) => {
      if (e.key === 'Escape') {
        setOpen(false)
        trigger.current?.focus()
      }
    }
    document.addEventListener('pointerdown', onPointer)
    document.addEventListener('keydown', onKey)
    return () => {
      document.removeEventListener('pointerdown', onPointer)
      document.removeEventListener('keydown', onKey)
    }
  }, [open])

  const initial = email.charAt(0).toUpperCase()

  return (
    <div
      ref={root}
      className="relative"
      onBlur={(e) => {
        if (!e.currentTarget.contains(e.relatedTarget as Node | null)) setOpen(false)
      }}
    >
      <button
        ref={trigger}
        type="button"
        aria-label="Account menu"
        aria-haspopup="menu"
        aria-expanded={open}
        aria-controls={open ? menuId : undefined}
        onClick={() => setOpen((v) => !v)}
        className={cx(
          'flex h-9 items-center gap-2 rounded-xl pr-2 pl-1 text-sm text-fg-muted',
          'transition-colors duration-150 hover:bg-surface-hover hover:text-fg motion-reduce:transition-none',
          open && 'bg-surface-hover text-fg',
          focusRing,
        )}
      >
        <span
          aria-hidden="true"
          className="flex size-7 items-center justify-center rounded-lg bg-linear-to-br from-brand-400 to-brand-700 text-xs font-semibold text-fg-on-brand"
        >
          {initial}
        </span>
        <span className="hidden max-w-48 truncate sm:inline">{email}</span>
        <ChevronDownIcon
          className={cx('size-4 transition-transform duration-150 motion-reduce:transition-none', open && 'rotate-180')}
        />
      </button>

      {open && (
        <div
          id={menuId}
          role="menu"
          aria-label="Account"
          className="absolute right-0 z-40 mt-2 w-64 origin-top-right rounded-2xl border border-border bg-surface-raised p-1.5 shadow-overlay animate-pop-in motion-reduce:animate-none"
        >
          <div role="none" className="px-3 pt-2 pb-2.5">
            <p className="text-xs text-fg-subtle">Signed in as</p>
            <p className="truncate text-sm font-medium text-fg">{email}</p>
          </div>
          <div role="none" className="my-1 h-px bg-border" />
          <button
            ref={firstItem}
            type="button"
            role="menuitem"
            disabled={logout.isPending}
            onClick={() => logout.mutate()}
            className={cx(
              'flex h-9 w-full items-center gap-2.5 rounded-lg px-3 text-sm text-fg',
              'transition-colors duration-150 hover:bg-surface-hover focus-visible:bg-surface-hover motion-reduce:transition-none',
              'focus-visible:outline-none disabled:opacity-60',
            )}
          >
            {logout.isPending ? <Spinner size="sm" decorative /> : <LogOutIcon className="size-4 text-fg-muted" />}
            Sign out
          </button>
        </div>
      )}
    </div>
  )
}
