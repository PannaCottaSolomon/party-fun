import { canManageEvent, pickRole } from '../auth/access'
import * as cognito from '../auth/cognito'
import { accessToken, currentSession, endSession, startSession } from '../auth/session'
import { bookingView, heldBookings, validateEvent } from '../domain/eventRules'
import { ticketsOfOrder } from '../domain/orders'
import type { Booking, BookingView, EventInput, PartyEvent, Role, User } from '../types'
import { replaceBookings, storeBooking, storedBookings } from './bookingStore'
import { ApiError } from './errors'
import { toBooking, toCreateRequest, toPartyEvent, toUpdateRequest, utcToInstant } from './eventMapping'
import * as events from './eventsApi'
import * as payments from './paymentApi'
import * as users from './userApi'
import type { AdminRetryResult, DlqCount, FailedRefund, FailedRefunds, ServicePayment } from './paymentApi'
import { toServiceId } from './serviceIds'

/**
 * Everything the views need, as async functions:
 *   login / register      → Amazon Cognito (the user pool behind the API Gateway's JWT authorizer)
 *   profile + role        → Authentication Service (POST /user/me, once, after sign-up)
 *   events, signups       → Event Service, through the API Gateway, with the Cognito access token
 *   payment status, admin → Payment Service (Stripe sandbox)
 */

export { ApiError }
export type { AdminRetryResult, DlqCount, FailedRefund, FailedRefunds, ServicePayment }
export type { Profile } from './userApi'

// ---------------------------------------------------------------- auth (Cognito)

async function signIn(identifier: string, password: string, role: Role, fullName?: string): Promise<User> {
  let tokens = await cognito.signIn(identifier.trim(), password)
  let claims = cognito.decodeJwt<cognito.IdTokenClaims>(tokens.idToken)
  // A new account is in no role group yet. Its profile is created now, which puts it in the chosen role's group
  // (the Authentication Service decides; ADMIN can't be chosen), then fresh tokens carry the new group.
  if (role !== 'ADMIN' && cognito.rolesFromGroups(claims['cognito:groups']).length === 0) {
    await users.createMyProfile(tokens.accessToken, { fullName: fullName?.trim() || cognito.displayName(claims), role })
    tokens = await cognito.refresh(tokens.refreshToken)
    claims = cognito.decodeJwt<cognito.IdTokenClaims>(tokens.idToken)
  }
  const user: User = {
    id: String(toServiceId(claims.sub)),
    username: cognito.displayName(claims),
    email: claims.email ?? '',
    role: pickRole(cognito.rolesFromGroups(claims['cognito:groups']), role),
  }
  startSession(user, tokens)
  return user
}

/**
 * Participant/organiser login, with a username or email. The account's Cognito group must match the role chosen on
 * the Login page. An account with no group yet (signed up, profile not created) gets the chosen role here, with
 * `fullName` as its name (else its username). Admins use the Admin login page (adminLogin).
 */
export function login(identifier: string, password: string, role: Role, fullName?: string): Promise<User> {
  return signIn(identifier, password, role, fullName)
}

/** Admin login (its own page). Only accounts in the Cognito `admin` group are accepted. */
export function adminLogin(identifier: string, password: string): Promise<User> {
  return signIn(identifier, password, 'ADMIN')
}

export function logout(): void {
  const refreshToken = currentSession()?.refreshToken
  endSession()
  if (refreshToken) void cognito.revoke(refreshToken)
}

/** Creates a Cognito account; Cognito emails a code to confirm it (confirmRegistration). */
export function register(input: { username: string; email: string; password: string; role: Role }) {
  if (input.role === 'ADMIN') return Promise.reject(new ApiError('Admin accounts cannot be registered.'))
  return cognito.signUp({ ...input, username: input.username.trim(), email: input.email.trim() })
}

export function confirmRegistration(username: string, code: string, role: Role): Promise<void> {
  return cognito.confirmSignUp(username.trim(), code.trim(), role)
}

