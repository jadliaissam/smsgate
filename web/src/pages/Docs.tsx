import { useEffect, useState, type MouseEvent, type ReactNode } from 'react'
import { Link } from '@tanstack/react-router'
import { APK_URL, useApk } from '../api/apk'
import { useDevices } from '../api/queries'
import { BookIcon, DownloadIcon, KeyIcon, PhoneIcon, SendIcon, WebhookIcon } from '../layout/icons'
import { PageHeader } from '../layout/PageHeader'
import { Badge } from '../ui/Badge'
import { Card } from '../ui/Card'
import { CopyButton } from '../ui/CopyButton'
import { buttonStyles, cx, focusRing } from '../ui/cx'
import { InfoIcon } from '../ui/icons'
import { Skeleton } from '../ui/Skeleton'
import { CodeBlock, CodeSample } from './CodeSample'
import {
  API_KEY_PLACEHOLDER,
  DEVICE_PLACEHOLDER,
  deliveredExample,
  deviceSamples,
  devicesExample,
  errorCodes,
  errorExample,
  getSamples,
  limits,
  listExample,
  listParams,
  listSamples,
  messageExample,
  sendFields,
  sendSamples,
  type Field,
  type Lang,
} from './docsContent'
import { LifecycleDiagram } from './LifecycleDiagram'

const sections = [
  { id: 'quickstart', title: 'Quickstart' },
  { id: 'authentication', title: 'Authentication' },
  { id: 'send', title: 'Send a message' },
  { id: 'get', title: 'Get a message' },
  { id: 'list', title: 'List messages' },
  { id: 'devices', title: 'List devices' },
  { id: 'lifecycle', title: 'Message lifecycle' },
  { id: 'errors', title: 'Errors' },
  { id: 'rate-limits', title: 'Rate limits' },
  { id: 'webhooks', title: 'Webhooks' },
  { id: 'android', title: 'Android app' },
] as const

type SectionId = (typeof sections)[number]['id']

const LANG_KEY = 'smsgate.docs.lang'

function readLang(): Lang {
  try {
    const v = localStorage.getItem(LANG_KEY)
    if (v === 'curl' || v === 'js' || v === 'python') return v
  } catch {
    // storage unavailable
  }
  return 'curl'
}

function useLang(): [Lang, (l: Lang) => void] {
  const [lang, setLang] = useState<Lang>(readLang)
  const set = (l: Lang) => {
    setLang(l)
    try {
      localStorage.setItem(LANG_KEY, l)
    } catch {
      // storage unavailable: the choice lasts for this visit
    }
  }
  return [lang, set]
}

/** The section nearest the top of the viewport (for the table of contents). */
function useActiveSection(): SectionId {
  const [active, setActive] = useState<SectionId>(sections[0].id)
  useEffect(() => {
    if (typeof IntersectionObserver === 'undefined') return
    const visible = new Set<string>()
    const io = new IntersectionObserver(
      (entries) => {
        for (const e of entries) {
          if (e.isIntersecting) visible.add(e.target.id)
          else visible.delete(e.target.id)
        }
        const first = sections.find((s) => visible.has(s.id))
        if (first) setActive(first.id)
      },
      { rootMargin: '-72px 0px -55% 0px' },
    )
    for (const s of sections) {
      const el = document.getElementById(s.id)
      if (el) io.observe(el)
    }
    return () => io.disconnect()
  }, [])
  return active
}

function prefersReducedMotion(): boolean {
  return window.matchMedia?.('(prefers-reduced-motion: reduce)').matches ?? false
}

/** In-page jump without touching the router's history entry. */
function jump(e: MouseEvent<HTMLAnchorElement>, id: string) {
  const el = document.getElementById(id)
  if (!el) return
  e.preventDefault()
  el.scrollIntoView({ behavior: prefersReducedMotion() ? 'auto' : 'smooth', block: 'start' })
  el.querySelector<HTMLElement>('h2')?.focus({ preventScroll: true })
}

// ---- small building blocks -------------------------------------------------

/** Inline code. */
function C({ children }: { children: ReactNode }) {
  return (
    <code className="rounded-md bg-surface-sunken px-1.5 py-0.5 font-mono text-[0.85em] text-fg inset-ring inset-ring-border">
      {children}
    </code>
  )
}

