import { useEffect, useRef, useState } from 'react'
import { useDevices, useRenameDevice, useRevokeDevice } from '../api/queries'
import type { Device, SIM } from '../api/types'
import { PageHeader } from '../layout/PageHeader'
import { BoltIcon, PencilIcon, PhoneIcon, PlusIcon, SimIcon, TrashIcon } from '../layout/icons'
import { Badge } from '../ui/Badge'
import { Button } from '../ui/Button'
import { Card } from '../ui/Card'
import { CopyButton } from '../ui/CopyButton'
import { cx, focusRing } from '../ui/cx'
import { EmptyState } from '../ui/EmptyState'
import { Modal } from '../ui/Modal'
import { QueryErrorCard } from '../ui/QueryErrorCard'
import { Skeleton } from '../ui/Skeleton'
import { absoluteTime, sinceTime, useNow } from '../ui/time'
import { PairModal } from './PairModal'

export function Devices() {
  const devices = useDevices()
  const now = useNow(30_000)
  const [pairing, setPairing] = useState(false)
  // The revoke dialog keeps its device while it animates out.
  const [revokeTarget, setRevokeTarget] = useState<Device | null>(null)
  const [revokeOpen, setRevokeOpen] = useState(false)
  const revoke = useRevokeDevice()

  const openPair = () => setPairing(true)
  const askRevoke = (d: Device) => {
    setRevokeTarget(d)
    setRevokeOpen(true)
  }
  const confirmRevoke = () => {
    if (revokeTarget) revoke.mutate(revokeTarget)
    setRevokeOpen(false)
  }

  return (
    <>
      <PageHeader
        title="Devices"
        description="Android phones paired with your account. Each one sends SMS from its own SIMs."
        actions={
          // Enabled once the list is known: the pair flow detects the new
          // phone against it.
          <Button icon={<PlusIcon />} onClick={openPair} disabled={!devices.data}>
            Pair a phone
          </Button>
        }
      />

      {devices.error && !devices.data ? (
        <QueryErrorCard
          title="Couldn’t load your devices"
          error={devices.error}
          retrying={devices.isFetching}
          onRetry={() => void devices.refetch()}
        />
      ) : !devices.data ? (
        <div data-testid="devices-skeleton" className="grid grid-cols-1 gap-4 sm:grid-cols-2 xl:grid-cols-3">
          {[0, 1, 2].map((i) => (
            <DeviceSkeleton key={i} />
          ))}
        </div>
      ) : devices.data.length === 0 ? (
        <EmptyDevices onPair={openPair} />
      ) : (
        // grid-cols-1 (minmax(0, 1fr)) and min-w-0 keep a card within narrow
        // screens: the full device ID truncates instead of widening the page.
        <ul className="grid grid-cols-1 gap-4 sm:grid-cols-2 xl:grid-cols-3">
          {devices.data.map((d) => (
            <li key={d.id} className="min-w-0 animate-fade-in motion-reduce:animate-none">
              <DeviceCard device={d} now={now} onRevoke={askRevoke} />
            </li>
          ))}
        </ul>
      )}

      <PairModal open={pairing} onClose={() => setPairing(false)} />

      <Modal
        open={revokeOpen}
        onClose={() => setRevokeOpen(false)}
        title={`Revoke ${revokeTarget?.name ?? 'device'}?`}
        description="The phone is signed out at once and stops sending messages. To use it again, pair it anew."
        size="sm"
        footer={
          <>
            <Button variant="secondary" onClick={() => setRevokeOpen(false)}>
              Cancel
            </Button>
            <Button variant="danger" icon={<TrashIcon />} onClick={confirmRevoke}>
              Revoke device
            </Button>
          </>
        }
      />
    </>
  )
}

// ---- device card ----------------------------------------------------------

interface DeviceCardProps {
  device: Device
  now: number
  onRevoke: (d: Device) => void
}

