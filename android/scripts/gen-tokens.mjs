#!/usr/bin/env node
// Generates android/app/src/main/java/com/jassam/smsgate/ui/theme/Tokens.kt from
// the dashboard's design tokens in web/src/index.css (single source of truth, D9).
//
//   node android/scripts/gen-tokens.mjs          write Tokens.kt and res/values*/tokens.xml
//   node android/scripts/gen-tokens.mjs --check  exit 1 if any of them is stale
//
// No dependencies. Every colour gets an sRGB value, made with the CSS Color 4
// gamut-mapping algorithm (chroma reduction in OKLCH; the method behind
// Tailwind's published hex fallbacks). Browsers on sRGB screens clamp channels
// instead, which differs by a few units only for the most saturated steps.
// Colours outside sRGB also get a Display P3 value (gamut-mapped to P3), so
// wide-gamut phones show the same saturation as the dashboard on a P3 screen;
// Android converts it back to sRGB on sRGB-only screens.

import { mkdirSync, readFileSync, writeFileSync } from 'node:fs'
import { dirname, resolve } from 'node:path'
import { fileURLToPath, pathToFileURL } from 'node:url'

const here = dirname(fileURLToPath(import.meta.url))
export const CSS_PATH = resolve(here, '../../web/src/index.css')
export const KT_PATH = resolve(here, '../app/src/main/java/com/jassam/smsgate/ui/theme/Tokens.kt')
// Window background and launcher colours for XML resources (used before Compose draws).
export const XML_LIGHT_PATH = resolve(here, '../app/src/main/res/values/tokens.xml')
export const XML_DARK_PATH = resolve(here, '../app/src/main/res/values-night/tokens.xml')

// ---------------------------------------------------------------- colour math

function oklchToOklab([l, c, h]) {
  const r = (h * Math.PI) / 180
  return [l, c * Math.cos(r), c * Math.sin(r)]
}

function oklabToLinearSrgb([L, a, b]) {
  const l = (L + 0.3963377774 * a + 0.2158037573 * b) ** 3
  const m = (L - 0.1055613458 * a - 0.0638541728 * b) ** 3
  const s = (L - 0.0894841775 * a - 1.291485548 * b) ** 3
  return [
    4.0767416621 * l - 3.3077115913 * m + 0.2309699292 * s,
    -1.2684380046 * l + 2.6097574011 * m - 0.3413193965 * s,
    -0.0041960863 * l - 0.7034186147 * m + 1.707614701 * s,
  ]
}

function linearSrgbToOklab([r, g, b]) {
  const l = Math.cbrt(0.4122214708 * r + 0.5363325363 * g + 0.0514459929 * b)
  const m = Math.cbrt(0.2119034982 * r + 0.6806995451 * g + 0.1073969566 * b)
  const s = Math.cbrt(0.0883024619 * r + 0.2817188376 * g + 0.6299787005 * b)
  return [
    0.2104542553 * l + 0.793617785 * m - 0.0040720468 * s,
    1.9779984951 * l - 2.428592205 * m + 0.4505937099 * s,
    0.0259040371 * l + 0.7827717662 * m - 0.808675766 * s,
  ]
}

const gamma = (x) => (x <= 0.0031308 ? 12.92 * x : 1.055 * x ** (1 / 2.4) - 0.055)
const inGamut = (rgb, eps = 1e-6) => rgb.every((v) => v >= -eps && v <= 1 + eps)
const clip = (rgb) => rgb.map((v) => Math.min(1, Math.max(0, v)))
const deltaEOK = (x, y) => Math.hypot(x[0] - y[0], x[1] - y[1], x[2] - y[2])

