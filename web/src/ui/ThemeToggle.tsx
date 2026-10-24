import { useEffect, useState } from 'react'
import { cx, focusRing } from './cx'
import { MonitorIcon, MoonIcon, SunIcon } from './icons'
import { applyTheme, readThemePreference, writeThemePreference, type ThemePreference } from './theme'

const order: ThemePreference[] = ['system', 'light', 'dark']
const icons = { system: MonitorIcon, light: SunIcon, dark: MoonIcon }
const labels = { system: 'System', light: 'Light', dark: 'Dark' }

/** Cycles system → light → dark; persisted, applied to <html> immediately. */
export function ThemeToggle({ className }: { className?: string }) {
  const [pref, setPref] = useState<ThemePreference>(readThemePreference)

  useEffect(() => {
    applyTheme(pref)
    if (pref !== 'system') return
    const mq = window.matchMedia?.('(prefers-color-scheme: dark)')
    if (!mq) return
    const onChange = () => applyTheme('system')
    mq.addEventListener('change', onChange)
    return () => mq.removeEventListener('change', onChange)
  }, [pref])

  const next = () => {
    const value = order[(order.indexOf(pref) + 1) % order.length]!
    writeThemePreference(value)
    setPref(value)
  }

  return (
    <button
      type="button"
      onClick={next}
      aria-label={`Theme: ${labels[pref]} (switch to ${labels[order[(order.indexOf(pref) + 1) % order.length]!]})`}
      title={`Theme: ${labels[pref]}`}
      className={cx(
        'relative flex size-9 items-center justify-center overflow-hidden rounded-xl text-[18px] text-fg-muted',
        'transition-colors duration-150 hover:bg-surface-hover hover:text-fg motion-reduce:transition-none',
        focusRing,
        className,
      )}
    >
      {order.map((key) => {
        const Icon = icons[key]
        const active = key === pref
        return (
          <Icon
            key={key}
            className={cx(
              'absolute transition-[opacity,rotate,scale] duration-200 ease-out',
              'motion-reduce:transition-none motion-reduce:scale-none motion-reduce:rotate-none',
              active ? 'scale-100 rotate-0 opacity-100' : 'scale-50 -rotate-90 opacity-0',
            )}
          />
        )
      })}
    </button>
  )
}
