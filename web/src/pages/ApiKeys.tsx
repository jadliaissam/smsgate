import { useId, useState, type FormEvent, type ReactNode } from 'react'
import { isApiError } from '../api/client'
import { errorCopy } from '../api/errors'
import { useApiKeys, useCreateApiKey, useRevokeApiKey } from '../api/queries'
import type { ApiKey } from '../api/types'
import { PageHeader } from '../layout/PageHeader'
import { KeyIcon, MailIcon, PlusIcon, TrashIcon } from '../layout/icons'
import { FormAlert } from '../layout/FormAlert'
import { ResendButton } from '../layout/ResendButton'
import { useSessionUser } from '../layout/session'
import { Badge } from '../ui/Badge'
import { Button } from '../ui/Button'
import { Card } from '../ui/Card'
import { CopyButton } from '../ui/CopyButton'
import { cx } from '../ui/cx'
import { EmptyState } from '../ui/EmptyState'
import { AlertIcon } from '../ui/icons'
import { Input } from '../ui/Input'
import { Modal } from '../ui/Modal'
import { Skeleton } from '../ui/Skeleton'
import { absoluteTime, relativeTime, useNow } from '../ui/time'

// Name | key | created | last used | action, on md and up; stacked cards below.
const columns = 'md:grid-cols-[minmax(0,1.5fr)_minmax(0,1.1fr)_minmax(0,0.9fr)_minmax(0,0.9fr)_6.5rem]'

export function ApiKeys() {
  const me = useSessionUser()
  const verified = me.email_verified
  const keys = useApiKeys()
  const now = useNow(30_000)
  // A fresh modal instance per opening: no state from a previous key survives.
  const [createSession, setCreateSession] = useState(0)
  const [createOpen, setCreateOpen] = useState(false)
  // The revoke dialog keeps its key while it animates out.
  const [revokeTarget, setRevokeTarget] = useState<ApiKey | null>(null)
  const [revokeOpen, setRevokeOpen] = useState(false)
  const revoke = useRevokeApiKey()

  const openCreate = () => {
    setCreateSession((n) => n + 1)
    setCreateOpen(true)
  }
  const askRevoke = (k: ApiKey) => {
    setRevokeTarget(k)
    setRevokeOpen(true)
  }
  const confirmRevoke = () => {
    if (revokeTarget) revoke.mutate(revokeTarget)
    setRevokeOpen(false)
  }

  return (
    <>
      <PageHeader
        title="API keys"
        description="Keys your servers use to call the SMSGate API. Treat them like passwords."
        actions={<CreateKeyButton verified={verified} onClick={openCreate} label="Create key" />}
      />

      {!verified && <VerifyNotice />}

      {keys.error ? (
        <Card role="alert" className="flex flex-col items-center py-12 text-center">
          <span className="mb-4 flex size-11 items-center justify-center rounded-2xl bg-danger-50 text-xl text-danger-600 dark:bg-danger-500/10 dark:text-danger-400">
            <AlertIcon />
          </span>
          <h2 className="text-[15px] font-semibold tracking-tight text-fg">Couldn’t load your API keys</h2>
          <p className="mt-1.5 max-w-sm text-sm text-pretty text-fg-muted">
            {errorCopy(isApiError(keys.error) ? keys.error.code : 'default')}
          </p>
          <Button variant="secondary" className="mt-6" loading={keys.isFetching} onClick={() => void keys.refetch()}>
            Retry
          </Button>
        </Card>
      ) : !keys.data ? (
        <KeysSkeleton />
      ) : keys.data.length === 0 ? (
        <Card padding="none" className="overflow-hidden">
          <div
            aria-hidden="true"
            className="pointer-events-none absolute inset-x-0 -top-28 mx-auto size-96 rounded-full bg-radial from-brand-400/25 to-transparent to-70% dark:from-brand-500/20"
          />
          <EmptyState
            className="relative py-16 sm:py-20"
            icon={<KeyIcon />}
            title="No API keys yet"
            description="Create a key and send SMS from your own code with a single HTTP request."
            action={
              <CreateKeyButton
                verified={verified}
                onClick={openCreate}
                label="Create your first key"
                tooltipAlign="center"
                className="h-12 rounded-2xl px-6 text-base"
              />
            }
          />
        </Card>
      ) : (
        <Card padding="none" className="overflow-hidden">
          <div
            aria-hidden="true"
            className={cx(
              'hidden gap-4 border-b border-border bg-surface-sunken/60 px-6 py-2.5 text-xs font-medium text-fg-muted md:grid',
              columns,
            )}
          >
            <span>Name</span>
            <span>Key</span>
            <span>Created</span>
            <span>Last used</span>
            <span />
          </div>
          <ul className="divide-y divide-border">
            {keys.data.map((k) => (
              <KeyRow key={k.id} apiKey={k} now={now} onRevoke={askRevoke} />
            ))}
          </ul>
        </Card>
      )}

      <CreateKeyModal key={createSession} open={createOpen} onClose={() => setCreateOpen(false)} />

      <Modal
        open={revokeOpen}
        onClose={() => setRevokeOpen(false)}
        title={`Revoke ${revokeTarget?.name ?? 'key'}?`}
        description={
          <>
            Requests signed with <code className="font-mono text-fg">{revokeTarget?.prefix}…</code> are rejected
            at once. This can’t be undone.
          </>
        }
        size="sm"
        footer={
          <>
            <Button variant="secondary" onClick={() => setRevokeOpen(false)}>
              Cancel
            </Button>
            <Button variant="danger" icon={<TrashIcon />} onClick={confirmRevoke}>
              Revoke key
            </Button>
          </>
        }
      />
    </>
  )
}

