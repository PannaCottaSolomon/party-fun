import { accessToken, endSession } from '../auth/session'
import { ApiError } from './errors'
import type { AdminRetryResult, DlqCount, FailedRefunds, ServicePayment } from './paymentApi'

/**
 * Calls to the Event Service (Spring Boot, `services/events`, `/events`) through the API Gateway. Every request
 * carries the user's Cognito access token as `Authorization: Bearer ...`: the gateway validates it, and so does the
 * service, which reads the user's role from its `cognito:groups`. A 401 refreshes the token and tries once more.
 */

/** Wire format of the Event Service's `EventDto`. Date-times are UTC local date-times, e.g. "2026-11-20T19:00:00". */
export interface EventDto {
  id: number
  title: string
  description: string | null
  organiserId: number | null
  location: string | null
  startDateTime: string | null
  endDateTime: string | null
  /** Places still available (decremented by each signup). */
  capacity: number
  /** Initial number of places; signups so far = maxCapacity - capacity. */
  maxCapacity: number | null
  minSignUps: number | null
  /** Signup deadline: the event is auto-cancelled if it has fewer than minSignUps by then. */
  expiresAt: string | null
  ticketPrice: number | null
  currency: string | null
  /** "ACTIVE" allows signups; any other value blocks them. */
  status: string
  createdAt: string | null
  updatedAt: string | null
}

/** The body of `POST /events` and `PATCH /events/{id}` (a PATCH only applies the fields it sends). */
export type EventRequest = Partial<Omit<EventDto, 'id' | 'createdAt' | 'updatedAt'>>

/** Wire format of the Event Service's `Booking` (one place at one event). */
export interface BookingDto {
  id: number
  userId: number
  eventId: number
  /** The signup (order) the ticket belongs to: an order's tickets are paid by one charge and settled together. */
  orderId?: string | null
  totalAmount: number | null
  currency: string | null
  status: 'PENDING' | 'CONFIRMED' | 'EXPIRED' | 'CANCELLED'
  createdAt: string
  /** Payment deadline of a PENDING booking (createdAt + 15 minutes). */
  expiresAt: string
  confirmedAt: string | null
  failedAt: string | null
  paymentBookingId: number | null
  /** The Payment Service's paymentId of the successful payment. */
  paymentReference: string | null
  failureReason: string | null
}

export const API_BASE_URL = (import.meta.env.VITE_API_BASE_URL ?? '').replace(/\/+$/, '')

type Query = Record<string, string | number | undefined>

function send(method: string, url: string, token: string, body?: unknown): Promise<Response> {
  const headers: Record<string, string> = { Accept: 'application/json', Authorization: `Bearer ${token}` }
  if (body !== undefined) headers['Content-Type'] = 'application/json'
  return fetch(url, { method, headers, body: body === undefined ? undefined : JSON.stringify(body) })
}

/** The message of an error answer: the service's ProblemDetail `detail`, or the gateway's `message`. */
function errorMessage(status: number, json: unknown): string {
  const { detail, message } = (json ?? {}) as { detail?: unknown; message?: unknown }
  if (typeof detail === 'string' && detail) return detail
  if (status === 403) return "Your account isn't allowed to do that."
  if (typeof message === 'string' && message) return message
  if (status === 503) return 'The Event Service is busy or unavailable. Please try again.'
  return `The Event Service answered ${status}.`
}

async function call<T>(method: 'GET' | 'POST' | 'PATCH' | 'DELETE', path: string, options: { query?: Query; body?: unknown } = {}): Promise<T> {
  if (!API_BASE_URL) throw new ApiError('The API is not configured: set VITE_API_BASE_URL to the API Gateway URL.')
  const params = new URLSearchParams()
  for (const [key, value] of Object.entries(options.query ?? {})) {
    if (value !== undefined) params.set(key, String(value))
  }
  const query = params.toString()
  const url = `${API_BASE_URL}${path}${query ? `?${query}` : ''}`

  let response: Response
  try {
    response = await send(method, url, await accessToken(), options.body)
    if (response.status === 401) response = await send(method, url, await accessToken(true), options.body)
  } catch (e) {
    if (e instanceof ApiError) throw e
    throw new ApiError("Can't reach the Event Service. Check your connection and try again.")
  }
  if (response.status === 401) {
    endSession()
    throw new ApiError('Your session has expired. Please log in again.', 401)
  }

  const text = await response.text()
  let json: unknown
  try {
    json = text ? JSON.parse(text) : null
  } catch {
    json = null
  }
  if (!response.ok) throw new ApiError(errorMessage(response.status, json), response.status)
  return json as T
}

