import { describe, expect, it, vi } from 'vitest'
import { render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { Button } from './Button'

describe('Button', () => {
  it('fires onClick when idle', async () => {
    const onClick = vi.fn()
    render(<Button onClick={onClick}>Save</Button>)
    await userEvent.click(screen.getByRole('button', { name: 'Save' }))
    expect(onClick).toHaveBeenCalledOnce()
  })

  it('loading disables the button and shows a spinner', async () => {
    const onClick = vi.fn()
    render(
      <Button loading onClick={onClick}>
        Save
      </Button>,
    )
    const btn = screen.getByRole('button', { name: /save/i })
    expect(btn).toBeDisabled()
    expect(btn).toHaveAttribute('aria-busy', 'true')
    expect(within(btn).getByRole('status', { name: /loading/i })).toBeInTheDocument()
    await userEvent.click(btn)
    expect(onClick).not.toHaveBeenCalled()
  })

  it('defaults to type="button" so it never submits forms by accident', () => {
    render(<Button>Go</Button>)
    expect(screen.getByRole('button')).toHaveAttribute('type', 'button')
  })
})
