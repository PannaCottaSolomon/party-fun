import { useState } from 'react'
import { Link, useParams } from 'react-router-dom'
import * as api from '../api/api'
import { ApiError } from '../api/api'
import { useUser } from '../auth/authContext'
import { Confetti } from '../components/Confetti'
import { HypeMeter } from '../components/HypeMeter'
import { Message } from '../components/Message'
import { StripeCardForm, TestCardHint } from '../components/StripeCardForm'
import { availableSpots, holdsPlace, maxTicketsFor, purchaseBlocker } from '../domain/eventRules'
import { formatDateTime, money, plural } from '../format'
import { useLoad } from '../hooks/useLoad'
import { unpaidTicketsMessage } from '../payments/bookingPayment'
import { useShowPaymentDetails } from '../settings/paymentDetails'
import type { BookingView } from '../types'

/** The tickets a declined (or unfinished) payment left PENDING with their place still held: the next payment retries them. */
function unpaidOf(bookings: BookingView[]): BookingView[] {
  return bookings.filter((b) => b.status === 'PENDING' && holdsPlace(b))
}

/** `bookings` with each booking replaced by its newer copy from `updates`, if there is one. */
function merge(bookings: BookingView[], updates: BookingView[]): BookingView[] {
  const latest = new Map(updates.map((b) => [b.id, b]))
  return bookings.map((b) => latest.get(b.id) ?? b)
}

/**
 * View 5: Checkout, for 1 to 10 tickets (one place each) at the event's fixed price. Payment is real, in the Stripe
 * sandbox (test cards, no real money): Stripe's card form turns the card into a PaymentMethod, and signing up with it
 * makes the Event Service take every place (all or nothing) and charge all the tickets at once, as one order, through
 * the Payment Service. A declined card keeps the tickets' places for 15 minutes, and the next attempt pays for those
 * same bookings. A user signs up once per event (for however many tickets).
 */
