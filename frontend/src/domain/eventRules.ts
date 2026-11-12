import { money } from '../format'
import type { Booking, BookingStatus, BookingView, EventErrors, EventInput, PartyEvent } from '../types'

/** Stripe's minimum charge in SGD: a paid ticket can't cost less. */
export const MIN_PAID_PRICE = 0.5
/** The most the Event Service (and the Payment Service) accept as a ticket price. */
export const MAX_TICKET_PRICE = 99_999_999.99

/** Most tickets one signup can buy (the Event Service's limit per signup request). */
export const MAX_TICKETS_PER_SIGNUP = 10

export function availableSpots(event: PartyEvent): number {
  return Math.max(0, event.maxCapacity - event.occupiedSpots)
}

/** How many tickets this event's checkout may offer: the per-signup limit, or fewer if fewer places are left. */
export function maxTicketsFor(event: PartyEvent): number {
  return Math.min(MAX_TICKETS_PER_SIGNUP, availableSpots(event))
}

export function isSoldOut(event: PartyEvent): boolean {
  return availableSpots(event) === 0
}

/** Hype % = tickets paid for / minimum tickets required × 100, capped at 100. */
export function hypePercent(event: PartyEvent): number {
  if (event.minTickets <= 0) return 100
  return Math.min(100, Math.round((event.hypeTickets / event.minTickets) * 100))
}

/** Why this user can't sign up for this event, or null if they can. */
export function purchaseBlocker(event: PartyEvent, userId: string, bookings: Booking[] = []): string | null {
  if (event.status === 'CANCELLED') return 'This event has been cancelled, so tickets are no longer available.'
  if (event.organiserId === userId) return "You can't buy tickets to an event you organise."
  if (heldBooking(bookings, event.id, userId)) return 'You already have tickets for this event. See My Bookings.'
  if (isSoldOut(event)) return 'This event is sold out.'
  return null
}

/**
 * The booking's status right now. The Event Service only marks a lapsed PENDING booking EXPIRED later, so one past
 * its payment deadline is shown as EXPIRED straight away.
 */
export function currentBookingStatus(booking: Booking, now: Date): BookingStatus {
  if (booking.status === 'PENDING' && new Date(booking.expiresAt).getTime() <= now.getTime()) return 'EXPIRED'
  return booking.status
}

/** A booking holds its place while it is CONFIRMED, or PENDING within its payment window. */
export function holdsPlace(booking: Booking, now: Date = new Date()): boolean {
  const status = currentBookingStatus(booking, now)
  return status === 'CONFIRMED' || status === 'PENDING'
}

/**
 * The user's bookings for this event that still hold a place, oldest first. A user signs up once per event, but that
 * signup may be for several tickets (one booking each).
 */
export function heldBookings(bookings: Booking[], eventId: string, userId: string, now: Date = new Date()): Booking[] {
  return bookings
    .filter((b) => b.eventId === eventId && b.userId === userId && holdsPlace(b, now))
    // Numeric-aware ids: "5" before "12", and "payment-16-2" before "payment-16-10" (tickets recovered from a payment)
    .sort((a, b) => a.createdAt.localeCompare(b.createdAt) || a.id.localeCompare(b.id, undefined, { numeric: true }))
}

/** One of the user's bookings for this event that still holds a place (the oldest), if any. */
export function heldBooking(bookings: Booking[], eventId: string, userId: string, now: Date = new Date()): Booking | undefined {
  return heldBookings(bookings, eventId, userId, now)[0]
}

/**
 * A booking with its event. `live` is the event as the Event Service has it now; when it's missing the event was
 * cancelled (cancelling deletes it), so the booking shows `snapshot` (the event when the booking was made) as
 * CANCELLED, and a booking that held a place is CANCELLED too.
 */
export function bookingView(booking: Booking, live: PartyEvent | undefined, snapshot: PartyEvent, now: Date): BookingView {
  const status = currentBookingStatus(booking, now)
  if (live) return { ...booking, status, event: live }
  const held = status === 'PENDING' || status === 'CONFIRMED'
  return { ...booking, status: held ? 'CANCELLED' : status, event: { ...snapshot, status: 'CANCELLED' } }
}

export function toEventDateTime(input: Pick<EventInput, 'date' | 'time'>): string {
  return `${input.date}T${input.time}`
}

/**
 * Create/Edit Event validation. `existing` is the event being edited: its capacity can't drop below the
 * spots already taken, and its price is fixed once tickets have been sold.
 */
export function validateEvent(input: EventInput, now: Date, existing?: PartyEvent): EventErrors {
  const errors: EventErrors = {}
  if (!input.name.trim()) errors.name = 'Event name is required.'
  if (!input.date) errors.date = 'Event date is required.'
  if (!input.time) errors.time = 'Event time is required.'
  if (!input.location.trim()) errors.location = 'Location is required.'

  if (!Number.isInteger(input.maxCapacity) || input.maxCapacity <= 0) {
    errors.maxCapacity = 'Maximum capacity must be a whole number above 0.'
  } else if (existing && input.maxCapacity < existing.occupiedSpots) {
    errors.maxCapacity = `Capacity can't be lower than the ${existing.occupiedSpots} spot(s) already taken.`
  }

  if (!Number.isInteger(input.minTickets) || input.minTickets <= 0) {
    errors.minTickets = 'Minimum tickets must be a whole number above 0.'
  } else if (Number.isInteger(input.maxCapacity) && input.minTickets > input.maxCapacity) {
    errors.minTickets = "Minimum tickets can't be more than the maximum capacity."
  }

  // 0 is a free event. A paid price must be at least Stripe's S$0.50 minimum charge, in whole cents, and within what
  // the Event Service accepts (at most 99,999,999.99).
  if (!Number.isFinite(input.ticketPrice) || input.ticketPrice < 0) {
    errors.ticketPrice = 'Ticket price must be 0 (free) or more.'
  } else if (Math.abs(Math.round(input.ticketPrice * 100) - input.ticketPrice * 100) > 1e-6) {
    errors.ticketPrice = 'Ticket price can have at most 2 decimal places.'
  } else if (input.ticketPrice > 0 && input.ticketPrice < MIN_PAID_PRICE) {
    errors.ticketPrice = `A paid ticket must cost at least ${money(MIN_PAID_PRICE)}; use 0 for a free event.`
  } else if (input.ticketPrice > MAX_TICKET_PRICE) {
    errors.ticketPrice = `Ticket price can't be more than ${money(MAX_TICKET_PRICE)}.`
  } else if (existing && existing.hypeTickets > 0 && input.ticketPrice !== existing.ticketPrice) {
    errors.ticketPrice = "The ticket price is fixed once tickets have been sold."
  }

  if (!input.hypeDeadline) {
    errors.hypeDeadline = 'Hype deadline is required.'
  } else if (input.date && input.time) {
    const deadline = new Date(input.hypeDeadline).getTime()
    const start = new Date(toEventDateTime(input)).getTime()
    if (!(deadline < start)) errors.hypeDeadline = 'The hype deadline must be before the event date and time.'
    else if (input.hypeDeadline !== existing?.hypeDeadline && deadline <= now.getTime()) {
      errors.hypeDeadline = 'The hype deadline must be in the future.'
    }
  }
  return errors
}
