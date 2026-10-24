import { useId, type ReactNode } from 'react'
import { Link } from '@tanstack/react-router'
import { isApiError } from '../api/client'
import { errorCopy } from '../api/errors'
import { useApiKeys, useDevices } from '../api/queries'
import { ArrowRightIcon, KeyIcon, MailIcon, PhoneIcon, SendIcon } from '../layout/icons'
import { ResendButton } from '../layout/ResendButton'
import { useSessionUser } from '../layout/session'
import { Button } from '../ui/Button'
import { Card } from '../ui/Card'
import { CopyButton } from '../ui/CopyButton'
import { buttonStyles, cx } from '../ui/cx'
import { AlertIcon, CheckIcon } from '../ui/icons'
import { Skeleton } from '../ui/Skeleton'

function greeting(hour: number): string {
  if (hour < 5) return 'Good evening'
  if (hour < 12) return 'Good morning'
  if (hour < 18) return 'Good afternoon'
  return 'Good evening'
}

/** "ada.lovelace@example.com" → "Ada". */
function firstName(email: string): string {
  const local = email.split('@')[0] ?? ''
  const word = local.split(/[._+-]/)[0] || local
  return word.charAt(0).toUpperCase() + word.slice(1)
}

function curlSnippet(origin: string, deviceId: string | undefined): string {
  const body = JSON.stringify({ device_id: deviceId ?? '<device-id>', to: '+37060000000', body: 'Hello from SMSGate' })
  return [
    `curl -X POST ${origin}/v1/messages \\`,
    `  -H "Authorization: Bearer $SMSGATE_API_KEY" \\`,
    `  -H "Content-Type: application/json" \\`,
    `  -d '${body}'`,
  ].join('\n')
}

export function Overview() {
  const me = useSessionUser()
  const devices = useDevices()
  const keys = useApiKeys()

  return (
    <>
      <section className="relative mb-8 overflow-hidden rounded-2xl border border-border bg-surface p-6 shadow-card sm:p-8">
        <div
          aria-hidden="true"
          className="pointer-events-none absolute -top-24 -right-16 size-80 rounded-full bg-radial from-brand-400/25 to-transparent to-70% dark:from-brand-500/20"
        />
        <div
          aria-hidden="true"
          className="pointer-events-none absolute -bottom-32 left-1/3 size-72 rounded-full bg-radial from-brand-200/40 to-transparent to-70% dark:from-brand-800/30"
        />
        <p className="relative text-sm font-medium text-brand-600 dark:text-brand-400">Get started</p>
        <h1 className="relative mt-1 text-2xl font-semibold tracking-tight text-balance text-fg sm:text-3xl">
          {greeting(new Date().getHours())}, {firstName(me.email)}
        </h1>
        <p className="relative mt-2 max-w-xl text-sm text-pretty text-fg-muted sm:text-[15px]">
          Three steps and your phone becomes an SMS gateway. Here’s where you are.
        </p>
      </section>

      <Checklist verified={me.email_verified} devices={devices} keys={keys} />
    </>
  )
}

interface ChecklistProps {
  verified: boolean
  devices: ReturnType<typeof useDevices>
  keys: ReturnType<typeof useApiKeys>
}

function Checklist({ verified, devices, keys }: ChecklistProps) {
  const error = devices.error ?? keys.error

  if (error) {
    const retrying = devices.isFetching || keys.isFetching
    return (
      <Card role="alert" className="flex flex-col items-center py-12 text-center">
        <span className="mb-4 flex size-11 items-center justify-center rounded-2xl bg-danger-50 text-xl text-danger-600 dark:bg-danger-500/10 dark:text-danger-400">
          <AlertIcon />
        </span>
        <h2 className="text-[15px] font-semibold tracking-tight text-fg">Couldn’t load your setup</h2>
        <p className="mt-1.5 max-w-sm text-sm text-pretty text-fg-muted">
          {errorCopy(isApiError(error) ? error.code : 'default')}
        </p>
        <Button
          variant="secondary"
          className="mt-6"
          loading={retrying}
          onClick={() => {
            if (devices.isError) void devices.refetch()
            if (keys.isError) void keys.refetch()
          }}
        >
          Retry
        </Button>
      </Card>
    )
  }

  if (!devices.data || !keys.data) return <ChecklistSkeleton />

  const firstDevice = devices.data[0]
  const paired = devices.data.length > 0
  const hasKey = keys.data.some((k) => k.revoked_at === null)
  const done = [verified, paired, hasKey].filter(Boolean).length

  return (
    <Card padding="none">
      <div className="flex items-center gap-4 border-b border-border p-5 sm:p-6">
        <ProgressRing done={done} total={3} />
        <div className="min-w-0">
          <h2 className="text-[15px] font-semibold tracking-tight text-fg">Set up your gateway</h2>
          <p className="mt-0.5 text-sm text-fg-muted">
            {done} of 3 done{done === 3 && ' — you’re ready to send.'}
          </p>
        </div>
      </div>

      <ol className="divide-y divide-border">
        <Step
          index={1}
          done={verified}
          icon={<MailIcon />}
          title="Verify your email"
          description={
            verified ? 'Your email address is confirmed.' : 'Click the link we emailed you. API keys need a verified email.'
          }
          action={verified ? null : <ResendButton />}
        />
        <Step
          index={2}
          done={paired}
          icon={<PhoneIcon />}
          title="Pair a phone"
          description={
            paired
              ? `${devices.data.length} ${devices.data.length === 1 ? 'phone' : 'phones'} paired.`
              : 'Install the SMSGate app on an Android phone and scan the pairing QR code.'
          }
          action={<StepLink to="/devices" primary={!paired} label={paired ? 'View devices' : 'Pair a phone'} />}
        />
        <Step
          index={3}
          done={hasKey}
          icon={<KeyIcon />}
          title="Create an API key"
          description={hasKey ? 'Your servers can authenticate.' : 'Your servers use it to call the SMSGate API.'}
          action={<StepLink to="/api-keys" primary={!hasKey && paired} label={hasKey ? 'View keys' : 'Create a key'} />}
        />
        <SendStep deviceId={firstDevice?.id} />
      </ol>
    </Card>
  )
}

