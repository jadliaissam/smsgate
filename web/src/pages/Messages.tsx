import { useMemo, useState } from 'react'
import { Link, useNavigate, useSearch } from '@tanstack/react-router'
import { isApiError } from '../api/client'
import { errorCopy } from '../api/errors'
import { useDevices, useMessages } from '../api/queries'
import type { Message, MessageStatus } from '../api/types'
import { FormAlert } from '../layout/FormAlert'
import { ArrowRightIcon, ChevronDownIcon, MessageIcon, PhoneIcon, SendIcon } from '../layout/icons'
import { PageHeader } from '../layout/PageHeader'
import { useSessionUser } from '../layout/session'
import { VerifiedButton } from '../layout/VerifiedButton'
import { Button } from '../ui/Button'
import { Card } from '../ui/Card'
import { buttonStyles, cx, focusRing } from '../ui/cx'
import { EmptyState } from '../ui/EmptyState'
import { AlertIcon } from '../ui/icons'
import { messageStatuses, statusFill, statusLabel } from '../ui/messageStatus'
import { Skeleton } from '../ui/Skeleton'
import { useNow } from '../ui/time'
import { MessageDrawer } from './MessageDrawer'
import { MessageListHeader, MessageRow } from './MessageRow'
import type { MessagesSearch } from './messagesSearch'
import { SendMessageModal } from './SendMessageModal'

const SEND_REASON = 'Verify your email to send messages. Check your inbox for the link.'

