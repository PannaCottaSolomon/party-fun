import { ApiError } from '../api/errors'
import type { Role } from '../types'

/**
 * Amazon Cognito user pool calls, made straight from the browser to the Cognito Identity Provider API (it allows
 * CORS), so no AWS SDK is needed. The app client must be a public client: no client secret, with the
 * USER_PASSWORD_AUTH and refresh-token flows enabled.
 */

const REGION = import.meta.env.VITE_COGNITO_REGION
const CLIENT_ID = import.meta.env.VITE_COGNITO_CLIENT_ID

export interface CognitoTokens {
  /** Sent to the API Gateway and the services as `Authorization: Bearer ...`. */
  accessToken: string
  /** Who the user is: email, username and `cognito:groups`. */
  idToken: string
  refreshToken: string
  /** When the access token expires (ms since the epoch). */
  expiresAt: number
}

/** The claims of a Cognito ID token that this app reads. */
export interface IdTokenClaims {
  sub: string
  email?: string
  preferred_username?: string
  name?: string
  'cognito:username'?: string
  'cognito:groups'?: string[]
}

/** Friendlier messages for the Cognito errors a user can act on. */
const MESSAGES: Record<string, string> = {
  UserNotFoundException: 'Invalid username or password.',
  UserNotConfirmedException: 'Confirm your email address first, with the code we emailed you.',
  PasswordResetRequiredException: 'You need to reset your password before you can log in.',
  UsernameExistsException: 'That username is already taken.',
  CodeMismatchException: 'That confirmation code is wrong. Check the email and try again.',
  ExpiredCodeException: 'That confirmation code has expired.',
  TooManyRequestsException: 'Too many attempts. Wait a moment and try again.',
  LimitExceededException: 'Too many attempts. Wait a moment and try again.',
}

const INVALID_EMAIL = 'Enter a valid email address, like janedoe12@email.com.'

/** The user pool's password policy (Cognito's default): checked on Register before Cognito is asked. */
export const PASSWORD_REQUIREMENTS =
  'Password must be at least 8 characters, with at least one capital letter, one lowercase letter, one number and one symbol (like ! @ # $).'

export function meetsPasswordPolicy(password: string): boolean {
  return password.length >= 8 && /[A-Z]/.test(password) && /[a-z]/.test(password) && /\d/.test(password) && /[^A-Za-z0-9]/.test(password)
}

/** Cognito's message for an error, with its raw email and password format errors (some quote a regex) made readable. */
function messageFor(type: string, message: string | undefined): string | undefined {
  if (type === 'InvalidPasswordException') return PASSWORD_REQUIREMENTS
  if (type === 'InvalidParameterException' && message) {
    if (/email/i.test(message)) return INVALID_EMAIL
    if (/password/i.test(message)) return PASSWORD_REQUIREMENTS
  }
  return MESSAGES[type] ?? message
}

async function call<T>(action: string, body: Record<string, unknown>): Promise<T> {
  if (!REGION || !CLIENT_ID) {
    throw new ApiError('Login is not configured: set VITE_COGNITO_REGION and VITE_COGNITO_CLIENT_ID.')
  }
  let response: Response
  try {
    response = await fetch(`https://cognito-idp.${REGION}.amazonaws.com/`, {
      method: 'POST',
      headers: {
        'Content-Type': 'application/x-amz-json-1.1',
        'X-Amz-Target': `AWSCognitoIdentityProviderService.${action}`,
      },
      body: JSON.stringify({ ClientId: CLIENT_ID, ...body }),
    })
  } catch {
    throw new ApiError("Can't reach Amazon Cognito to log you in. Check your connection.")
  }
  const json = (await response.json().catch(() => ({}))) as { __type?: string; message?: string }
  if (!response.ok) {
    const type = json.__type?.split('#').pop() ?? ''
    throw new ApiError(messageFor(type, json.message) ?? `Cognito answered ${response.status}.`, response.status)
  }
  return json as T
}

interface AuthResult {
  AuthenticationResult?: { AccessToken: string; IdToken: string; RefreshToken?: string; ExpiresIn: number }
  ChallengeName?: string
}

