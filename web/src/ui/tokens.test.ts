import { describe, expect, it } from 'vitest'
import { readdirSync, readFileSync } from 'node:fs'
import { join } from 'node:path'

// Components may only use design tokens (brand, ink, success, warning,
// danger, and the semantic aliases), never Tailwind's raw palette or
// arbitrary color literals.
const dir = join(import.meta.dirname)
// The ui kit plus the app's layout and pages: one discipline everywhere.
const sources = ['.', '../layout', '../pages'].flatMap((sub) =>
  readdirSync(join(dir, sub))
    .filter((f) => /\.(tsx?|css)$/.test(f) && !/\.test\.tsx?$/.test(f))
    .map((f) => ({ file: join(sub, f), src: readFileSync(join(dir, sub, f), 'utf8') })),
)

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

// ---- WCAG contrast of the semantic text/surface pairs, both themes ----

const css = readFileSync(join(dir, '..', 'index.css'), 'utf8')

function parseVars(block: string): Map<string, string> {
  const vars = new Map<string, string>()
  for (const m of block.matchAll(/--([\w-]+):\s*([^;]+);/g)) vars.set(m[1]!, m[2]!.trim())
  return vars
}

const themeBlock = css.slice(css.indexOf('@theme {'), css.indexOf('@layer base'))
const darkBlock = css.match(/\n {2}\.dark \{([\s\S]*?)\n {2}\}/)?.[1] ?? ''
const lightVars = parseVars(themeBlock)
const darkVars = new Map([...lightVars, ...parseVars(darkBlock)])

type RGB = [number, number, number]

function oklchToLinearSrgb(l: number, c: number, hDeg: number): RGB {
  const h = (hDeg * Math.PI) / 180
  const a = c * Math.cos(h)
  const b = c * Math.sin(h)
  const l_ = (l + 0.3963377774 * a + 0.2158037573 * b) ** 3
  const m_ = (l - 0.1055613458 * a - 0.0638541728 * b) ** 3
  const s_ = (l - 0.0894841775 * a - 1.291485548 * b) ** 3
  const clamp = (v: number) => Math.min(1, Math.max(0, v))
  return [
    clamp(4.0767416621 * l_ - 3.3077115913 * m_ + 0.2309699292 * s_),
    clamp(-1.2684380046 * l_ + 2.6097574011 * m_ - 0.3413193965 * s_),
    clamp(-0.0041960863 * l_ - 0.7034186147 * m_ + 1.707614701 * s_),
  ]
}

function resolve(vars: Map<string, string>, name: string): RGB {
  let value = vars.get(name)
  for (let i = 0; value?.startsWith('var(') && i < 10; i++) value = vars.get(value.slice(6, -1))
  const m = value?.match(/^oklch\(([\d.]+) ([\d.]+) ([\d.]+)\)$/)
  if (!m) throw new Error(`--${name} is not an opaque oklch color: ${value}`)
  return oklchToLinearSrgb(Number(m[1]), Number(m[2]), Number(m[3]))
}

// Gamut-clamped linear sRGB is already linear, so WCAG luminance is a dot product.
const luminance = ([r, g, b]: RGB) => 0.2126 * r + 0.7152 * g + 0.0722 * b
function contrast(vars: Map<string, string>, fg: string, bg: string): number {
  const [hi, lo] = [luminance(resolve(vars, fg)), luminance(resolve(vars, bg))].sort((x, y) => y - x)
  return (hi! + 0.05) / (lo! + 0.05)
}

const textPairs = ['color-fg', 'color-fg-muted', 'color-fg-subtle'].flatMap((fg) =>
  ['color-surface', 'color-canvas', 'color-surface-raised'].map((bg) => [fg, bg] as const),
)
// White text on filled buttons: every stop of the primary gradient, and danger.
const onFillPairs = [
  ['color-white', 'color-brand-600'],
  ['color-white', 'color-brand-700'],
  ['color-white', 'color-danger-600'],
  ['color-white', 'color-danger-700'],
] as const

describe('contrast (WCAG AA, 4.5:1)', () => {
  it('converts known colors correctly', () => {
    const v = new Map([
      ['w', 'oklch(1 0 0)'],
      ['k', 'oklch(0 0 0)'],
    ])
    expect(contrast(v, 'w', 'k')).toBeCloseTo(21, 1)
  })

  for (const [theme, vars] of [
    ['light', lightVars],
    ['dark', darkVars],
  ] as const) {
    it.each([...textPairs, ...onFillPairs])(`${theme}: %s on %s`, (fg, bg) => {
      expect(contrast(vars, fg, bg)).toBeGreaterThanOrEqual(4.5)
    })
  }
})

describe('primary button gradient', () => {
  it('keeps white text ≥ 4.5:1 on every gradient stop (incl. hover)', () => {
    const src = readFileSync(join(dir, 'cx.ts'), 'utf8')
    const primary = src.match(/primary:\s*([\s\S]*?),\n\s+secondary:/)?.[1] ?? ''
    const stops = [...primary.matchAll(/(?:^|[\s'])(?:hover:)?(?:bg|from|via|to)-(brand-\d+)\b/g)].map((m) => m[1]!)
    expect(stops.length).toBeGreaterThanOrEqual(3)
    for (const stop of stops) expect(contrast(lightVars, 'color-white', `color-${stop}`), stop).toBeGreaterThanOrEqual(4.5)
  })
})