// ---- create button (disabled with a reason until the email is verified) ----

interface CreateKeyButtonProps {
  verified: boolean
  onClick: () => void
  label: string
  tooltipAlign?: 'end' | 'center'
  className?: string
}

function CreateKeyButton({ verified, onClick, label, tooltipAlign = 'end', className }: CreateKeyButtonProps) {
  const tipId = useId()
  if (verified) {
    return (
      <Button icon={<PlusIcon />} onClick={onClick} className={className}>
        {label}
      </Button>
    )
  }
  // aria-disabled (not disabled) keeps it focusable so keyboard users reach
  // the reason; it has no click handler. Hover lands on the wrapper.
  return (
    <span className="group/tip relative inline-flex">
      <Button
        icon={<PlusIcon />}
        aria-disabled="true"
        aria-describedby={tipId}
        className={cx('pointer-events-none opacity-55', className)}
      >
        {label}
      </Button>
      <span
        id={tipId}
        role="tooltip"
        className={cx(
          'pointer-events-none absolute top-full z-30 mt-2 w-60 rounded-xl border border-border bg-surface-raised px-3 py-2 text-left text-xs leading-5 font-normal whitespace-normal text-fg shadow-overlay',
          'invisible opacity-0 transition-[opacity,visibility] duration-150 ease-out motion-reduce:transition-none',
          'group-hover/tip:visible group-hover/tip:opacity-100 group-focus-within/tip:visible group-focus-within/tip:opacity-100',
          // Centered without a transform: half the w-60 width as a negative margin.
          tooltipAlign === 'end' ? 'right-0' : 'left-1/2 -ml-30',
        )}
      >
        Verify your email to create API keys. Check your inbox for the link.
      </span>
    </span>
  )
}

function VerifyNotice() {
  return (
    <section
      aria-label="Verify your email to create API keys"
      className="mb-6 flex flex-col gap-3 rounded-2xl border border-border bg-surface p-4 shadow-card sm:flex-row sm:items-center"
    >
      <div className="flex min-w-0 flex-1 items-start gap-3">
        <span className="flex size-9 shrink-0 items-center justify-center rounded-xl bg-warning-100 text-lg text-warning-700 dark:bg-warning-500/15 dark:text-warning-300">
          <MailIcon />
        </span>
        <div className="min-w-0 text-sm">
          <p className="font-medium text-fg">Verify your email to create API keys</p>
          <p className="mt-0.5 text-fg-muted">Keys can send SMS from your phones, so we confirm it’s you first.</p>
        </div>
      </div>
      <ResendButton className="self-end sm:self-auto" />
    </section>
  )
}

