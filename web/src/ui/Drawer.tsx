import { useId, useRef, type ReactNode } from 'react'
import { createPortal } from 'react-dom'
import { cx } from './cx'
import { XIcon } from './icons'
import { usePresence, useOverlayBehaviour } from './useOverlay'

export interface DrawerProps {
  open: boolean
  onClose: () => void
  title: ReactNode
  description?: ReactNode
  footer?: ReactNode
  children?: ReactNode
}

/** Side panel sliding in from the right; same a11y contract as Modal. */
export function Drawer({ open, onClose, title, description, footer, children }: DrawerProps) {
  const { mounted, leaving } = usePresence(open, 180)
  const panelRef = useRef<HTMLDivElement>(null)
  const titleId = useId()
  const descId = useId()
  useOverlayBehaviour(open && mounted, panelRef, onClose)

  if (!mounted) return null

  return createPortal(
    <div className="fixed inset-0 z-50 flex justify-end">
      <div
        data-testid="drawer-backdrop"
        aria-hidden="true"
        onClick={leaving ? undefined : onClose}
        className={cx(
          'absolute inset-0 bg-overlay motion-reduce:animate-none',
          leaving ? 'animate-fade-out' : 'animate-fade-in',
        )}
      />
      <div
        ref={panelRef}
        role="dialog"
        aria-modal="true"
        aria-labelledby={titleId}
        aria-describedby={description ? descId : undefined}
        tabIndex={-1}
        className={cx(
          'relative flex h-full w-full max-w-md flex-col border-l border-border bg-surface-raised shadow-overlay outline-none',
          'motion-reduce:animate-none',
          leaving ? 'animate-slide-out-right' : 'animate-slide-in-right',
        )}
      >
        <header className="flex items-start justify-between gap-4 border-b border-border px-6 py-5">
          <div className="min-w-0">
            <h2 id={titleId} className="truncate text-base font-semibold tracking-tight text-fg">
              {title}
            </h2>
            {description && (
              <p id={descId} className="mt-1 text-sm text-fg-muted">
                {description}
              </p>
            )}
          </div>
          <button
            type="button"
            onClick={onClose}
            aria-label="Close"
            data-focus-skip
            className="-mt-1 -mr-2 flex size-8 shrink-0 items-center justify-center rounded-lg text-lg text-fg-subtle transition-colors duration-150 hover:bg-surface-hover hover:text-fg focus-visible:ring-2 focus-visible:ring-brand-500/60 focus-visible:outline-none motion-reduce:transition-none"
          >
            <XIcon />
          </button>
        </header>
        <div className="flex-1 overflow-y-auto px-6 py-6">{children}</div>
        {footer && <footer className="flex justify-end gap-2 border-t border-border px-6 py-4">{footer}</footer>}
      </div>
    </div>,
    document.body,
  )
}
