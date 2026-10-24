import { Badge } from '../ui/Badge'
import { Button } from '../ui/Button'
import { Card } from '../ui/Card'
import { ThemeToggle } from '../ui/ThemeToggle'
import { useToast } from '../ui/Toast'

/** Temporary "/" page so dev and build work before routing (removed in W3). */
export function Placeholder() {
  const toast = useToast()
  return (
    <main className="relative flex min-h-dvh items-center justify-center overflow-hidden px-4">
      <div
        aria-hidden="true"
        className="pointer-events-none absolute -top-40 left-1/2 size-[36rem] -translate-x-1/2 rounded-full bg-brand-500/15 blur-3xl"
      />
      <div className="absolute top-4 right-4">
        <ThemeToggle />
      </div>
      <Card padding="lg" className="relative w-full max-w-md text-center">
        <div className="mx-auto mb-5 flex size-12 items-center justify-center rounded-2xl bg-linear-to-br from-brand-400 to-brand-700 text-xl font-semibold text-fg-on-brand shadow-glow">
          S
        </div>
        <h1 className="text-xl font-semibold tracking-tight text-fg">SMSGate</h1>
        <p className="mt-2 text-sm text-fg-muted">Your phones, one API. The dashboard is on its way.</p>
        <div className="mt-5 flex justify-center">
          <Badge tone="brand" pulse>
            Building
          </Badge>
        </div>
        <div className="mt-6 flex justify-center gap-2">
          <Button variant="secondary" onClick={() => toast.info('Nothing to see yet', 'Pages arrive with the router.')}>
            Say hello
          </Button>
        </div>
      </Card>
    </main>
  )
}