/** Whether a new account must enter the emailed code (the Authentication Service's EMAIL_VERIFICATION_ENABLED). */
export function emailVerificationEnabled(): Promise<boolean> {
  return users.emailVerificationEnabled()
}

/** Confirms a new account without the emailed code, while email verification is switched off. */
export function confirmWithoutCode(username: string): Promise<void> {
  return users.confirmWithoutCode(username.trim())
}

/**
 * Finishes a confirmed sign-up without logging the user in: signs in once, only to create the profile with the chosen
 * role (the Authentication Service adds the Cognito group), then signs out again. The user then logs in on the Login
 * page and their tokens carry the new group.
 */
export async function completeRegistration(input: { username: string; password: string; role: Role; fullName: string }): Promise<void> {
  if (input.role === 'ADMIN') throw new ApiError('Admin accounts cannot be registered.')
  const tokens = await cognito.signIn(input.username.trim(), input.password)
  try {
    await users.createMyProfile(tokens.accessToken, { fullName: input.fullName.trim(), role: input.role })
  } finally {
    void cognito.revoke(tokens.refreshToken)
  }
}

// ---------------------------------------------------------------- events (Event Service)

function viewer(): User | null {
  return currentSession()?.user ?? null
}

function byDate(a: PartyEvent, b: PartyEvent): number {
  return a.dateTime.localeCompare(b.dateTime)
}

/** The Event Service's numeric id for a route's event id. */
function serviceEventId(eventId: string): number {
  if (!/^\d+$/.test(eventId)) throw new ApiError('Event not found.', 404)
  return Number(eventId)
}

export async function listEvents(): Promise<PartyEvent[]> {
  const user = viewer()
  return (await events.listEvents()).map((dto) => toPartyEvent(dto, user)).sort(byDate)
}

export async function getEvent(eventId: string): Promise<PartyEvent> {
  try {
    return toPartyEvent(await events.getEvent(serviceEventId(eventId)), viewer())
  } catch (e) {
    throw e instanceof ApiError && e.status === 404 ? new ApiError('Event not found. It may have been cancelled.', 404) : e
  }
}

/** The event, or undefined if the Event Service no longer has it (cancelled events are deleted). */
async function findEvent(eventId: string): Promise<PartyEvent | undefined> {
  try {
    return await getEvent(eventId)
  } catch (e) {
    if (e instanceof ApiError && e.status === 404) return undefined
    throw e
  }
}

/** The events this user manages: an organiser's own events, or every event for an Admin. */
export async function listOrganisedEvents(organiser: User): Promise<PartyEvent[]> {
  return (await listEvents()).filter((e) => canManageEvent(organiser, e))
}

/**
 * An event this user may manage (their own, or any event for an Admin), or an error otherwise. (The Event Service
 * only checks the organiser/admin role, not which organiser owns the event, so this check is the frontend's.)
 */
export async function getOrganisedEvent(organiser: User, eventId: string): Promise<PartyEvent> {
  const event = await findEvent(eventId)
  if (!event || !canManageEvent(organiser, event)) throw new ApiError("Event not found, or it isn't one of your events.")
  return event
}

function checkValid(input: EventInput, existing?: PartyEvent): void {
  const errors = validateEvent(input, new Date(), existing)
  const first = Object.values(errors)[0]
  if (first) throw new ApiError(first)
}

/** New events are open for signups (ACTIVE), shown as PENDING. */
export async function createEvent(organiser: User, input: EventInput): Promise<PartyEvent> {
  checkValid(input)
  return toPartyEvent(await events.createEvent(toCreateRequest(input, organiser)), organiser)
}

/** Sends only the fields that changed. */
export async function updateEvent(organiser: User, eventId: string, input: EventInput): Promise<PartyEvent> {
  const existing = await getOrganisedEvent(organiser, eventId)
  if (existing.status === 'CANCELLED') throw new ApiError('Cancelled events can no longer be edited.')
  checkValid(input, existing)
  const changes = toUpdateRequest(input, existing)
  if (Object.keys(changes).length === 0) return existing
  return toPartyEvent(await events.updateEvent(serviceEventId(eventId), changes), organiser)
}

