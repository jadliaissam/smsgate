import { Link } from '@tanstack/react-router'
import { AuthHeader, AuthLayout } from '../layout/AuthLayout'
import { buttonStyles, cx } from '../ui/cx'

export function NotFound() {
  return (
    <AuthLayout>
      <div className="text-center">
        <p className="mb-3 bg-linear-to-br from-brand-400 to-brand-700 bg-clip-text font-mono text-5xl font-semibold tracking-tight text-transparent">
          404
        </p>
        <AuthHeader title="Page not found" description="The page you’re looking for doesn’t exist or has moved." />
        <Link to="/" className={cx(buttonStyles('primary'), 'w-full')}>
          Back to dashboard
        </Link>
      </div>
    </AuthLayout>
  )
}
