import { useEffect, useEffectEvent, useState } from 'react'

interface LoadState<T> {
  key: string
  data?: T
  error?: string
}

/**
 * Runs `load` whenever `key` changes (or `reload` is called) and tracks its result.
 * `data` is undefined while the first load for a key is in progress.
 */
export function useLoad<T>(key: string, load: () => Promise<T>) {
  const [state, setState] = useState<LoadState<T>>({ key: '' })
  const [version, setVersion] = useState(0)
  const run = useEffectEvent(load)

  useEffect(() => {
    let active = true
    run().then(
      (data) => active && setState({ key, data }),
      (error: unknown) => active && setState({ key, error: error instanceof Error ? error.message : 'Something went wrong.' }),
    )
    return () => {
      active = false
    }
  }, [key, version])

  const current = state.key === key
  return {
    data: current ? state.data : undefined,
    error: current ? state.error : undefined,
    loading: !current,
    reload: () => setVersion((v) => v + 1),
  }
}