// ---- list ------------------------------------------------------------------

interface KeyRowProps {
  apiKey: ApiKey
  now: number
  onRevoke: (k: ApiKey) => void
}

function KeyRow({ apiKey: k, now, onRevoke }: KeyRowProps) {
  const revoked = k.revoked_at !== null
  return (
    <li
      aria-label={k.name}
      data-revoked={String(revoked)}
      className={cx(
        'grid grid-cols-[minmax(0,1fr)_auto] items-center gap-x-4 gap-y-3 px-4 py-4 sm:px-5 md:gap-y-0 md:px-6 md:py-3.5',
        'transition-colors duration-150 ease-out animate-fade-in motion-reduce:animate-none motion-reduce:transition-none',
        columns,
        revoked ? 'bg-surface-sunken/50' : 'hover:bg-surface-hover/50',
      )}
    >
      <div className="col-start-1 row-start-1 flex min-w-0 items-center gap-3 md:col-auto md:row-auto">
        <span
          aria-hidden="true"
          className={cx(
            'flex size-9 shrink-0 items-center justify-center rounded-xl text-lg inset-ring',
            revoked
              ? 'bg-surface-sunken text-fg-subtle inset-ring-border'
              : 'bg-brand-50 text-brand-600 inset-ring-brand-600/15 dark:bg-brand-500/12 dark:text-brand-300 dark:inset-ring-brand-400/20',
          )}
        >
          <KeyIcon />
        </span>
        <div className="min-w-0">
          <p className={cx('truncate text-sm font-semibold tracking-tight', revoked ? 'text-fg-muted' : 'text-fg')}>
            {k.name}
          </p>
          {k.revoked_at && (
            <Badge tone="neutral" className="mt-1">
              <time dateTime={k.revoked_at} title={absoluteTime(k.revoked_at)}>
                Revoked {relativeTime(k.revoked_at, now)}
              </time>
            </Badge>
          )}
        </div>
      </div>

      <div className="col-span-2 min-w-0 md:col-span-1">
        <code
          className={cx(
            'inline-block max-w-full truncate rounded-lg border border-border bg-surface-sunken px-2 py-1 font-mono text-xs',
            revoked ? 'text-fg-subtle line-through decoration-fg-subtle/60' : 'text-fg',
          )}
        >
          {k.prefix}…
        </code>
      </div>

      <dl className="col-span-2 grid grid-cols-2 gap-4 text-sm md:contents">
        <TimeCell label="Created" iso={k.created_at} now={now} muted={revoked} />
        <TimeCell label="Last used" iso={k.last_used_at} now={now} muted={revoked} />
      </dl>

      <div className="col-start-2 row-start-1 flex justify-end md:col-auto md:row-auto">
        {!revoked && (
          <Button
            variant="ghost"
            size="sm"
            icon={<TrashIcon />}
            aria-label={`Revoke ${k.name}`}
            onClick={() => onRevoke(k)}
            className="hover:bg-danger-50 hover:text-danger-700 dark:hover:bg-danger-500/10 dark:hover:text-danger-300"
          >
            Revoke
          </Button>
        )}
      </div>
    </li>
  )
}

function TimeCell({ label, iso, now, muted }: { label: string; iso: string | null; now: number; muted: boolean }) {
  return (
    <div className="min-w-0">
      <dt className="mb-0.5 text-xs font-medium text-fg-muted md:sr-only">{label}</dt>
      <dd className={cx('truncate', muted || !iso ? 'text-fg-muted' : 'text-fg')}>
        {iso ? (
          <time dateTime={iso} title={absoluteTime(iso)}>
            {relativeTime(iso, now)}
          </time>
        ) : (
          'Never'
        )}
      </dd>
    </div>
  )
}

