import { Link } from 'react-router-dom'
import { orderStatus, orderTotal } from '../domain/orders'
import { formatDate, formatTime, money } from '../format'
import type { BookingView } from '../types'
import { BookingStatusBadge, StatusBadge } from './StatusBadge'

/** One booking: the tickets of one signup (an order), shown together with the order's total. */
export function BookingCard({ tickets }: { tickets: BookingView[] }) {
  const [first] = tickets
  const { event } = first
  const status = orderStatus(tickets)
  const total = orderTotal(tickets)
  return (
    <Link to={`/bookings/${first.id}`} className="card booking-card">
      <div className="event-card-top">
        <StatusBadge status={event.status} />
        <BookingStatusBadge status={status} />
      </div>
      <h3>{event.name}</h3>
      <p className="meta">
        {formatDate(event.dateTime)} · {formatTime(event.dateTime)}
      </p>
      <p className="meta">{event.location}</p>
      <div className="booking-card-foot">
        <span>{tickets.length === 1 ? '1 ticket' : `${tickets.length} tickets`}</span>
        <strong>{total === 0 ? 'Free' : `${money(total)}${status === 'CONFIRMED' ? ' paid' : ''}`}</strong>
      </div>
    </Link>
  )
}