/**
 * Organiser cancels: the Event Service deletes the event, cancels its bookings and asks the Payment Service to refund
 * every paid one. Refunds happen there, after this returns; failed ones go to its dead-letter queue for an Admin.
 */
export async function cancelEvent(organiser: User, eventId: string): Promise<PartyEvent> {
  await getOrganisedEvent(organiser, eventId)
  const cancelled = toPartyEvent(await events.cancelEvent(serviceEventId(eventId)), organiser)
  return { ...cancelled, status: 'CANCELLED', cancelReason: 'ORGANISER' }
}

// ---------------------------------------------------------------- signups and payment (Event Service)

/** Records the bookings the Event Service returned (all for one event), with that event as it is now. */
async function record(dtos: events.BookingDto[], fallback: PartyEvent): Promise<BookingView[]> {
  const bookings = dtos.map(toBooking)
  if (bookings.length === 0) return []
  const event = (await findEvent(bookings[0].eventId)) ?? fallback
  const now = new Date()
  return bookings.map((booking) => {
    storeBooking(booking, event)
    return bookingView(booking, event, event, now)
  })
}

/**
 * Signs the user up for `quantity` tickets: one place and one booking each, reserved all or nothing, all in one order.
 * For a paid event `paymentMethodId` is the Stripe PaymentMethod from the card form, and the Event Service charges it
 * once for every ticket right away (through the Payment Service). The bookings come back all CONFIRMED when paid (or
 * free), or all PENDING with `failureReason` and their places held for 15 minutes so the payment can be retried.
 */
export async function signUp(user: User, event: PartyEvent, paymentMethodId?: string, quantity = 1): Promise<BookingView[]> {
  let dtos: events.BookingDto[]
  try {
    dtos = await events.signUp(serviceEventId(event.id), Number(user.id), paymentMethodId, quantity)
  } catch (error) {
    // One signup per user per event: the user booked this one on another device or browser, which this one can't
    // know. Its tickets are recovered (if paid), and the user is told instead of shown the service's message.
    if (error instanceof ApiError && error.status === 409 && /already has an active booking/.test(error.message)) {
      await recoverPaidBookings(user)
      throw new ApiError('You already have a booking for this event. See My Bookings.', 409)
    }
    throw error
  }
  return record(dtos, event)
}

/** Retries the payment of a PENDING booking with another (or the same) card, before its payment window closes. */
export async function retryPayment(booking: BookingView, paymentMethodId: string): Promise<BookingView> {
  const dto = await events.payForBooking(serviceEventId(booking.eventId), Number(booking.id), paymentMethodId)
  return (await record([dto], booking.event))[0]
}

/**
 * Retries the payment of several PENDING bookings with one card. One charge pays every unpaid ticket of an order, so
 * one ticket per order is retried and the order's other tickets take its outcome (the Event Service settles an order
 * as a whole): retrying each of them would only charge a declined card again. Stops at the first request that fails
 * outright (e.g. the Payment Service is down) and returns the bookings updated so far alongside that error.
 */
export async function retryPayments(
  bookings: BookingView[],
  paymentMethodId: string,
): Promise<{ results: BookingView[]; error?: unknown }> {
  const orders = new Map<string, BookingView[]>()
  for (const booking of bookings) {
    const key = booking.orderId ?? `booking-${booking.id}`
    orders.set(key, [...(orders.get(key) ?? []), booking])
  }
  const results: BookingView[] = []
  for (const [first, ...others] of orders.values()) {
    let paid: BookingView
    try {
      paid = await retryPayment(first, paymentMethodId)
    } catch (error) {
      return { results, error }
    }
    results.push(paid, ...others.map((ticket) => sameOutcome(ticket, paid)))
  }
  return { results }
}

/** Another ticket of `paid`'s order, after that order's payment: it is settled with it (recorded in this browser). */
function sameOutcome(ticket: BookingView, paid: BookingView): BookingView {
  const { event, ...booking } = ticket
  const settled: Booking = {
    ...booking,
    status: paid.status,
    confirmedAt: paid.confirmedAt,
    failureReason: paid.failureReason,
    paymentId: paid.paymentId,
  }
  storeBooking(settled, event)
  return { ...settled, event }
}

