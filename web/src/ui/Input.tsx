import { useId, type InputHTMLAttributes, type ReactNode, type Ref } from 'react'
import { cx } from './cx'

export interface InputProps extends Omit<InputHTMLAttributes<HTMLInputElement>, 'size'> {
  label?: ReactNode
  hint?: ReactNode
  /** Error copy; marks the field invalid and replaces the hint. */
  error?: ReactNode
  /** Content rendered inside the field on the right (e.g. a unit or button). */
  trailing?: ReactNode
  ref?: Ref<HTMLInputElement>
}

export function Input({ label, hint, error, trailing, id, className, ...rest }: InputProps) {
  const autoId = useId()
  const inputId = id ?? autoId
  const noteId = `${inputId}-note`
  const note = error ?? hint

  return (
    <div className={cx('flex flex-col gap-1.5', className)}>
      {label && (
        <label htmlFor={inputId} className="text-[13px] font-medium text-fg">
          {label}
        </label>
      )}
      <div className="relative">
        <input
          id={inputId}
          aria-invalid={error ? true : undefined}
          aria-describedby={note ? noteId : undefined}
          className={cx(
            'h-10 w-full rounded-xl border bg-surface px-3.5 text-sm text-fg shadow-control',
            'placeholder:text-fg-subtle',
            'transition-[border-color,box-shadow] duration-150 ease-out motion-reduce:transition-none',
            'focus:outline-none focus-visible:ring-4',
            'disabled:cursor-not-allowed disabled:bg-surface-sunken disabled:text-fg-muted',
            error
              ? 'border-danger-500 focus-visible:border-danger-500 focus-visible:ring-danger-500/15'
              : 'border-border hover:border-border-strong focus-visible:border-brand-500 focus-visible:ring-brand-500/15',
            trailing ? 'pr-11' : undefined,
          )}
          {...rest}
        />
        {trailing && <div className="absolute inset-y-0 right-1.5 flex items-center">{trailing}</div>}
      </div>
      {note && (
        <p
          id={noteId}
          className={cx('text-[13px] leading-5', error ? 'text-danger-600 dark:text-danger-400' : 'text-fg-muted')}
        >
          {note}
        </p>
      )}
    </div>
  )
}
