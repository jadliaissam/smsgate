import { useId, useState, type FormEvent, type ReactNode } from 'react'
import { Link } from '@tanstack/react-router'
import { isApiError } from '../api/client'
import { errorCopy } from '../api/errors'
import { useDevices, useSendMessage } from '../api/queries'
import { MAX_SEGMENTS, normalizeNumber, segments, validE164 } from '../api/segments'
import type { Device, Message, SendMessageInput } from '../api/types'
import { FormAlert } from '../layout/FormAlert'
import { ArrowRightIcon, MailIcon, PhoneIcon, SendIcon } from '../layout/icons'
import { ResendButton } from '../layout/ResendButton'
import { Button } from '../ui/Button'
import { buttonStyles, cx } from '../ui/cx'
import { Input } from '../ui/Input'
import { Modal } from '../ui/Modal'
import { Skeleton } from '../ui/Skeleton'
import { useToast } from '../ui/Toast'

/** Time-to-live choices (seconds); the backend default is 5 minutes. */
const ttlOptions = [
  { seconds: 60, label: '1 min' },
  { seconds: 300, label: '5 min' },
  { seconds: 900, label: '15 min' },
  { seconds: 3600, label: '60 min' },
] as const
const DEFAULT_TTL = 300

/** A fresh Idempotency-Key (crypto.randomUUID needs a secure context). */
function newIdempotencyKey(): string {
  if (typeof crypto.randomUUID === 'function') return crypto.randomUUID()
  const b = crypto.getRandomValues(new Uint8Array(16))
  return Array.from(b, (x) => x.toString(16).padStart(2, '0')).join('')
}

type Phase = 'form' | 'unverified'

interface FieldErrors {
  device?: string
  to?: string
  body?: string
  form?: string
}

const fieldOf: Record<string, keyof FieldErrors> = {
  invalid_to: 'to',
  body_empty: 'body',
  body_too_long: 'body',
  invalid_body: 'body',
  device_not_found: 'device',
  invalid_sim_slot: 'device',
}

interface SendMessageModalProps {
  open: boolean
  onClose: () => void
  /** Called with the queued message just before the modal closes. */
  onSent?: (m: Message) => void
}

/**
 * Send a test SMS from one of the user's phones. Mount a fresh instance per
 * opening (key): its Idempotency-Key is generated once, so retrying after a
 * lost response (network error, 5xx) can never send the SMS twice.
 */
