import { describe, expect, it, vi } from 'vitest'
import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { ThemeToggle } from './ThemeToggle'

describe('ThemeToggle', () => {
  it('cycles system → light → dark → system, persisting and applying the choice', async () => {
    render(<ThemeToggle />)
    const btn = screen.getByRole('button', { name: /theme: system/i })

    await userEvent.click(btn)
    expect(screen.getByRole('button', { name: /theme: light/i })).toBeInTheDocument()
    expect(localStorage.getItem('smsgate-theme')).toBe('light')
    expect(document.documentElement).not.toHaveClass('dark')

    await userEvent.click(btn)
    expect(localStorage.getItem('smsgate-theme')).toBe('dark')
    expect(document.documentElement).toHaveClass('dark')

    await userEvent.click(btn)
    expect(localStorage.getItem('smsgate-theme')).toBeNull()
    expect(document.documentElement).not.toHaveClass('dark')
  })

  it('still works when localStorage throws', async () => {
    vi.spyOn(Storage.prototype, 'setItem').mockImplementation(() => {
      throw new Error('denied')
    })
    vi.spyOn(Storage.prototype, 'getItem').mockImplementation(() => {
      throw new Error('denied')
    })
    render(<ThemeToggle />)
    await userEvent.click(screen.getByRole('button', { name: /theme: system/i }))
    await userEvent.click(screen.getByRole('button', { name: /theme: light/i }))
    expect(document.documentElement).toHaveClass('dark')
  })
})
