import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import type { BookingDto, EventDto } from './eventsApi'

const BASE = 'https://api.partyfun.test'
const COGNITO = 'https://cognito-idp.ap-southeast-1.amazonaws.com/'

function jwt(claims: object): string {
  const part = (value: object) => btoa(JSON.stringify(value)).replace(/=+$/, '')
  return `${part({ alg: 'RS256' })}.${part(claims)}.signature`
}

const ID_TOKEN = jwt({ sub: 'sub-participant', email: 'p@x.sg', 'cognito:username': 'pat', 'cognito:groups': ['participant'] })

const EVENT: EventDto = {
  id: 12,
  title: 'CS302 Party',
  description: 'Fun',
  organiserId: 7,
  location: 'SCIS 1',
  startDateTime: '2026-11-20T11:00:00',
  endDateTime: null,
  capacity: 90,
  maxCapacity: 100,
  minSignUps: 20,
  expiresAt: '2026-11-13T15:59:00',
  ticketPrice: 15,
  currency: 'SGD',
  status: 'ACTIVE',
  createdAt: null,
  updatedAt: null,
}

function bookingDto(overrides: Partial<BookingDto> = {}): BookingDto {
  const now = new Date()
  return {
    id: 5,
    userId: 0,
    eventId: 12,
    totalAmount: 15,
    currency: 'SGD',
    status: 'CONFIRMED',
    createdAt: now.toISOString().slice(0, 19),
    expiresAt: new Date(now.getTime() + 15 * 60_000).toISOString().slice(0, 19),
    confirmedAt: now.toISOString().slice(0, 19),
    failedAt: null,
    paymentBookingId: 9,
    paymentReference: '31',
    failureReason: null,
    ...overrides,
  }
}

type Handler = (url: URL, init: RequestInit) => Response | undefined

/** When set, Cognito refuses the refresh token (it expired or was revoked). */
let refreshRejected = false

/** A fetch stand-in: Cognito's InitiateAuth, then the given handlers for the Event Service. */
function backend(...handlers: Handler[]) {
  let accessTokens = 0
  const fetchMock = vi.fn(async (input: string, init: RequestInit) => {
    const url = new URL(input)
    const headers = (init.headers ?? {}) as Record<string, string>
    if (input === COGNITO && headers['X-Amz-Target'] === 'AWSCognitoIdentityProviderService.InitiateAuth') {
      if (refreshRejected && (init.body as string).includes('REFRESH_TOKEN_AUTH')) {
        return Response.json({ __type: 'NotAuthorizedException', message: 'Refresh Token has expired' }, { status: 400 })
      }
      accessTokens++
      return Response.json({
        AuthenticationResult: { AccessToken: `access-${accessTokens}`, IdToken: ID_TOKEN, RefreshToken: 'refresh-1', ExpiresIn: 3600 },
      })
    }
    for (const handler of handlers) {
      const response = handler(url, init)
      if (response) return response
    }
    return new Response(null, { status: 404 })
  })
  vi.stubGlobal('fetch', fetchMock)
  return fetchMock
}

const authOf = (init: RequestInit) => (init.headers as Record<string, string>).Authorization

async function loadApi() {
  vi.stubEnv('VITE_API_BASE_URL', `${BASE}/`)
  vi.stubEnv('VITE_COGNITO_REGION', 'ap-southeast-1')
  vi.stubEnv('VITE_COGNITO_CLIENT_ID', 'client-1')
  vi.resetModules()
  return import('./api')
}

beforeEach(() => {
  vi.resetModules()
  refreshRejected = false
})
afterEach(() => {
  vi.unstubAllGlobals()
  vi.unstubAllEnvs()
})

