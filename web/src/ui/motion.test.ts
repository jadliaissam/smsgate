import { describe, expect, it } from 'vitest'
import { readdirSync, readFileSync } from 'node:fs'
import { join } from 'node:path'

// [Review Focus 5] With prefers-reduced-motion: reduce, no transform or
// keyframe animation may run. Every motion utility used in a component must
// be paired with the matching motion-reduce: reset in the same file.
const dir = join(import.meta.dirname)
const sources = readdirSync(dir)
  .filter((f) => /\.tsx?$/.test(f) && !/\.test\.tsx?$/.test(f))
  .map((f) => ({ file: f, src: readFileSync(join(dir, f), 'utf8') }))

// Tokens already gated by motion-safe: or that are motion-reduce: resets do
// not count as motion use.
const withoutReduce = (src: string) => src.replace(/[^\s'"`]*motion-(?:reduce|safe):[^\s'"`]+/g, '')

// A reset like motion-reduce:scale-none (specificity 0,1,0) cannot beat a
// pseudo-class transform like active:scale-[0.98] (0,2,0), so transforms on
// hover/active/focus/group-*/peer-* must be gated with motion-safe: instead.
const pseudoTransform = /(?:^|[\s'"`])((?:[\w-]+:)*(?:hover|active|focus|focus-visible|focus-within|group-[\w-]+|peer-[\w-]+):(?:[\w-]+:)*-?(?:translate-[xyz]|scale|rotate)-[^\s'"`]+)/g
const ungatedPseudoTransforms = (src: string) =>
  [...src.matchAll(pseudoTransform)].map((m) => m[1]!).filter((t) => !t.includes('motion-safe:'))

const rules: { name: string; use: RegExp; reset: string }[] = [
  { name: 'keyframe animation', use: /(?:^|[\s'"`:])animate-(?!none\b)[\w[\]-]+/, reset: 'motion-reduce:animate-none' },
  { name: 'transition', use: /(?:^|[\s'"`:])transition(?:-\[[^\]\s]+\]|-[\w-]+)?(?=[\s'"`])/, reset: 'motion-reduce:transition-none' },
  { name: 'translate', use: /(?:^|[\s'"`:])-?translate-[xyz]-/, reset: 'motion-reduce:translate-none' },
  { name: 'scale', use: /(?:^|[\s'"`:])-?scale-(?!none\b)[\w[\].-]+/, reset: 'motion-reduce:scale-none' },
]

describe('reduced motion', () => {
  it('scans the ui kit', () => {
    expect(sources.length).toBeGreaterThanOrEqual(12)
  })

  it('the detectors catch each motion utility', () => {
    const [anim, trans, translate, scale] = rules.map((r) => r.use)
    expect(anim!.test(' animate-pop-in ')).toBe(true)
    expect(anim!.test(' animate-none ')).toBe(false)
    expect(trans!.test(' transition-colors ')).toBe(true)
    expect(trans!.test("'transition-[opacity,scale] ")).toBe(true)
    expect(translate!.test(' hover:-translate-y-0.5')).toBe(true)
    expect(scale!.test(' active:scale-[0.98]')).toBe(true)
    expect(withoutReduce('motion-reduce:scale-none')).toBe('')
    expect(withoutReduce('motion-safe:active:scale-[0.98]')).toBe('')
    expect(ungatedPseudoTransforms("'active:scale-[0.98] hover:-translate-y-0.5'")).toEqual([
      'active:scale-[0.98]',
      'hover:-translate-y-0.5',
    ])
    expect(ungatedPseudoTransforms(' motion-safe:active:scale-95 motion-safe:hover:-translate-y-0.5')).toEqual([])
  })

  it.each(sources)('$file gates pseudo-class transforms with motion-safe:', ({ src }) => {
    expect(ungatedPseudoTransforms(src)).toEqual([])
  })

  it.each(sources)('$file pairs motion utilities with motion-reduce: resets', ({ src }) => {
    const plain = withoutReduce(src)
    const usesMotion = /animate-|transition|translate|scale/.test(plain)
    if (usesMotion) expect(src).toContain('motion-reduce:')
    for (const rule of rules) {
      if (rule.use.test(plain)) expect(src, `${rule.name} without ${rule.reset}`).toContain(rule.reset)
    }
  })
})
