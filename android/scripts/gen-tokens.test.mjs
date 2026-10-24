// node --test android/scripts
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { test } from 'node:test'
import { CSS_PATH, generate, oklchToRgba, parseColor, readTokens } from './gen-tokens.mjs'

const hex = (s) => ({ r: parseInt(s.slice(1, 3), 16), g: parseInt(s.slice(3, 5), 16), b: parseInt(s.slice(5, 7), 16) })
function near(actual, expected, label) {
  const e = hex(expected)
  for (const ch of ['r', 'g', 'b'])
    assert.ok(Math.abs(actual[ch] - e[ch]) <= 1, `${label}: ${ch} ${actual[ch]} vs ${e[ch]} (expected ${expected})`)
}

test('known OKLCH → sRGB conversions (±1 per channel)', () => {
  // In gamut: our brand-600.
  near(oklchToRgba(0.55, 0.22, 278), '#5B56ED', 'brand-600')
  // Tailwind v4 published hex values for the same OKLCH inputs.
  near(oklchToRgba(0.696, 0.17, 162.48), '#00BC7D', 'emerald-500')
  near(oklchToRgba(0.769, 0.188, 70.08), '#FE9A00', 'amber-500 (out of gamut)')
  near(oklchToRgba(0.645, 0.246, 16.439), '#FF2056', 'rose-500 (out of gamut)')
  near(oklchToRgba(0.511, 0.262, 276.966), '#4F39F6', 'indigo-600 (out of gamut)')
  near(oklchToRgba(1, 0, 0), '#FFFFFF', 'white')
  near(oklchToRgba(0, 0, 0), '#000000', 'black')
})

test('alpha, percentages and var() references', () => {
  assert.deepEqual(parseColor('oklch(1 0 0 / 0.08)'), { r: 255, g: 255, b: 255, a: 20 })
  assert.deepEqual(parseColor('oklch(100% 0 0 / 50%)'), { r: 255, g: 255, b: 255, a: 128 })
  const vars = { 'ink-50': { r: 1, g: 2, b: 3, a: 255 } }
  assert.deepEqual(parseColor('var(--color-ink-50)', vars), vars['ink-50'])
  assert.throws(() => parseColor('var(--color-nope)', vars), /unknown colour reference/)
  assert.throws(() => parseColor('#fff'), /unsupported/)
})

test('reads palettes and light/dark roles from index.css', () => {
  const { palette, light, dark } = readTokens(readFileSync(CSS_PATH, 'utf8'))
  for (const p of ['brand-600', 'ink-950', 'success-500', 'warning-500', 'danger-500', 'white', 'black'])
    assert.ok(palette[p], `palette ${p}`)
  assert.deepEqual(light.surface, palette.white)
  assert.deepEqual(light.canvas, palette['ink-50'])
  assert.deepEqual(dark.canvas, palette['ink-950'])
  assert.deepEqual(dark['fg-on-brand'], palette.white, 'dark inherits roles it does not override')
  assert.equal(dark.border.a, 20)
  assert.deepEqual(Object.keys(dark), Object.keys(light))
  assert.ok(light['shadow-tint-sm'] && dark['shadow-highlight'])
})

test('committed theme files are up to date with index.css', () => {
  for (const [path, content] of Object.entries(generate()))
    assert.equal(readFileSync(path, 'utf8'), content, `${path} is stale; run: node android/scripts/gen-tokens.mjs`)
})