function Section({ id, title, description, children }: { id: SectionId; title: string; description?: ReactNode; children: ReactNode }) {
  return (
    <section id={id} aria-labelledby={`${id}-h`} className="scroll-mt-20">
      <Card padding="lg">
        <h2 id={`${id}-h`} tabIndex={-1} className="text-lg font-semibold tracking-tight text-fg focus:outline-none">
          {title}
        </h2>
        {description && <div className="mt-1.5 text-sm leading-6 text-pretty text-fg-muted">{description}</div>}
        <div className="mt-6 space-y-6">{children}</div>
      </Card>
    </section>
  )
}

function Endpoint({ method, path, base }: { method: 'GET' | 'POST'; path: string; base: string }) {
  return (
    <div className="flex min-w-0 items-center gap-3 rounded-xl border border-border bg-surface-sunken/60 py-1.5 pr-1.5 pl-2">
      <Badge tone={method === 'POST' ? 'brand' : 'success'} className="font-mono tracking-wide">
        {method}
      </Badge>
      <code className="min-w-0 flex-1 truncate font-mono text-sm text-fg">{path}</code>
      <CopyButton value={`${base}${path}`} label="Copy URL" />
    </div>
  )
}

function SubHeading({ children }: { children: ReactNode }) {
  return <h3 className="mb-3 text-sm font-semibold text-fg">{children}</h3>
}

function FieldList({ fields, caption }: { fields: Field[]; caption: string }) {
  return (
    <div>
      <SubHeading>{caption}</SubHeading>
      <dl className="divide-y divide-border overflow-hidden rounded-xl border border-border">
        {fields.map((f) => (
          <div key={f.name} className="grid gap-1.5 px-4 py-3 sm:grid-cols-[12rem_minmax(0,1fr)] sm:gap-4">
            <dt className="flex flex-wrap items-center gap-x-2 gap-y-1">
              <code className="font-mono text-[13px] font-medium text-fg">{f.name}</code>
              <span className="font-mono text-xs text-fg-subtle">{f.type}</span>
              {f.required && (
                <span className="text-[11px] font-medium tracking-wide text-warning-800 uppercase dark:text-warning-300">
                  required
                </span>
              )}
            </dt>
            <dd className="text-sm leading-6 text-fg-muted">{f.description}</dd>
          </div>
        ))}
      </dl>
    </div>
  )
}

function Note({ children }: { children: ReactNode }) {
  return (
    <div className="flex gap-3 rounded-xl border border-brand-600/15 bg-brand-50/70 p-4 text-sm leading-6 text-fg dark:border-brand-400/20 dark:bg-brand-500/10">
      <InfoIcon className="mt-1 size-4 shrink-0 text-brand-600 dark:text-brand-400" />
      <div className="min-w-0">{children}</div>
    </div>
  )
}

function statusTone(status: number) {
  if (status === 429) return 'warning' as const
  if (status >= 500) return 'danger' as const
  return 'neutral' as const
}

function formatSize(bytes: number): string {
  return `${(bytes / (1024 * 1024)).toFixed(1)} MB`
}

// ---- page -------------------------------------------------------------------

