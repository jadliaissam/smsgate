import { useEffect, useMemo, useRef, useState, type ReactNode } from 'react'
import { encode } from 'uqr'
import { isApiError } from '../api/client'
import { errorCopy } from '../api/errors'
import { useCreatePairingCode, useDevices } from '../api/queries'
import type { Device } from '../api/types'
import { MailIcon } from '../layout/icons'
import { ResendButton } from '../layout/ResendButton'
import { Button } from '../ui/Button'
import { CopyButton } from '../ui/CopyButton'
import { cx } from '../ui/cx'
import { AlertIcon } from '../ui/icons'
import { Modal } from '../ui/Modal'
import { Skeleton } from '../ui/Skeleton'
import { Spinner } from '../ui/Spinner'
import { useToast } from '../ui/Toast'

/** How often the open modal polls /app/devices for the newly paired phone. */
export const PAIR_POLL_MS = 2000
/** How long the success state stays before the modal closes itself. */
export const PAIR_SUCCESS_CLOSE_MS = 1500

interface PairModalProps {
  open: boolean
  onClose: () => void
}

/**
 * [Review Focus 3] Generates a pairing code on open and shows it as a QR
 * code plus the typed form, counts down to expires_at (then offers a new
 * code), and polls the devices list until a device that was not there at
 * open time appears.
 */
export function PairModal({ open, onClose }: PairModalProps) {
  return (
    <Modal
      open={open}
      onClose={onClose}
      size="lg"
      title="Pair a phone"
      description="Link an Android phone to your account by scanning a code with the SMSGate app."
    >
      {/* Mounted only while the dialog is: every open starts a fresh flow. */}
      <PairFlow active={open} onDone={onClose} />
    </Modal>
  )
}

interface Issued {
  code: string
  qrPayload: string
  expiresAt: number
  issuedAt: number
}

