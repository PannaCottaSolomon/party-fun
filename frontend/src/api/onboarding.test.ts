import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

const BASE = 'https://api.partyfun.test'
const COGNITO = 'https://cognito-idp.ap-southeast-1.amazonaws.com/'

function jwt(claims: object): string {
  const part = (value: object) => btoa(JSON.stringify(value)).replace(/=+$/, '')
  return `${part({ alg: 'RS256' })}.${part(claims)}.signature`
}

/** An ID token for a freshly signed-up account: no Cognito group yet, unless `groups` is given. */
const idToken = (groups?: string[]) =>
  jwt({ sub: 'sub-new', email: 'new@x.sg', 'cognito:username': 'newbie', ...(groups ? { 'cognito:groups': groups } : {}) })

interface Calls {
  cognito: string[]
  profile: { auth: string; body: unknown }[]
}

/**
 * Cognito (sign-in, refresh, revoke) and the Authentication Service's POST /user/me. The account has no group until
 * its profile is created; after that, refreshed tokens carry the chosen role's group.
 */
function backend(profileAnswer: () => Response = () => Response.json({ id: 'sub-new' }, { status: 201 })) {
  const calls: Calls = { cognito: [], profile: [] }
  let group: string | undefined
  vi.stubGlobal(
    'fetch',
    vi.fn(async (input: string, init: RequestInit) => {
      const headers = (init.headers ?? {}) as Record<string, string>
      if (input === COGNITO) {
        const action = headers['X-Amz-Target'].split('.').pop() ?? ''
        const body = JSON.parse(init.body as string) as { AuthFlow?: string }
        calls.cognito.push(action === 'InitiateAuth' ? `InitiateAuth:${body.AuthFlow}` : action)
        if (action === 'RevokeToken') return Response.json({})
        return Response.json({
          AuthenticationResult: {
            AccessToken: `access-${calls.cognito.length}`,
            IdToken: idToken(group ? [group] : undefined),
            RefreshToken: 'refresh-1',
            ExpiresIn: 3600,
          },
        })
      }
      if (input === `${BASE}/user/me` && init.method === 'POST') {
        const body = JSON.parse(init.body as string) as { role: string }
        calls.profile.push({ auth: headers.Authorization, body })
        const response = profileAnswer()
        if (response.ok) group = body.role === 'ORGANISER' ? 'Organiser' : 'Participant'
        return response
      }
      return new Response(null, { status: 404 })
    }),
  )
  return calls
}

async function loadApi() {
  vi.stubEnv('VITE_API_BASE_URL', `${BASE}/`)
  // Unset (a local .env.local may point it at a running service): the profile call then goes through the gateway.
  vi.stubEnv('VITE_USER_API_URL', '')
  vi.stubEnv('VITE_COGNITO_REGION', 'ap-southeast-1')
  vi.stubEnv('VITE_COGNITO_CLIENT_ID', 'client-1')
  vi.resetModules()
  return import('./api')
}

beforeEach(() => vi.resetModules())
afterEach(() => {
  vi.unstubAllGlobals()
  vi.unstubAllEnvs()
})