/** API reference with copy-ready samples prefilled for this account. */
export function Docs() {
  const base = window.location.origin
  const devicesQ = useDevices()
  const first = devicesQ.data?.[0]
  const deviceId = first?.id ?? DEVICE_PLACEHOLDER
  const noDevices = devicesQ.isSuccess && !first
  const [lang, setLang] = useLang()
  const active = useActiveSection()
  const sample = { lang, onLang: setLang }

  return (
    <div className="xl:grid xl:grid-cols-[minmax(0,1fr)_10.5rem] xl:gap-10">
      <div className="min-w-0">
        <PageHeader
          title="API docs"
          description="Send SMS from your own phones with one HTTP request. Samples below are filled in for your account."
        />

        <div className="mb-6 flex flex-wrap items-center gap-3 rounded-2xl border border-border bg-surface/70 py-2 pr-2 pl-4 shadow-card">
          <span className="text-xs font-medium tracking-wide text-fg-subtle uppercase">Base URL</span>
          <code className="min-w-0 flex-1 truncate font-mono text-sm text-fg">{base}</code>
          <CopyButton value={base} label="Copy base URL" />
        </div>

        <div className="space-y-6">
          <Section
            id="quickstart"
            title="Quickstart"
            description="Three steps from zero to your first SMS."
          >
            <ol className="grid gap-3 sm:grid-cols-3">
              <QuickStep n={1} icon={<PhoneIcon />} title="Pair a phone">
                {first ? (
                  <>
                    Samples use <span className="font-medium text-fg">{first.name}</span>.{' '}
                    <Link to="/devices" className={cx('font-medium text-brand-700 hover:underline dark:text-brand-300', focusRing, 'rounded')}>
                      Manage devices
                    </Link>
                  </>
                ) : (
                  <>Install the Android app, then scan the pairing QR from Devices.</>
                )}
              </QuickStep>
              <QuickStep n={2} icon={<KeyIcon />} title="Create an API key">
                Keys are shown once.{' '}
                <Link to="/api-keys" className={cx('font-medium text-brand-700 hover:underline dark:text-brand-300', focusRing, 'rounded')}>
                  Create one
                </Link>{' '}
                and replace <C>{API_KEY_PLACEHOLDER}</C>.
              </QuickStep>
              <QuickStep n={3} icon={<SendIcon />} title="Send a message">
                Run the request below. It answers <C>202</C> with the queued message.
              </QuickStep>
            </ol>

            {noDevices && (
              <Note>
                No phone is paired yet, so the samples use <C>{DEVICE_PLACEHOLDER}</C>.{' '}
                <Link to="/devices" className={cx('font-medium text-brand-700 underline dark:text-brand-300', focusRing, 'rounded')}>
                  Pair a phone
                </Link>{' '}
                and this page fills in its id.
              </Note>
            )}

            <CodeSample samples={sendSamples(base, deviceId)} label="Copy send request" {...sample} />
          </Section>

          <Section
            id="authentication"
            title="Authentication"
            description={
              <>
                Every <C>/v1</C> request carries an API key as a bearer token. A missing, wrong or revoked key gets{' '}
                <C>401 unauthorized</C>.
              </>
            }
          >
            <CodeBlock caption="Header" code={`Authorization: Bearer ${API_KEY_PLACEHOLDER}`} copyLabel="Copy header" />
            <ul className="list-disc space-y-1.5 pl-5 text-sm leading-6 text-fg-muted marker:text-fg-subtle">
              <li>
                Keys start with <C>sk_live_</C> and are shown once, when you create them on{' '}
                <Link to="/api-keys" className={cx('font-medium text-brand-700 hover:underline dark:text-brand-300', focusRing, 'rounded')}>
                  API keys
                </Link>
                . Revoking a key takes effect immediately.
              </li>
              <li>Call the API from your server. Never put a key in a web page or a mobile app.</li>
              <li>
                Send <C>Content-Type: application/json</C> on every <C>POST</C>, otherwise the API answers{' '}
                <C>415</C>.
              </li>
            </ul>
          </Section>

          <Section
            id="send"
            title="Send a message"
            description="Queues one SMS on one of your phones. The phone is woken up and sends it from its own SIM."
          >
            <Endpoint method="POST" path="/v1/messages" base={base} />
            <FieldList caption="Body (JSON)" fields={sendFields} />
            <div>
              <SubHeading>Idempotency-Key header (recommended)</SubHeading>
              <p className="text-sm leading-6 text-fg-muted">
                Send a unique value (for example a UUID, up to {limits.idemKeyMax} characters) per message. If the
                request times out, retry with the <span className="text-fg">same key and same body</span>: you get{' '}
                <C>200</C> with the original message instead of a second SMS. The same key with a different body is
                rejected with <C>409 idempotency_mismatch</C>.
              </p>
            </div>
            <div className="grid gap-4 lg:grid-cols-2">
              <CodeBlock caption="202 Accepted (new) · 200 OK (replay)" code={messageExample(deviceId)} copyLabel="Copy response" />
              <div className="space-y-3 text-sm leading-6 text-fg-muted">
                <p>
                  The response is the message object. It never contains the body. Keep its <C>id</C> to check the
                  status later.
                </p>
                <p>
                  <C>expires_at</C> is <C>created_at</C> plus <C>ttl_seconds</C>. If the phone has not picked the
                  message up by then, it becomes <C>expired</C> and is never sent.
                </p>
                <p>
                  Errors use the{' '}
                  <a href="#errors" onClick={(e) => jump(e, 'errors')} className={cx('font-medium text-brand-700 hover:underline dark:text-brand-300', focusRing, 'rounded')}>
                    error envelope
                  </a>
                  , for example <C>422 invalid_to</C> or <C>404 device_not_found</C>.
                </p>
              </div>
            </div>
          </Section>

          <Section id="get" title="Get a message" description="Reads one message with its current status and timestamps.">
            <Endpoint method="GET" path="/v1/messages/{id}" base={base} />
            <CodeSample samples={getSamples(base)} label="Copy get request" {...sample} />
            <CodeBlock caption="200 OK" code={deliveredExample(deviceId)} copyLabel="Copy response" />
            <p className="text-sm leading-6 text-fg-muted">
              Each status sets its timestamp (<C>dispatched_at</C>, <C>sent_at</C>, <C>delivered_at</C>,{' '}
              <C>failed_at</C>). A failed message carries <C>error_code</C> and, if the phone gave one,{' '}
              <C>error_message</C>. An unknown id answers <C>404 message_not_found</C>.
            </p>
          </Section>

          <Section
            id="list"
            title="List messages"
            description="Your messages, newest first, filtered and paginated with a cursor."
          >
            <Endpoint method="GET" path="/v1/messages" base={base} />
            <FieldList caption="Query parameters (all optional)" fields={listParams} />
            <CodeSample samples={listSamples(base, deviceId)} label="Copy list request" {...sample} />
            <div className="grid gap-4 lg:grid-cols-2">
              <CodeBlock caption="200 OK" code={listExample(deviceId)} copyLabel="Copy response" />
              <p className="text-sm leading-6 text-fg-muted">
                <C>next_before</C> is the id to pass as <span className="font-mono text-fg">before</span> for the next,
                older page. It is <span className="font-mono text-fg">null</span> on the last page. Filters apply to
                every page, so keep them when you follow the cursor.
              </p>
            </div>
          </Section>

          <Section
            id="devices"
            title="List devices"
            description="Your paired phones with their SIMs, so you can pick device_id and sim_slot."
          >
            <Endpoint method="GET" path="/v1/devices" base={base} />
            <CodeSample samples={deviceSamples(base)} label="Copy devices request" {...sample} />
            <CodeBlock caption="200 OK" code={devicesExample(deviceId, first?.name ?? 'Pixel 8')} copyLabel="Copy response" />
            <p className="text-sm leading-6 text-fg-muted">
              <C>online</C> means the phone checked in within the last {limits.onlineWindowMin} minutes.{' '}
              <C>battery</C>, <C>charging</C> and <C>last_seen_at</C> are <span className="font-mono text-fg">null</span>{' '}
              until its first heartbeat. Use a SIM&apos;s <C>slot</C> as <C>sim_slot</C> when sending.
            </p>
          </Section>

          <Section
            id="lifecycle"
            title="Message lifecycle"
            description="Poll GET /v1/messages/{id} to follow a message. delivered, failed and expired are final."
          >
            <LifecycleDiagram />
            <div className="grid gap-4 sm:grid-cols-2">
              <Guarantee title="At most once">
                The phone claims each message exactly once before sending it. Retries, reconnects and duplicate wake-ups
                never produce a second SMS. The trade-off: a message can be lost, never doubled. For one-time codes,
                send a new code instead of resending blindly.
              </Guarantee>
              <Guarantee title="No late messages">
                A message nobody picked up before <C>expires_at</C> becomes <C>expired</C>. If the phone took it but
                never reported back, it becomes <C>failed</C> with <C>device_lost</C> {limits.deviceLostMin} minutes
                after expiry, and is corrected if a late report arrives.
              </Guarantee>
            </div>
            <p className="text-sm leading-6 text-fg-muted">
              <C>delivered</C> needs a delivery report from the carrier. Some carriers never send one, so a message can
              stay <C>sent</C>.
            </p>
          </Section>

          <Section
            id="errors"
            title="Errors"
            description="Every error has the same JSON shape. Branch on code; message is for humans and may change."
          >
            <CodeBlock caption="422 Unprocessable Entity" code={errorExample} copyLabel="Copy example" />
            <ul className="divide-y divide-border overflow-hidden rounded-xl border border-border">
              {errorCodes.map((e) => (
                <li key={e.code} className="grid gap-1.5 px-4 py-3 sm:grid-cols-[3.5rem_13rem_minmax(0,1fr)] sm:items-baseline sm:gap-4">
                  <span>
                    <Badge tone={statusTone(e.status)} className="font-mono">
                      {e.status}
                    </Badge>
                  </span>
                  <code className="font-mono text-[13px] font-medium break-all text-fg">{e.code}</code>
                  <span className="text-sm leading-6 text-fg-muted">{e.meaning}</span>
                </li>
              ))}
            </ul>
          </Section>

          <Section
            id="rate-limits"
            title="Rate limits"
            description={
              <>
                Sending is limited per API key. Over the limit, the API answers <C>429 rate_limited</C> with a{' '}
                <C>Retry-After</C> header in seconds: wait that long, then retry with the same Idempotency-Key.
              </>
            }
          >
            <dl className="grid grid-cols-2 gap-3 lg:grid-cols-3">
              <Limit term="Sends per key" value={`${limits.sendPerMin} / min`} hint="POST /v1/messages" />
              <Limit term="Message validity" value={`${limits.ttlMin}–${limits.ttlMax} s`} hint={`default ${limits.ttlDefault} s`} />
              <Limit term="Message length" value={`${limits.maxSegments} segments`} hint={`${limits.gsmChars} GSM-7 / ${limits.unicodeChars} Unicode chars`} />
              <Limit term="Page size" value={`${limits.listMax} max`} hint={`default ${limits.listDefault}`} />
              <Limit term="Request body" value={`${limits.maxBodyKB} KB`} hint="larger gets 413" />
              <Limit term="Reads" value="Not limited" hint="poll every few seconds at most" />
            </dl>
            <p className="text-sm leading-6 text-fg-muted">
              Test sends from this dashboard have their own budget and do not use your keys&apos; limit. Android
              also limits how fast one app may send SMS; a message it blocks ends as <C>failed</C>.
            </p>
          </Section>

          <Section id="webhooks" title="Webhooks">
            <div className="flex flex-col gap-4 sm:flex-row sm:items-start">
              <span className="grid size-11 shrink-0 place-items-center rounded-xl bg-surface-sunken text-xl text-fg-muted inset-ring inset-ring-border">
                <WebhookIcon />
              </span>
              <div className="min-w-0 space-y-1.5">
                <Badge tone="brand">Coming soon</Badge>
                <p className="text-sm leading-6 text-fg-muted">
                  Status changes pushed to your URL, signed so you can verify them. Until then, poll{' '}
                  <C>GET /v1/messages/{'{id}'}</C> or list recent messages with <C>since</C>.
                </p>
              </div>
            </div>
          </Section>

          <section id="android" aria-labelledby="android-h" className="scroll-mt-20">
            <ApkCard />
          </section>
        </div>
      </div>

      <aside className="hidden xl:block">
        <nav aria-label="On this page" className="sticky top-20">
          <p className="mb-3 flex items-center gap-2 text-xs font-semibold tracking-wide text-fg-subtle uppercase">
            <BookIcon className="size-3.5" />
            On this page
          </p>
          <ul className="space-y-0.5 border-l border-border">
            {sections.map((s) => (
              <li key={s.id}>
                <a
                  href={`#${s.id}`}
                  onClick={(e) => jump(e, s.id)}
                  aria-current={active === s.id ? 'location' : undefined}
                  className={cx(
                    '-ml-px block border-l-2 py-1 pl-3 text-[13px]',
                    'transition-colors duration-150 motion-reduce:transition-none',
                    active === s.id
                      ? 'border-brand-600 font-medium text-brand-700 dark:border-brand-400 dark:text-brand-300'
                      : 'border-transparent text-fg-muted hover:border-border-strong hover:text-fg',
                    focusRing,
                  )}
                >
                  {s.title}
                </a>
              </li>
            ))}
          </ul>
        </nav>
      </aside>
    </div>
  )
}

