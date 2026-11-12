import { Link, useNavigate, useParams } from 'react-router-dom'
import * as api from '../api/api'
import { useUser } from '../auth/authContext'
import { EventForm } from '../components/EventForm'
import { Message } from '../components/Message'
import { useLoad } from '../hooks/useLoad'

/** View 12: Edit Event (organiser only). Same form and validation as Create Event. */
export function EditEventPage() {
  const { eventId = '' } = useParams()
  const user = useUser()
  const navigate = useNavigate()
  const { data: event, error } = useLoad(`organised:${eventId}`, () => api.getOrganisedEvent(user, eventId))

  if (error) return <Message kind="error">{error}</Message>
  if (!event) return <p className="muted">Loading…</p>

  return (
    <div className="narrow">
      <Link to={`/organiser/events/${event.id}`} className="back">
        ← Back to event
      </Link>
      <h1>Edit Event</h1>
      {event.status === 'CANCELLED' ? (
        <Message kind="warning">Cancelled events can no longer be edited.</Message>
      ) : (
        <>
          {event.hypeTickets > 0 && (
            <p className="muted">
              Tickets have already been sold, so the ticket price is fixed and the capacity can&apos;t go below the
              spots already taken.
            </p>
          )}
          <EventForm
            existing={event}
            submitLabel="Save Changes"
            cancelTo={`/organiser/events/${event.id}`}
            onSubmit={async (input) => {
              await api.updateEvent(user, event.id, input)
              navigate(`/organiser/events/${event.id}`, { state: { notice: 'Event updated.' } })
            }}
          />
        </>
      )}
    </div>
  )
}