/**
 * Records the tickets the user paid for on another device or browser. The Event Service can't list a user's bookings
 * yet, so this browser only knows the ones made here; the Payment Service can list the user's payments, and a PAID one
 * for an event that still exists paid for an order of confirmed tickets (as many as its payment-side booking says).
 * They are recorded under the payment's id ("payment-16", or "payment-16-1", "payment-16-2", ... for several), since
 * the Event Service's booking ids aren't known. Best effort: if the payments can't be read, only this browser's
 * bookings count.
 */
async function recoverPaidBookings(user: User): Promise<void> {
  let paid: ServicePayment[]
  try {
    paid = (await events.listUserPayments(Number(user.id))).filter((payment) => payment.status === 'PAID')
  } catch {
    return
  }
  const known = storedBookings(user.id).map((s) => s.booking)
  const missing = paid.filter(
    (payment) =>
      // Skip payments already recorded, and events this browser already holds tickets for (the payment is theirs)
      !known.some((b) => b.paymentId === payment.paymentId) &&
      heldBookings(known, String(payment.eventId), user.id).length === 0,
  )
  if (missing.length === 0) return
  const live = new Map((await listEvents()).map((e) => [e.id, e]))
  for (const payment of missing) {
    const event = live.get(String(payment.eventId))
    if (!event) continue // cancelled (deleted): Payment refunds it
    const paidAt = utcToInstant(payment.paidAt ?? payment.createdAt ?? new Date().toISOString())
    const tickets = await ticketsPaidBy(payment)
    const order = `payment-${payment.paymentId}`
    for (let i = 1; i <= tickets; i++) {
      const booking: Booking = {
        id: tickets === 1 ? order : `${order}-${i}`,
        eventId: event.id,
        userId: user.id,
        orderId: order,
        status: 'CONFIRMED',
        amount: Math.round((Number(payment.amount) / tickets) * 100) / 100,
        createdAt: paidAt,
        expiresAt: paidAt,
        confirmedAt: paidAt,
        paymentId: payment.paymentId,
      }
      storeBooking(booking, event)
    }
  }
}

/** How many tickets a payment paid for: its payment-side booking's quantity (one order), or 1 if unknown. */
async function ticketsPaidBy(payment: ServicePayment): Promise<number> {
  try {
    const { quantity } = await events.getPaymentBooking(payment.bookingId)
    return Number.isInteger(quantity) && quantity > 0 ? quantity : 1
  } catch {
    return 1
  }
}

/** Stands in for an event the Event Service no longer has (cancelling deletes it) and this browser never saw. */
function goneEvent(eventId: string): PartyEvent {
  return {
    id: eventId,
    name: `Event #${eventId}`,
    description: '',
    dateTime: '',
    location: '',
    ticketPrice: 0,
    maxCapacity: 0,
    minTickets: 0,
    hypeTickets: 0,
    occupiedSpots: 0,
    hypeDeadline: '',
    status: 'CANCELLED',
    organiserId: '',
    organiserName: '–',
  }
}

/**
 * Makes this browser's copy of the user's bookings the Event Service's list (GET /events/bookings), so every browser
 * and device shows the same bookings. Each keeps an event snapshot: the live event, else the one this browser saved
 * earlier, else a placeholder (the event was cancelled before this browser saw it). Returns false if the list
 * couldn't be read (e.g. an older Event Service without the endpoint); this browser's copy is then left as it was.
 */
async function syncBookings(user: User): Promise<boolean> {
  let dtos: events.BookingDto[]
  let live: Map<string, PartyEvent>
  try {
    ;[dtos, live] = await Promise.all([
      events.listBookings(Number(user.id)),
      listEvents().then((list) => new Map(list.map((e) => [e.id, e]))),
    ])
  } catch {
    return false
  }
  const snapshots = new Map(storedBookings(user.id).map((s) => [s.booking.eventId, s.event]))
  replaceBookings(
    user.id,
    dtos.map(toBooking).map((booking) => ({
      booking,
      event: live.get(booking.eventId) ?? snapshots.get(booking.eventId) ?? goneEvent(booking.eventId),
    })),
  )
  return true
}