export function Messages() {
  const me = useSessionUser()
  const search = useSearch({ from: '/_app/messages' })
  const navigate = useNavigate({ from: '/messages' })
  const devices = useDevices()
  const messages = useMessages({ status: search.status, deviceId: search.device })
  const now = useNow(30_000)

  // A fresh modal per opening: a new Idempotency-Key and empty fields.
  // `?send` (from the Overview) opens it on arrival; `?message=<id>` opens
  // that message's drawer once the list has it.
  const [sendSession, setSendSession] = useState(0)
  const [sendOpen, setSendOpen] = useState(() => search.send === true && me.email_verified)
  const [flashId, setFlashId] = useState<string>()
  // The drawer keeps its message while it animates out.
  const [selectedId, setSelectedId] = useState<string | undefined>(search.message)
  const [selectedSnapshot, setSelectedSnapshot] = useState<Message | null>(null)
  const [drawerOpen, setDrawerOpen] = useState(search.message !== undefined)

  const names = useMemo(() => new Map(devices.data?.map((d) => [d.id, d.name])), [devices.data])
  const rows = useMemo(() => messages.data?.pages.flatMap((p) => p.data) ?? [], [messages.data])
  // Polling refreshes the open message too.
  const selected = rows.find((m) => m.id === selectedId) ?? selectedSnapshot

  const filtered = search.status !== undefined || search.device !== undefined
  const setFilters = (next: MessagesSearch) => void navigate({ search: (prev) => ({ ...prev, ...next }), replace: true })
  // Drop a one-shot `send` / `message` param once its overlay closes.
  const clearParam = (key: 'send' | 'message') => {
    if (search[key] !== undefined) void navigate({ search: (prev) => ({ ...prev, [key]: undefined }), replace: true })
  }
  const openSend = () => {
    setSendSession((n) => n + 1)
    setSendOpen(true)
  }
  const select = (m: Message) => {
    setSelectedId(m.id)
    setSelectedSnapshot(m)
    setDrawerOpen(true)
  }

  const sendButton = (label: string, align: 'end' | 'center' = 'end') => (
    <VerifiedButton
      verified={me.email_verified}
      onClick={openSend}
      icon={<SendIcon />}
      label={label}
      reason={SEND_REASON}
      tooltipAlign={align}
    />
  )

  return (
    <>
      <PageHeader
        title="Messages"
        description="Every SMS sent through your phones, newest first. Click one to follow its journey."
        actions={sendButton('Send test message')}
      />

      <Filters
        status={search.status}
        device={search.device}
        devices={devices.data}
        onChange={setFilters}
      />

      {messages.error && !messages.data ? (
        <Card role="alert" className="flex flex-col items-center py-12 text-center">
          <span className="mb-4 flex size-11 items-center justify-center rounded-2xl bg-danger-50 text-xl text-danger-600 dark:bg-danger-500/10 dark:text-danger-400">
            <AlertIcon />
          </span>
          <h2 className="text-[15px] font-semibold tracking-tight text-fg">Couldn’t load your messages</h2>
          <p className="mt-1.5 max-w-sm text-sm text-pretty text-fg-muted">
            {errorCopy(isApiError(messages.error) ? messages.error.code : 'default')}
          </p>
          <Button variant="secondary" className="mt-6" loading={messages.isFetching} onClick={() => void messages.refetch()}>
            Retry
          </Button>
        </Card>
      ) : !messages.data ? (
        <MessagesSkeleton />
      ) : rows.length === 0 && !messages.isPlaceholderData ? (
        <Card padding="none" className="overflow-hidden">
          <div
            aria-hidden="true"
            className="pointer-events-none absolute inset-x-0 -top-28 mx-auto size-96 rounded-full bg-radial from-brand-400/25 to-transparent to-70% dark:from-brand-500/20"
          />
          {filtered ? (
            <EmptyState
              className="relative py-16"
              icon={<MessageIcon />}
              title="No messages match these filters"
              description="Try another status or device, or clear the filters to see everything."
              action={
                <Button variant="secondary" onClick={() => setFilters({ status: undefined, device: undefined })}>
                  Clear filters
                </Button>
              }
            />
          ) : devices.data?.length === 0 ? (
            <EmptyState
              className="relative py-16 sm:py-20"
              icon={<PhoneIcon />}
              title="Pair a phone to start sending"
              description="Messages go out from your own Android phone and SIM. Pair one, then send a test message from here."
              action={
                <Link to="/devices" className={cx(buttonStyles('primary', 'md'), 'group/link h-12 rounded-2xl px-6 text-base')}>
                  Pair a phone
                  <ArrowRightIcon className="size-4 transition-transform duration-150 motion-safe:group-hover/link:translate-x-0.5 motion-reduce:transition-none" />
                </Link>
              }
            />
          ) : (
            <EmptyState
              className="relative py-16 sm:py-20"
              icon={<MessageIcon />}
              title="No messages yet"
              description="Send a test message to see it travel from queued to delivered, or send one with your API key."
              action={sendButton('Send test message', 'center')}
            />
          )}
        </Card>
      ) : (
        <>
          <Card
            padding="none"
            className={cx(
              'overflow-hidden transition-opacity duration-150 motion-reduce:transition-none',
              messages.isPlaceholderData && 'opacity-60',
            )}
            aria-busy={messages.isPlaceholderData || undefined}
          >
            <MessageListHeader />
            <ul aria-label="Messages" className="divide-y divide-border">
              {rows.map((m) => (
                <MessageRow
                  key={m.id}
                  message={m}
                  deviceName={names.get(m.device_id)}
                  now={now}
                  onSelect={select}
                  flash={m.id === flashId}
                />
              ))}
            </ul>
          </Card>

          {messages.isFetchNextPageError && (
            <div className="mt-4">
              <FormAlert>{errorCopy(isApiError(messages.error) ? messages.error.code : 'default')}</FormAlert>
            </div>
          )}
          {messages.hasNextPage ? (
            <div className="mt-5 flex justify-center">
              <Button
                variant="secondary"
                loading={messages.isFetchingNextPage}
                onClick={() => void messages.fetchNextPage()}
              >
                Load more
              </Button>
            </div>
          ) : (
            rows.length > 10 && <p className="mt-5 text-center text-xs text-fg-subtle">That’s everything.</p>
          )}
        </>
      )}

      <SendMessageModal
        key={sendSession}
        open={sendOpen}
        onClose={() => {
          setSendOpen(false)
          clearParam('send')
        }}
        onSent={(m) => setFlashId(m.id)}
      />

      <MessageDrawer
        open={drawerOpen}
        onClose={() => {
          setDrawerOpen(false)
          clearParam('message')
        }}
        message={selected}
        deviceName={selected ? names.get(selected.device_id) : undefined}
      />
    </>
  )
}

