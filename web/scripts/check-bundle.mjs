// Bundle budget gate (runs as `postbuild`): gzip sizes of the entry JS chunk
// and of all CSS in dist/ must stay under the limits below.
import { readFileSync, readdirSync } from 'node:fs'
import { gzipSync } from 'node:zlib'
import { join } from 'node:path'

const KB = 1024
const LIMIT_JS = 200 * KB
const LIMIT_CSS = 40 * KB
const dist = new URL('../dist/', import.meta.url).pathname
const assets = join(dist, 'assets')

const gz = (file) => gzipSync(readFileSync(file), { level: 9 }).length
const kb = (n) => `${(n / KB).toFixed(1)} KB`

const html = readFileSync(join(dist, 'index.html'), 'utf8')
const entryMatch = html.match(/<script[^>]*type="module"[^>]*src="\/assets\/([^"]+\.js)"/)
if (!entryMatch) {
  console.error('check-bundle: entry module script not found in dist/index.html')
  process.exit(1)
}
const entry = entryMatch[1]

let failed = false
const check = (label, size, limit) => {
  const ok = size <= limit
  console.log(`${ok ? 'ok  ' : 'FAIL'} ${label}: ${kb(size)} gzip (limit ${kb(limit)})`)
  if (!ok) failed = true
}

const files = readdirSync(assets)
check(`entry ${entry}`, gz(join(assets, entry)), LIMIT_JS)
let css = 0
for (const f of files.filter((f) => f.endsWith('.css'))) css += gz(join(assets, f))
check('css total', css, LIMIT_CSS)

const lazy = files.filter((f) => f.endsWith('.js') && f !== entry)
console.log(`info ${lazy.length} other JS chunks, ${kb(lazy.reduce((s, f) => s + gz(join(assets, f)), 0))} gzip total (not budgeted)`)

if (failed) process.exit(1)
