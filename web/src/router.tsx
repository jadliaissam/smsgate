import type { QueryClient } from '@tanstack/react-query'
import {
  createBrowserHistory,
  createRootRouteWithContext,
  createRoute,
  createRouter,
  lazyRouteComponent,
  redirect,
  type RouterHistory,
} from '@tanstack/react-router'
import { createQueryClient, isUnauthorized, meQuery } from './api/queries'
import { AuthLayout } from './layout/AuthLayout'
import { ErrorPanel, PagePending, SessionError, SplashScreen } from './layout/RouteStates'
import { NotFound } from './pages/NotFound'

export interface RouterContext {
  queryClient: QueryClient
}

// Every search param is a plain string: no JSON coercion of values such as a
// verification token that happens to look like a number.
function parseSearch(searchStr: string): Record<string, string> {
  return Object.fromEntries(new URLSearchParams(searchStr))
}

function stringifySearch(search: Record<string, unknown>): string {
  const params = new URLSearchParams()
  for (const [k, v] of Object.entries(search)) if (v !== undefined && v !== null && v !== '') params.set(k, String(v))
  const s = params.toString()
  return s ? `?${s}` : ''
}

const optionalString = (v: unknown) => (typeof v === 'string' && v !== '' ? v : undefined)

const rootRoute = createRootRouteWithContext<RouterContext>()({
  notFoundComponent: NotFound,
})

// ---- public (signed-out) pages ------------------------------------------

const authRoute = createRoute({ getParentRoute: () => rootRoute, id: '_auth', component: AuthLayout })

const loginRoute = createRoute({
  getParentRoute: () => authRoute,
  path: '/login',
  validateSearch: (s: Record<string, unknown>): { next?: string } => {
    const next = optionalString(s.next)
    return next ? { next } : {}
  },
  component: lazyRouteComponent(() => import('./pages/Login'), 'Login'),
})

const signupRoute = createRoute({
  getParentRoute: () => authRoute,
  path: '/signup',
  component: lazyRouteComponent(() => import('./pages/Signup'), 'Signup'),
})

const verifyRoute = createRoute({
  getParentRoute: () => authRoute,
  path: '/verify',
  validateSearch: (s: Record<string, unknown>): { token?: string } => {
    const token = optionalString(s.token)
    return token ? { token } : {}
  },
  component: lazyRouteComponent(() => import('./pages/Verify'), 'Verify'),
})

// ---- protected app (session required) -----------------------------------

export const APP_ROUTE_ID = '/_app'

const appRoute = createRoute({
  getParentRoute: () => rootRoute,
  id: '_app',
  // [Review Focus 1] No session → /login?next=<where the user was going>.
  beforeLoad: async ({ context, location }) => {
    try {
      return { me: await context.queryClient.ensureQueryData(meQuery) }
    } catch (err) {
      if (isUnauthorized(err)) {
        throw redirect({ to: '/login', search: { next: location.pathname + location.searchStr }, replace: true })
      }
      throw err
    }
  },
  component: lazyRouteComponent(() => import('./layout/AppShell'), 'AppShell'),
  pendingComponent: SplashScreen,
  errorComponent: SessionError,
})

const overviewRoute = createRoute({
  getParentRoute: () => appRoute,
  path: '/',
  component: lazyRouteComponent(() => import('./pages/Overview'), 'Overview'),
})

const devicesRoute = createRoute({
  getParentRoute: () => appRoute,
  path: '/devices',
  component: lazyRouteComponent(() => import('./pages/Devices'), 'Devices'),
})

const apiKeysRoute = createRoute({
  getParentRoute: () => appRoute,
  path: '/api-keys',
  component: lazyRouteComponent(() => import('./pages/ApiKeys'), 'ApiKeys'),
})

const routeTree = rootRoute.addChildren([
  authRoute.addChildren([loginRoute, signupRoute, verifyRoute]),
  appRoute.addChildren([overviewRoute, devicesRoute, apiKeysRoute]),
])

function createAppRouter(queryClient: QueryClient, history: RouterHistory) {
  return createRouter({
    routeTree,
    history,
    context: { queryClient },
    parseSearch,
    stringifySearch,
    defaultPreload: 'intent',
    // Route data lives in TanStack Query; let it decide freshness.
    defaultPreloadStaleTime: 0,
    defaultViewTransition: true,
    defaultPendingComponent: PagePending,
    defaultErrorComponent: ErrorPanel,
    scrollRestoration: true,
  })
}

export type AppRouter = ReturnType<typeof createAppRouter>

declare module '@tanstack/react-router' {
  interface Register {
    router: AppRouter
  }
}

export interface AppInstance {
  router: AppRouter
  queryClient: QueryClient
}

interface CreateAppOptions {
  history?: RouterHistory
  /** Retry transient query failures (off in tests). */
  retry?: boolean
}

/** Wires the QueryClient and router together (one instance per app/test). */
export function createApp({ history = createBrowserHistory(), retry = true }: CreateAppOptions = {}): AppInstance {
  // [Review Focus 1] A 401 `unauthorized` from any /app/* query or mutation
  // while a protected page is shown means the session expired mid-use.
  const onUnauthorized = () => {
    const { status, matches, location } = router.state
    // While a navigation is loading, the protected route's beforeLoad owns
    // the redirect; on public pages a 401 is the expected signed-out answer.
    if (status !== 'idle' || !matches.some((m) => m.routeId === APP_ROUTE_ID)) return
    const next = location.pathname + location.searchStr
    // Leave the protected pages first so nothing refetches into the cache,
    // then drop all of it: the next user must not see this one's data.
    void router.navigate({ to: '/login', search: { next }, replace: true }).then(() => queryClient.clear())
  }
  const queryClient = createQueryClient({ onUnauthorized, retry })
  const router = createAppRouter(queryClient, history)
  return { router, queryClient }
}
