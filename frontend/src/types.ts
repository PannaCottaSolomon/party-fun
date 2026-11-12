/** ADMIN accounts are seeded only; nobody can register as one. */
export type Role = 'PARTICIPANT' | 'ORGANISER' | 'ADMIN'

export interface User {
  /** The numeric user id the Events and Payment Services use (as a string), derived from the Cognito `sub`. */
  id: string
  username: string
  email: string
  role: Role
}

/**
 * The only three event statuses. "Greenlit" etc. are never statuses. An ACTIVE event (open for signups) in the Event
 * Service is PENDING until it has its minimum signups (a full hype meter), then CONFIRMED; signups stay open either way.
 */
export type EventStatus = 'PENDING' | 'CONFIRMED' | 'CANCELLED'

/** Why an event was cancelled: by its organiser, or because the hype deadline passed below the minimum. */
export type CancelReason = 'ORGANISER' | 'HYPE_DEADLINE'

export interface PartyEvent {
  /** The Event Service's numeric id, as a string (used in URLs). */
  id: string
  name: string
  description: string
  /** Local date-time, e.g. "2026-10-20T19:00". */
  dateTime: string
  location: string
  /** Fixed price per ticket in SGD. */
  ticketPrice: number
  maxCapacity: number
  /** Minimum signups needed (before the hype deadline) for the event to go ahead. */
  minTickets: number
  /** Signups so far (paid or still holding a place while payment is pending). Drives the hype meter. */
  hypeTickets: number
  /** Places taken: the same tickets, since every ticket takes one place. */
  occupiedSpots: number
  /** Local date-time; always before the event's date-time. Empty if the event has no deadline. */
  hypeDeadline: string
  status: EventStatus
  cancelReason?: CancelReason
  organiserId: string
  organiserName: string
}

/**
 * The Event Service's booking states: PENDING holds the place for 15 minutes while it waits for payment (a declined
 * card keeps it PENDING so the payment can be retried), CONFIRMED is paid, EXPIRED wasn't paid in time, CANCELLED
 * means the event was cancelled (paid bookings are then refunded by the Payment Service).
 */
export type BookingStatus = 'PENDING' | 'CONFIRMED' | 'EXPIRED' | 'CANCELLED'

/**
 * One ticket: one place at one event. A signup for several tickets creates one booking per ticket, all in one order
 * that is paid by one charge.
 */
export interface Booking {
  id: string
  eventId: string
  userId: string
  /** The signup (order) this ticket belongs to: its tickets are paid, and confirmed, together. */
  orderId?: string
  status: BookingStatus
  /** The ticket price at signup, in SGD (0 for a free event). */
  amount: number
  /** ISO instant. */
  createdAt: string
  /** ISO instant: the deadline for paying a PENDING booking. */
  expiresAt: string
  /** ISO instant, once paid. */
  confirmedAt?: string
  /** Why the last payment attempt failed (kept after a successful retry). */
  failureReason?: string
  /** The Payment Service's id of the successful payment. Its live status (paid, refunded) comes from there. */
  paymentId?: number
}

export interface BookingView extends Booking {
  event: PartyEvent
}

/** What the Create/Edit Event form submits. */
export interface EventInput {
  name: string
  description: string
  /** yyyy-mm-dd */
  date: string
  /** HH:mm */
  time: string
  location: string
  maxCapacity: number
  minTickets: number
  ticketPrice: number
  /** yyyy-mm-ddTHH:mm */
  hypeDeadline: string
}

export type EventErrors = Partial<Record<keyof EventInput, string>>