export function SendMessageModal({ open, onClose, onSent }: SendMessageModalProps) {
  const devices = useDevices()
  const send = useSendMessage()
  const toast = useToast()
  const formId = useId()
  const [idempotencyKey] = useState(newIdempotencyKey)
  const [phase, setPhase] = useState<Phase>('form')
  const [chosenDevice, setChosenDevice] = useState<string>()
  const [simSlot, setSimSlot] = useState<number | null>(null)
  const [to, setTo] = useState('')
  const [body, setBody] = useState('')
  const [ttl, setTtl] = useState(DEFAULT_TTL)
  const [errors, setErrors] = useState<FieldErrors>({})

  const list = devices.data
  // Default: the first online phone, else the first one.
  const device = list?.find((d) => d.id === chosenDevice) ?? list?.find((d) => d.online) ?? list?.[0]
  const count = segments(body)
  const tooLong = count.segments > MAX_SEGMENTS

  const close = () => {
    if (!send.isPending) onClose()
  }

  const pickDevice = (id: string) => {
    setChosenDevice(id)
    setSimSlot(null)
    setErrors((e) => ({ ...e, device: undefined }))
  }

  const submit = (e: FormEvent) => {
    e.preventDefault()
    if (send.isPending || !device) return
    const number = normalizeNumber(to)
    const next: FieldErrors = {}
    if (!validE164(number)) next.to = errorCopy('invalid_to')
    if (body === '') next.body = errorCopy('body_empty')
    else if (tooLong) next.body = errorCopy('body_too_long')
    setErrors(next)
    if (next.to || next.body) return

    const input: SendMessageInput = { device_id: device.id, to: number, body, ttl_seconds: ttl }
    if (simSlot !== null) input.sim_slot = simSlot
    send.mutate(
      { input, idempotencyKey },
      {
        onSuccess: (msg) => {
          toast.success('Message queued', `${device.name} will send it to ${number}.`)
          onSent?.(msg)
          onClose()
        },
        onError: (err) => {
          const code = isApiError(err) ? err.code : 'default'
          if (code === 'email_not_verified') {
            setPhase('unverified')
            return
          }
          setErrors({ [fieldOf[code] ?? 'form']: errorCopy(code) })
        },
      },
    )
  }

  const noDevices = list !== undefined && list.length === 0
  const footer: ReactNode =
    phase === 'unverified' || noDevices ? (
      <Button variant="secondary" onClick={close}>
        Close
      </Button>
    ) : (
      <>
        <Button variant="secondary" disabled={send.isPending} onClick={close}>
          Cancel
        </Button>
        <Button type="submit" form={formId} icon={<SendIcon />} loading={send.isPending} disabled={!device || tooLong}>
          Send message
        </Button>
      </>
    )

  return (
    <Modal
      open={open}
      onClose={close}
      hideClose={send.isPending}
      size="lg"
      title="Send a test message"
      description={phase === 'form' ? 'Sends a real SMS from your phone, exactly as the API would.' : undefined}
      footer={footer}
    >
      {phase === 'unverified' ? (
        <div className="flex flex-col items-start gap-3 rounded-xl border border-warning-200 bg-warning-50 p-4 text-sm dark:border-warning-500/25 dark:bg-warning-500/10">
          <div className="flex items-start gap-2.5">
            <MailIcon className="mt-0.5 size-4 shrink-0 text-warning-700 dark:text-warning-300" />
            <div>
              <p className="font-medium text-fg">Verify your email first</p>
              <p className="mt-0.5 text-fg-muted">
                Sending unlocks once your email address is confirmed. Click the link we sent you, then try again.
              </p>
            </div>
          </div>
          <ResendButton />
        </div>
      ) : devices.error ? (
        <FormAlert>{errorCopy(isApiError(devices.error) ? devices.error.code : 'default')}</FormAlert>
      ) : !list ? (
        <div className="flex flex-col gap-3" aria-busy="true" data-testid="send-skeleton">
          <Skeleton className="h-16 w-full rounded-xl" />
          <Skeleton className="h-10 w-full rounded-xl" />
          <Skeleton className="h-28 w-full rounded-xl" />
        </div>
      ) : noDevices ? (
        <div className="flex flex-col items-start gap-3 rounded-xl border border-border bg-surface-sunken/60 p-4 text-sm">
          <p className="font-medium text-fg">Pair a phone first</p>
          <p className="text-fg-muted">Messages are sent from your own Android phone. Pair one, then come back here.</p>
          <Link to="/devices" onClick={onClose} className={cx(buttonStyles('primary', 'sm'))}>
            Pair a phone
            <ArrowRightIcon className="size-3.5" />
          </Link>
        </div>
      ) : (
        <form id={formId} onSubmit={submit} noValidate className="flex flex-col gap-5">
          <DevicePicker devices={list} selected={device?.id} onPick={pickDevice} disabled={send.isPending} error={errors.device} />

          {device && device.sims.length > 0 && (
            <SimPicker device={device} slot={simSlot} onPick={setSimSlot} disabled={send.isPending} />
          )}

          <Input
            label="To"
            name="to"
            type="tel"
            inputMode="tel"
            autoComplete="off"
            placeholder="+37060000000"
            hint="International format with the country code, e.g. +37060000000."
            readOnly={send.isPending}
            value={to}
            onChange={(e) => {
              setTo(e.target.value)
              setErrors((x) => ({ ...x, to: undefined }))
            }}
            error={errors.to}
          />

          <BodyField
            value={body}
            onChange={(v) => {
              setBody(v)
              setErrors((x) => ({ ...x, body: undefined }))
            }}
            readOnly={send.isPending}
            error={errors.body}
            segments={count.segments}
            ucs2={count.ucs2}
          />

          <Segmented
            label="Expires after"
            hint="If the phone hasn’t sent it by then, it’s dropped and marked expired."
            options={ttlOptions.map((o) => ({ value: o.seconds, label: o.label }))}
            value={ttl}
            onChange={setTtl}
            disabled={send.isPending}
          />

          {errors.form && <FormAlert>{errors.form}</FormAlert>}
        </form>
      )}
    </Modal>
  )
}

