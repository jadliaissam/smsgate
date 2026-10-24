import { use, useId, useRef, type ReactNode, type RefObject } from 'react'
import { createPortal } from 'react-dom'
import { cx } from './cx'
import { XIcon } from './icons'
import { OverlayDepth, usePresence, useOverlayBehaviour } from './useOverlay'

export interface ModalProps {
  open: boolean
  onClose: () => void
  title: ReactNode
  description?: ReactNode
  /** Action row (buttons), right-aligned at the bottom. */
  footer?: ReactNode
  size?: 'sm' | 'md' | 'lg'
  /** Element to focus on open (defaults to the first focusable). */
  initialFocusRef?: RefObject<HTMLElement | null>
  /** Hide the corner close button (e.g. during a blocking step). */
  hideClose?: boolean
  children?: ReactNode
}

const widths = { sm: 'max-w-sm', md: 'max-w-md', lg: 'max-w-xl' }

/** Centered dialog: portal, focus trap, Esc/backdrop close, scale+fade. */
export function Modal({
  open,
  onClose,
  title,
  description,
  footer,
  size = 'md',
  initialFocusRef,
  hideClose = false,
  children,
}: ModalProps) {
  const { mounted, leaving } = usePresence(open, 150)
  const panelRef = useRef<HTMLDivElement>(null)
  const titleId = useId()
  const descId = useId()
  const depth = use(OverlayDepth) + 1
  useOverlayBehaviour(open && mounted, depth, panelRef, onClose, initialFocusRef)

  if (!mounted) return null

  return createPortal(
    <OverlayDepth value={depth}>
    <div className="fixed inset-0 z-50 flex items-end justify-center p-4 sm:items-center sm:p-6">
      <div
        data-testid="modal-backdrop"
        aria-hidden="true"
        onClick={leaving ? undefined : onClose}
        className={cx(
          'absolute inset-0 bg-overlay backdrop-blur-[2px] motion-reduce:animate-none',
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
          'relative w-full overflow-hidden rounded-2xl border border-border bg-surface-raised shadow-overlay outline-none',
          'motion-reduce:animate-none',
          leaving ? 'animate-pop-out' : 'animate-pop-in',
          widths[size],
        )}
      >
        <div className="px-6 pt-6 pb-5">
          <div className="flex items-start justify-between gap-4">
            <div className="min-w-0">
              <h2 id={titleId} className="text-base font-semibold tracking-tight text-fg">
                {title}
              </h2>
              {description && (
                <p id={descId} className="mt-1 text-sm text-fg-muted">
                  {description}
                </p>
              )}
            </div>
            {!hideClose && (
              <button
                type="button"
                onClick={onClose}
                aria-label="Close"
                data-focus-skip
                className="-mt-1 -mr-2 flex size-8 shrink-0 items-center justify-center rounded-lg text-lg text-fg-subtle transition-colors duration-150 hover:bg-surface-hover hover:text-fg focus-visible:ring-2 focus-visible:ring-brand-500/60 focus-visible:outline-none motion-reduce:transition-none"
              >
                <XIcon />
              </button>
            )}
          </div>
          {children && <div className="mt-5">{children}</div>}
        </div>
        {footer && (
          <div className="flex flex-col-reverse gap-2 border-t border-border bg-surface-sunken/60 px-6 py-4 sm:flex-row sm:justify-end">
            {footer}
          </div>
        )}
      </div>
    </div>
    </OverlayDepth>,
    document.body,
  )
}
