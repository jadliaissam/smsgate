import { describe, expect, it } from 'vitest'
import { readdirSync, readFileSync } from 'node:fs'
import { join } from 'node:path'

// Components may only use design tokens (brand, ink, success, warning,
// danger, and the semantic aliases), never Tailwind's raw palette or
// arbitrary color literals.
const dir = join(import.meta.dirname)
const sources = readdirSync(dir)
  .filter((f) => /\.(tsx?|css)$/.test(f) && !/\.test\.tsx?$/.test(f))
  .map((f) => ({ file: f, src: readFileSync(join(dir, f), 'utf8') }))

const palette =
  'slate|gray|zinc|neutral|stone|red|orange|amber|yellow|lime|green|emerald|teal|cyan|sky|blue|indigo|violet|purple|fuchsia|pink|rose'
const rawPalette = new RegExp(`\\b[a-z-]+-(?:${palette})-(?:50|[1-9]00|950)\\b`)
const arbitraryColor = /-\[(?:#|rgb|hsl|oklch|color)/

describe('design tokens only', () => {
  it.each(sources)('$file uses no raw palette colors', ({ src }) => {
    expect(src.match(rawPalette)?.[0] ?? null).toBeNull()
    expect(src.match(arbitraryColor)?.[0] ?? null).toBeNull()
  })

  it('the detector catches raw palette names', () => {
    expect(rawPalette.test('bg-indigo-500')).toBe(true)
    expect(rawPalette.test('dark:hover:text-gray-200')).toBe(true)
    expect(rawPalette.test('bg-brand-600 text-fg-muted')).toBe(false)
    expect(arbitraryColor.test('bg-[#fff]')).toBe(true)
  })
})
