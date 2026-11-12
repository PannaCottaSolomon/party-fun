import { availableSpots, hypePercent } from '../domain/eventRules'
import { formatDate, formatDateTime, formatTime, money } from '../format'
import type { PartyEvent } from '../types'

/** The detail grid shared by Event Details and Manage Event. */
export function EventFacts({ event }: { event: PartyEvent }) {
  const facts: [string, string][] = [
    ['Date', formatDate(event.dateTime)],
    ['Time', formatTime(event.dateTime)],
    ['Location', event.location],
    ['Ticket price', event.ticketPrice === 0 ? 'Free' : `${money(event.ticketPrice)} (fixed)`],
    ['Maximum capacity', String(event.maxCapacity)],
    ['Available spots', String(availableSpots(event))],
    ['Minimum tickets required', String(event.minTickets)],
    ['Tickets towards hype', `${event.hypeTickets} (${hypePercent(event)}%)`],
    ['Hype deadline', formatDateTime(event.hypeDeadline)],
    ['Organiser', event.organiserName],
  ]
  return (
    <dl className="facts">
      {facts.map(([label, value]) => (
        <div key={label}>
          <dt>{label}</dt>
          <dd>{value}</dd>
        </div>
      ))}
    </dl>
  )
}

/** Why the event was cancelled, and what happens to tickets. */
export function CancelledNotice({ event }: { event: PartyEvent }) {
  if (event.status !== 'CANCELLED') return null
  return event.cancelReason === 'HYPE_DEADLINE' ? (
    <>
      This event was cancelled because it didn&apos;t reach {event.minTickets} tickets before the hype deadline.
      Eligible ticket purchases are refunded through Stripe.
    </>
  ) : event.cancelReason === 'ORGANISER' ? (
    <>This event was cancelled by the organiser. Eligible ticket purchases are refunded through Stripe.</>
  ) : (
    <>This event was cancelled. Eligible ticket purchases are refunded through Stripe.</>
  )
}
