import { useCallback, useMemo } from 'react'
import type { Device } from '../api/types'

/**
 * A message's device as the UI knows it: its name; null when the device is
 * no longer paired; undefined while the devices list is not loaded (loading
 * or failed), when it can't be told apart from a removed one.
 */
export type DeviceName = string | null | undefined

/** Resolves device ids to names; "removed" only once the list is known. */
export function useDeviceNames(devices: Device[] | undefined): (id: string) => DeviceName {
  const names = useMemo(() => (devices ? new Map(devices.map((d) => [d.id, d.name])) : undefined), [devices])
  return useCallback((id: string) => (names ? (names.get(id) ?? null) : undefined), [names])
}