export function CheckoutPage() {
  const { eventId = '' } = useParams()
  const user = useUser()
  const { data: event, error } = useLoad(`event:${eventId}`, () => api.getEvent(eventId))
  const { data: held = [] } = useLoad(`held:${eventId}:${user.id}`, () => api.getHeldBookings(user, eventId))
  /** The bookings once signup returned them: each CONFIRMED (done), or PENDING (the next payment retries it). */
  const [bookings, setBookings] = useState<BookingView[]>([])
  const [quantity, setQuantity] = useState(1)
  const [busy, setBusy] = useState(false)
  const [freeError, setFreeError] = useState<string>()
  const showPaymentDetails = useShowPaymentDetails()

  if (error) return <Message kind="error">{error}</Message>
  if (!event) return <p className="muted">Loading…</p>

  const confirmed = bookings.filter((b) => b.status === 'CONFIRMED')
  const unpaid = unpaidOf(bookings)

  if (confirmed.length > 0 && unpaid.length === 0) {
    const paid = confirmed.reduce((sum, b) => sum + b.amount, 0)
    const lost = bookings.length - confirmed.length
    const tickets = confirmed.length === 1 ? 'a ticket' : `${confirmed.length} tickets`
    const latestEvent = confirmed[confirmed.length - 1].event
    return (
      <div className="narrow">
        <Confetti />
        <div className="celebration">
          <h1>Woohoo, you&apos;re going! 🎉</h1>
          <p>See you at {latestEvent.name}.</p>
        </div>
        <Message kind="success">
          <strong>{paid > 0 ? 'Payment successful.' : "You're in!"}</strong>{' '}
          {paid > 0 ? `You paid ${money(paid)} with Stripe for` : 'You have'} {tickets} to {latestEvent.name}.
        </Message>
        {lost > 0 && (
          <Message kind="warning">
            {lost === 1 ? "1 ticket wasn't paid in time, so its place was" : `${lost} tickets weren't paid in time, so their places were`}{' '}
            released.
          </Message>
        )}
        {showPaymentDetails && confirmed.some((b) => b.paymentId !== undefined) && (
          <p className="muted small">
            {confirmed.length === 1 ? 'Payment' : 'Payments'}{' '}
            <span className="mono">
              {confirmed
                .map((b) => b.paymentId)
                .filter((id) => id !== undefined)
                .join(', ')}
            </span>
          </p>
        )}
        <div className="card">
          <HypeMeter event={latestEvent} large />
        </div>
        <div className="form-actions">
          <Link to="/events" className="button secondary">
            Browse more events
          </Link>
          {confirmed.length === 1 ? (
            <Link to={`/bookings/${confirmed[0].id}`} className="button">
              View booking
            </Link>
          ) : (
            <Link to="/bookings" className="button">
              View my tickets
            </Link>
          )}
        </div>
      </div>
    )
  }

  /** Throws (so the card form shows why) unless every ticket of this signup is now paid. */
  function checkPaid(result: BookingView[]) {
    const stillUnpaid = unpaidOf(result)
    if (stillUnpaid.length > 0) throw new ApiError(unpaidTicketsMessage(stillUnpaid, result.length))
    if (!result.some((b) => b.status === 'CONFIRMED')) {
      const status = result[0]?.status.toLowerCase() ?? 'not confirmed'
      throw new ApiError(`${result.length === 1 ? 'This booking is' : 'These bookings are'} ${status}. See My Bookings.`)
    }
  }

  /**
   * Signs up for `quantity` tickets, or retries the payment of the tickets a declined card left PENDING (a new signup
   * once their 15 minutes are up). A payment that didn't go through for every ticket throws, so the card form shows why.
   */
  async function pay(paymentMethodId?: string) {
    if (paymentMethodId && unpaid.length > 0) {
      const { results, error: failed } = await api.retryPayments(unpaid, paymentMethodId)
      const updated = merge(bookings, results)
      setBookings(updated)
      if (failed) throw failed
      checkPaid(updated)
      return
    }
    const result = await api.signUp(user, event!, paymentMethodId, tickets)
    setBookings(result)
    checkPaid(result)
  }

  async function signUpFree() {
    setBusy(true)
    setFreeError(undefined)
    try {
      await pay()
    } catch (err) {
      setFreeError(err instanceof Error ? err.message : 'Could not sign you up.')
    } finally {
      setBusy(false)
    }
  }

  const blocker = bookings.length > 0 ? null : purchaseBlocker(event, user.id, held)
  const free = event.ticketPrice === 0
  const maxTickets = Math.max(1, maxTicketsFor(event))
  /** Fixed once signup has created the bookings: the rest of checkout only pays for the unpaid ones. */
  const retrying = unpaid.length > 0
  const tickets = retrying ? bookings.length : Math.min(quantity, maxTickets)
  const canChooseQuantity = !retrying && maxTickets > 1
  const due = retrying ?unpaid.reduce((sum, b) => sum + b.amount, 0) : event.ticketPrice * tickets

  return (
    <div className="narrow">
      <Link to={`/events/${event.id}`} className="back">
        ← Back to event
      </Link>
      <h1>Checkout</h1>
      {blocker ? (
        <Message kind="warning">{blocker}</Message>
      ) : (
        <div className="card checkout">
          <h2>{event.name}</h2>
          <p className="meta">
            {formatDateTime(event.dateTime)} · {event.location}
          </p>
          <dl className="summary">
            <div>
              <dt>{canChooseQuantity ? <label htmlFor="ticket-quantity">Tickets</label> : 'Tickets'}</dt>
              <dd>
                {!canChooseQuantity ? (
                  tickets
                ) : (
                  <select
                    id="ticket-quantity"
                    className="quantity-select"
                    value={tickets}
                    onChange={(e) => setQuantity(Number(e.target.value))}
                    disabled={busy}
                  >
                    {Array.from({ length: maxTickets }, (_, i) => i + 1).map((n) => (
                      <option key={n} value={n}>
                        {n}
                      </option>
                    ))}
                  </select>
                )}
              </dd>
            </div>
            {!free && tickets > 1 && (
              <div>
                <dt>Price per ticket</dt>
                <dd>{money(event.ticketPrice)}</dd>
              </div>
            )}
            {retrying && confirmed.length > 0 && (
              <div>
                <dt>Already paid</dt>
                <dd>{plural(confirmed.length, 'ticket')}</dd>
              </div>
            )}
            <div className="total">
              <dt>{retrying ? `Still to pay (${plural(unpaid.length, 'ticket')})` : 'Total'}</dt>
              <dd>
                <strong>{free ? 'Free' : money(due)}</strong>
              </dd>
            </div>
          </dl>
          <p className="muted small">
            {plural(availableSpots(event), 'spot')} left
            {!retrying && maxTickets > 1 && ` (up to ${maxTickets} per order)`}.
            {!free && tickets > 1 && ` All ${tickets} tickets are paid in one charge.`}
            {!free &&
              (showPaymentDetails
                ? ' Payment is handled by Stripe (sandbox: test cards only, no real money).'
                : ' Payment is handled securely by Stripe.')}
          </p>

          {free ? (
            <>
              {freeError && <Message kind="error">{freeError}</Message>}
              <button type="button" className="button block" onClick={signUpFree} disabled={busy}>
                {busy ? 'Signing you up…' : tickets === 1 ? 'Get a free ticket' : `Get ${tickets} free tickets`}
              </button>
            </>
          ) : (
            <>
              <StripeCardForm amount={due} onPaymentMethod={pay} />
              {showPaymentDetails && <TestCardHint />}
            </>
          )}
        </div>
      )}
    </div>
  )
}