/** GET /events: every event. */
export function listEvents(): Promise<EventDto[]> {
  return call('GET', '/events')
}

/** GET /events/{id} (404 if it doesn't exist, e.g. it was cancelled: cancelling deletes the event). */
export function getEvent(id: number): Promise<EventDto> {
  return call('GET', `/events/${id}`)
}

/** POST /events (organiser, admin): status defaults to ACTIVE, maxCapacity to capacity, currency to SGD. */
export function createEvent(event: EventRequest): Promise<EventDto> {
  return call('POST', '/events', { body: event })
}

/** PATCH /events/{id} (organiser, admin): only the fields sent are changed. */
export function updateEvent(id: number, changes: EventRequest): Promise<EventDto> {
  return call('PATCH', `/events/${id}`, { body: changes })
}

/**
 * POST /events/{id}/cancel (organiser, admin): deletes the event, cancels its bookings, and asks the Payment Service
 * to refund the paid ones. Returns the event as it was.
 */
export function cancelEvent(id: number): Promise<EventDto> {
  return call('POST', `/events/${id}/cancel`)
}

/** DELETE /events/{id} (organiser, admin): deletes the event and cancels its bookings WITHOUT refunds. */
export function deleteEvent(id: number): Promise<void> {
  return call('DELETE', `/events/${id}`)
}

/**
 * POST /events/{id}/signup: takes `quantity` places (default 1, at most 10; all or nothing, 409 if fewer are left)
 * and charges `paymentMethodId` (a Stripe PaymentMethod, required for paid events) once for all of them. Returns one
 * booking per ticket, all in one order: all CONFIRMED if paid (or free), otherwise all PENDING with `failureReason`,
 * their places held for 15 minutes.
 */
export function signUp(eventId: number, userId: number, paymentMethodId?: string, quantity?: number): Promise<BookingDto[]> {
  return call('POST', `/events/${eventId}/signup`, { query: { userId, paymentMethodId, quantity } })
}

/** GET /events/bookings?userId=: every booking of the user (one per ticket), in every status, newest first. */
export function listBookings(userId: number): Promise<BookingDto[]> {
  return call('GET', '/events/bookings', { query: { userId } })
}

/**
 * POST /events/{id}/bookings/{bookingId}/payment: retries the payment of a PENDING booking before it expires. The
 * charge pays every unpaid ticket of the booking's order; a booking already CONFIRMED comes back as it is (no charge).
 */
export function payForBooking(eventId: number, bookingId: number, paymentMethodId: string): Promise<BookingDto> {
  return call('POST', `/events/${eventId}/bookings/${bookingId}/payment`, { query: { paymentMethodId } })
}

/** The Payment Service's booking behind a payment: one per order, with its number of tickets. */
export interface PaymentBooking {
  bookingId: number
  quantity: number
}

/** GET /bookings/{bookingId}: the Payment Service's booking (same gateway and token as listUserPayments). */
export function getPaymentBooking(bookingId: number): Promise<PaymentBooking> {
  return call('GET', `/bookings/${bookingId}`)
}

/**
 * GET /payments/user/{userId}: the user's payments. The Payment Service's route on the same API Gateway, which needs
 * the access token, so it goes through this client. Used to find tickets bought on another device or browser.
 */
export function listUserPayments(userId: number): Promise<ServicePayment[]> {
  return call('GET', `/payments/user/${userId}`)
}

// ---- The Payment Service's Admin endpoints (/admin/*), through the same API Gateway with the Admin's access token:
// the Payment Service checks it is a Cognito user in the admin group. No key is ever held by the browser.

/** Admin: how many failed refunds wait in the refund dead-letter queue. */
export function getDlqCount(): Promise<DlqCount> {
  return call('GET', '/admin/refunds/dlq/count')
}

/** Admin: the failed refunds (REFUND_FAILED payments), oldest first, each with its DLQ message count. */
export function listFailedRefunds(): Promise<FailedRefunds> {
  return call('GET', '/admin/refunds/failed')
}

/** Admin: retries one failed refund now; its DLQ message is removed only if the refund succeeds. */
export function retryFailedRefund(paymentId: number): Promise<AdminRetryResult> {
  return call('POST', `/admin/refunds/${paymentId}/retry`)
}
