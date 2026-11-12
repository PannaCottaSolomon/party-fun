import { afterEach, describe, expect, it, vi } from 'vitest'
import { ApiError } from './errors'
import * as payments from './paymentApi'
import { toServiceId } from './serviceIds'

function respondWith(status: number, body: unknown) {
  const fetchMock = vi.fn().mockResolvedValue(new Response(JSON.stringify(body), { status }))
  vi.stubGlobal('fetch', fetchMock)
  return fetchMock
}

function headersOf(fetchMock: ReturnType<typeof vi.fn>): Record<string, string> {
  return (fetchMock.mock.calls[0][1] as RequestInit).headers as Record<string, string>
}

afterEach(() => vi.unstubAllGlobals())

describe('toServiceId', () => {
  it('maps Cognito subs to stable, positive 31-bit integers', () => {
    const ids = [
      '9a1b2c3d-0000-4000-8000-000000000001',
      '9a1b2c3d-0000-4000-8000-000000000002',
      'f3e2d1c0-1111-4222-8333-444455556666',
      'u-participant',
    ]
    const mapped = ids.map(toServiceId)

    expect(ids.map(toServiceId)).toEqual(mapped)
    expect(new Set(mapped).size).toBe(ids.length)
    for (const n of mapped) {
      expect(Number.isInteger(n)).toBe(true)
      expect(n).toBeGreaterThan(0)
      expect(n).toBeLessThanOrEqual(2 ** 31 - 1)
    }
  })
})

describe('Payment Service client', () => {
  it("reads a payment's live status, with no key from the browser", async () => {
    const payment = respondWith(200, { paymentId: 4, status: 'REFUNDED' })
    expect((await payments.getPayment(4)).status).toBe('REFUNDED')
    expect(payment.mock.calls[0][0]).toMatch(/\/payments\/4$/)
    expect(headersOf(payment)).not.toHaveProperty('X-Service-Api-Key')
    expect(headersOf(payment)).not.toHaveProperty('X-Admin-Api-Key')
  })

  it("turns the service's JSON error into an ApiError with its message and status", async () => {
    respondWith(404, { status: 404, message: 'Payment 3 not found' })

    const error = await payments.getPayment(3).catch((e: unknown) => e)

    expect(error).toBeInstanceOf(ApiError)
    expect(error).toMatchObject({ message: 'Payment 3 not found', status: 404 })
  })

  it('explains when the Payment Service cannot be reached', async () => {
    vi.stubGlobal('fetch', vi.fn().mockRejectedValue(new TypeError('Failed to fetch')))

    await expect(payments.getPayment(1)).rejects.toThrow(/Can't reach the Payment Service/)
  })
})
