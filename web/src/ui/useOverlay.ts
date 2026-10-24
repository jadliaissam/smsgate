import { useEffect, useRef, useState, type RefObject } from 'react'

const FOCUSABLE =
  'a[href],area[href],button:not([disabled]),input:not([disabled]):not([type="hidden"]),select:not([disabled]),' +
  'textarea:not([disabled]),iframe,[contenteditable="true"],[tabindex]:not([tabindex="-1"])'

export function prefersReducedMotion(): boolean {
  return typeof window !== 'undefined' && window.matchMedia?.('(prefers-reduced-motion: reduce)').matches === true
}

/**
 * Keeps an overlay mounted for `exitMs` after `open` turns false so it can
 * play its exit animation. Returns whether to render and whether leaving.
 */
export function usePresence(open: boolean, exitMs: number): { mounted: boolean; leaving: boolean } {
  const [mounted, setMounted] = useState(open)
  // Derive mount from props during render (no effect round-trip on open).
  if (open && !mounted) setMounted(true)

  useEffect(() => {
    if (open || !mounted) return
    const t = window.setTimeout(() => setMounted(false), prefersReducedMotion() ? 0 : exitMs)
    return () => window.clearTimeout(t)
  }, [open, mounted, exitMs])

  return { mounted: mounted || open, leaving: mounted && !open }
}

let lockCount = 0

/**
 * Modal behaviour for an open overlay panel: focus moves inside on open, Tab
 * is trapped, Escape calls onClose, body scroll is locked, and focus returns
 * to the previously focused element when the overlay closes.
 */
export function useOverlayBehaviour(
  open: boolean,
  panelRef: RefObject<HTMLElement | null>,
  onClose: () => void,
  initialFocusRef?: RefObject<HTMLElement | null>,
) {
  const onCloseRef = useRef(onClose)
  useEffect(() => {
    onCloseRef.current = onClose
  })

  useEffect(() => {
    if (!open) return
    const panel = panelRef.current
    if (!panel) return
    const previouslyFocused = document.activeElement as HTMLElement | null

    const focusables = () => Array.from(panel.querySelectorAll<HTMLElement>(FOCUSABLE))

    // Initial focus: explicit target, else the first control that is not
    // marked data-focus-skip (e.g. the corner close button), else the panel.
    const first =
      initialFocusRef?.current ?? focusables().find((el) => !el.hasAttribute('data-focus-skip')) ?? panel
    first.focus({ preventScroll: true })

    const onKeyDown = (e: KeyboardEvent) => {
      if (e.key === 'Escape') {
        e.stopPropagation()
        onCloseRef.current()
        return
      }
      if (e.key !== 'Tab') return
      const items = focusables()
      if (items.length === 0) {
        e.preventDefault()
        panel.focus()
        return
      }
      const firstItem = items[0]!
      const lastItem = items[items.length - 1]!
      const active = document.activeElement
      if (e.shiftKey && (active === firstItem || !panel.contains(active))) {
        e.preventDefault()
        lastItem.focus()
      } else if (!e.shiftKey && (active === lastItem || !panel.contains(active))) {
        e.preventDefault()
        firstItem.focus()
      }
    }
    document.addEventListener('keydown', onKeyDown)

    if (lockCount++ === 0) {
      const gap = window.innerWidth - document.documentElement.clientWidth
      document.body.style.overflow = 'hidden'
      if (gap > 0) document.body.style.paddingRight = `${gap}px`
    }

    return () => {
      document.removeEventListener('keydown', onKeyDown)
      if (--lockCount === 0) {
        document.body.style.overflow = ''
        document.body.style.paddingRight = ''
      }
      if (previouslyFocused && document.contains(previouslyFocused)) previouslyFocused.focus({ preventScroll: true })
    }
  }, [open, panelRef, initialFocusRef])
}
