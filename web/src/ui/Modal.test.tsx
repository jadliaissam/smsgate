import { describe, expect, it, vi } from 'vitest'
import { useState } from 'react'
import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { Modal } from './Modal'

function Harness({ onClose }: { onClose?: () => void }) {
  const [open, setOpen] = useState(false)
  return (
    <>
      <button onClick={() => setOpen(true)}>Open</button>
      <Modal
        open={open}
        onClose={() => {
          onClose?.()
          setOpen(false)
        }}
        title="Rename device"
      >
        <input aria-label="Name" />
        <button>Save</button>
      </Modal>
    </>
  )
}

function Nested({ onOuterClose }: { onOuterClose: () => void }) {
  const [inner, setInner] = useState(true)
  return (
    <Modal open onClose={onOuterClose} title="Outer">
      <button>outer action</button>
      <Modal open={inner} onClose={() => setInner(false)} title="Inner">
        <button>inner action</button>
      </Modal>
    </Modal>
  )
}

describe('Modal', () => {
  it('with stacked overlays only the topmost handles Escape and Tab', async () => {
    const onOuterClose = vi.fn()
    render(<Nested onOuterClose={onOuterClose} />)
    const inner = screen.getByRole('dialog', { name: 'Inner' })
    expect(inner.contains(document.activeElement)).toBe(true)
    for (let i = 0; i < 4; i++) {
      await userEvent.tab()
      expect(inner.contains(document.activeElement)).toBe(true)
    }
    await userEvent.keyboard('{Escape}')
    expect(onOuterClose).not.toHaveBeenCalled()
    await vi.waitFor(() => expect(screen.queryByRole('dialog', { name: 'Inner' })).not.toBeInTheDocument())
    await userEvent.keyboard('{Escape}')
    expect(onOuterClose).toHaveBeenCalledOnce()
  })

  it('renders into a portal as a labelled dialog and moves focus inside', async () => {
    const { container } = render(<Harness />)
    await userEvent.click(screen.getByRole('button', { name: 'Open' }))
    const dialog = screen.getByRole('dialog', { name: 'Rename device' })
    expect(dialog).toHaveAttribute('aria-modal', 'true')
    expect(container.contains(dialog)).toBe(false)
    expect(dialog.contains(document.activeElement)).toBe(true)
  })

  it('closes on Escape and returns focus to the trigger', async () => {
    const onClose = vi.fn()
    render(<Harness onClose={onClose} />)
    const trigger = screen.getByRole('button', { name: 'Open' })
    await userEvent.click(trigger)
    await userEvent.keyboard('{Escape}')
    expect(onClose).toHaveBeenCalledOnce()
    expect(trigger).toHaveFocus()
    await vi.waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument())
  })

  it('closes on backdrop click but not on panel click', async () => {
    const onClose = vi.fn()
    render(<Harness onClose={onClose} />)
    await userEvent.click(screen.getByRole('button', { name: 'Open' }))
    await userEvent.click(screen.getByRole('dialog'))
    expect(onClose).not.toHaveBeenCalled()
    await userEvent.click(screen.getByTestId('modal-backdrop'))
    expect(onClose).toHaveBeenCalledOnce()
  })

  it('traps Tab focus inside the dialog', async () => {
    render(<Harness />)
    await userEvent.click(screen.getByRole('button', { name: 'Open' }))
    const dialog = screen.getByRole('dialog')
    for (let i = 0; i < 6; i++) {
      await userEvent.tab()
      expect(dialog.contains(document.activeElement)).toBe(true)
    }
    await userEvent.tab({ shift: true })
    expect(dialog.contains(document.activeElement)).toBe(true)
  })
})