describe('Cognito login', () => {
  it('logs in with USER_PASSWORD_AUTH and takes the role and numeric user id from the ID token', async () => {
    const fetchMock = backend()
    const api = await loadApi()
    const { toServiceId } = await import('./serviceIds')

    const user = await api.login('pat', 'secret', 'PARTICIPANT')

    expect(user).toEqual({ id: String(toServiceId('sub-participant')), username: 'pat', email: 'p@x.sg', role: 'PARTICIPANT' })
    const [, init] = fetchMock.mock.calls[0]
    expect(JSON.parse(init.body as string)).toEqual({
      ClientId: 'client-1',
      AuthFlow: 'USER_PASSWORD_AUTH',
      AuthParameters: { USERNAME: 'pat', PASSWORD: 'secret' },
    })
    await expect(api.login('pat', 'secret', 'ORGANISER')).rejects.toThrow('This account is registered as a participant.')
  })
})

describe('Event Service through the API Gateway', () => {
  it('sends the access token and maps the events', async () => {
    const fetchMock = backend((url, init) =>
      url.pathname === '/events' && init.method === 'GET' ? Response.json([EVENT]) : undefined,
    )
    const api = await loadApi()
    await api.login('pat', 'secret', 'PARTICIPANT')

    const events = await api.listEvents()

    expect(events).toHaveLength(1)
    expect(events[0]).toMatchObject({ id: '12', name: 'CS302 Party', occupiedSpots: 10, status: 'PENDING' })
    const [url, init] = fetchMock.mock.calls[1]
    expect(url).toBe(`${BASE}/events`)
    expect(authOf(init)).toBe('Bearer access-1')
  })

  it('refreshes the token once when the gateway answers 401', async () => {
    const fetchMock = backend((url, init) => {
      if (url.pathname !== '/events/12') return undefined
      return authOf(init) === 'Bearer access-1' ? new Response(null, { status: 401 }) : Response.json(EVENT)
    })
    const api = await loadApi()
    await api.login('pat', 'secret', 'PARTICIPANT')

    expect((await api.getEvent('12')).name).toBe('CS302 Party')
    const refresh = JSON.parse(fetchMock.mock.calls[2][1].body as string)
    expect(refresh).toMatchObject({ AuthFlow: 'REFRESH_TOKEN_AUTH', AuthParameters: { REFRESH_TOKEN: 'refresh-1' } })
    expect(authOf(fetchMock.mock.calls[3][1])).toBe('Bearer access-2')
  })

  it('ends the session when Cognito rejects the refresh token', async () => {
    refreshRejected = true
    backend((url) => (url.pathname === '/events/12' ? new Response(null, { status: 401 }) : undefined))
    const api = await loadApi()
    const session = await import('../auth/session')
    await api.login('pat', 'secret', 'PARTICIPANT')
    const ended = vi.fn()
    session.onSessionEnded(ended)

    await expect(api.getEvent('12')).rejects.toMatchObject({ status: 401, message: expect.stringMatching(/session has expired/) })
    expect(session.currentSession()).toBeNull()
    expect(ended).toHaveBeenCalledOnce()
  })

  it("shows the service's ProblemDetail message", async () => {
    backend((url) =>
      url.pathname === '/events/12/signup'
        ? Response.json({ title: 'Conflict', status: 409, detail: 'Event 12 has no remaining capacity' }, { status: 409 })
        : url.pathname === '/events/12'
          ? Response.json(EVENT)
          : undefined,
    )
    const api = await loadApi()
    const user = await api.login('pat', 'secret', 'PARTICIPANT')
    const event = await api.getEvent('12')

    await expect(api.signUp(user, event, 'pm_1')).rejects.toMatchObject({
      message: 'Event 12 has no remaining capacity',
      status: 409,
    })
  })

  it('signs up with the user id and PaymentMethod, records the booking, and shows it cancelled once the event is gone', async () => {
    let eventExists = true
    const fetchMock = backend((url, init) => {
      if (url.pathname === '/events/12/signup' && init.method === 'POST') {
        return Response.json([bookingDto({ userId: Number(url.searchParams.get('userId')) })], { status: 201 })
      }
      if (url.pathname === '/events/12') return eventExists ? Response.json({ ...EVENT, capacity: 89 }) : new Response(null, { status: 404 })
      if (url.pathname === '/events') return Response.json(eventExists ? [EVENT] : [])
      return undefined
    })
    const api = await loadApi()
    const user = await api.login('pat', 'secret', 'PARTICIPANT')
    const event = await api.getEvent('12')

    const [booking, ...others] = await api.signUp(user, event, 'pm_card_visa')

    const signup = new URL(fetchMock.mock.calls.find(([url]) => url.includes('/signup'))![0])
    expect(Object.fromEntries(signup.searchParams)).toEqual({ userId: user.id, paymentMethodId: 'pm_card_visa', quantity: '1' })
    expect(others).toEqual([])
    expect(booking).toMatchObject({ id: '5', status: 'CONFIRMED', paymentId: 31, event: { occupiedSpots: 11 } })
    expect((await api.getHeldBookings(user, '12')).map((b) => b.id)).toEqual(['5'])
    expect(await api.listMyBookings(user)).toMatchObject([{ id: '5', status: 'CONFIRMED' }])

    eventExists = false
    expect(await api.listMyBookings(user)).toMatchObject([{ id: '5', status: 'CANCELLED', event: { status: 'CANCELLED' } }])
    expect(await api.getBooking(user, '5')).toMatchObject({ status: 'CANCELLED' })
  })

  it('retries the payment of a PENDING booking', async () => {
    const fetchMock = backend((url) => {
      if (url.pathname === '/events/12/signup') {
        return Response.json([bookingDto({ status: 'PENDING', paymentReference: null, confirmedAt: null, failureReason: 'Card declined' })], { status: 201 })
      }
      if (url.pathname === '/events/12/bookings/5/payment') return Response.json(bookingDto())
      if (url.pathname === '/events/12') return Response.json(EVENT)
      return undefined
    })
    const api = await loadApi()
    const user = await api.login('pat', 'secret', 'PARTICIPANT')

    const [pending] = await api.signUp(user, await api.getEvent('12'), 'pm_card_chargeDeclined')
    expect(pending).toMatchObject({ status: 'PENDING', failureReason: 'Card declined' })

    const paid = await api.retryPayment(pending, 'pm_card_visa')
    expect(paid.status).toBe('CONFIRMED')
    const [retryUrl, retryInit] = fetchMock.mock.calls.find(([url]) => url.includes('/payment?'))!
    expect(new URL(retryUrl).searchParams.get('paymentMethodId')).toBe('pm_card_visa')
    expect(retryInit.method).toBe('POST')
  })

  it('signs up for several tickets, records one booking each, and retries only the unpaid ones', async () => {
    const fetchMock = backend((url) => {
      if (url.pathname === '/events/12/signup') {
        const userId = Number(url.searchParams.get('userId'))
        return Response.json(
          [
            bookingDto({ id: 5, userId }),
            bookingDto({ id: 6, userId, status: 'PENDING', paymentReference: null, confirmedAt: null, failureReason: 'Card declined' }),
            bookingDto({ id: 7, userId }),
          ],
          { status: 201 },
        )
      }
      if (url.pathname === '/events/12/bookings/6/payment') return Response.json(bookingDto({ id: 6, userId: Number(user.id) }))
      if (url.pathname === '/events/12') return Response.json(EVENT)
      return undefined
    })
    const api = await loadApi()
    const user = await api.login('pat', 'secret', 'PARTICIPANT')

    const bookings = await api.signUp(user, await api.getEvent('12'), 'pm_card_visa', 3)

    const signup = new URL(fetchMock.mock.calls.find(([url]) => url.includes('/signup'))![0])
    expect(signup.searchParams.get('quantity')).toBe('3')
    expect(bookings.map((b) => [b.id, b.status])).toEqual([
      ['5', 'CONFIRMED'],
      ['6', 'PENDING'],
      ['7', 'CONFIRMED'],
    ])
    expect((await api.getHeldBookings(user, '12')).map((b) => b.id).sort()).toEqual(['5', '6', '7'])

    const { results, error } = await api.retryPayments(
      bookings.filter((b) => b.status === 'PENDING'),
      'pm_card_visa',
    )
    expect(error).toBeUndefined()
    expect(results).toMatchObject([{ id: '6', status: 'CONFIRMED' }])
    expect(fetchMock.mock.calls.filter(([url]) => url.includes('/payment?'))).toHaveLength(1)
  })

  it('stops retrying payments at the first request that fails and reports what was paid', async () => {
    backend((url) => {
      if (url.pathname === '/events/12/bookings/5/payment') return Response.json(bookingDto({ id: 5 }))
      if (url.pathname === '/events/12/bookings/6/payment') {
        return Response.json({ status: 503, detail: 'The payment service is unavailable, please try again' }, { status: 503 })
      }
      if (url.pathname === '/events/12') return Response.json(EVENT)
      return undefined
    })
    const api = await loadApi()
    await api.login('pat', 'secret', 'PARTICIPANT')
    const event = await api.getEvent('12')
    const pending = (id: string) =>
      ({ id, eventId: '12', userId: '0', status: 'PENDING', amount: 15, createdAt: '', expiresAt: '', event }) as const

    const { results, error } = await api.retryPayments([pending('5'), pending('6'), pending('7')], 'pm_card_visa')

    expect(results.map((b) => b.id)).toEqual(['5'])
    expect(error).toMatchObject({ status: 503, message: 'The payment service is unavailable, please try again' })
  })

  it("retries one ticket per order, which pays the whole order, and settles the order's other tickets with it", async () => {
    const declined = { status: 'PENDING', paymentReference: null, confirmedAt: null, failureReason: 'Card declined', orderId: 'order-1' } as const
    const fetchMock = backend((url) => {
      if (url.pathname === '/events/12/signup') {
        const userId = Number(url.searchParams.get('userId'))
        return Response.json([5, 6, 7].map((id) => bookingDto({ id, userId, ...declined })), { status: 201 })
      }
      if (url.pathname === '/events/12/bookings/5/payment') return Response.json(bookingDto({ id: 5, userId: Number(user.id), orderId: 'order-1' }))
      if (url.pathname === '/events/12') return Response.json(EVENT)
      return undefined
    })
    const api = await loadApi()
    const user = await api.login('pat', 'secret', 'PARTICIPANT')
    const unpaid = await api.signUp(user, await api.getEvent('12'), 'pm_card_chargeDeclined', 3)

    const { results, error } = await api.retryPayments(unpaid, 'pm_card_visa')

    expect(error).toBeUndefined()
    expect(fetchMock.mock.calls.filter(([url]) => url.includes('/payment?'))).toHaveLength(1)  // one charge for 3 tickets
    expect(results.map((b) => [b.id, b.status, b.paymentId])).toEqual([
      ['5', 'CONFIRMED', 31],
      ['6', 'CONFIRMED', 31],
      ['7', 'CONFIRMED', 31],
    ])
    // Recorded too: the event page and My Bookings show all three tickets paid
    expect((await api.getHeldBookings(user, '12')).map((b) => b.status)).toEqual(['CONFIRMED', 'CONFIRMED', 'CONFIRMED'])
  })

  it('opens a multi-ticket signup as one booking: every ticket of its order, from any of them', async () => {
    backend((url) => {
      if (url.pathname === '/events/12/signup') {
        const userId = Number(url.searchParams.get('userId'))
        return Response.json([5, 6, 7].map((id) => bookingDto({ id, userId, orderId: 'order-1' })), { status: 201 })
      }
      if (url.pathname === '/events/12') return Response.json(EVENT)
      return undefined
    })
    const api = await loadApi()
    const user = await api.login('pat', 'secret', 'PARTICIPANT')
    await api.signUp(user, await api.getEvent('12'), 'pm_card_visa', 3)

    expect((await api.getOrder(user, '6')).map((t) => t.id)).toEqual(['5', '6', '7'])
    await expect(api.getOrder(user, '999')).rejects.toThrow('Booking not found')
  })

  it('tries a declined card once per order, not once per ticket', async () => {
    const declined = { status: 'PENDING', paymentReference: null, confirmedAt: null, failureReason: 'Card declined', orderId: 'order-1' } as const
    const fetchMock = backend((url) => {
      if (url.pathname === '/events/12/signup') {
        const userId = Number(url.searchParams.get('userId'))
        return Response.json([5, 6].map((id) => bookingDto({ id, userId, ...declined })), { status: 201 })
      }
      if (url.pathname === '/events/12/bookings/5/payment') {
        return Response.json(bookingDto({ id: 5, userId: Number(user.id), ...declined, failureReason: 'Insufficient funds' }))
      }
      if (url.pathname === '/events/12') return Response.json(EVENT)
      return undefined
    })
    const api = await loadApi()
    const user = await api.login('pat', 'secret', 'PARTICIPANT')
    const unpaid = await api.signUp(user, await api.getEvent('12'), 'pm_card_chargeDeclined', 2)

    const { results } = await api.retryPayments(unpaid, 'pm_card_insufficientFunds')

    expect(fetchMock.mock.calls.filter(([url]) => url.includes('/payment?'))).toHaveLength(1)
    expect(results.map((b) => [b.status, b.failureReason])).toEqual([
      ['PENDING', 'Insufficient funds'],
      ['PENDING', 'Insufficient funds'],
    ])
  })

  it('recovers every ticket of an order paid for on another device', async () => {
    backend((url) => {
      if (url.pathname.startsWith('/payments/user/')) {
        return Response.json([{ paymentId: 20, bookingId: 9, eventId: 12, amount: 45, status: 'PAID', paidAt: '2026-11-04T06:05:01' }])
      }
      if (url.pathname === '/bookings/9') return Response.json({ bookingId: 9, quantity: 3, totalAmount: 45 })
      if (url.pathname === '/events') return Response.json([EVENT])
      if (url.pathname === '/events/12') return Response.json(EVENT)
      return undefined
    })
    const api = await loadApi()
    const user = await api.login('pat', 'secret', 'PARTICIPANT')

    const held = await api.getHeldBookings(user, '12')

    expect(held.map((b) => [b.id, b.amount, b.orderId])).toEqual([
      ['payment-20-1', 15, 'payment-20'],
      ['payment-20-2', 15, 'payment-20'],
      ['payment-20-3', 15, 'payment-20'],
    ])
  })

  it("shows the Event Service's bookings, so every browser shows the same ones", async () => {
    backend((url) => {
      if (url.pathname === '/events/bookings') {
        const userId = Number(url.searchParams.get('userId'))
        return Response.json([
          bookingDto({ id: 8, userId, status: 'PENDING', paymentReference: null, confirmedAt: null }),
          bookingDto({ id: 7, userId, eventId: 99, status: 'CANCELLED' }), // its event was cancelled (deleted)
          bookingDto({ id: 5, userId }),
        ])
      }
      if (url.pathname === '/events') return Response.json([EVENT])
      if (url.pathname === '/events/12') return Response.json(EVENT)
      return undefined
    })
    const api = await loadApi()
    const user = await api.login('pat', 'secret', 'PARTICIPANT')

    // A fresh browser: nothing recorded here, yet it shows every booking, whatever its status
    const mine = await api.listMyBookings(user)
    expect(mine.map((b) => [b.id, b.status]).sort()).toEqual([
      ['5', 'CONFIRMED'],
      ['7', 'CANCELLED'],
      ['8', 'PENDING'],
    ])
    expect(mine.find((b) => b.id === '7')?.event).toMatchObject({ name: 'Event #99', status: 'CANCELLED' })
    expect((await api.getHeldBookings(user, '12')).map((b) => b.id).sort()).toEqual(['5', '8'])
    expect(await api.getBooking(user, '8')).toMatchObject({ status: 'PENDING', event: { name: 'CS302 Party' } })
  })

  it('recovers a ticket paid for on another device from the Payment Service', async () => {
    const payment = (overrides: object) => ({ paymentId: 16, eventId: 12, amount: 15, status: 'PAID', paidAt: '2026-11-04T06:05:01.977419', ...overrides })
    const fetchMock = backend((url) => {
      if (url.pathname.startsWith('/payments/user/')) {
        // Event 99 was cancelled (deleted), and payment 17 was refunded: neither is a ticket
        return Response.json([payment({}), payment({ paymentId: 17, status: 'REFUNDED' }), payment({ paymentId: 18, eventId: 99 })])
      }
      if (url.pathname === '/events') return Response.json([EVENT])
      if (url.pathname === '/events/12') return Response.json(EVENT)
      return undefined
    })
    const api = await loadApi()
    const user = await api.login('pat', 'secret', 'PARTICIPANT')

    const [held, ...others] = await api.getHeldBookings(user, '12')

    expect(others).toEqual([])
    expect(held).toMatchObject({ id: 'payment-16', eventId: '12', status: 'CONFIRMED', amount: 15, paymentId: 16 })
    expect(held?.confirmedAt).toBe('2026-11-04T06:05:01.977Z')
    const [paymentsUrl, paymentsInit] = fetchMock.mock.calls.find(([url]) => url.includes('/payments/user/'))!
    expect(paymentsUrl).toBe(`${BASE}/payments/user/${user.id}`)
    expect(authOf(paymentsInit)).toBe('Bearer access-1')
    expect(await api.listMyBookings(user)).toMatchObject([{ id: 'payment-16', status: 'CONFIRMED' }])
    expect(await api.getBooking(user, 'payment-16')).toMatchObject({ status: 'CONFIRMED', event: { name: 'CS302 Party' } })
  })

  it('tells the user they already booked instead of showing the duplicate-booking 409', async () => {
    backend((url) => {
      if (url.pathname === '/events/12/signup') {
        const detail = `User ${url.searchParams.get('userId')} already has an active booking for event 12`
        return Response.json({ title: 'Conflict', status: 409, detail }, { status: 409 })
      }
      if (url.pathname.startsWith('/payments/user/')) return Response.json([{ paymentId: 16, eventId: 12, amount: 15, status: 'PAID', paidAt: '2026-11-04T06:05:01' }])
      if (url.pathname === '/events') return Response.json([EVENT])
      if (url.pathname === '/events/12') return Response.json(EVENT)
      return undefined
    })
    const api = await loadApi()
    const user = await api.login('pat', 'secret', 'PARTICIPANT')

    await expect(api.signUp(user, await api.getEvent('12'), 'pm_card_visa')).rejects.toMatchObject({
      message: 'You already have a booking for this event. See My Bookings.',
      status: 409,
    })
    expect((await api.getHeldBookings(user, '12')).map((b) => b.id)).toEqual(['payment-16'])
  })

  it("falls back to this browser's bookings when the payments can't be read", async () => {
    backend((url) => (url.pathname.startsWith('/payments/user/') ? new Response(null, { status: 503 }) : undefined))
    const api = await loadApi()
    const user = await api.login('pat', 'secret', 'PARTICIPANT')

    expect(await api.getHeldBookings(user, '12')).toEqual([])
    expect(await api.listMyBookings(user)).toEqual([])
  })

  it("puts names to the services' user ids from the account list, with the Admin's token", async () => {
    const sub = 'a9cae50c-90f1-708f-7c8b-1f4d8618b9a5'
    const fetchMock = backend((url) => (url.pathname === '/user/accounts'
      ? Response.json([{ id: sub, email: 'sherman@smu.edu.sg', fullName: 'Sherman', role: 'PARTICIPANT', phoneNumber: null }])
      : undefined))
    const api = await loadApi()
    await api.login('pat', 'secret', 'PARTICIPANT')

    const accounts = await api.accountsByServiceId()

    expect(accounts.get(214806247)?.fullName).toBe('Sherman') // the Event/Payment Services' id for that sub
    // By path: the account service's base URL can come from VITE_USER_API_URL (e.g. a developer's .env.local)
    const call = fetchMock.mock.calls.find(([url]) => new URL(url).pathname === '/user/accounts')
    expect(call && authOf(call[1])).toBe('Bearer access-1')
  })

  it('shows bare user ids when the accounts cannot be listed, instead of failing the dashboard', async () => {
    backend((url) => (url.pathname === '/user/accounts'
      ? Response.json({ status: 404, message: 'Not Found' }, { status: 404 })
      : undefined))
    const api = await loadApi()
    await api.login('pat', 'secret', 'PARTICIPANT')

    expect((await api.accountsByServiceId()).size).toBe(0)
  })

  it("calls the Payment Service's Admin endpoints through the API Gateway with the Admin's token, never a key", async () => {
    const retried = { paymentId: 25, outcome: 'REFUNDED', paymentStatus: 'REFUNDED', dlqMessagesFound: 1, dlqMessagesRemoved: 1, remainingInDlq: 0 }
    const fetchMock = backend((url, init) => {
      if (url.pathname === '/admin/refunds/dlq/count') return Response.json({ queue: 'payments.refunds.dlq', ready: 1, unacknowledged: 0, total: 1 })
      if (url.pathname === '/admin/refunds/failed') return Response.json({ count: 1, failedRefunds: [{ paymentId: 25, dlqMessages: 1 }] })
      if (url.pathname === '/admin/refunds/25/retry' && init.method === 'POST') return Response.json(retried)
      return undefined
    })
    const api = await loadApi()
    await api.login('pat', 'secret', 'PARTICIPANT')

    expect((await api.getDlqCount()).total).toBe(1)
    expect((await api.listFailedRefunds()).failedRefunds[0].paymentId).toBe(25)
    expect(await api.retryFailedRefund(25)).toMatchObject({ outcome: 'REFUNDED', dlqMessagesRemoved: 1 })

    const adminCalls = fetchMock.mock.calls.filter(([url]) => url.includes('/admin/'))
    expect(adminCalls.map(([url]) => url)).toEqual([
      `${BASE}/admin/refunds/dlq/count`,
      `${BASE}/admin/refunds/failed`,
      `${BASE}/admin/refunds/25/retry`,
    ])
    for (const [, init] of adminCalls) {
      expect(authOf(init)).toBe('Bearer access-1')
      expect(init.headers).not.toHaveProperty('X-Admin-Api-Key')
    }
  })

  it('shows a 403 from the Admin endpoints as not allowed', async () => {
    backend((url) => (url.pathname === '/admin/refunds/failed'
      ? Response.json({ status: 403, message: 'This endpoint requires the ADMIN role' }, { status: 403 })
      : undefined))
    const api = await loadApi()
    await api.login('pat', 'secret', 'PARTICIPANT')

    await expect(api.listFailedRefunds()).rejects.toMatchObject({ status: 403, message: "Your account isn't allowed to do that." })
  })

  it("won't let an organiser manage someone else's event", async () => {
    backend((url) => (url.pathname === '/events/12' ? Response.json({ ...EVENT, organiserId: 999 }) : undefined))
    const api = await loadApi()
    const user = await api.login('pat', 'secret', 'PARTICIPANT')

    await expect(api.getOrganisedEvent(user, '12')).rejects.toThrow("isn't one of your events")
    await expect(api.getEvent('not-a-number')).rejects.toThrow('Event not found')
  })

  it('lets an admin manage any event, and lists every event as theirs to manage', async () => {
    backend((url) => {
      if (url.pathname === '/events/12') return Response.json({ ...EVENT, organiserId: 999 })
      if (url.pathname === '/events') return Response.json([{ ...EVENT, id: 12, organiserId: 999 }, { ...EVENT, id: 13, organiserId: 7 }])
      return undefined
    })
    const api = await loadApi()
    const user = await api.login('pat', 'secret', 'PARTICIPANT')
    const admin = { ...user, role: 'ADMIN' as const }

    await expect(api.getOrganisedEvent(admin, '12')).resolves.toMatchObject({ id: '12' })
    expect((await api.listOrganisedEvents(admin)).map((e) => e.id)).toEqual(['12', '13'])
  })
})
