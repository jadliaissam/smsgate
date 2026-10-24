import { useId, useRef, type KeyboardEvent, type ReactNode } from 'react'
import { CopyButton } from '../ui/CopyButton'
import { cx, focusRing } from '../ui/cx'
import { langs, type Lang, type Samples } from './docsContent'

const frame = 'overflow-hidden rounded-xl border border-border bg-surface-sunken shadow-control'
const pre = 'overflow-x-auto p-4 text-[12.5px] leading-6 text-fg'

interface CodeSampleProps {
  samples: Samples
  lang: Lang
  onLang: (lang: Lang) => void
  /** What the sample does, for the copy button ("Copy send request"). */
  label: string
}

/**
 * One request in three languages: an ARIA tablist (arrow keys, Home/End,
 * automatic activation) over a single code panel with its own copy button.
 */
export function CodeSample({ samples, lang, onLang, label }: CodeSampleProps) {
  const id = useId()
  const tabs = useRef<(HTMLButtonElement | null)[]>([])
  const index = langs.findIndex((l) => l.id === lang)

  const select = (i: number) => {
    const n = langs.length
    const next = ((i % n) + n) % n
    onLang(langs[next]!.id)
    tabs.current[next]?.focus()
  }

  const onKeyDown = (e: KeyboardEvent<HTMLDivElement>) => {
    const moves: Record<string, number> = { ArrowRight: index + 1, ArrowLeft: index - 1, Home: 0, End: langs.length - 1 }
    const to = moves[e.key]
    if (to === undefined) return
    e.preventDefault()
    select(to)
  }

  const tabId = (l: Lang) => `${id}-tab-${l}`
  const panelId = `${id}-panel`

  return (
    <div className={frame}>
      <div className="flex items-center justify-between gap-2 border-b border-border py-1 pr-1.5 pl-1.5">
        <div role="tablist" aria-label="Language" className="flex gap-0.5" onKeyDown={onKeyDown}>
          {langs.map((l, i) => {
            const selected = l.id === lang
            return (
              <button
                key={l.id}
                ref={(el) => {
                  tabs.current[i] = el
                }}
                type="button"
                role="tab"
                id={tabId(l.id)}
                aria-selected={selected}
                aria-controls={panelId}
                tabIndex={selected ? 0 : -1}
                onClick={() => onLang(l.id)}
                className={cx(
                  'relative h-8 rounded-lg px-2.5 text-[13px] font-medium',
                  'transition-colors duration-150 ease-out motion-reduce:transition-none',
                  selected
                    ? 'bg-surface text-fg shadow-control inset-ring inset-ring-border'
                    : 'text-fg-muted hover:bg-surface-hover hover:text-fg',
                  focusRing,
                )}
              >
                {l.label}
              </button>
            )
          })}
        </div>
        <CopyButton value={samples[lang]} label={label} />
      </div>
      <div role="tabpanel" id={panelId} aria-labelledby={tabId(lang)} tabIndex={0} className={cx('rounded-b-xl', focusRing)}>
        <pre className={pre}>
          <code className="font-mono">{samples[lang]}</code>
        </pre>
      </div>
    </div>
  )
}

interface CodeBlockProps {
  /** Small caption in the header bar, e.g. "202 Accepted". */
  caption: ReactNode
  code: string
  copyLabel?: string
}

/** A single block (response example) with a caption and copy button. */
export function CodeBlock({ caption, code, copyLabel = 'Copy' }: CodeBlockProps) {
  return (
    <div className={frame}>
      <div className="flex min-h-10 items-center justify-between gap-2 border-b border-border py-1 pr-1.5 pl-4">
        <span className="font-mono text-xs text-fg-muted">{caption}</span>
        <CopyButton value={code} label={copyLabel} />
      </div>
      <pre className={pre}>
        <code className="font-mono">{code}</code>
      </pre>
    </div>
  )
}