function PairFlow({ active, onDone }: { active: boolean; onDone: () => void }) {
  const toast = useToast()
  const create = useCreatePairingCode()
  const [issued, setIssued] = useState<Issued | null>(null)
  const [now, setNow] = useState(() => Date.now())
  const [baseline, setBaseline] = useState<ReadonlySet<string> | null>(null)
  const [paired, setPaired] = useState<Device | null>(null)

  const generate = () =>
    create.mutate(undefined, {
      onSuccess: (pc) => {
        // Client-clock deadline from the server-measured TTL (skew-free).
        setNow(pc.receivedAt)
        setIssued({
          code: pc.code,
          qrPayload: pc.qr_payload,
          expiresAt: pc.receivedAt + pc.ttlMs,
          issuedAt: pc.receivedAt,
        })
      },
    })

  // One code per open (the ref also absorbs StrictMode's double effect).
  const started = useRef(false)
  useEffect(() => {
    if (started.current) return
    started.current = true
    generate()
    // eslint-disable-next-line react-hooks/exhaustive-deps -- once, on open
  }, [])

  const unverified = isApiError(create.error, 'email_not_verified')
  const devices = useDevices({
    refetchInterval: active && !paired && !unverified ? PAIR_POLL_MS : false,
    // The cached list may be stale: fetch afresh on open for the baseline.
    refetchOnMount: 'always',
  })

  // Ids present when the modal opened (the first successful fetch after it
  // mounted, never the cached list); anything new afterwards is our phone.
  if (baseline === null && devices.isFetchedAfterMount && devices.status === 'success') {
    setBaseline(new Set(devices.data.map((d) => d.id)))
  }
  const fresh = baseline && !paired ? devices.data?.find((d) => !baseline.has(d.id)) : undefined
  if (fresh) setPaired(fresh)

  useEffect(() => {
    if (!paired) return
    toast.success(`${paired.name} paired`, 'It’s ready to send messages.')
    const t = window.setTimeout(onDone, PAIR_SUCCESS_CLOSE_MS)
    return () => window.clearTimeout(t)
    // eslint-disable-next-line react-hooks/exhaustive-deps -- once per paired device
  }, [paired])

  // Countdown: tick exactly on whole seconds of the time left.
  const expiresAt = issued?.expiresAt ?? null
  useEffect(() => {
    if (expiresAt === null) return
    let interval: number | undefined
    const tick = () => {
      const t = Date.now()
      setNow(t)
      if (t >= expiresAt) window.clearInterval(interval)
    }
    const first = (expiresAt - Date.now()) % 1000
    const timeout = window.setTimeout(
      () => {
        // Assign first: the first tick may already be past expiry and must
        // be able to clear the interval.
        interval = window.setInterval(tick, 1000)
        tick()
      },
      first > 0 ? first : 1000,
    )
    return () => {
      window.clearTimeout(timeout)
      window.clearInterval(interval)
    }
  }, [expiresAt])

  if (paired) return <PairedView device={paired} />

  if (create.isError && !issued) {
    if (unverified) return <VerifyPrompt />
    return (
      <div role="alert" className="flex flex-col items-center py-8 text-center">
        <span className="mb-4 flex size-11 items-center justify-center rounded-2xl bg-danger-50 text-xl text-danger-600 dark:bg-danger-500/10 dark:text-danger-400">
          <AlertIcon />
        </span>
        <p className="text-[15px] font-semibold tracking-tight text-fg">Couldn’t create a pairing code</p>
        <p className="mt-1.5 max-w-sm text-sm text-pretty text-fg-muted">
          {errorCopy(isApiError(create.error) ? create.error.code : 'default')}
        </p>
        <Button variant="secondary" className="mt-6" loading={create.isPending} onClick={generate}>
          Try again
        </Button>
      </div>
    )
  }

  const leftMs = issued ? Math.max(0, issued.expiresAt - now) : 0
  const expired = issued !== null && leftMs <= 0

  return (
    <div className="grid gap-6 sm:grid-cols-[auto_1fr] sm:items-center sm:gap-8">
      <div className="relative mx-auto w-56 sm:w-52">
        {/* Soft brand glow behind the card; the card itself is qr-surface (white) in both themes so phones can scan it. */}
        <div
          aria-hidden="true"
          className="absolute -inset-3 rounded-[28px] bg-linear-to-br from-brand-400/60 via-brand-500/35 to-brand-700/50 blur-xl animate-glow motion-reduce:animate-none dark:from-brand-500/50 dark:to-brand-800/50"
        />
        <div className="relative aspect-square overflow-hidden rounded-2xl bg-qr-surface p-2 shadow-raised ring-1 ring-ink-950/5">
          {issued ? (
            <QrCode
              value={issued.qrPayload}
              className={cx(
                'size-full transition-[filter,opacity] duration-200 ease-out motion-reduce:transition-none',
                (expired || create.isPending) && 'opacity-40 blur-[3px]',
              )}
            />
          ) : (
            <Skeleton className="size-full rounded-xl" />
          )}
          {expired && (
            <div className="absolute inset-0 flex flex-col items-center justify-center gap-3 bg-qr-surface/70 p-4 text-center animate-fade-in motion-reduce:animate-none">
              <p className="text-sm font-semibold text-ink-900">Code expired</p>
              <Button size="sm" loading={create.isPending} onClick={generate}>
                Generate new code
              </Button>
            </div>
          )}
          {!expired && create.isPending && issued && (
            <div className="absolute inset-0 flex items-center justify-center text-brand-600">
              <Spinner size="lg" label="Generating a new code" />
            </div>
          )}
        </div>
      </div>

      <div className="min-w-0">
        <ol className="space-y-3">
          <Step n={1}>Install the SMSGate app (APK) on your Android phone.</Step>
          <Step n={2}>Open the app and choose to pair with a QR code.</Step>
          <Step n={3}>Scan this code — or type the code below.</Step>
        </ol>

        <div className="mt-5 rounded-2xl border border-border bg-surface-sunken/60 p-4">
          <p className="text-xs font-medium text-fg-muted">Pairing code</p>
          <div className="mt-1 flex items-center gap-3">
            {issued ? (
              <span
                className={cx(
                  'font-mono text-2xl font-semibold tracking-[0.18em] text-fg tabular-nums sm:text-[28px]',
                  expired && 'text-fg-subtle line-through decoration-2',
                )}
              >
                {formatCode(issued.code)}
              </span>
            ) : (
              <Skeleton className="h-8 w-44" />
            )}
            {issued && !expired && <CopyButton value={issued.code} label="Copy code" className="shrink-0" />}
            <div className="ml-auto">
              {issued && <CountdownRing leftMs={leftMs} totalMs={issued.expiresAt - issued.issuedAt} />}
            </div>
          </div>
        </div>

        <p className="mt-4 flex items-center gap-2 text-[13px] text-fg-muted" aria-live="polite">
          {expired ? (
            'This code has expired. Generate a new one to continue.'
          ) : (
            <>
              <span className="relative flex size-2" aria-hidden="true">
                <span className="absolute inset-0 rounded-full bg-brand-500 animate-ping-soft motion-reduce:animate-none" />
                <span className="relative size-2 rounded-full bg-brand-500" />
              </span>
              Waiting for your phone…
            </>
          )}
        </p>
      </div>
    </div>
  )
}

/** "ABCD2345" → "ABCD-2345". */
function formatCode(code: string): string {
  return code.length === 8 ? `${code.slice(0, 4)}-${code.slice(4)}` : code
}

function Step({ n, children }: { n: number; children: ReactNode }) {
  return (
    <li className="flex gap-3 text-sm text-fg">
      <span className="flex size-6 shrink-0 items-center justify-center rounded-full bg-brand-50 text-xs font-semibold text-brand-700 inset-ring inset-ring-brand-600/15 dark:bg-brand-500/15 dark:text-brand-300">
        {n}
      </span>
      <span className="pt-0.5 text-pretty">{children}</span>
    </li>
  )
}

