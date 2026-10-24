import { createContext, useEffect, useRef, useState, type RefObject } from 'react'

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

/** Nesting depth of the overlay being rendered (portals keep context). */
export const OverlayDepth = createContext(0)

interface OverlayEntry {
  depth: number
  seq: number
  panel: HTMLElement
  restoreTo: HTMLElement | null
}

// Module-level stack of open overlays: only the topmost handles Esc and Tab.
// Order is nesting depth first, then opening order (child effects run before
// parent effects, so depth, not push order, decides for nested overlays).
const stack: OverlayEntry[] = []
let seq = 0

function topmost(): OverlayEntry | undefined {
  let top: OverlayEntry | undefined
  for (const e of stack) if (!top || e.depth > top.depth || (e.depth === top.depth && e.seq > top.seq)) top = e
  return top
}

const focusablesIn = (panel: HTMLElement) => Array.from(panel.querySelectorAll<HTMLElement>(FOCUSABLE))

// Initial focus: explicit target, else the first control not marked
// data-focus-skip (e.g. the corner close button), else the panel itself.
function focusInitial(panel: HTMLElement, explicit?: HTMLElement | null) {
  const target = explicit ?? focusablesIn(panel).find((el) => !el.hasAttribute('data-focus-skip')) ?? panel
  target.focus({ preventScroll: true })
}

/**
 * Modal behaviour for an open overlay panel: focus moves inside on open, Tab
 * is trapped, Escape calls onClose, body scroll is locked, and focus returns
 * to the previously focused element when the overlay closes. With stacked
 * overlays only the topmost reacts to the keyboard.
 */
export function useOverlayBehaviour(
  open: boolean,
  depth: number,
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

    // A nested overlay that mounted in the same commit already captured the
    // real trigger; take it over and let the child fall back to this panel.
    let restoreTo: HTMLElement | null = null
    for (const e of stack) {
      if (e.depth > depth) {
        restoreTo ??= e.restoreTo
        e.restoreTo = null
      }
    }
    if (!stack.some((e) => e.depth > depth)) restoreTo = document.activeElement as HTMLElement | null

    const entry: OverlayEntry = { depth, seq: ++seq, panel, restoreTo }
    stack.push(entry)
    if (topmost() === entry) focusInitial(panel, initialFocusRef?.current)

    const onKeyDown = (e: KeyboardEvent) => {
      if (topmost() !== entry) return
      if (e.key === 'Escape') {
        e.stopPropagation()
        onCloseRef.current()
        return
      }
      if (e.key !== 'Tab') return
      const items = focusablesIn(panel)
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
      stack.splice(stack.indexOf(entry), 1)
      if (--lockCount === 0) {
        document.body.style.overflow = ''
        document.body.style.paddingRight = ''
      }
      if (entry.restoreTo?.isConnected) entry.restoreTo.focus({ preventScroll: true })
      const next = topmost()
      if (next && !next.panel.contains(document.activeElement)) focusInitial(next.panel)
    }
  }, [open, depth, panelRef, initialFocusRef])
}
