import { describe, expect, it } from 'vitest'
import { readFileSync } from 'node:fs'
import { join } from 'node:path'
import { THEME_STORAGE_KEY } from '../ui/theme'

// The pre-paint theme script must be a same-origin file (CSP default-src
// 'self' blocks inline scripts) loaded synchronously in <head>.
const root = join(import.meta.dirname, '..', '..')
const html = readFileSync(join(root, 'index.html'), 'utf8')
const script = readFileSync(join(root, 'public', 'theme.js'), 'utf8')

function run(stored: string | null, systemDark: boolean): boolean {
  const classes = new Set<string>()
  const doc = { documentElement: { classList: { add: (c: string) => classes.add(c) }, style: { colorScheme: '' } } }
  const win = { matchMedia: () => ({ matches: systemDark }) }
  const storage = { getItem: (k: string) => (k === THEME_STORAGE_KEY ? stored : null) }
  new Function('document', 'window', 'localStorage', script)(doc, win, storage)
  return classes.has('dark')
}

describe('pre-paint theme script', () => {
  it('has no inline scripts in index.html and loads /theme.js blocking in <head>', () => {
    const head = html.slice(0, html.indexOf('</head>'))
    expect(head).toMatch(/<script src="\/theme\.js"><\/script>/)
    expect(html).not.toMatch(/<script>(?!<\/script>)/)
    expect(html.match(/<script(?![^>]*\bsrc=)[^>]*>/g)).toBeNull()
  })

  it('applies stored and system preferences with the same key as theme.ts', () => {
    expect(run('dark', false)).toBe(true)
    expect(run('light', true)).toBe(false)
    expect(run(null, true)).toBe(true)
    expect(run(null, false)).toBe(false)
  })
})