function KeysSkeleton() {
  return (
    <Card padding="none" data-testid="api-keys-skeleton" aria-busy="true">
      <div className="hidden h-9 border-b border-border md:block" />
      {[0, 1, 2].map((i) => (
        <div
          key={i}
          className={cx(
            'grid grid-cols-[minmax(0,1fr)_auto] items-center gap-4 px-4 py-4 sm:px-5 md:px-6',
            columns,
            i > 0 && 'border-t border-border',
          )}
        >
          <div className="flex items-center gap-3">
            <Skeleton className="size-9 rounded-xl" />
            <Skeleton className="h-4 w-32" />
          </div>
          <Skeleton className="hidden h-6 w-28 md:block" />
          <Skeleton className="hidden h-4 w-20 md:block" />
          <Skeleton className="hidden h-4 w-20 md:block" />
          <Skeleton className="ml-auto h-8 w-20" />
        </div>
      ))}
    </Card>
  )
}

// ---- create modal ----------------------------------------------------------

type Phase = 'form' | 'reveal' | 'unverified'

interface CreateKeyModalProps {
  open: boolean
  onClose: () => void
}

/**
 * Name → create → reveal. [Review Focus 2] The full key lives only in this
 * component's state while it is shown: the mutation is reset as soon as the
 * key is copied into state (its cache entry is dropped), and closing wipes
 * the state at once. It cannot be dismissed while the key is being created,
 * and closing before the key was copied asks for confirmation.
 */
function CreateKeyModal({ open, onClose }: CreateKeyModalProps) {
  const create = useCreateApiKey()
  const formId = useId()
  const [phase, setPhase] = useState<Phase>('form')
  const [name, setName] = useState('')
  const [nameError, setNameError] = useState<string>()
  const [formError, setFormError] = useState<string>()
  const [secret, setSecret] = useState<string | null>(null)
  const [copied, setCopied] = useState(false)
  const [confirmingClose, setConfirmingClose] = useState(false)

  const forceClose = () => {
    setSecret(null)
    setName('')
    setConfirmingClose(false)
    create.reset()
    onClose()
  }

  /** Esc, backdrop, X, Cancel, Done. */
  const requestClose = () => {
    if (create.isPending) return
    if (phase === 'reveal' && secret !== null && !copied) {
      setConfirmingClose(true)
      return
    }
    forceClose()
  }

  const submit = (e: FormEvent) => {
    e.preventDefault()
    if (create.isPending) return
    setNameError(undefined)
    setFormError(undefined)
    const trimmed = name.trim()
    if (!trimmed) {
      setNameError(errorCopy('invalid_name'))
      return
    }
    create.mutate(trimmed, {
      onSuccess: (created) => {
        setSecret(created.key)
        setPhase('reveal')
        create.reset()
      },
      onError: (err) => {
        const code = isApiError(err) ? err.code : 'default'
        if (code === 'email_not_verified') setPhase('unverified')
        else if (code === 'invalid_name') setNameError(errorCopy(code))
        else setFormError(errorCopy(code))
      },
    })
  }

  const footer: Record<Phase, ReactNode> = {
    form: (
      <>
        <Button variant="secondary" disabled={create.isPending} onClick={requestClose}>
          Cancel
        </Button>
        <Button type="submit" form={formId} icon={<KeyIcon />} loading={create.isPending}>
          Create key
        </Button>
      </>
    ),
    reveal: confirmingClose ? (
      <>
        <Button variant="secondary" onClick={() => setConfirmingClose(false)}>
          Go back
        </Button>
        <Button variant="danger" onClick={forceClose}>
          Close anyway
        </Button>
      </>
    ) : (
      <Button onClick={requestClose}>Done</Button>
    ),
    unverified: (
      <Button variant="secondary" onClick={requestClose}>
        Close
      </Button>
    ),
  }

  return (
    <Modal
      open={open}
      onClose={requestClose}
      hideClose={create.isPending}
      title={phase === 'reveal' ? 'Save your API key' : 'Create an API key'}
      description={
        phase === 'form'
          ? 'Name it after the app or server that will use it, so you know what to revoke later.'
          : undefined
      }
      footer={footer[phase]}
    >
      {phase === 'form' && (
        <form id={formId} onSubmit={submit} noValidate className="flex flex-col gap-4">
          <Input
            label="Name"
            name="name"
            placeholder="e.g. Production server"
            autoComplete="off"
            maxLength={64}
            autoFocus
            readOnly={create.isPending}
            value={name}
            onChange={(e) => setName(e.target.value)}
            error={nameError}
          />
          {formError && <FormAlert>{formError}</FormAlert>}
        </form>
      )}

      {phase === 'reveal' && (
        <RevealedKey secret={secret} confirmingClose={confirmingClose} onCopied={() => setCopied(true)} />
      )}

      {phase === 'unverified' && (
        <div className="flex flex-col items-start gap-3 rounded-xl border border-warning-200 bg-warning-50 p-4 text-sm dark:border-warning-500/25 dark:bg-warning-500/10">
          <div className="flex items-start gap-2.5">
            <MailIcon className="mt-0.5 size-4 shrink-0 text-warning-700 dark:text-warning-300" />
            <div>
              <p className="font-medium text-fg">Verify your email first</p>
              <p className="mt-0.5 text-fg-muted">
                API keys unlock once your email address is confirmed. Click the link we sent you, then try again.
              </p>
            </div>
          </div>
          <ResendButton />
        </div>
      )}
    </Modal>
  )
}

