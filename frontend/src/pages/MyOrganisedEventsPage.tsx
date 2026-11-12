import { useState } from 'react'
import { Link, useLocation } from 'react-router-dom'
import * as api from '../api/api'
import { useUser } from '../auth/authContext'
import { EventCard } from '../components/EventCard'
import { Message } from '../components/Message'
import { useLoad } from '../hooks/useLoad'
import type { EventStatus } from '../types'

const FILTERS: { value: EventStatus | 'ALL'; label: string }[] = [
  { value: 'ALL', label: 'All' },
  { value: 'PENDING', label: 'Pending' },
  { value: 'CONFIRMED', label: 'Confirmed' },
  { value: 'CANCELLED', label: 'Cancelled' },
]

/** View 9: My Organised Events (organiser), or Manage Events (Admin: every event). */
export function MyOrganisedEventsPage() {
  const user = useUser()
  const isAdmin = user.role === 'ADMIN'
  const notice = (useLocation().state as { notice?: string } | null)?.notice
  const { data: events, error } = useLoad(`organised:${user.id}`, () => api.listOrganisedEvents(user))
  const [filter, setFilter] = useState<EventStatus | 'ALL'>('ALL')
  const visible = (events ?? []).filter((e) => filter === 'ALL' || e.status === filter)

  return (
    <>
      <div className="page-head">
        <h1>{isAdmin ? 'Manage Events' : 'My Organised Events'}</h1>
        <Link to="/organiser/events/create" className="button">
          + Create Event
        </Link>
      </div>
      {notice && <Message kind="success">{notice}</Message>}
      <div className="tabs" role="tablist" aria-label="Filter by status">
        {FILTERS.map(({ value, label }) => (
          <button
            key={value}
            type="button"
            role="tab"
            aria-selected={filter === value}
            className={filter === value ? 'active' : ''}
            onClick={() => setFilter(value)}
          >
            {label}
            {events && ` (${value === 'ALL' ? events.length : events.filter((e) => e.status === value).length})`}
          </button>
        ))}
      </div>
      {error && <Message kind="error">{error}</Message>}
      {!events && !error && <p className="muted">{isAdmin ? 'Loading every event…' : 'Loading your events…'}</p>}
      {events && visible.length === 0 && <p className="muted">No events here yet.</p>}
      <div className="grid">
        {visible.map((event) => (
          <EventCard key={event.id} event={event} to={`/organiser/events/${event.id}`} />
        ))}
      </div>
    </>
  )
}