describe('finishing a sign-up (Register page)', () => {
  it('creates the profile with the chosen role, signs out again and leaves the user logged out', async () => {
    const calls = backend()
    const api = await loadApi()
    const { currentSession } = await import('../auth/session')

    await api.completeRegistration({ username: ' newbie ', password: 'Secret123!', role: 'ORGANISER', fullName: ' Nina New ' })

    expect(calls.profile).toEqual([{ auth: 'Bearer access-1', body: { fullName: 'Nina New', role: 'ORGANISER' } }])
    expect(calls.cognito).toEqual(['InitiateAuth:USER_PASSWORD_AUTH', 'RevokeToken'])
    expect(currentSession()).toBeNull()
  })

  it('then the normal login gets the new role from Cognito, without creating the profile again', async () => {
    const calls = backend()
    const api = await loadApi()
    await api.completeRegistration({ username: 'newbie', password: 'Secret123!', role: 'PARTICIPANT', fullName: 'Nina' })

    const user = await api.login('newbie', 'Secret123!', 'PARTICIPANT')

    expect(user.role).toBe('PARTICIPANT')
    expect(calls.profile).toHaveLength(1)
  })

  it('treats an already existing profile as done', async () => {
    backend(() => Response.json({ status: 409, message: 'Your profile already exists' }, { status: 409 }))
    const api = await loadApi()

    await expect(
      api.completeRegistration({ username: 'newbie', password: 'Secret123!', role: 'PARTICIPANT', fullName: 'Nina' }),
    ).resolves.toBeUndefined()
  })

  it("shows the service's reason when the profile can't be created, and still signs out", async () => {
    const calls = backend(() =>
      Response.json({ status: 403, message: 'Confirm your email address before creating your profile' }, { status: 403 }),
    )
    const api = await loadApi()

    await expect(
      api.completeRegistration({ username: 'newbie', password: 'Secret123!', role: 'PARTICIPANT', fullName: 'Nina' }),
    ).rejects.toThrow('Confirm your email address before creating your profile')
    await vi.waitFor(() => expect(calls.cognito).toContain('RevokeToken'))
  })

  it('never asks for the admin role', async () => {
    const calls = backend()
    const api = await loadApi()

    await expect(
      api.completeRegistration({ username: 'newbie', password: 'Secret123!', role: 'ADMIN', fullName: 'Nina' }),
    ).rejects.toThrow('Admin accounts cannot be registered.')
    expect(calls.cognito).toEqual([])
  })
})

describe('the email verification switch (Authentication Service)', () => {
  function stubFetch(answer: (url: string, init?: RequestInit) => Response | Promise<Response>) {
    const fetchMock = vi.fn(answer)
    vi.stubGlobal('fetch', fetchMock)
    return fetchMock
  }

  it('reads the switch from GET /auth/config', async () => {
    stubFetch(() => Response.json({ emailVerificationEnabled: false }))
    const api = await loadApi()

    await expect(api.emailVerificationEnabled()).resolves.toBe(false)
  })

  it('asks for the code when the service cannot be reached (the safe default)', async () => {
    stubFetch(() => Promise.reject(new TypeError('Failed to fetch')))
    const api = await loadApi()

    await expect(api.emailVerificationEnabled()).resolves.toBe(true)
  })

  it('confirms a new account without the code by username', async () => {
    const fetchMock = stubFetch(() => new Response(null, { status: 204 }))
    const api = await loadApi()

    await api.confirmWithoutCode(' newbie ')

    const [url, init] = fetchMock.mock.calls[0]
    expect(url).toBe(`${BASE}/auth/confirm-sign-up`)
    expect(init?.method).toBe('POST')
    expect(JSON.parse(init?.body as string)).toEqual({ username: 'newbie' })
  })

  it("shows the service's reason when confirming without the code is refused", async () => {
    stubFetch(() =>
      Response.json(
        { status: 403, message: 'Email verification is on: confirm the account with the code from the email' },
        { status: 403 },
      ),
    )
    const api = await loadApi()

    await expect(api.confirmWithoutCode('newbie')).rejects.toThrow('confirm the account with the code from the email')
  })
})

describe('login of an account whose profile was never created (safety net)', () => {
  it('creates the profile with the toggle role, refreshes the tokens and logs in with the new group', async () => {
    const calls = backend()
    const api = await loadApi()

    const user = await api.login('newbie', 'Secret123!', 'PARTICIPANT', 'Nina New')

    expect(user).toMatchObject({ username: 'newbie', email: 'new@x.sg', role: 'PARTICIPANT' })
    expect(calls.profile).toEqual([{ auth: 'Bearer access-1', body: { fullName: 'Nina New', role: 'PARTICIPANT' } }])
    expect(calls.cognito).toEqual(['InitiateAuth:USER_PASSWORD_AUTH', 'InitiateAuth:REFRESH_TOKEN_AUTH'])
  })

  it('uses the username as the name when none was typed', async () => {
    const calls = backend()
    const api = await loadApi()

    await api.login('newbie', 'Secret123!', 'ORGANISER')

    expect(calls.profile[0].body).toEqual({ fullName: 'newbie', role: 'ORGANISER' })
  })

  it('does nothing of the sort on the Admin login page', async () => {
    const calls = backend()
    const api = await loadApi()

    await expect(api.adminLogin('newbie', 'Secret123!')).rejects.toThrow('This is not an admin account.')
    expect(calls.profile).toEqual([])
  })
})