interface RevealedKeyProps {
  secret: string | null
  confirmingClose: boolean
  onCopied: () => void
}

function RevealedKey({ secret, confirmingClose, onCopied }: RevealedKeyProps) {
  return (
    <div className="flex flex-col gap-4">
      <div className="relative">
        <div aria-hidden="true" className="absolute -inset-1 rounded-2xl bg-brand-500/15 blur-lg" />
        <div className="relative flex items-start gap-2 rounded-xl border border-brand-200 bg-brand-50 p-1.5 pl-3.5 shadow-card dark:border-brand-500/30 dark:bg-brand-950/60">
          {/* After close the key is already wiped; a mask holds the layout during the exit animation. */}
          <code className="min-w-0 flex-1 py-1.5 font-mono text-[13px] leading-6 break-all text-fg select-all">
            {secret ?? 'sk_live_••••••••••••••••••••••••'}
          </code>
          {secret && <CopyButton value={secret} label="Copy key" showLabel className="shrink-0" onCopied={onCopied} />}
        </div>
      </div>
      {confirmingClose ? (
        <div
          role="alert"
          className="flex items-start gap-2.5 rounded-xl border border-danger-200 bg-danger-50 px-3.5 py-3 text-sm text-danger-800 animate-fade-in motion-reduce:animate-none dark:border-danger-500/25 dark:bg-danger-500/10 dark:text-danger-200"
        >
          <AlertIcon className="mt-0.5 size-4 shrink-0" />
          <p>
            <strong className="font-semibold">{"You won't see this key again."}</strong> You haven’t copied it yet.
            Close anyway?
          </p>
        </div>
      ) : (
        <div
          role="note"
          className="flex items-start gap-2.5 rounded-xl border border-warning-200 bg-warning-50 px-3.5 py-3 text-sm text-warning-900 dark:border-warning-500/25 dark:bg-warning-500/10 dark:text-warning-200"
        >
          <AlertIcon className="mt-0.5 size-4 shrink-0" />
          <p>
            <strong className="font-semibold">{"You won't see this again."}</strong> Copy it now and keep it somewhere
            safe, such as your server’s environment variables. Lost it? Revoke it and create a new one.
          </p>
        </div>
      )}
    </div>
  )
}