// ---- fields ----------------------------------------------------------------

interface DevicePickerProps {
  devices: Device[]
  selected: string | undefined
  onPick: (id: string) => void
  disabled: boolean
  error?: string
}

/** Radio cards; online phones stand out, offline ones stay selectable. */
function DevicePicker({ devices, selected, onPick, disabled, error }: DevicePickerProps) {
  const name = useId()
  const errorId = useId()
  const current = devices.find((d) => d.id === selected)
  return (
    <fieldset aria-describedby={error ? errorId : undefined}>
      <legend className="mb-1.5 text-[13px] font-medium text-fg">From</legend>
      <div className="grid max-h-56 gap-2 overflow-y-auto p-0.5 sm:grid-cols-2">
        {devices.map((d) => {
          const checked = d.id === selected
          return (
            <label
              key={d.id}
              className={cx(
                'relative flex cursor-pointer items-center gap-3 rounded-xl border p-3 shadow-control',
                'transition-[border-color,background-color,box-shadow] duration-150 ease-out motion-reduce:transition-none',
                'has-focus-visible:ring-2 has-focus-visible:ring-brand-500/60',
                checked
                  ? 'border-brand-500 bg-brand-50/70 dark:bg-brand-500/10'
                  : 'border-border bg-surface hover:border-border-strong',
                !d.online && !checked && 'opacity-70',
                disabled && 'pointer-events-none',
              )}
            >
              <input
                type="radio"
                name={name}
                value={d.id}
                checked={checked}
                disabled={disabled}
                onChange={() => onPick(d.id)}
                className="sr-only"
              />
              <span
                aria-hidden="true"
                className={cx(
                  'flex size-9 shrink-0 items-center justify-center rounded-lg text-lg inset-ring',
                  d.online
                    ? 'bg-linear-to-br from-brand-500 to-brand-700 text-fg-on-brand inset-ring-fg-on-brand/15'
                    : 'bg-surface-sunken text-fg-subtle inset-ring-border',
                )}
              >
                <PhoneIcon />
              </span>
              <span className="min-w-0">
                <span className="block truncate text-sm font-semibold text-fg">{d.name}</span>
                <span className="flex items-center gap-1.5 text-xs text-fg-muted">
                  <span
                    aria-hidden="true"
                    className={cx('size-1.5 rounded-full', d.online ? 'bg-success-500' : 'bg-fg-subtle')}
                  />
                  {d.online ? 'Online' : 'Offline'}
                </span>
              </span>
            </label>
          )
        })}
      </div>
      {error ? (
        <p id={errorId} className="mt-1.5 text-[13px] leading-5 text-danger-600 dark:text-danger-400">
          {error}
        </p>
      ) : current && !current.online ? (
        <p className="mt-1.5 text-[13px] leading-5 text-warning-800 dark:text-warning-300">
          {current.name} is offline. The message waits until it reconnects, or expires.
        </p>
      ) : null}
    </fieldset>
  )
}

function SimPicker({
  device,
  slot,
  onPick,
  disabled,
}: {
  device: Device
  slot: number | null
  onPick: (slot: number | null) => void
  disabled: boolean
}) {
  const sims = [...device.sims].sort((a, b) => a.slot - b.slot)
  return (
    <Segmented
      label="SIM"
      options={[
        { value: null, label: 'Phone default' },
        ...sims.map((s) => ({ value: s.slot, label: `SIM ${s.slot + 1}${s.carrier ? ` · ${s.carrier}` : ''}` })),
      ]}
      value={slot}
      onChange={onPick}
      disabled={disabled}
    />
  )
}

interface SegmentedProps<T> {
  label: string
  hint?: string
  options: { value: T; label: string }[]
  value: T
  onChange: (v: T) => void
  disabled?: boolean
}