function tokensFrom(result: AuthResult, refreshToken?: string): CognitoTokens {
  const auth = result.AuthenticationResult
  if (!auth) {
    throw new ApiError(
      result.ChallengeName === 'NEW_PASSWORD_REQUIRED'
        ? 'This account must set a new password first. Ask an admin to set a permanent password.'
        : `Cognito asked for an extra login step (${result.ChallengeName ?? 'unknown'}) that this app doesn't support.`,
    )
  }
  return {
    accessToken: auth.AccessToken,
    idToken: auth.IdToken,
    refreshToken: auth.RefreshToken ?? refreshToken ?? '',
    expiresAt: Date.now() + auth.ExpiresIn * 1000,
  }
}

/** Logs in with a username (or email, if the pool allows it as an alias) and password. */
export async function signIn(username: string, password: string): Promise<CognitoTokens> {
  const result = await call<AuthResult>('InitiateAuth', {
    AuthFlow: 'USER_PASSWORD_AUTH',
    AuthParameters: { USERNAME: username, PASSWORD: password },
  })
  return tokensFrom(result)
}

/** New access and ID tokens for a refresh token (Cognito keeps the same refresh token unless rotation is on). */
export async function refresh(refreshToken: string): Promise<CognitoTokens> {
  const result = await call<AuthResult>('InitiateAuth', {
    AuthFlow: 'REFRESH_TOKEN_AUTH',
    AuthParameters: { REFRESH_TOKEN: refreshToken },
  })
  return tokensFrom(result, refreshToken)
}

/** Revokes the refresh token (and the tokens issued with it). Best effort: logging out locally always works. */
export async function revoke(refreshToken: string): Promise<void> {
  await call('RevokeToken', { Token: refreshToken }).catch(() => undefined)
}

/**
 * Creates an unconfirmed account; Cognito emails a confirmation code. The browser never adds the user to a group:
 * once confirmed, the Authentication Service does that (POST /user/me, see api.completeRegistration). `role` is also
 * sent as client metadata, for any pool trigger that wants it.
 */
export async function signUp(input: { username: string; email: string; password: string; role: Role }): Promise<{ confirmed: boolean; destination?: string }> {
  const result = await call<{ UserConfirmed: boolean; CodeDeliveryDetails?: { Destination?: string } }>('SignUp', {
    Username: input.username,
    Password: input.password,
    UserAttributes: [{ Name: 'email', Value: input.email }],
    ClientMetadata: { role: input.role },
  })
  return { confirmed: result.UserConfirmed, destination: result.CodeDeliveryDetails?.Destination }
}

/** Confirms a new account with the emailed code. */
export async function confirmSignUp(username: string, code: string, role: Role): Promise<void> {
  await call('ConfirmSignUp', { Username: username, ConfirmationCode: code, ClientMetadata: { role } })
}

/** Reads a JWT's payload. It isn't verified here: the API Gateway and the services verify every token. */
export function decodeJwt<T>(token: string): T {
  const payload = token.split('.')[1] ?? ''
  const base64 = payload.replace(/-/g, '+').replace(/_/g, '/').padEnd(Math.ceil(payload.length / 4) * 4, '=')
  try {
    const bytes = Uint8Array.from(atob(base64), (c) => c.charCodeAt(0))
    return JSON.parse(new TextDecoder().decode(bytes)) as T
  } catch {
    throw new ApiError('Cognito returned a token this app could not read.')
  }
}

/** The app roles of the user's Cognito groups (participant, organiser, admin; any case). Other groups are ignored. */
export function rolesFromGroups(groups: string[] = []): Role[] {
  const known: Role[] = ['PARTICIPANT', 'ORGANISER', 'ADMIN']
  return known.filter((role) => groups.some((group) => group.toUpperCase() === role))
}

/** A display name: the preferred username or name, else the login username (unless it's the sub), else the email. */
export function displayName(claims: IdTokenClaims): string {
  const username = claims['cognito:username']
  return claims.preferred_username || claims.name || (username && username !== claims.sub ? username : '') || claims.email || 'user'
}
