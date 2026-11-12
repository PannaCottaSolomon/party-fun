import { useState, type FormEvent, type ReactNode } from 'react'
import { Link } from 'react-router-dom'
import { validateEvent } from '../domain/eventRules'
import type { EventErrors, EventInput, PartyEvent } from '../types'
import { Message } from './Message'

interface FormValues {
  name: string
  description: string
  date: string
  time: string
  location: string
  maxCapacity: string
  minTickets: string
  ticketPrice: string
  hypeDeadline: string
}

const EMPTY: FormValues = {
  name: '',
  description: '',
  date: '',
  time: '',
  location: '',
  maxCapacity: '',
  minTickets: '',
  ticketPrice: '',
  hypeDeadline: '',
}

function fromEvent(event: PartyEvent): FormValues {
  const [date, time] = event.dateTime.split('T')
  return {
    name: event.name,
    description: event.description,
    date,
    time,
    location: event.location,
    maxCapacity: String(event.maxCapacity),
    minTickets: String(event.minTickets),
    ticketPrice: String(event.ticketPrice),
    hypeDeadline: event.hypeDeadline,
  }
}

function toInput(values: FormValues): EventInput {
  const number = (s: string) => (s.trim() === '' ? NaN : Number(s))
  return {
    name: values.name,
    description: values.description,
    date: values.date,
    time: values.time,
    location: values.location,
    maxCapacity: number(values.maxCapacity),
    minTickets: number(values.minTickets),
    // Empty means a free event (price 0)
    ticketPrice: values.ticketPrice.trim() === '' ? 0 : Number(values.ticketPrice),
    hypeDeadline: values.hypeDeadline,
  }
}

interface Props {
  /** The event being edited; omit when creating. */
  existing?: PartyEvent
  submitLabel: string
  cancelTo: string
  onSubmit: (input: EventInput) => Promise<void>
}

/** The Create Event / Edit Event form, with the same validation for both. */
export function EventForm({ existing, submitLabel, cancelTo, onSubmit }: Props) {
  const [values, setValues] = useState<FormValues>(() => (existing ? fromEvent(existing) : EMPTY))
  const [errors, setErrors] = useState<EventErrors>({})
  const [submitError, setSubmitError] = useState<string>()
  const [busy, setBusy] = useState(false)
  const priceLocked = !!existing && existing.hypeTickets > 0

  const set = (field: keyof FormValues) => (e: { target: { value: string } }) =>
    setValues((v) => ({ ...v, [field]: e.target.value }))

  async function submit(e: FormEvent) {
    e.preventDefault()
    const input = toInput(values)
    const found = validateEvent(input, new Date(), existing)
    setErrors(found)
    setSubmitError(undefined)
    if (Object.keys(found).length > 0) return
    setBusy(true)
    try {
      await onSubmit(input)
    } catch (err) {
      setSubmitError(err instanceof Error ? err.message : 'Could not save the event.')
      setBusy(false)
    }
  }

  const field = (name: keyof FormValues, label: string, input: ReactNode, hint?: string) => (
    <label className={`field${errors[name] ? ' has-error' : ''}`}>
      <span>{label}</span>
      {input}
      {hint && !errors[name] && <small className="hint">{hint}</small>}
      {errors[name] && <small className="error-text">{errors[name]}</small>}
    </label>
  )

  return (
    <form className="card form" onSubmit={submit} noValidate>
      {submitError && <Message kind="error">{submitError}</Message>}
      {field('name', 'Event name', <input value={values.name} onChange={set('name')} maxLength={100} />)}
      {field(
        'description',
        'Description',
        <textarea value={values.description} onChange={set('description')} rows={4} maxLength={1000} />,
      )}
      <div className="form-row">
        {field('date', 'Event date', <input type="date" value={values.date} onChange={set('date')} />)}
        {field('time', 'Event time', <input type="time" value={values.time} onChange={set('time')} />)}
      </div>
      {field(
        'location',
        'Location',
        <input value={values.location} onChange={set('location')} placeholder="e.g. SMU Connexion" maxLength={150} />,
      )}
      <div className="form-row">
        {field(
          'maxCapacity',
          'Maximum capacity',
          <input type="number" min={1} step={1} value={values.maxCapacity} onChange={set('maxCapacity')} />,
          existing ? `${existing.occupiedSpots} spot(s) currently taken` : undefined,
        )}
        {field(
          'minTickets',
          'Minimum tickets required',
          <input type="number" min={1} step={1} value={values.minTickets} onChange={set('minTickets')} />,
          'Paid tickets needed before the hype deadline',
        )}
      </div>
      <div className="form-row">
        {field(
          'ticketPrice',
          'Ticket price (SGD)',
          <input
            type="number"
            min={0}
            step={0.01}
            placeholder="0 (free)"
            value={values.ticketPrice}
            onChange={set('ticketPrice')}
            disabled={priceLocked}
          />,
          priceLocked ? 'Fixed: tickets have already been sold at this price' : 'Fixed price per ticket; leave empty or 0 for a free event',
        )}
        {field(
          'hypeDeadline',
          'Hype deadline',
          <input type="datetime-local" value={values.hypeDeadline} onChange={set('hypeDeadline')} />,
          'Must be before the event starts',
        )}
      </div>
      <div className="form-actions">
        <Link to={cancelTo} className="button secondary">
          Cancel
        </Link>
        <button type="submit" className="button" disabled={busy}>
          {busy ? 'Saving…' : submitLabel}
        </button>
      </div>
    </form>
  )
}
