import { createContext, use, useCallback, useEffect, useMemo, useRef, useState, type ReactNode } from 'react'
import { createPortal } from 'react-dom'
import { cx, focusRing } from './cx'
import { AlertIcon, CheckCircleIcon, InfoIcon, XIcon } from './icons'
import { prefersReducedMotion } from './useOverlay'

export type ToastTone = 'success' | 'error' | 'info'

interface ToastItem {
  id: number
  tone: ToastTone
  title: string
  description?: string
  duration: number
  leaving: boolean
}

export interface ToastApi {
  show(tone: ToastTone, title: string, description?: string): number
  success(title: string, description?: string): number
  error(title: string, description?: string): number
  info(title: string, description?: string): number
  dismiss(id: number): void
}

const ToastContext = createContext<ToastApi | null>(null)

export const TOAST_DURATION_MS = 4000
export const ERROR_TOAST_DURATION_MS = 8000
const EXIT_MS = 160
const MAX_VISIBLE = 4
/** A toast live region: stacks its toasts like the container did. */
const region = 'flex w-full flex-col items-center gap-2 sm:items-end'

export function ToastProvider({ children }: { children: ReactNode }) {
  const [items, setItems] = useState<ToastItem[]>([])
  const nextId = useRef(1)
  const timers = useRef(new Map<number, number>())

  const remove = useCallback((id: number) => {
    setItems((list) => list.filter((t) => t.id !== id))
    timers.current.delete(id)
  }, [])

  const dismiss = useCallback(
    (id: number) => {
      window.clearTimeout(timers.current.get(id))
      setItems((list) => list.map((t) => (t.id === id ? { ...t, leaving: true } : t)))
      timers.current.set(
        id,
        window.setTimeout(() => remove(id), prefersReducedMotion() ? 0 : EXIT_MS),
      )
    },
    [remove],
  )

  const show = useCallback(
    (tone: ToastTone, title: string, description?: string) => {
      const id = nextId.current++
      const duration = tone === 'error' ? ERROR_TOAST_DURATION_MS : TOAST_DURATION_MS
      setItems((list) => [...list, { id, tone, title, description, duration, leaving: false }].slice(-MAX_VISIBLE))
      return id
    },
    [],
  )

  useEffect(() => {
    const map = timers.current
    return () => map.forEach((t) => window.clearTimeout(t))
  }, [])

  const api = useMemo<ToastApi>(
    () => ({
      show,
      dismiss,
      success: (title, description) => show('success', title, description),
      error: (title, description) => show('error', title, description),
      info: (title, description) => show('info', title, description),
    }),
    [show, dismiss],
  )

  const hasErrors = items.some((t) => t.tone === 'error')
  const hasPolite = items.some((t) => t.tone !== 'error')

  return (
    <ToastContext value={api}>
      {children}
      {createPortal(
        // Two sibling live regions (never nested): errors are announced
        // assertively, everything else politely.
        // Real elements, not display:contents: some browsers drop
        // announcements from live regions that generate no box.
        <div className="pointer-events-none fixed inset-x-0 bottom-0 z-[60] flex flex-col items-center p-4 sm:inset-x-auto sm:right-0 sm:items-end sm:p-6">
          <section aria-label="Notifications" aria-live="polite" aria-relevant="additions" className={region}>
            {items
              .filter((t) => t.tone !== 'error')
              .map((t) => (
                <Toast key={t.id} item={t} onDismiss={dismiss} />
              ))}
          </section>
          <section
            aria-label="Errors"
            aria-live="assertive"
            aria-relevant="additions"
            className={cx(region, hasPolite && hasErrors && 'mt-2')}
          >
            {items
              .filter((t) => t.tone === 'error')
              .map((t) => (
                <Toast key={t.id} item={t} onDismiss={dismiss} />
              ))}
          </section>
        </div>,
        document.body,
      )}
    </ToastContext>
  )
}

const toneStyles: Record<ToastTone, { icon: typeof InfoIcon; color: string }> = {
  success: { icon: CheckCircleIcon, color: 'text-success-600 dark:text-success-400' },
  error: { icon: AlertIcon, color: 'text-danger-600 dark:text-danger-400' },
  info: { icon: InfoIcon, color: 'text-brand-600 dark:text-brand-400' },
}

function Toast({ item, onDismiss }: { item: ToastItem; onDismiss: (id: number) => void }) {
  const { icon: Icon, color } = toneStyles[item.tone]
  const [hovered, setHovered] = useState(false)
  const [focused, setFocused] = useState(false)
  const remaining = useRef(item.duration)
  const paused = hovered || focused

  // Auto-dismiss countdown; pausing keeps the time left.
  useEffect(() => {
    if (paused || item.leaving) return
    const started = Date.now()
    const t = window.setTimeout(() => onDismiss(item.id), remaining.current)
    return () => {
      window.clearTimeout(t)
      remaining.current = Math.max(0, remaining.current - (Date.now() - started))
    }
  }, [paused, item.leaving, item.id, onDismiss])

  return (
    <div
      data-toast
      onMouseEnter={() => setHovered(true)}
      onMouseLeave={() => setHovered(false)}
      onFocus={() => setFocused(true)}
      onBlur={(e) => {
        if (!e.currentTarget.contains(e.relatedTarget as Node | null)) setFocused(false)
      }}
      className={cx(
        'pointer-events-auto flex w-full max-w-sm items-start gap-3 rounded-2xl border border-border bg-surface-raised/95 p-4 pr-3 shadow-overlay backdrop-blur-md',
        'motion-reduce:animate-none',
        item.leaving ? 'animate-toast-out' : 'animate-toast-in',
      )}
    >
      <Icon className={cx('mt-px size-5 shrink-0', color)} />
      <div className="min-w-0 flex-1">
        <p className="text-sm font-medium text-fg">{item.title}</p>
        {item.description && <p className="mt-0.5 text-sm text-fg-muted">{item.description}</p>}
      </div>
      <button
        type="button"
        onClick={() => onDismiss(item.id)}
        aria-label="Dismiss notification"
        className={cx(
          '-my-1 flex size-7 shrink-0 items-center justify-center rounded-lg text-fg-subtle hover:bg-surface-hover hover:text-fg',
          focusRing,
        )}
      >
        <XIcon />
      </button>
    </div>
  )
}

export function useToast(): ToastApi {
  const ctx = use(ToastContext)
  if (!ctx) throw new Error('useToast must be used inside <ToastProvider>')
  return ctx
}