// Linear sRGB ↔ linear Display P3 (both D65; P3 uses the sRGB transfer curve).
const SRGB_TO_P3 = [
  [0.8224621209, 0.1775378791, 0],
  [0.0331941989, 0.9668058011, 0],
  [0.0170826307, 0.0723974407, 0.9105199286],
]
const P3_TO_SRGB = [
  [1.2249401763, -0.2249401763, 0],
  [-0.0420569547, 1.0420569547, 0],
  [-0.0196375546, -0.0786360456, 1.0982736002],
]
const mul = (m, v) => m.map((row) => row[0] * v[0] + row[1] * v[1] + row[2] * v[2])

/** A target RGB space: from/to linear sRGB (the OKLab matrices' native space). */
export const SPACES = {
  srgb: { fromSrgb: (v) => v, toSrgb: (v) => v },
  p3: { fromSrgb: (v) => mul(SRGB_TO_P3, v), toSrgb: (v) => mul(P3_TO_SRGB, v) },
}

const lchToLinear = (lch, space) => space.fromSrgb(oklabToLinearSrgb(oklchToOklab(lch)))
const linearToOklab = (rgb, space) => linearSrgbToOklab(space.toSrgb(rgb))

/** True when oklch(l c h) lies inside `space` without mapping. */
export function oklchInGamut([l, c, h], space = SPACES.srgb) {
  return l >= 1 || l <= 0 || inGamut(lchToLinear([l, c, h], space))
}

/** CSS Color 4 §13.2 gamut mapping (OKLCH chroma reduction) to linear RGB of `space`, in [0, 1]. */
export function oklchToLinear([l, c, h], space = SPACES.srgb) {
  if (l >= 1) return [1, 1, 1]
  if (l <= 0) return [0, 0, 0]
  const origin = lchToLinear([l, c, h], space)
  if (inGamut(origin)) return clip(origin)
  const JND = 0.02
  const EPS = 0.0001
  let clipped = clip(origin)
  if (deltaEOK(linearToOklab(clipped, space), oklchToOklab([l, c, h])) < JND) return clipped
  let min = 0
  let max = c
  let minInGamut = true
  while (max - min > EPS) {
    const chroma = (min + max) / 2
    const current = lchToLinear([l, chroma, h], space)
    if (minInGamut && inGamut(current)) {
      min = chroma
      continue
    }
    clipped = clip(current)
    const e = deltaEOK(linearToOklab(clipped, space), oklchToOklab([l, chroma, h]))
    if (e < JND) {
      if (JND - e < EPS) return clipped
      minInGamut = false
      min = chroma
    } else {
      max = chroma
    }
  }
  return clipped
}

const to8 = (v) => Math.round(gamma(v) * 255)

/**
 * oklch(L C H [/ A]) → { r, g, b, a } (8-bit sRGB) plus, when the colour is
 * outside sRGB, `p3: [r, g, b]` (8-bit Display P3).
 */
export function oklchToRgba(l, c, h, alpha = 1) {
  const [r, g, b] = oklchToLinear([l, c, h]).map(to8)
  const out = { r, g, b, a: Math.round(alpha * 255) }
  if (!oklchInGamut([l, c, h])) out.p3 = oklchToLinear([l, c, h], SPACES.p3).map(to8)
  return out
}

export function rgbaToHex({ r, g, b, a }) {
  const h2 = (n) => n.toString(16).padStart(2, '0').toUpperCase()
  return a === 255 ? `#${h2(r)}${h2(g)}${h2(b)}` : `#${h2(r)}${h2(g)}${h2(b)}${h2(a)}`
}

// ---------------------------------------------------------------- CSS parsing

const num = (s) => (s.trim().endsWith('%') ? parseFloat(s) / 100 : parseFloat(s))

