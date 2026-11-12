import type { ReactNode } from 'react'

type Kind = 'info' | 'success' | 'error' | 'warning'

/** An inline notice: info, success, warning or error. */
export function Message({ kind = 'info', children }: { kind?: Kind; children: ReactNode }) {
  return (
    <div className={`message message-${kind}`} role={kind === 'error' ? 'alert' : 'status'}>
      {children}
    </div>
  )
}
