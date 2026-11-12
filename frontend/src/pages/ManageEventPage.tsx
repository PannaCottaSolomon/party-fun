import { useState } from 'react'
import { Link, useLocation, useNavigate, useParams } from 'react-router-dom'
import * as api from '../api/api'
import { useUser } from '../auth/authContext'
import { ConfirmDialog } from '../components/ConfirmDialog'
import { CancelledNotice, EventFacts } from '../components/EventFacts'
import { HypeMeter } from '../components/HypeMeter'
import { Message } from '../components/Message'
import { StatusBadge } from '../components/StatusBadge'
import { formatDateTime, plural } from '../format'
import { useLoad } from '../hooks/useLoad'

/** View 11: Organised Event Details / Manage Event: an organiser edits or cancels their event; an Admin, any event. */
export function ManageEventPage() {
  const { eventId = '' } = useParams()
  const user = useUser()
  const navigate = useNavigate()
  const notice = (useLocation().state as { notice?: string } | null)?.notice
  const { data: event, error } = useLoad(`organised:${eventId}`, () => api.getOrganisedEvent(user, eventId))
  const [confirming, setConfirming] = useState(false)
  const [busy, setBusy] = useState(false)
  const [dialogError, setDialogError] = useState<string>()

  if (error) return <Message kind="error">{error}</Message>
  if (!event) return <p className="muted">Loading…</p>

  /** The Event Service deletes the event and has the Payment Service refund its paid bookings. */
  async function cancel() {
    setBusy(true)
    setDialogError(undefined)
    try {
      const cancelled = await api.cancelEvent(user, eventId)
      navigate('/organiser/events', {
        replace: true,
        state: {
          notice: `“${cancelled.name}” was cancelled. Every paid booking is refunded through Stripe by the Payment Service; refunds that fail go to the Admin's failed-refunds dashboard.`,
        },
      })
    } catch (err) {
      // Nothing was cancelled: keep the dialog open to try again.
      setDialogError(err instanceof Error ? err.message : 'Could not cancel the event.')
      setBusy(false)
    }
  }

  const cancelled = event.status === 'CANCELLED'

  return (
    <article className="detail">
      <Link to="/organiser/events" className="back">
        {user.role === 'ADMIN' ? '← Manage Events' : '← My Organised Events'}
      </Link>
      <header className="detail-head">
        <StatusBadge status={event.status} />
        <h1>{event.name}</h1>
        <p className="meta">
          {formatDateTime(event.dateTime)} · {event.location}
        </p>
      </header>

      {notice && <Message kind="success">{notice}</Message>}
      {cancelled && (
        <Message kind="warning">
          <CancelledNotice event={event} />
        </Message>
      )}

      <section className="card detail-hype">
        <HypeMeter event={event} large />
        {!cancelled && (
          <div className="cta">
            <Link to={`/organiser/events/${event.id}/edit`} className="button secondary">
              Edit Event
            </Link>
            <button type="button" className="button danger" onClick={() => setConfirming(true)}>
              Cancel Event
            </button>
          </div>
        )}
      </section>

      {event.description && <p className="description">{event.description}</p>}
      <EventFacts event={event} />

      <ConfirmDialog
        open={confirming}
        title="Cancel this event?"
        confirmLabel="Cancel Event"
        danger
        busy={busy}
        onConfirm={cancel}
        onCancel={() => {
          setConfirming(false)
          setDialogError(undefined)
        }}
      >
        <p>
          Cancelling removes this event and cancels it for all participants. Every paid ticket is refunded through
          Stripe by the Payment Service.
        </p>
        {event.occupiedSpots > 0 && <p>{plural(event.occupiedSpots, 'participant')} signed up so far.</p>}
        {dialogError && <Message kind="error">{dialogError}</Message>}
        <p className="muted">This can&apos;t be undone.</p>
      </ConfirmDialog>
    </article>
  )
}