function QuickStep({ n, icon, title, children }: { n: number; icon: ReactNode; title: string; children: ReactNode }) {
  return (
    <li className="relative rounded-xl border border-border bg-surface-sunken/50 p-4">
      <div className="mb-3 flex items-center gap-2.5">
        <span className="grid size-8 place-items-center rounded-lg bg-brand-50 text-base text-brand-700 inset-ring inset-ring-brand-600/15 dark:bg-brand-500/12 dark:text-brand-300 dark:inset-ring-brand-400/20">
          {icon}
        </span>
        <span className="text-xs font-semibold text-fg-subtle">Step {n}</span>
      </div>
      <p className="text-sm font-semibold text-fg">{title}</p>
      <p className="mt-1 text-sm leading-6 text-fg-muted">{children}</p>
    </li>
  )
}

function Guarantee({ title, children }: { title: string; children: ReactNode }) {
  return (
    <div className="rounded-xl border border-border bg-surface-sunken/50 p-4">
      <p className="text-sm font-semibold text-fg">{title}</p>
      <p className="mt-1 text-sm leading-6 text-fg-muted">{children}</p>
    </div>
  )
}

function Limit({ term, value, hint }: { term: string; value: string; hint: string }) {
  return (
    <div className="rounded-xl border border-border bg-surface-sunken/50 p-4">
      <dt className="text-xs font-medium text-fg-subtle">{term}</dt>
      <dd className="mt-1">
        <span className="block text-base font-semibold tracking-tight text-fg tabular-nums">{value}</span>
        <span className="mt-0.5 block text-xs text-fg-muted">{hint}</span>
      </dd>
    </div>
  )
}

