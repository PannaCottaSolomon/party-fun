import { Link, useNavigate } from 'react-router-dom'
import * as api from '../api/api'
import { useAuth, useUser } from '../auth/authContext'
import { Message } from '../components/Message'
import { BookingStatusBadge } from '../components/StatusBadge'
import { bookingStats, organiserStats } from '../domain/profileStats'
import { formatDate, formatTime, money } from '../format'
import { useLoad } from '../hooks/useLoad'
import { setShowPaymentDetails, useShowPaymentDetails } from '../settings/paymentDetails'
import type { PartyEvent, Role } from '../types'

const ROLE_LABELS: Record<Role, string> = { PARTICIPANT: 'Participant', ORGANISER: 'Organiser', ADMIN: 'Admin' }

function Stat({ value, label }: { value: string | number; label: string }) {
  return (
    <div className="stat">
      <dt>{label}</dt>
      <dd>{value}</dd>
    </div>
  )
}

/**
 * View 8: Profile. The account, a summary of the user's tickets (and, for organisers, their events) and their next
 * event. Everything comes from data the app already loads: the recorded bookings and the Event Service's events.
 */
export function ProfilePage() {
  const user = useUser()
  const { logout } = useAuth()
  const navigate = useNavigate()
  const isAdmin = user.role === 'ADMIN'
  const isOrganiser = user.role === 'ORGANISER'
  const initial = (user.username || user.email || '?').charAt(0).toUpperCase()
  const showPaymentDetails = useShowPaymentDetails()

  const bookings = useLoad(`bookings:${user.id}`, () => (isAdmin ? Promise.resolve([]) : api.listMyBookings(user)))
  const organised = useLoad(`organised:${user.id}`, () =>
    isOrganiser ? api.listOrganisedEvents(user) : Promise.resolve<PartyEvent[]>([]),
  )

  function signOut() {
    logout()
    navigate(isAdmin ? '/admin/login' : '/login', { replace: true })
  }

  const mine = bookings.data && bookingStats(bookings.data, new Date())
  const hosting = isOrganiser && organised.data ? organiserStats(organised.data) : undefined
  const next = mine?.next

  return (
    <div className="narrow profile">
      <h1 className="page-title">Profile</h1>

      <section className="card profile-head">
        <span className="avatar avatar-large" aria-hidden="true">
          {initial}
        </span>
        <h2>{user.username}</h2>
        <p className="muted">{user.email}</p>
        <span className="tag">{ROLE_LABELS[user.role]}</span>
      </section>

      {!isAdmin && (
        <>
          <section className="profile-section">
            <h2>Your activity</h2>
            {isOrganiser && (
              <>
                <h3 className="stat-group">As a host</h3>
                {organised.error && <Message kind="error">{organised.error}</Message>}
                {!hosting && !organised.error && <p className="muted">Loading your events…</p>}
                {hosting && (
                  <dl className="stat-grid">
                    <Stat value={hosting.events} label={hosting.events === 1 ? 'Event organised' : 'Events organised'} />
                    <Stat value={hosting.ticketsSold} label="Tickets sold" />
                    <Stat value={hosting.hypeReached} label="Reached their hype" />
                  </dl>
                )}
                <h3 className="stat-group">Your tickets</h3>
              </>
            )}
            {bookings.error && <Message kind="error">{bookings.error}</Message>}
            {!mine && !bookings.error && <p className="muted">Loading your tickets…</p>}
            {mine && (
              <dl className="stat-grid">
                <Stat value={mine.tickets} label={mine.tickets === 1 ? 'Ticket booked' : 'Tickets booked'} />
                <Stat value={mine.upcoming} label={mine.upcoming === 1 ? 'Upcoming event' : 'Upcoming events'} />
                <Stat value={money(mine.spent)} label="Total spent" />
              </dl>
            )}
          </section>

          {mine && (
            <section className="profile-section">
              <h2>Next up</h2>
              {next ? (
                <Link to={`/bookings/${next.id}`} className="card next-up">
                  <span className="date-chip" aria-hidden="true">
                    <span>{new Date(next.event.dateTime).toLocaleDateString('en-SG', { month: 'short' })}</span>
                    <strong>{new Date(next.event.dateTime).getDate()}</strong>
                  </span>
                  <span className="next-up-body">
                    <strong>{next.event.name}</strong>
                    <span className="meta">
                      {formatDate(next.event.dateTime)} · {formatTime(next.event.dateTime)}
                    </span>
                    <span className="meta">{next.event.location}</span>
                  </span>
                  <BookingStatusBadge status={next.status} />
                </Link>
              ) : (
                <div className="card empty">
                  <p className="muted">You have no upcoming events.</p>
                </div>
              )}
            </section>
          )}
        </>
      )}

      <section className="card profile-section">
        <h2>Account</h2>
        <dl className="summary">
          <div>
            <dt>Username</dt>
            <dd>{user.username}</dd>
          </div>
          <div>
            <dt>Email</dt>
            <dd>{user.email}</dd>
          </div>
          <div>
            <dt>Role</dt>
            <dd>{ROLE_LABELS[user.role]}</dd>
          </div>
        </dl>
        {!isAdmin && (
          <label className="setting">
            <span className="setting-text">
              <strong>Show payment details</strong>
              <small className="muted">
                Stripe cards, sandbox notes and payment IDs at checkout and on your bookings. Turn off for a clean
                checkout.
              </small>
            </span>
            <input
              type="checkbox"
              role="switch"
              className="switch"
              checked={showPaymentDetails}
              onChange={(e) => setShowPaymentDetails(e.target.checked)}
            />
          </label>
        )}
        <button type="button" className="button secondary block" onClick={signOut}>
          Logout
        </button>
      </section>
    </div>
  )
}
