import { render } from '@testing-library/react'
import { createMemoryHistory } from '@tanstack/react-router'
import { App } from '../App'
import { createApp } from '../router'
import type { Me } from '../api/types'

/** Mounts the real app (router, query client, toasts) at `path`. */
export function renderApp(path: string) {
  const history = createMemoryHistory({ initialEntries: [path] })
  const app = createApp({ history, retry: false })
  const utils = render(<App app={app} />)
  return { ...app, history, ...utils }
}

export const verifiedMe: Me = { id: 'u1', email: 'ada@example.com', email_verified: true, plan: 'free' }
export const unverifiedMe: Me = { ...verifiedMe, email_verified: false }

/** Current location as a path + query string, e.g. "/login?next=%2Fdevices". */
export function locationOf(router: ReturnType<typeof createApp>['router']): string {
  const { pathname, searchStr } = router.state.location
  return pathname + searchStr
}

export function deferred() {
  let resolve!: () => void
  const promise = new Promise<void>((r) => (resolve = r))
  return { promise, resolve }
}