/** Parses a colour value: oklch(...) or var(--color-x) resolved through `vars`. */
export function parseColor(value, vars = {}) {
  const v = value.trim()
  const ref = /^var\(\s*--color-([a-z0-9-]+)\s*\)$/.exec(v)
  if (ref) {
    const target = vars[ref[1]]
    if (!target) throw new Error(`unknown colour reference ${v}`)
    return target
  }
  const m = /^oklch\(\s*([^\s/]+)\s+([^\s/]+)\s+([^\s/)]+)\s*(?:\/\s*([^\s)]+)\s*)?\)$/.exec(v)
  if (!m) throw new Error(`unsupported colour value "${v}"`)
  const hue = m[3] === 'none' ? 0 : parseFloat(m[3])
  const chroma = m[2] === 'none' ? 0 : m[2].endsWith('%') ? (parseFloat(m[2]) / 100) * 0.4 : parseFloat(m[2])
  return oklchToRgba(num(m[1]), chroma, hue, m[4] === undefined ? 1 : num(m[4]))
}

/** Top-level `--name: value` declarations of the first block whose prelude is `selector`. */
export function blockDeclarations(css, selector) {
  const clean = css.replace(/\/\*[\s\S]*?\*\//g, '')
  const re = new RegExp(`(^|[\\s}])${selector.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')}\\s*\\{`)
  const start = re.exec(clean)
  if (!start) throw new Error(`block "${selector}" not found in index.css`)
  let i = start.index + start[0].length
  let depth = 1
  let text = ''
  for (; i < clean.length && depth > 0; i++) {
    const ch = clean[i]
    if (ch === '{') depth++
    else if (ch === '}') depth--
    else if (depth === 1) text += ch
    if (ch === '}' && depth === 1) text += ';' // a nested block ends a statement
  }
  const out = {}
  for (const stmt of text.split(';')) {
    const m = /^\s*--([a-z0-9-]+)\s*:\s*([\s\S]+?)\s*$/.exec(stmt)
    if (m) out[m[1]] = m[2]
  }
  return out
}

const PALETTE = /^(white|black|[a-z]+-\d+)$/
const SHADOW_ROLE = /^shadow-(tint-[a-z]+|highlight|ring)$/

/** Reads index.css into { palette, light, dark } (name → rgba). */
export function readTokens(css) {
  const theme = blockDeclarations(css, '@theme')
  const root = blockDeclarations(css, ':root')
  const dark = blockDeclarations(css, '.dark')

  const palette = {}
  for (const [k, v] of Object.entries(theme)) {
    const name = k.replace(/^color-/, '')
    if (k.startsWith('color-') && PALETTE.test(name)) palette[name] = parseColor(v)
  }
  const roles = (decls, base) => {
    const out = { ...base }
    for (const [k, v] of Object.entries(decls)) {
      if (k.startsWith('color-') && !PALETTE.test(k.slice(6))) out[k.slice(6)] = parseColor(v, palette)
      else if (SHADOW_ROLE.test(k)) out[k] = parseColor(v, palette)
    }
    return out
  }
  const light = roles({ ...theme, ...root })
  const darkOnly = roles(dark)
  const unknown = Object.keys(darkOnly).filter((k) => !(k in light))
  if (unknown.length) throw new Error(`.dark defines roles the light theme lacks: ${unknown.join(', ')}`)
  const darkRoles = { ...light, ...darkOnly } // .dark overrides only some roles; the rest inherit
  if (Object.keys(palette).length === 0 || Object.keys(light).length === 0) throw new Error('no tokens found')
  return { palette, light, dark: darkRoles }
}

// ---------------------------------------------------------------- Kotlin output

const pascal = (s) => s.split('-').map((p) => p[0].toUpperCase() + p.slice(1)).join('')
const camel = (s) => {
  const p = pascal(s)
  return p[0].toLowerCase() + p.slice(1)
}
const f4 = (n) => `${(n / 255).toFixed(4)}f`
const kColor = ({ r, g, b, a, p3 }) =>
  p3
    ? `Color(${p3.map(f4).join(', ')}, ${f4(a)}, ColorSpaces.DisplayP3)`
    : `Color(0x${[a, r, g, b].map((n) => n.toString(16).padStart(2, '0').toUpperCase()).join('')})`
const kComment = (c) => (c.p3 ? `${rgbaToHex(c)} in sRGB; P3 ${rgbaToHex({ r: c.p3[0], g: c.p3[1], b: c.p3[2], a: c.a })}` : rgbaToHex(c))

export function renderKotlin({ palette, light, dark }) {
  const roleNames = Object.keys(light)
  const L = []
  L.push('// GENERATED by android/scripts/gen-tokens.mjs from web/src/index.css. Do not edit.')
  L.push('// Regenerate with: node android/scripts/gen-tokens.mjs')
  L.push('package com.jassam.smsgate.ui.theme')
  L.push('')
  L.push('import androidx.compose.runtime.Immutable')
  L.push('import androidx.compose.ui.graphics.Color')
  L.push('import androidx.compose.ui.graphics.colorspace.ColorSpaces')
  L.push('')
  L.push('/** Raw palettes (same steps as the dashboard). UI code uses [SemanticColors] where a role exists. */')
  L.push('object Palette {')
  for (const [name, c] of Object.entries(palette)) L.push(`    val ${pascal(name)} = ${kColor(c)} // ${kComment(c)}`)
  L.push('}')
  L.push('')
  L.push('/** Semantic colour roles; light and dark values mirror the dashboard. */')
  L.push('@Immutable')
  L.push('data class SemanticColors(')
  for (const n of roleNames) L.push(`    val ${camel(n)}: Color,`)
  L.push(')')
  for (const [label, set] of [
    ['LightRoles', light],
    ['DarkRoles', dark],
  ]) {
    L.push('')
    L.push(`val ${label} = SemanticColors(`)
    for (const n of roleNames) L.push(`    ${camel(n)} = ${kColor(set[n])}, // ${kComment(set[n])}`)
    L.push(')')
  }
  return L.join('\n') + '\n'
}

const xHex = ({ r, g, b, a }) => `#${[a, r, g, b].map((n) => n.toString(16).padStart(2, '0').toUpperCase()).join('')}`

/** Colour resources for XML (window background, launcher icon); Compose code uses Tokens.kt. */
export function renderXml(roles, palette) {
  const entries = [
    ['token_canvas', roles.canvas],
    ['token_brand_400', palette['brand-400']],
    ['token_brand_600', palette['brand-600']],
    ['token_brand_800', palette['brand-800']],
    ['token_white', palette.white],
  ]
  return [
    '<?xml version="1.0" encoding="utf-8"?>',
    '<!-- GENERATED by android/scripts/gen-tokens.mjs from web/src/index.css. Do not edit. -->',
    '<resources>',
    ...entries.map(([n, c]) => `    <color name="${n}">${xHex(c)}</color>`),
    '</resources>',
    '',
  ].join('\n')
}

/** All generated files: path → content. */
export function generate(css = readFileSync(CSS_PATH, 'utf8')) {
  const t = readTokens(css)
  return {
    [KT_PATH]: renderKotlin(t),
    [XML_LIGHT_PATH]: renderXml(t.light, t.palette),
    [XML_DARK_PATH]: renderXml(t.dark, t.palette),
  }
}

// ---------------------------------------------------------------- CLI

if (process.argv[1] && import.meta.url === pathToFileURL(resolve(process.argv[1])).href) {
  const files = generate()
  if (process.argv.includes('--check')) {
    const stale = Object.entries(files).filter(([path, content]) => {
      try {
        return readFileSync(path, 'utf8') !== content
      } catch {
        return true
      }
    })
    if (stale.length) {
      for (const [path] of stale) console.error(`stale: ${path}`)
      console.error('Theme tokens are out of date with web/src/index.css. Run `node android/scripts/gen-tokens.mjs` and commit the result.')
      process.exit(1)
    }
    console.log('Theme tokens are up to date.')
  } else {
    for (const [path, content] of Object.entries(files)) {
      mkdirSync(dirname(path), { recursive: true })
      writeFileSync(path, content)
      console.log(`wrote ${path}`)
    }
  }
}