// ---- filters ---------------------------------------------------------------

interface FiltersProps {
  status: MessageStatus | undefined
  device: string | undefined
  devices: { id: string; name: string }[] | undefined
  onChange: (next: MessagesSearch) => void
}

function Filters({ status, device, devices, onChange }: FiltersProps) {
  const options: (MessageStatus | undefined)[] = [undefined, ...messageStatuses]
  const unknownDevice = device !== undefined && devices !== undefined && !devices.some((d) => d.id === device)
  return (
    <div className="mb-4 flex flex-col gap-3 sm:flex-row sm:items-center sm:justify-between">
      <div
        role="group"
        aria-label="Filter by status"
        className="-mx-4 flex gap-1 overflow-x-auto px-4 pb-1 sm:mx-0 sm:px-0 sm:pb-0"
      >
        <div className="flex shrink-0 gap-1 rounded-xl border border-border bg-surface-sunken/70 p-1">
          {options.map((s) => {
            const active = s === status
            return (
              <button
                key={s ?? 'all'}
                type="button"
                aria-pressed={active}
                onClick={() => onChange({ status: s })}
                className={cx(
                  'flex h-8 items-center gap-1.5 rounded-lg px-3 text-[13px] font-medium whitespace-nowrap',
                  'transition-[background-color,color,box-shadow] duration-150 ease-out motion-reduce:transition-none',
                  active ? 'bg-surface-raised text-fg shadow-control' : 'text-fg-muted hover:text-fg',
                  focusRing,
                )}
              >
                {s && <span aria-hidden="true" className={cx('size-1.5 rounded-full', statusFill[s])} />}
                {s ? statusLabel[s] : 'All'}
              </button>
            )
          })}
        </div>
      </div>

      <div className="relative shrink-0 sm:w-52">
        <select
          aria-label="Filter by device"
          value={device ?? ''}
          onChange={(e) => onChange({ device: e.target.value || undefined })}
          className={cx(
            'h-10 w-full cursor-pointer appearance-none rounded-xl border border-border bg-surface pr-9 pl-3.5 text-sm text-fg shadow-control',
            'transition-[border-color,box-shadow] duration-150 ease-out hover:border-border-strong motion-reduce:transition-none',
            'focus:outline-none focus-visible:border-brand-500 focus-visible:ring-4 focus-visible:ring-brand-500/15',
          )}
        >
          <option value="">All devices</option>
          {devices?.map((d) => (
            <option key={d.id} value={d.id}>
              {d.name}
            </option>
          ))}
          {unknownDevice && <option value={device}>Removed device</option>}
        </select>
        <ChevronDownIcon
          aria-hidden="true"
          className="pointer-events-none absolute inset-y-0 right-3 my-auto size-4 text-fg-subtle"
        />
      </div>
    </div>
  )
}

function MessagesSkeleton() {
  return (
    <Card padding="none" data-testid="messages-skeleton" aria-busy="true" aria-label="Loading messages">
      <div className="hidden h-9 border-b border-border md:block" />
      {[0, 1, 2, 3, 4].map((i) => (
        <div key={i} className={cx('flex items-center gap-4 px-4 py-4 sm:px-5 md:px-6', i > 0 && 'border-t border-border')}>
          <Skeleton className="h-6 w-24 rounded-full" />
          <Skeleton className="h-4 w-32" />
          <Skeleton className="hidden h-4 w-24 md:block" />
          <Skeleton className="ml-auto h-4 w-20" />
        </div>
      ))}
    </Card>
  )
}
