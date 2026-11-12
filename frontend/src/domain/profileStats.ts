import type { BookingView, PartyEvent } from '../types'

export interface BookingStats {
  /** Paid (or free) tickets. */
  tickets: number
  /** Bookings still holding a place (paid, or awaiting payment) for events that haven't started or been cancelled. */
  upcoming: number
  /** Total paid for confirmed tickets, in SGD. Cancelled bookings (refunded) don't count. */
  spent: number
  /** The soonest upcoming booking, if any. */
  next?: BookingView
}

/** The Profile page's activity numbers for a participant (or an organiser's own tickets). */
export function bookingStats(bookings: BookingView[], now: Date): BookingStats {
  const confirmed = bookings.filter((b) => b.status === 'CONFIRMED')
  const upcoming = bookings
    .filter(
      (b) =>
        (b.status === 'CONFIRMED' || b.status === 'PENDING') &&
        b.event.status !== 'CANCELLED' &&
        new Date(b.event.dateTime).getTime() > now.getTime(),
    )
    .sort((a, b) => a.event.dateTime.localeCompare(b.event.dateTime))
  return {
    tickets: confirmed.length,
    upcoming: upcoming.length,
    spent: confirmed.reduce((sum, b) => sum + b.amount, 0),
    next: upcoming[0],
  }
}

export interface OrganiserStats {
  /** Every event this organiser created, cancelled ones included. */
  events: number
  /** Places taken across the organiser's events that are still on. */
  ticketsSold: number
  /** Events still on that have reached their minimum tickets. */
  hypeReached: number
}

/** The Profile page's numbers for the events an organiser runs. */
export function organiserStats(events: PartyEvent[]): OrganiserStats {
  const live = events.filter((e) => e.status !== 'CANCELLED')
  return {
    events: events.length,
    ticketsSold: live.reduce((sum, e) => sum + e.occupiedSpots, 0),
    hypeReached: live.filter((e) => e.hypeTickets >= e.minTickets).length,
  }
}