function DeviceCard({ device, now, onRevoke }: DeviceCardProps) {
  const [editing, setEditing] = useState(false)
  const renameButton = useRef<HTMLButtonElement>(null)
  // Enter/Esc hand focus back to the rename button; a blur save does not.
  const refocus = useRef(false)
  useEffect(() => {
    if (editing || !refocus.current) return
    refocus.current = false
    renameButton.current?.focus()
  }, [editing])
  const stopEditing = (viaKeyboard: boolean) => {
    refocus.current = viaKeyboard
    setEditing(false)
  }

  return (
    <Card interactive padding="none" role="article" aria-label={device.name} className="flex h-full min-w-0 flex-col">
      <div className="flex items-start gap-3.5 p-5 pb-4">
        <span
          aria-hidden="true"
          className={cx(
            'flex size-11 shrink-0 items-center justify-center rounded-xl text-[22px] inset-ring',
            device.online
              ? 'bg-linear-to-br from-brand-500 to-brand-700 text-fg-on-brand shadow-glow inset-ring-fg-on-brand/15'
              : 'bg-surface-sunken text-fg-subtle inset-ring-border',
          )}
        >
          <PhoneIcon />
        </span>
        <div className="min-w-0 flex-1">
          {editing ? (
            <RenameInput device={device} onDone={stopEditing} />
          ) : (
            <div className="group flex min-w-0 items-center gap-1">
              <h2
                onClick={() => setEditing(true)}
                title="Click to rename"
                className="cursor-text truncate text-[15px] leading-8 font-semibold tracking-tight text-fg"
              >
                {device.name}
              </h2>
              <button
                ref={renameButton}
                type="button"
                onClick={() => setEditing(true)}
                aria-label={`Rename ${device.name}`}
                className={cx(
                  'flex size-7 shrink-0 items-center justify-center rounded-lg text-sm text-fg-subtle',
                  'transition-[opacity,background-color,color] duration-150 ease-out motion-reduce:transition-none',
                  'hover:bg-surface-hover hover:text-fg sm:opacity-0 sm:group-hover:opacity-100 sm:focus-visible:opacity-100',
                  focusRing,
                )}
              >
                <PencilIcon />
              </button>
            </div>
          )}
          <div className="mt-1">
            {device.online ? (
              <Badge tone="success" pulse>
                Online
              </Badge>
            ) : (
              <Badge tone="neutral" dot>
                {device.last_seen_at ? <span title={absoluteTime(device.last_seen_at)}>Last seen {sinceTime(device.last_seen_at, now)}</span> : 'Never connected'}
              </Badge>
            )}
          </div>
        </div>
      </div>

      <dl className="flex min-w-0 flex-1 flex-col gap-4 px-5 pb-5">
        <div>
          <dt className="mb-1.5 text-xs font-medium text-fg-muted">Battery</dt>
          <dd>
            <Battery level={device.battery} charging={device.charging === true} />
          </dd>
        </div>
        <div>
          <dt className="mb-1.5 text-xs font-medium text-fg-muted">SIM cards</dt>
          <dd>
            <SimChips sims={device.sims} />
          </dd>
        </div>
        <div className="min-w-0">
          <dt className="mb-1 text-xs font-medium text-fg-muted">Device ID</dt>
          <dd className="flex min-w-0 items-center gap-1">
            <code data-testid="device-id" className="min-w-0 truncate font-mono text-xs text-fg">{device.id}</code>
            <CopyButton value={device.id} label="Copy device ID" className="-my-1 shrink-0" />
          </dd>
        </div>
      </dl>

      <div className="flex justify-end border-t border-border px-3 py-2">
        <Button
          variant="ghost"
          size="sm"
          icon={<TrashIcon />}
          aria-label={`Revoke ${device.name}`}
          onClick={() => onRevoke(device)}
          className="hover:bg-danger-50 hover:text-danger-700 dark:hover:bg-danger-500/10 dark:hover:text-danger-300"
        >
          Revoke
        </Button>
      </div>
    </Card>
  )
}

/** Inline rename: Enter or blur saves (optimistically), Esc cancels. */
function RenameInput({ device, onDone }: { device: Device; onDone: (viaKeyboard: boolean) => void }) {
  const rename = useRenameDevice()
  const finished = useRef(false)

  const finish = (value: string | null, viaKeyboard = false) => {
    if (finished.current) return
    finished.current = true
    const name = value?.trim() ?? ''
    if (name && name !== device.name) rename.mutate({ id: device.id, name })
    onDone(viaKeyboard)
  }

  return (
    <input
      aria-label="Device name"
      defaultValue={device.name}
      maxLength={64}
      autoFocus
      onFocus={(e) => e.currentTarget.select()}
      onBlur={(e) => finish(e.currentTarget.value)}
      onKeyDown={(e) => {
        if (e.key === 'Enter') {
          e.preventDefault()
          finish(e.currentTarget.value, true)
        } else if (e.key === 'Escape') {
          e.preventDefault()
          finish(null, true)
        }
      }}
      className={cx(
        '-ml-2 h-8 w-full rounded-lg border border-brand-500 bg-surface px-2 text-[15px] font-semibold tracking-tight text-fg',
        'ring-4 ring-brand-500/15 outline-none',
      )}
    />
  )
}