function ChecklistSkeleton() {
  return (
    <Card padding="none" data-testid="checklist-skeleton" aria-busy="true" aria-label="Loading your setup">
      <div className="flex items-center gap-4 border-b border-border p-5 sm:p-6">
        <Skeleton circle className="size-14" />
        <div className="flex-1 space-y-2">
          <Skeleton className="h-4 w-40" />
          <Skeleton className="h-3.5 w-24" />
        </div>
      </div>
      {[0, 1, 2, 3].map((i) => (
        <div key={i} className="flex items-center gap-4 border-b border-border p-5 last:border-b-0 sm:px-6">
          <Skeleton circle className="size-9" />
          <div className="flex-1 space-y-2">
            <Skeleton className="h-4 w-36" />
            <Skeleton className="h-3.5 w-64 max-w-full" />
          </div>
        </div>
      ))}
    </Card>
  )
}

/** Circular progress: the arc eases to `done / total`. */
function ProgressRing({ done, total }: { done: number; total: number }) {
  const r = 24
  const c = 2 * Math.PI * r
  const complete = done === total
  return (
    <div className="relative size-14 shrink-0" aria-hidden="true">
      <svg viewBox="0 0 56 56" className="size-full -rotate-90">
        <circle cx="28" cy="28" r={r} fill="none" strokeWidth="5" className="stroke-surface-sunken" />
        <circle
          cx="28"
          cy="28"
          r={r}
          fill="none"
          strokeWidth="5"
          strokeLinecap="round"
          strokeDasharray={c}
          strokeDashoffset={c * (1 - done / total)}
          className={cx(
            'transition-[stroke-dashoffset] duration-700 ease-snappy motion-reduce:transition-none',
            complete ? 'stroke-success-500' : 'stroke-brand-500',
          )}
        />
      </svg>
      <span className="absolute inset-0 flex items-center justify-center text-sm font-semibold text-fg tabular-nums">
        {done}/{total}
      </span>
    </div>
  )
}

interface StepProps {
  index: number
  done?: boolean
  icon: ReactNode
  title: string
  description: ReactNode
  action?: ReactNode
  children?: ReactNode
  /** Informational steps have no done state. */
  informational?: boolean
}

function Step({ index, done = false, icon, title, description, action, children, informational = false }: StepProps) {
  const titleId = useId()
  return (
    <li
      aria-labelledby={titleId}
      data-done={informational ? undefined : String(done)}
      className="group flex gap-4 p-5 sm:px-6"
    >
      <StepMarker index={index} done={done} icon={icon} />
      <div className="min-w-0 flex-1">
        <div className="flex flex-wrap items-start justify-between gap-x-4 gap-y-3">
          <div className="min-w-0">
            <h3
              id={titleId}
              className={cx('text-sm font-semibold tracking-tight', done ? 'text-fg-muted line-through decoration-fg-subtle/50' : 'text-fg')}
            >
              {title}
            </h3>
            <p className="mt-0.5 text-sm text-fg-muted">{description}</p>
          </div>
          {action && <div className="shrink-0">{action}</div>}
        </div>
        {children}
      </div>
    </li>
  )
}

function StepMarker({ index, done, icon }: { index: number; done: boolean; icon: ReactNode }) {
  return (
    <span className="relative flex size-9 shrink-0 items-center justify-center">
      {done ? (
        <span className="flex size-9 items-center justify-center rounded-full bg-success-500 text-lg text-fg-on-brand shadow-control animate-pop-in motion-reduce:animate-none">
          <CheckIcon strokeWidth={2.5} />
          <span className="sr-only">Done:</span>
        </span>
      ) : (
        <span className="flex size-9 items-center justify-center rounded-full border border-border bg-surface-raised text-[17px] text-fg-muted shadow-control">
          {icon}
          <span className="sr-only">Step {index}:</span>
        </span>
      )}
    </span>
  )
}

function StepLink({ to, label, primary }: { to: '/devices' | '/api-keys'; label: string; primary: boolean }) {
  return (
    <Link to={to} className={cx(buttonStyles(primary ? 'primary' : 'secondary', 'sm'), 'group/link')}>
      {label}
      <ArrowRightIcon className="size-3.5 transition-transform duration-150 motion-safe:group-hover/link:translate-x-0.5 motion-reduce:transition-none" />
    </Link>
  )
}

function SendStep({ deviceId }: { deviceId: string | undefined }) {
  const snippet = curlSnippet(window.location.origin, deviceId)
  return (
    <Step
      index={4}
      informational
      icon={<SendIcon />}
      title="Send your first SMS"
      description="One HTTP call with your API key. Replace the number with your own to try it."
    >
      <div className="mt-4 overflow-hidden rounded-xl border border-border bg-surface-sunken">
        <div className="flex items-center justify-between border-b border-border py-1 pr-1.5 pl-4">
          <span className="font-mono text-xs text-fg-subtle">curl</span>
          <CopyButton value={snippet} label="Copy command" />
        </div>
        <pre className="overflow-x-auto p-4 text-[12.5px] leading-6 text-fg">
          <code className="font-mono">{snippet}</code>
        </pre>
      </div>
    </Step>
  )
}
