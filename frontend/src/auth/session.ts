import { ApiError } from '../api/errors'
import type { User } from '../types'
import { refresh, type CognitoTokens } from './cognito'

/**
 * The logged-in user and their Cognito tokens, kept in localStorage so a reload stays logged in. The API clients read
 * the access token from here (refreshing it when it's about to expire); the AuthProvider listens for the session
 * ending (logout, or a refresh token that no longer works) to send the user back to Login.
 */
const SESSION_KEY = 'partyfun.session.v2'

/** Refresh the access token this long before it expires, so a request never carries one that's about to lapse. */
const REFRESH_MARGIN_MS = 60_000

export interface Session {
  user: User
  accessToken: string
  refreshToken: string
  /** When the access token expires (ms since the epoch). */
  expiresAt: number
}

function storage(): Storage | null {
  try {
    return typeof localStorage === 'undefined' ? null : localStorage
  } catch {
    return null
  }
}

function load(): Session | null {
  try {
    const raw = storage()?.getItem(SESSION_KEY)
    return raw ? (JSON.parse(raw) as Session) : null
  } catch {
    return null
  }
}

function save(session: Session | null): void {
  try {
    if (session) storage()?.setItem(SESSION_KEY, JSON.stringify(session))
    else storage()?.removeItem(SESSION_KEY)
  } catch {
    // Storage blocked: the session just won't survive a reload.
  }
}

let current: Session | null = load()
let refreshing: Promise<Session> | null = null
const listeners = new Set<() => void>()

export function currentSession(): Session | null {
  return current
}

export function startSession(user: User, tokens: CognitoTokens): Session {
  current = { user, accessToken: tokens.accessToken, refreshToken: tokens.refreshToken, expiresAt: tokens.expiresAt }
  save(current)
  return current
}

/** Forgets the session and tells the listeners (the AuthProvider), so the app shows Login again. */
export function endSession(): void {
  const hadSession = current !== null
  current = null
  refreshing = null
  save(null)
  if (hadSession) listeners.forEach((listener) => listener())
}

/** Calls `listener` whenever the session ends. Returns the unsubscribe function. */
export function onSessionEnded(listener: () => void): () => void {
  listeners.add(listener)
  return () => listeners.delete(listener)
}

/**
 * A valid access token for `Authorization: Bearer ...`. Refreshes it first when it's about to expire, or when
 * `forceRefresh` is set (a service just answered 401). Concurrent callers share one refresh. If Cognito rejects the
 * refresh token (expired or revoked) the session ends; if Cognito can't be reached, the session is kept.
 */
export async function accessToken(forceRefresh = false): Promise<string> {
  const session = current
  if (!session) throw new ApiError('Please log in again.', 401)
  if (!forceRefresh && session.expiresAt - REFRESH_MARGIN_MS > Date.now()) return session.accessToken

  refreshing ??= refresh(session.refreshToken)
    .then((tokens) => startSession(session.user, tokens))
    .catch((e: unknown) => {
      if (!(e instanceof ApiError && e.status === 400)) throw e
      endSession()
      throw new ApiError('Your session has expired. Please log in again.', 401)
    })
    .finally(() => {
      refreshing = null
    })
  return (await refreshing).accessToken
}
