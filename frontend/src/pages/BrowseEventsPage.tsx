import { useState } from 'react'
import * as api from '../api/api'
import { EventCard } from '../components/EventCard'
import { Message } from '../components/Message'
import { useLoad } from '../hooks/useLoad'
import type { EventStatus } from '../types'

/** View 3: Browse Events, the home page for both roles. */
export function BrowseEventsPage() {
  const { data: events, error } = useLoad('events', api.listEvents)
  const [query, setQuery] = useState('')
  const [status, setStatus] = useState<EventStatus | 'ALL'>('ALL')

  const q = query.trim().toLowerCase()
  const visible = (events ?? []).filter(
    (e) =>
      (status === 'ALL' || e.status === status) &&
      (!q || e.name.toLowerCase().includes(q) || e.location.toLowerCase().includes(q)),
  )

  return (
    <>
      <section className="hero">
        <span className="hero-eyebrow">Discover events near you</span>
        <h1>Find your next party</h1>
        <p>
          Grab tickets and hype events up. An event that doesn&apos;t reach its minimum by the hype deadline is cancelled
          and refunded.
        </p>
      </section>
      <div className="toolbar">
        <input
          type="search"
          placeholder="Search events or locations"
          value={query}
          onChange={(e) => setQuery(e.target.value)}
          aria-label="Search events"
        />
        <select value={status} onChange={(e) => setStatus(e.target.value as EventStatus | 'ALL')} aria-label="Status">
          <option value="ALL">All statuses</option>
          <option value="PENDING">Pending</option>
          <option value="CONFIRMED">Confirmed</option>
          <option value="CANCELLED">Cancelled</option>
        </select>
      </div>
      {error && <Message kind="error">{error}</Message>}
      {!events && !error && <p className="muted">Loading events…</p>}
      {events && visible.length === 0 && <p className="muted">No events match your search.</p>}
      <div className="grid">
        {visible.map((event) => (
          <EventCard key={event.id} event={event} />
        ))}
      </div>
    </>
  )
}
