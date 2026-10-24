import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { act, fireEvent, render, screen } from '@testing-library/react'
import { ToastProvider, useToast } from './Toast'

function Trigger() {
  const toast = useToast()
  return (
    <>
      <button onClick={() => toast.success('Device renamed')}>ok</button>
      <button onClick={() => toast.error('Could not revoke', 'Try again in a moment.')}>fail</button>
    </>
  )
}

describe('Toast', () => {
  beforeEach(() => vi.useFakeTimers())
  afterEach(() => vi.useRealTimers())

  it('announces politely, then auto-dismisses after 4 s', () => {
    render(
      <ToastProvider>
        <Trigger />
      </ToastProvider>,
    )
    const region = screen.getByRole('region', { name: /notifications/i })
    expect(region).toHaveAttribute('aria-live', 'polite')

    fireEvent.click(screen.getByText('ok'))
    expect(screen.getByText('Device renamed')).toBeInTheDocument()

    act(() => vi.advanceTimersByTime(3900))
    expect(screen.getByText('Device renamed')).toBeInTheDocument()

    act(() => vi.advanceTimersByTime(600))
    expect(screen.queryByText('Device renamed')).not.toBeInTheDocument()
  })

  it('shows description and can be dismissed manually', () => {
    render(
      <ToastProvider>
        <Trigger />
      </ToastProvider>,
    )
    fireEvent.click(screen.getByText('fail'))
    expect(screen.getByText('Try again in a moment.')).toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: /dismiss/i }))
    act(() => vi.advanceTimersByTime(300))
    expect(screen.queryByText('Could not revoke')).not.toBeInTheDocument()
  })

  it('useToast outside the provider throws a helpful error', () => {
    vi.spyOn(console, 'error').mockImplementation(() => {})
    expect(() => render(<Trigger />)).toThrow(/ToastProvider/)
  })
})
