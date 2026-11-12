import type { Role } from '../types'
import { ApiError } from './errors'

/**
 * The Authentication (User) Service: profiles and roles. Cognito creates the account; this service then creates the
 * profile and puts the user in the role's Cognito group (`POST /user/me`, once, right after sign-up). Reached through
 * the API Gateway like the other services, or directly with VITE_USER_API_URL (e.g. http://localhost:8082 locally).
 */
export const USER_API_URL = (import.meta.env.VITE_USER_API_URL || import.meta.env.VITE_API_BASE_URL || '').replace(/\/+$/, '')

export interface Profile {
  id: string
  email: string
  fullName: string
  role: Role
  phoneNumber: string | null
}

function baseUrl(): string {
  if (!USER_API_URL) {
    throw new ApiError('The account service is not configured: set VITE_USER_API_URL (or VITE_API_BASE_URL).')
  }
  return USER_API_URL
}

/** The error message of an account-service answer (its `message`), or a generic one. */
async function errorFrom(response: Response): Promise<ApiError> {
  const { message } = (await response.json().catch(() => ({}))) as { message?: unknown }
  return new ApiError(typeof message === 'string' && message ? message : `The account service answered ${response.status}.`, response.status)
}

/**
 * Whether new accounts must enter the code Cognito emails (the service's EMAIL_VERIFICATION_ENABLED switch). If the
 * service can't be asked, the code is required: the safe default.
 */
export async function emailVerificationEnabled(): Promise<boolean> {
  try {
    const response = await fetch(`${baseUrl()}/auth/config`, { headers: { Accept: 'application/json' } })
    if (!response.ok) return true
    const { emailVerificationEnabled: enabled } = (await response.json()) as { emailVerificationEnabled?: unknown }
    return enabled !== false
  } catch {
    return true
  }
}

/** Confirms a just-created account without the emailed code (only allowed while email verification is off). */
export async function confirmWithoutCode(username: string): Promise<void> {
  let response: Response
  try {
    response = await fetch(`${baseUrl()}/auth/confirm-sign-up`, {
      method: 'POST',
      headers: { Accept: 'application/json', 'Content-Type': 'application/json' },
      body: JSON.stringify({ username }),
    })
  } catch {
    throw new ApiError("Can't reach the account service to confirm your account. Check your connection and try again.")
  }
  if (!response.ok) throw await errorFrom(response)
}

/** Every account's profile (`GET /user/accounts`, Admin only). */
export async function listAccounts(accessToken: string): Promise<Profile[]> {
  let response: Response
  try {
    response = await fetch(`${baseUrl()}/user/accounts`, {
      headers: { Accept: 'application/json', Authorization: `Bearer ${accessToken}` },
    })
  } catch {
    throw new ApiError("Can't reach the account service.")
  }
  if (!response.ok) throw await errorFrom(response)
  return (await response.json()) as Profile[]
}

/**
 * Creates the signed-in user's profile and gives them the chosen role (participant or organiser). `accessToken` is the
 * Cognito access token of the account that just signed up; it has no group yet, so refresh the tokens afterwards to
 * get one. Safe to repeat: a profile that already exists (409) counts as done.
 */
export async function createMyProfile(accessToken: string, input: { fullName: string; role: Role }): Promise<void> {
  const base = baseUrl()
  let response: Response
  try {
    response = await fetch(`${base}/user/me`, {
      method: 'POST',
      headers: { Accept: 'application/json', 'Content-Type': 'application/json', Authorization: `Bearer ${accessToken}` },
      body: JSON.stringify(input),
    })
  } catch {
    throw new ApiError("Can't reach the account service to set up your profile. Check your connection and try again.")
  }
  if (response.ok) return
  const error = await errorFrom(response)
  if (error.status === 409 && error.message === 'Your profile already exists') return
  throw error
}
