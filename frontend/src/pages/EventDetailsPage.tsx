import { Link, useParams } from 'react-router-dom'
import * as api from '../api/api'
import { canManageEvent } from '../auth/access'
import { useUser } from '../auth/authContext'
import { CancelledNotice, EventFacts } from '../components/EventFacts'
import { HypeMeter } from '../components/HypeMeter'
import { Message } from '../components/Message'
import { StatusBadge } from '../components/StatusBadge'
import { isSoldOut } from '../domain/eventRules'
import { formatDateTime } from '../format'
import { useLoad } from '../hooks/useLoad'
import type { Booking } from '../types'

/** What the user already holds for this event: one ticket (linked to its booking) or several (linked to My Bookings). */
function HeldTicketsNotice({ held }: { held: Booking[] }) {
  const unpaid = held.filter((b) => b.status === 'PENDING').length
  if (held.length === 1) {
    return (
      <>
        {unpaid ? 'Your place is held, but your payment is not complete yet.' : 'You have a ticket for this event.'}{' '}
        <Link to={`/bookings/${held[0].id}`}>View your booking</Link>
      </>
    )
  }
  return (
    <>
      You have {held.length} tickets for this event
      {unpaid > 0 ? `, ${unpaid === held.length ? 'none' : `${held.length - unpaid}`} of them paid so far.` : '.'}{' '}
      <Link to="/bookings">View your tickets</Link>
    </>
  )
}

/** View 4: Event Details, with the hype meter and the Buy Tickets call to action. */
export function EventDetailsPage() {
  const { eventId = '' } = useParams()
  const user = useUser()
  const { data: event, error } = useLoad(`event:${eventId}`, () => api.getEvent(eventId))
  const { data: held = [] } = useLoad(`held:${eventId}:${user.id}`, () => api.getHeldBookings(user, eventId))

  if (error) return <Message kind="error">{error}</Message>
  if (!event) return <p className="muted">Loading…</p>

  // The organiser, or an Admin (who manages every event), gets "Manage this event" instead of Buy Tickets.
  const own = canManageEvent(user, event)

  return (
    <article className="detail">
      <Link to="/events" className="back">
        ← All events
      </Link>
      <header className="detail-head">
        <StatusBadge status={event.status} />
        <h1>{event.name}</h1>
        <p className="meta">
          {formatDateTime(event.dateTime)} · {event.location}
        </p>
      </header>

      <section className="card detail-hype">
        <HypeMeter event={event} large />
        <div className="cta">
          {event.status === 'CANCELLED' ? (
            <Message kind="warning">
              <CancelledNotice event={event} /> Tickets can no longer be bought.
            </Message>
          ) : own ? (
            <Link to={`/organiser/events/${event.id}`} className="button">
              Manage this event
            </Link>
          ) : held.length > 0 ? (
            <Message kind="info">
              <HeldTicketsNotice held={held} />
            </Message>
          ) : isSoldOut(event) ? (
            <Message kind="warning">This event is sold out.</Message>
          ) : (
            <Link to={`/events/${event.id}/checkout`} className="button big">
              Buy Tickets
            </Link>
          )}
        </div>
      </section>

      {event.description && <p className="description">{event.description}</p>}
      <EventFacts event={event} />
    </article>
  )
}
