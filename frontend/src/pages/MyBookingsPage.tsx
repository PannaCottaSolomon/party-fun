import { Link } from 'react-router-dom'
import * as api from '../api/api'
import { useUser } from '../auth/authContext'
import { BookingCard } from '../components/BookingCard'
import { Message } from '../components/Message'
import { groupOrders } from '../domain/orders'
import { useLoad } from '../hooks/useLoad'

/**
 * View 6: My Bookings, the events this user bought tickets for (participants and organisers alike). A signup for
 * several tickets is one booking (one card with "N tickets"), not one card per ticket.
 */
export function MyBookingsPage() {
  const user = useUser()
  const { data: bookings, error } = useLoad(`bookings:${user.id}`, () => api.listMyBookings(user))
  const orders = groupOrders(bookings ?? [])

  return (
    <>
      <h1 className="page-title">My Bookings</h1>
      {error && <Message kind="error">{error}</Message>}
      {!bookings && !error && <p className="muted page-title">Loading bookings…</p>}
      {bookings?.length === 0 && (
        <div className="card empty empty-state">
          <span className="empty-icon" aria-hidden="true">
            <svg width="30" height="30" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round">
              <path d="M3 8a2 2 0 0 0 0 4 2 2 0 0 1 0 4v2h18v-2a2 2 0 0 1 0-4 2 2 0 0 0 0-4V6H3z" />
              <path d="M14 6v12" strokeDasharray="2 2" />
            </svg>
          </span>
          <h2>No bookings yet</h2>
          <p className="muted">
            You haven&apos;t booked any events yet. When you grab a ticket, it shows up here with its payment status.
          </p>
          <Link to="/events" className="button big">
            Browse Events
          </Link>
        </div>
      )}
      <div className="grid grid-centered">
        {orders.map((tickets) => (
          <BookingCard key={tickets[0].id} tickets={tickets} />
        ))}
      </div>
    </>
  )
}
