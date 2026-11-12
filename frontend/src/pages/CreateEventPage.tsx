import { useNavigate } from 'react-router-dom'
import * as api from '../api/api'
import { useUser } from '../auth/authContext'
import { EventForm } from '../components/EventForm'

/** View 10: Create Event (organiser or Admin; the creator is the organiser). New events start as PENDING. */
export function CreateEventPage() {
  const user = useUser()
  const navigate = useNavigate()

  return (
    <div className="narrow">
      <h1>Create Event</h1>
      <p className="muted">
        Your event starts as <strong>Pending</strong> and stays open for signups. If it hasn&apos;t reached the minimum
        number of tickets by the hype deadline, it&apos;s cancelled and ticket holders are refunded.
        {user.role === 'ADMIN' && ' As an Admin, you are listed as its organiser.'}
      </p>
      <EventForm
        submitLabel="Create Event"
        cancelTo="/organiser/events"
        onSubmit={async (input) => {
          const event = await api.createEvent(user, input)
          navigate(`/organiser/events/${event.id}`, { state: { notice: 'Event created. It is now pending.' } })
        }}
      />
    </div>
  )
}
