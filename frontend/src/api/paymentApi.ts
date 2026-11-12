import { ApiError } from './errors'

/**
 * Calls to the real Payment Service (Spring Boot, `services/payment`), which talks to the Stripe **sandbox**: no real
 * money moves. Ticket payments go through the Event Service (its signup charges the card via the Payment Service), and
 * so do the refunds when an event is cancelled; the browser only reads a payment's live status here.
 *
 * The Admin dashboard calls need a key the browser must never hold, so they go to this app's own server instead (same
 * origin, under `/api/...`): nginx in Docker, or the Vite dev server. It forwards exactly those calls to the Payment
 * Service and adds the key there (`X-Admin-Api-Key`). When the Admin calls can send `Authorization: Bearer <token>`
 * instead, that forwarding goes.
 */

export type ServicePaymentStatus = 'PENDING' | 'PAID' | 'FAILED' | 'REFUND_PENDING' | 'REFUNDED' | 'REFUND_FAILED'

/** Payment's `PaymentResponse`. */
export interface ServicePayment {
  paymentId: number
  bookingId: number
  eventBookingId: string | null
  userId: number
  eventId: number
  amount: number
  currency: string
  status: ServicePaymentStatus
  stripePaymentIntentId: string | null
  stripeRefundId: string | null
  paymentMethod: string | null
  paidAt: string | null
  refundedAt: string | null
  createdAt: string | null
  updatedAt: string | null
  /** Only in the response that created a payment still waiting for the card (Stripe.js confirms it). */
  clientSecret?: string | null
  failureReason?: string | null
}

export type RefundOutcome = 'REFUNDED' | 'ALREADY_REFUNDED' | 'PENDING' | 'FAILED' | 'SKIPPED'

/** Payment's `DlqCountResponse`: the messages waiting in the refund dead-letter queue (payments.refunds.dlq). */
export interface DlqCount {
  queue: string
  /** Failed refunds waiting for an Admin. */
  ready: number
  /** Messages an Admin recovery is handling right now; null when RabbitMQ's management API wasn't reachable. */
  unacknowledged: number | null
  total: number
  source: 'management-api' | 'amqp'
}

/** One failed refund on the Admin dashboard (Payment's `FailedRefundView`). */
export interface FailedRefund {
  paymentId: number
  bookingId: number
  eventId: number
  userId: number
  amount: number
  amountCents: number
  currency: string
  status: ServicePaymentStatus
  failureReason: string | null
  refundAttempts: number
  failedAt: string | null
  stripePaymentIntentId: string | null
  stripeRefundId: string | null
  /** Its messages in the DLQ. 0 means the failure is only recorded in MySQL; it can be retried all the same. */
  dlqMessages: number
}

/** Payment's `FailedRefundsResponse`. */
export interface FailedRefunds {
  count: number
  dlq: DlqCount
  failedRefunds: FailedRefund[]
  /** Payment ids of DLQ messages whose payment is no longer REFUND_FAILED (stale; a retry removes them). */
  unmatchedDlqMessages: number[]
}

/** Payment's `AdminRetryResponse`: what retrying one failed refund did (it runs synchronously). */
export interface AdminRetryResult {
  paymentId: number
  outcome: Exclude<RefundOutcome, 'SKIPPED'>
  paymentStatus: ServicePaymentStatus
  stripeRefundId: string | null
  dlqMessagesFound: number
  /** DLQ messages acknowledged and removed: the payment's message(s) on success, 0 when it still failed. */
  dlqMessagesRemoved: number
  remainingInDlq: number
  message: string | null
}

export const PAYMENT_API_URL = (import.meta.env.VITE_PAYMENT_API_URL || 'http://localhost:8080').replace(/\/+$/, '')

// The Admin endpoints (/admin/*) need the Admin's access token, so they go through the API Gateway client
// (eventsApi.ts), like every other call that needs a login.

async function call<T>(method: 'GET' | 'POST', path: string, options: { body?: unknown } = {}): Promise<T> {
  const headers: Record<string, string> = {}
  if (options.body !== undefined) headers['Content-Type'] = 'application/json'

  let response: Response
  try {
    response = await fetch(`${PAYMENT_API_URL}${path}`, {
      method,
      headers,
      body: options.body === undefined ? undefined : JSON.stringify(options.body),
    })
  } catch {
    throw new ApiError(`Can't reach the Payment Service at ${PAYMENT_API_URL}. Is it running?`)
  }

  const text = await response.text()
  let json: unknown
  try {
    json = text ? JSON.parse(text) : null
  } catch {
    json = null
  }
  if (!response.ok) {
    const message = (json as { message?: unknown } | null)?.message
    throw new ApiError(
      typeof message === 'string' && message ? message : `Payment Service answered ${response.status}.`,
      response.status,
    )
  }
  return json as T
}

export function getStripeConfig(): Promise<{ stripePublishableKey: string | null }> {
  return call('GET', '/payments/config')
}

export function getPayment(paymentId: number): Promise<ServicePayment> {
  return call('GET', `/payments/${paymentId}`)
}