function ApkCard() {
  const apk = useApk()
  return (
    <Card padding="lg" className="overflow-hidden">
      <div
        aria-hidden="true"
        className="pointer-events-none absolute -top-24 -right-24 size-64 rounded-full bg-brand-500/10 blur-3xl dark:bg-brand-400/10"
      />
      <div className="relative flex flex-col gap-5 sm:flex-row sm:items-center">
        <span className="grid size-12 shrink-0 place-items-center rounded-2xl bg-linear-to-b from-brand-500 to-brand-700 text-2xl text-fg-on-brand shadow-glow">
          <PhoneIcon />
        </span>
        <div className="min-w-0 flex-1">
          <h2 id="android-h" tabIndex={-1} className="text-lg font-semibold tracking-tight text-fg focus:outline-none">
            Android app
          </h2>
          <div aria-live="polite" className="mt-1 text-sm leading-6 text-fg-muted">
            {apk.isPending ? (
              <span className="flex items-center gap-2">
                <span className="sr-only">Checking for the app…</span>
                <Skeleton className="h-4 w-56" />
              </span>
            ) : apk.data?.available ? (
              <>
                Install it on the phone whose SIM should send, then pair it from{' '}
                <Link to="/devices" className={cx('font-medium text-brand-700 hover:underline dark:text-brand-300', focusRing, 'rounded')}>
                  Devices
                </Link>
                .{apk.data.size !== null && <span className="text-fg-subtle"> APK · {formatSize(apk.data.size)}</span>}
              </>
            ) : (
              <>
                <span className="font-medium text-fg">Android app coming soon.</span> The APK will be downloadable here
                as soon as it is published.
              </>
            )}
          </div>
        </div>
        {apk.data?.available ? (
          <a href={APK_URL} download className={cx(buttonStyles('primary'), 'self-start sm:self-center')}>
            <DownloadIcon className="text-[1.1em]" />
            Download APK
          </a>
        ) : (
          !apk.isPending && (
            <Badge tone="neutral" className="self-start sm:self-center">
              Not available yet
            </Badge>
          )
        )}
      </div>
    </Card>
  )
}