function Battery({ level, charging }: { level: number | null; charging: boolean }) {
  if (level === null) return <p className="text-sm text-fg-subtle">Not reported yet</p>
  const pct = Math.max(0, Math.min(100, level))
  const fill =
    charging || pct > 30
      ? 'from-success-500 to-success-400'
      : pct > 15
        ? 'from-warning-500 to-warning-400'
        : 'from-danger-600 to-danger-500'
  return (
    <div className="flex items-center gap-2.5">
      <div
        role="meter"
        aria-label="Battery"
        aria-valuenow={pct}
        aria-valuemin={0}
        aria-valuemax={100}
        aria-valuetext={`${pct}%${charging ? ', charging' : ''}`}
        className="h-2 flex-1 overflow-hidden rounded-full bg-surface-sunken inset-ring inset-ring-border"
      >
        <div
          className={cx(
            'h-full rounded-full bg-linear-to-r transition-[width] duration-500 ease-out motion-reduce:transition-none',
            fill,
          )}
          style={{ width: `${pct}%` }}
        />
      </div>
      {charging && (
        <span role="img" aria-label="Charging" className="text-sm text-warning-500">
          <BoltIcon className="fill-current" />
        </span>
      )}
      <span className="w-9 text-right text-xs font-medium text-fg tabular-nums">{pct}%</span>
    </div>
  )
}

function SimChips({ sims }: { sims: SIM[] }) {
  if (sims.length === 0) return <p className="text-sm text-fg-subtle">No SIMs reported</p>
  return (
    <ul className="flex flex-wrap gap-1.5">
      {[...sims]
        .sort((a, b) => a.slot - b.slot)
        .map((s) => (
          <li
            key={s.slot}
            title={s.number}
            className="inline-flex h-7 items-center gap-1.5 rounded-lg border border-border bg-surface-sunken/70 px-2 text-xs"
          >
            <SimIcon className="text-sm text-brand-600 dark:text-brand-400" />
            <span className="font-semibold text-fg">SIM {s.slot + 1}</span>
            <span className="text-fg-muted">{s.carrier || 'Unknown carrier'}</span>
          </li>
        ))}
    </ul>
  )
}

function DeviceSkeleton() {
  return (
    <Card padding="none">
      <div className="flex items-start gap-3.5 p-5 pb-4">
        <Skeleton className="size-11 rounded-xl" />
        <div className="flex-1 space-y-2 pt-1">
          <Skeleton className="h-4 w-32" />
          <Skeleton className="h-5 w-20 rounded-full" />
        </div>
      </div>
      <div className="space-y-4 px-5 pb-5">
        <Skeleton className="h-2 w-full rounded-full" />
        <div className="flex gap-1.5">
          <Skeleton className="h-7 w-24" />
          <Skeleton className="h-7 w-24" />
        </div>
        <Skeleton className="h-4 w-48" />
      </div>
      <div className="h-12 border-t border-border" />
    </Card>
  )
}

function EmptyDevices({ onPair }: { onPair: () => void }) {
  return (
    <Card padding="none" className="overflow-hidden">
      <div
        aria-hidden="true"
        className="pointer-events-none absolute inset-x-0 -top-28 mx-auto size-96 rounded-full bg-radial from-brand-400/25 to-transparent to-70% dark:from-brand-500/20"
      />
      <EmptyState
        className="relative py-16 sm:py-20"
        icon={<PhoneIcon />}
        title="No phones paired yet"
        description="Pair an Android phone with the SMSGate app and it becomes your SMS gateway. It takes about a minute."
        action={
          <Button icon={<PlusIcon />} onClick={onPair} className="h-12 rounded-2xl px-6 text-base">
            Pair your first phone
          </Button>
        }
      />
    </Card>
  )
}
