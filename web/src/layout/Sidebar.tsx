import type { ComponentType, SVGProps } from 'react'
import { Link, useRouterState } from '@tanstack/react-router'
import { Badge } from '../ui/Badge'
import { cx, focusRing } from '../ui/cx'
import { HomeIcon, KeyIcon, MessageIcon, PhoneIcon } from './icons'
import { Wordmark } from './Logo'

interface NavItem {
  to: '/' | '/devices' | '/messages' | '/api-keys'
  label: string
  icon: ComponentType<SVGProps<SVGSVGElement>>
}

const items: NavItem[] = [
  { to: '/', label: 'Overview', icon: HomeIcon },
  { to: '/devices', label: 'Devices', icon: PhoneIcon },
  { to: '/messages', label: 'Messages', icon: MessageIcon },
  { to: '/api-keys', label: 'API keys', icon: KeyIcon },
]

function useActiveIndex(): number {
  const pathname = useRouterState({ select: (s) => s.location.pathname })
  return items.findIndex((i) => (i.to === '/' ? pathname === '/' : pathname === i.to || pathname.startsWith(`${i.to}/`)))
}

// The active pill is one element that slides between items (CSS transition
// on a transform), rather than a background on each link.
const slide = 'transition-transform duration-250 ease-snappy motion-reduce:transition-none'

/** Desktop sidebar (md and up). */
export function Sidebar({ plan }: { plan: string }) {
  const active = useActiveIndex()
  return (
    <aside className="fixed inset-y-0 left-0 z-30 hidden w-64 flex-col border-r border-border bg-surface/70 px-3 py-5 backdrop-blur-xl md:flex">
      <Link to="/" className={cx('mx-2 mb-8 self-start rounded-lg', focusRing)} aria-label="SMSGate overview">
        <Wordmark />
      </Link>

      <nav aria-label="Main" className="relative">
        <span
          aria-hidden="true"
          className={cx(
            'absolute inset-x-0 top-0 h-10 rounded-xl bg-brand-50 inset-ring inset-ring-brand-600/10 dark:bg-brand-500/12 dark:inset-ring-brand-400/15',
            slide,
            active < 0 && 'opacity-0',
          )}
          // 2.5rem item + 0.25rem gap per step.
          style={{ transform: `translateY(${Math.max(active, 0) * 2.75}rem)` }}
        >
          <span className="absolute top-2.5 bottom-2.5 left-0 w-[3px] rounded-r-full bg-brand-600 dark:bg-brand-400" />
        </span>
        <ul className="relative flex flex-col gap-1">
          {items.map((item) => (
            <li key={item.to} className="relative">
              <Link
                to={item.to}
                activeOptions={{ exact: item.to === '/' }}
                className={cx(
                  'flex h-10 items-center gap-3 rounded-xl px-3 text-sm font-medium text-fg-muted',
                  'transition-colors duration-150 hover:text-fg motion-reduce:transition-none',
                  'data-[status=active]:text-brand-700 dark:data-[status=active]:text-brand-300',
                  focusRing,
                )}
              >
                <item.icon className="size-[18px] shrink-0" />
                {item.label}
              </Link>
            </li>
          ))}
        </ul>
      </nav>

      <div className="mt-auto mx-2 rounded-2xl border border-border bg-surface-sunken/60 p-4">
        <Badge tone="brand" className="capitalize">
          {plan} plan
        </Badge>
        <p className="mt-2 text-xs leading-5 text-fg-muted">Your phones, your SIMs, one simple HTTP API.</p>
      </div>
    </aside>
  )
}

/** Bottom tab bar (below md). */
export function MobileTabBar() {
  const active = useActiveIndex()
  return (
    <nav
      aria-label="Tab bar"
      className="fixed inset-x-0 bottom-0 z-30 border-t border-border bg-surface/85 pb-[env(safe-area-inset-bottom)] backdrop-blur-xl md:hidden"
    >
      <div className="relative px-2 py-1.5">
        {/* One column per item: the pill is one column wide and slides by whole columns. */}
        <span
          aria-hidden="true"
          className="pointer-events-none absolute inset-y-1.5 left-2"
          style={{ width: `calc((100% - 1rem) / ${items.length})` }}
        >
          <span
            className={cx('block size-full', slide, active < 0 && 'opacity-0')}
            style={{ transform: `translateX(${Math.max(active, 0) * 100}%)` }}
          >
            <span className="mx-auto block h-full w-16 rounded-2xl bg-brand-50 dark:bg-brand-500/12" />
          </span>
        </span>
        <ul className="relative grid" style={{ gridTemplateColumns: `repeat(${items.length}, minmax(0, 1fr))` }}>
          {items.map((item) => (
            <li key={item.to} className="relative">
              <Link
                to={item.to}
                activeOptions={{ exact: item.to === '/' }}
                className={cx(
                  'flex h-12 flex-col items-center justify-center gap-0.5 rounded-xl text-[11px] font-medium text-fg-muted',
                  'transition-colors duration-150 motion-reduce:transition-none',
                  'data-[status=active]:text-brand-700 dark:data-[status=active]:text-brand-300',
                  focusRing,
                )}
              >
                <item.icon className="size-5" />
                {item.label}
              </Link>
            </li>
          ))}
        </ul>
      </div>
    </nav>
  )
}