/**
 * Brings this browser's bookings up to date: from the Event Service's list, or, if it can't be read, by recovering
 * tickets paid for elsewhere from the Payment Service (best effort).
 */
async function refreshBookings(user: User): Promise<void> {
  if (!(await syncBookings(user))) await recoverPaidBookings(user)
}

/** The user's bookings (tickets) for this event that still hold a place, oldest first, as the Event Service has them. */
export async function getHeldBookings(user: User, eventId: string): Promise<Booking[]> {
  await refreshBookings(user)
  return heldBookings(storedBookings(user.id).map((s) => s.booking), eventId, user.id)
}

/** The user's bookings, each with its event as the Event Service has it now. */
export async function listMyBookings(user: User): Promise<BookingView[]> {
  await refreshBookings(user)
  const stored = storedBookings(user.id)
  if (stored.length === 0) return []
  const live = new Map((await listEvents()).map((e) => [e.id, e]))
  const now = new Date()
  return stored
    .map(({ booking, event }) => bookingView(booking, live.get(booking.eventId), event, now))
    .sort((a, b) => a.event.dateTime.localeCompare(b.event.dateTime))
}

export async function getBooking(user: User, bookingId: string): Promise<BookingView> {
  await refreshBookings(user) // its status may have changed in another browser (e.g. paid there)
  const stored = storedBookings(user.id).find((s) => s.booking.id === bookingId)
  if (!stored) throw new ApiError('Booking not found.')
  return bookingView(stored.booking, await findEvent(stored.booking.eventId), stored.event, new Date())
}

/**
 * Every ticket of the order `bookingId` belongs to (itself included), sorted by id, each with its event as the Event
 * Service has it now. A single-ticket booking is an order of one.
 */
export async function getOrder(user: User, bookingId: string): Promise<BookingView[]> {
  const stored = storedBookings(user.id)
  const found = stored.find((s) => s.booking.id === bookingId)
  if (!found) throw new ApiError('Booking not found.')
  const live = await findEvent(found.booking.eventId)
  const now = new Date()
  return ticketsOfOrder(found.booking, stored.map((s) => s.booking)).map((booking) => {
    const snapshot = stored.find((s) => s.booking.id === booking.id)!.event
    return bookingView(booking, live, snapshot, now)
  })
}

// ---------------------------------------------------------------- payments (Payment Service)

/** Live payment from the Payment Service (e.g. whether a cancelled event's payment was refunded yet). */
export function getPayment(paymentId: number): Promise<ServicePayment> {
  return payments.getPayment(paymentId)
}

// The Admin calls go to the Payment Service's /admin/* through the API Gateway with the Admin's access token.

/** Admin: how many failed refunds wait in the Payment Service's refund dead-letter queue. */
export function getDlqCount(): Promise<DlqCount> {
  return events.getDlqCount()
}

/** Admin: the failed refunds, each with its dead-letter queue message count. */
export function listFailedRefunds(): Promise<FailedRefunds> {
  return events.listFailedRefunds()
}

/**
 * Admin: every account's profile, keyed by the numeric user id the Event and Payment Services store (the hash of the
 * account id), so their `userId`s can be shown as people. Best effort: if the accounts can't be listed (e.g. the
 * Authentication Service is older, or unreachable), the map is empty and callers show the bare id.
 */
export async function accountsByServiceId(): Promise<Map<number, users.Profile>> {
  try {
    const accounts = await users.listAccounts(await accessToken())
    return new Map(accounts.map((account) => [toServiceId(account.id), account]))
  } catch {
    return new Map()
  }
}

/** Admin: retries one failed refund now (synchronously); on success its dead-letter queue message is removed. */
export function retryFailedRefund(paymentId: number): Promise<AdminRetryResult> {
  return events.retryFailedRefund(paymentId)
}