/** A small radio group drawn as a segmented control. */
function Segmented<T extends string | number | null>({ label, hint, options, value, onChange, disabled }: SegmentedProps<T>) {
  const name = useId()
  const hintId = useId()
  return (
    <fieldset aria-describedby={hint ? hintId : undefined}>
      <legend className="mb-1.5 text-[13px] font-medium text-fg">{label}</legend>
      <div className="inline-flex max-w-full flex-wrap gap-1 rounded-xl border border-border bg-surface-sunken/70 p-1">
        {options.map((o) => {
          const checked = o.value === value
          return (
            <label
              key={String(o.value)}
              className={cx(
                'flex h-8 cursor-pointer items-center rounded-lg px-3 text-[13px] font-medium whitespace-nowrap',
                'transition-[background-color,color,box-shadow] duration-150 ease-out motion-reduce:transition-none',
                'has-focus-visible:ring-2 has-focus-visible:ring-brand-500/60',
                checked ? 'bg-surface-raised text-fg shadow-control' : 'text-fg-muted hover:text-fg',
                disabled && 'pointer-events-none opacity-70',
              )}
            >
              <input
                type="radio"
                name={name}
                checked={checked}
                disabled={disabled}
                onChange={() => onChange(o.value)}
                className="sr-only"
              />
              {o.label}
            </label>
          )
        })}
      </div>
      {hint && (
        <p id={hintId} className="mt-1.5 text-[13px] leading-5 text-fg-muted">
          {hint}
        </p>
      )}
    </fieldset>
  )
}

interface BodyFieldProps {
  value: string
  onChange: (v: string) => void
  readOnly: boolean
  error?: string
  segments: number
  ucs2: boolean
}

/** Message textarea with a live SMS segment counter (GSM-7 vs UCS-2). */
function BodyField({ value, onChange, readOnly, error, segments: n, ucs2 }: BodyFieldProps) {
  const id = useId()
  const noteId = `${id}-note`
  const counterId = `${id}-counter`
  const over = n > MAX_SEGMENTS
  const chars = [...value].length
  return (
    <div className="flex flex-col gap-1.5">
      <label htmlFor={id} className="text-[13px] font-medium text-fg">
        Message
      </label>
      <textarea
        id={id}
        name="body"
        rows={4}
        placeholder="Hello from SMSGate 👋"
        readOnly={readOnly}
        value={value}
        onChange={(e) => onChange(e.target.value)}
        aria-invalid={error || over ? true : undefined}
        aria-describedby={cx(counterId, error && noteId) || undefined}
        className={cx(
          'min-h-24 w-full resize-y rounded-xl border bg-surface px-3.5 py-2.5 text-sm leading-6 text-fg shadow-control',
          'placeholder:text-fg-subtle',
          'transition-[border-color,box-shadow] duration-150 ease-out motion-reduce:transition-none',
          'focus:outline-none focus-visible:ring-4',
          error || over
            ? 'border-danger-500 focus-visible:border-danger-500 focus-visible:ring-danger-500/15'
            : 'border-border hover:border-border-strong focus-visible:border-brand-500 focus-visible:ring-brand-500/15',
        )}
      />
      <div className="flex flex-wrap items-start justify-between gap-x-4 gap-y-1">
        {error ? (
          <p id={noteId} className="text-[13px] leading-5 text-danger-600 dark:text-danger-400">
            {error}
          </p>
        ) : (
          <p className="text-[13px] leading-5 text-fg-muted">
            {ucs2 ? 'Emoji or special characters: 70 per SMS (67 when split).' : 'Plain text: 160 per SMS (153 when split).'}
          </p>
        )}
        <p
          id={counterId}
          data-testid="segment-counter"
          aria-live="polite"
          className={cx(
            'ml-auto flex items-center gap-2 text-[13px] leading-5 tabular-nums',
            over ? 'font-medium text-danger-600 dark:text-danger-400' : 'text-fg-muted',
          )}
        >
          <span>{chars} chars</span>
          <span aria-hidden="true" className="text-fg-subtle">
            ·
          </span>
          <span>
            {n} / {MAX_SEGMENTS} SMS
          </span>
          <span
            className={cx(
              'rounded-md px-1.5 py-px font-mono text-[11px] inset-ring',
              ucs2
                ? 'bg-warning-50 text-warning-800 inset-ring-warning-600/20 dark:bg-warning-500/12 dark:text-warning-300 dark:inset-ring-warning-400/20'
                : 'bg-surface-sunken text-fg-muted inset-ring-border',
            )}
          >
            {ucs2 ? 'UCS-2' : 'GSM-7'}
          </span>
        </p>
      </div>
    </div>
  )
}
