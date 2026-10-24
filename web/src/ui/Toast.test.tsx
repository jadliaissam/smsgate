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
    const region = screen.getByRole('region', { name: /^notifications$/i })
    expect(region).toHaveAttribute('aria-live', 'polite')

    fireEvent.click(screen.getByText('ok'))
    expect(screen.getByText('Device renamed')).toBeInTheDocument()

    act(() => vi.advanceTimersByTime(3900))
    expect(screen.getByText('Device renamed')).toBeInTheDocument()

    act(() => vi.advanceTimersByTime(600))
    expect(screen.queryByText('Device renamed')).not.toBeInTheDocument()
  })

  it('keeps errors for 8 s and never nests live regions', () => {
    render(
      <ToastProvider>
        <Trigger />
      </ToastProvider>,
    )
    fireEvent.click(screen.getByText('fail'))
    const toast = screen.getByText('Could not revoke')
    expect(toast.closest('[role="alert"]')).toBeNull()
    expect(screen.getByRole('region', { name: /errors/i })).toHaveAttribute('aria-live', 'assertive')
    act(() => vi.advanceTimersByTime(7900))
    expect(screen.getByText('Could not revoke')).toBeInTheDocument()
    act(() => vi.advanceTimersByTime(400))
    expect(screen.queryByText('Could not revoke')).not.toBeInTheDocument()
  })

  it('pauses auto-dismiss while hovered or focused', () => {
    render(
      <ToastProvider>
        <Trigger />
      </ToastProvider>,
    )
    fireEvent.click(screen.getByText('ok'))
    const card = screen.getByText('Device renamed').closest('[data-toast]')!
    act(() => vi.advanceTimersByTime(3000))
    fireEvent.mouseEnter(card)
    act(() => vi.advanceTimersByTime(10000))
    expect(screen.getByText('Device renamed')).toBeInTheDocument()
    fireEvent.mouseLeave(card)
    act(() => vi.advanceTimersByTime(900))
    expect(screen.getByText('Device renamed')).toBeInTheDocument()
    act(() => vi.advanceTimersByTime(400))
    expect(screen.queryByText('Device renamed')).not.toBeInTheDocument()

    fireEvent.click(screen.getByText('ok'))
    const again = screen.getByText('Device renamed').closest('[data-toast]')!
    fireEvent.focusIn(again.querySelector('button')!)
    act(() => vi.advanceTimersByTime(10000))
    expect(screen.getByText('Device renamed')).toBeInTheDocument()
    fireEvent.focusOut(again.querySelector('button')!)
    act(() => vi.advanceTimersByTime(4500))
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