/**
 * QR code drawn as React SVG from uqr's module matrix (no innerHTML). Dark
 * modules are near-black on white with a 4-module quiet zone, whatever the
 * theme, so cameras read it reliably.
 */
function QrCode({ value, className }: { value: string; className?: string }) {
  const { path, size } = useMemo(() => {
    const qr = encode(value, { ecc: 'M', border: 0 })
    let d = ''
    qr.data.forEach((row, y) => {
      let x = 0
      while (x < row.length) {
        if (!row[x]) {
          x++
          continue
        }
        const start = x
        while (x < row.length && row[x]) x++
        d += `M${start} ${y}h${x - start}v1h${start - x}z`
      }
    })
    return { path: d, size: qr.size }
  }, [value])
  const quiet = 4
  return (
    <svg
      role="img"
      aria-label="Pairing QR code"
      viewBox={`${-quiet} ${-quiet} ${size + quiet * 2} ${size + quiet * 2}`}
      shapeRendering="crispEdges"
      className={className}
    >
      <rect x={-quiet} y={-quiet} width={size + quiet * 2} height={size + quiet * 2} className="fill-qr-surface" />
      <path d={path} className="fill-ink-950" />
    </svg>
  )
}

function CountdownRing({ leftMs, totalMs }: { leftMs: number; totalMs: number }) {
  const r = 20
  const c = 2 * Math.PI * r
  const fraction = totalMs > 0 ? Math.min(1, leftMs / totalMs) : 0
  const seconds = Math.ceil(leftMs / 1000)
  const label = `${Math.floor(seconds / 60)}:${String(seconds % 60).padStart(2, '0')}`
  const low = seconds <= 60
  return (
    <div className="relative size-14 shrink-0">
      <svg viewBox="0 0 48 48" className="size-full -rotate-90" aria-hidden="true">
        <circle cx="24" cy="24" r={r} fill="none" strokeWidth="3.5" className="stroke-border" />
        <circle
          cx="24"
          cy="24"
          r={r}
          fill="none"
          strokeWidth="3.5"
          strokeLinecap="round"
          strokeDasharray={c}
          strokeDashoffset={c * (1 - fraction)}
          className={cx(
            'transition-[stroke-dashoffset,stroke] duration-1000 ease-linear motion-reduce:transition-none',
            low ? 'stroke-warning-500' : 'stroke-brand-500',
          )}
        />
      </svg>
      <span
        role="timer"
        className={cx(
          'absolute inset-0 flex items-center justify-center text-xs font-semibold tabular-nums',
          low ? 'text-warning-700 dark:text-warning-300' : 'text-fg',
        )}
      >
        <span className="sr-only">Code expires in </span>
        {label}
      </span>
    </div>
  )
}

function PairedView({ device }: { device: Device }) {
  return (
    <div role="status" className="flex flex-col items-center py-8 text-center">
      <div className="relative mb-5">
        <span
          aria-hidden="true"
          className="absolute inset-0 rounded-full bg-success-500/40 animate-ping-soft motion-reduce:animate-none"
        />
        <span className="relative flex size-16 items-center justify-center rounded-full bg-linear-to-br from-success-600 to-success-700 text-fg-on-success shadow-raised animate-success-pop motion-reduce:animate-none">
          <svg
            viewBox="0 0 24 24"
            fill="none"
            stroke="currentColor"
            strokeWidth={2.5}
            strokeLinecap="round"
            strokeLinejoin="round"
            aria-hidden="true"
            className="size-8"
          >
            <path
              d="m5 12.5 4.5 4.5L19 7.5"
              strokeDasharray="24"
              className="animate-check [animation-delay:180ms] motion-reduce:animate-none"
            />
          </svg>
        </span>
      </div>
      <h3 className="text-lg font-semibold tracking-tight text-fg">{device.name} is paired</h3>
      <p className="mt-1 text-sm text-fg-muted">It’s ready to send messages.</p>
    </div>
  )
}

function VerifyPrompt() {
  return (
    <div className="flex flex-col items-center py-6 text-center">
      <span className="mb-4 flex size-12 items-center justify-center rounded-2xl bg-brand-50 text-2xl text-brand-600 inset-ring inset-ring-brand-600/15 dark:bg-brand-500/15 dark:text-brand-300">
        <MailIcon />
      </span>
      <p className="text-[15px] font-semibold tracking-tight text-fg">Verify your email to pair a phone</p>
      <p className="mt-1.5 max-w-sm text-sm text-pretty text-fg-muted">
        Click the link we emailed you, then come back here. Didn’t get it? We can send a new one.
      </p>
      <ResendButton className="mt-5" />
    </div>
  )
}
