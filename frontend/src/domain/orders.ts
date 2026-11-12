import type { Booking, BookingStatus } from '../types'

/**
 * A signup for several tickets creates one booking per ticket, all in one order paid by one charge. These helpers put
 * those bookings back together so the user sees one booking with N tickets, not N bookings.
 *
 * The tickets of one signup share an order id; bookings without one (made before orders existed) are grouped by event
 * and createdAt, which the Event Service also gives every booking of one signup.
 */
export function orderKey(booking: Booking): string {
  return booking.orderId ?? `${booking.eventId}|${booking.createdAt}`
}

/** Numeric-aware: "5" before "12", and "payment-16-2" before "payment-16-10" (tickets recovered from a payment). */
function byTicketId(a: Booking, b: Booking): number {
  return a.id.localeCompare(b.id, undefined, { numeric: true })
}

/** The bookings grouped into orders, keeping the order in which each order first appears; tickets sorted by id. */
export function groupOrders<T extends Booking>(bookings: T[]): T[][] {
  const orders = new Map<string, T[]>()
  for (const booking of bookings) {
    const key = orderKey(booking)
    orders.set(key, [...(orders.get(key) ?? []), booking])
  }
  return [...orders.values()].map((tickets) => [...tickets].sort(byTicketId))
}

/** The tickets of `booking`'s order (itself included), sorted by id. */
export function ticketsOfOrder<T extends Booking>(booking: T, all: T[]): T[] {
  const key = orderKey(booking)
  return all.filter((b) => orderKey(b) === key).sort(byTicketId)
}

/**
 * One status for the whole order. Its tickets are paid and confirmed together, so they normally agree; if they don't
 * (e.g. one was recorded before the others were settled), the one the user must act on wins: PENDING (still to pay),
 * then CONFIRMED, EXPIRED, CANCELLED.
 */
export function orderStatus(tickets: Booking[]): BookingStatus {
  const precedence: BookingStatus[] = ['PENDING', 'CONFIRMED', 'EXPIRED', 'CANCELLED']
  return precedence.find((status) => tickets.some((t) => t.status === status)) ?? tickets[0].status
}

/** The order's total in SGD: each booking carries its own ticket's price. Rounded to cents. */
export function orderTotal(tickets: Booking[]): number {
  return Math.round(tickets.reduce((sum, t) => sum + t.amount, 0) * 100) / 100
}

/** The Payment Service ids of the order's payments, each once (normally one: one charge pays the whole order). */
export function orderPaymentIds(tickets: Booking[]): number[] {
  return [...new Set(tickets.map((t) => t.paymentId).filter((id): id is number => id !== undefined))]
}
