import { formatTime, plural } from '../format'
import type { Booking } from '../types'

/** What to tell the user when the Event Service kept the booking PENDING: the place is held, but it isn't paid. */
export function unpaidMessage(booking: Pick<Booking, 'expiresAt' | 'failureReason'>): string {
  const held = `Your place is held until ${formatTime(booking.expiresAt)}`
  return booking.failureReason
    ? `The payment didn't go through: ${booking.failureReason}. ${held}: try again or use another card.`
    : `The payment hasn't completed yet. ${held}: try again in a moment.`
}

/**
 * The same for a multi-ticket signup where `unpaid` of the `total` tickets are still PENDING. An order's tickets are
 * paid by one charge, so normally all of them are; fewer only when they belong to different orders (or to bookings
 * made before orders existed). Their places are held until the earliest payment deadline.
 */
export function unpaidTicketsMessage(unpaid: Pick<Booking, 'expiresAt' | 'failureReason'>[], total: number): string {
  if (unpaid.length === 0) return ''
  if (total <= 1) return unpaidMessage(unpaid[0])
  const deadline = unpaid.map((b) => b.expiresAt).sort()[0]
  const reason = unpaid.find((b) => b.failureReason)?.failureReason
  const which = unpaid.length === total ? `all ${total} tickets` : `${unpaid.length} of your ${total} tickets`
  const held = `${unpaid.length === 1 ? 'Its place is' : 'Their places are'} held until ${formatTime(deadline)}`
  return reason
    ? `The payment didn't go through for ${which}: ${reason}. ${held}: try again or use another card.`
    : `The payment for ${plural(unpaid.length, 'ticket')} hasn't completed yet. ${held}: try again in a moment.`
}
