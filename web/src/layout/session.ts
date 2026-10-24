import { getRouteApi } from '@tanstack/react-router'
import { useMe } from '../api/queries'
import type { Me } from '../api/types'

const appRoute = getRouteApi('/_app')

/**
 * The signed-in user inside the protected layout: the live `me` query (it
 * updates after verify/resend), falling back to what the guard loaded.
 */
export function useSessionUser(): Me {
  const { data } = useMe()
  const { me } = appRoute.useRouteContext()
  return data ?? me
}
