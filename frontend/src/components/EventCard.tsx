import { Link } from 'react-router-dom'
import { availableSpots, isSoldOut } from '../domain/eventRules'
import { formatDate, formatTime, money } from '../format'
import type { PartyEvent } from '../types'
import { HypeMeter } from './HypeMeter'
import { StatusBadge } from './StatusBadge'

/** An event tile. `to` defaults to the public Event Details page. */
export function EventCard({ event, to = `/events/${event.id}` }: { event: PartyEvent; to?: string }) {
  const spots = availableSpots(event)
  return (
    <Link to={to} className={`card event-card${event.status === 'CANCELLED' ? ' is-cancelled' : ''}`}>
      <div className="event-card-top">
        <StatusBadge status={event.status} />
        <span className="price">{event.ticketPrice === 0 ? 'Free' : money(event.ticketPrice)}</span>
      </div>
      <h3>{event.name}</h3>
      <p className="meta">
        {formatDate(event.dateTime)} · {formatTime(event.dateTime)}
      </p>
      <p className="meta">{event.location}</p>
      <HypeMeter event={event} />
      <p className="spots">
        {event.status === 'CANCELLED'
          ? 'Not available'
          : isSoldOut(event)
            ? 'Sold out'
            : `${spots} of ${event.maxCapacity} spots left`}
      </p>
    </Link>
  )
}
