import { useState } from 'react'
import { Link, useParams } from 'react-router-dom'
import * as api from '../api/api'
import { ApiError } from '../api/api'
import { useUser } from '../auth/authContext'
import { CancelledNotice } from '../components/EventFacts'
import { Message } from '../components/Message'
import { BookingStatusBadge, StatusBadge } from '../components/StatusBadge'
import { StripeCardForm, TestCardHint } from '../components/StripeCardForm'
import { StripePaymentPanel } from '../components/StripePaymentPanel'
import { orderPaymentIds, orderStatus, orderTotal } from '../domain/orders'
import { formatDateTime, money } from '../format'
import { useLoad } from '../hooks/useLoad'
import { unpaidTicketsMessage } from '../payments/bookingPayment'
import { useShowPaymentDetails } from '../settings/paymentDetails'

function when(iso: string | undefined): string {
  return iso ? new Date(iso).toLocaleString('en-SG') : '–'
}

/**
 * View 7: Booking Details, for one booking: every ticket of one signup (an order), with the order's total and one
 * receipt line per ticket. An order whose card was declined stays PENDING with its places held for 15 minutes; it can
 * be paid here with another card until then, in one charge for all its unpaid tickets.
 */
export function BookingDetailsPage() {
  const { bookingId = '' } = useParams()
  const user = useUser()
  const { data: tickets, error, reload } = useLoad(`order:${bookingId}`, () => api.getOrder(user, bookingId))
  const [paid, setPaid] = useState(false)
  const showPaymentDetails = useShowPaymentDetails()

  if (error) return <Message kind="error">{error}</Message>
  if (!tickets) return <p className="muted">Loading…</p>

  const [first] = tickets
  const { event } = first
  const status = orderStatus(tickets)
  const total = orderTotal(tickets)
  const unpaid = tickets.filter((t) => t.status === 'PENDING' && t.amount > 0)
  const unpaidTotal = orderTotal(unpaid)
  const many = tickets.length > 1
  const confirmedAt = tickets.map((t) => t.confirmedAt).find(Boolean)
  const failureReason = tickets.map((t) => t.failureReason).find(Boolean)
  const payBefore = unpaid.map((t) => t.expiresAt).sort()[0]

  /** One charge pays every unpaid ticket of the order (the Event Service settles an order as a whole). */
  async function pay(paymentMethodId: string) {
    const { results, error: failure } = await api.retryPayments(unpaid, paymentMethodId)
    reload()
    if (failure) throw failure
    const stillUnpaid = results.filter((t) => t.status === 'PENDING')
    if (stillUnpaid.length === 0) setPaid(true)
    else throw new ApiError(unpaidTicketsMessage(stillUnpaid, results.length))
  }

  return (
    <article className="detail narrow">
      <Link to="/bookings" className="back">
        ← My Bookings
      </Link>
      <header className="detail-head">
        <StatusBadge status={event.status} />
        <h1>{event.name}</h1>
        <p className="meta">
          {formatDateTime(event.dateTime)} · {event.location}
        </p>
        {event.status !== 'CANCELLED' && <Link to={`/events/${event.id}`}>View event</Link>}
      </header>

      {paid && (
        <Message kind="success">
          Payment successful. {many ? `All ${tickets.length} tickets are` : 'Your ticket is'} confirmed.
        </Message>
      )}
      {event.status === 'CANCELLED' && (
        <Message kind="warning">
          <CancelledNotice event={event} />
        </Message>
      )}
      {status === 'EXPIRED' && (
        <Message kind="warning">
          {many ? "These tickets weren't" : "This booking wasn't"} paid in time, so{' '}
          {many ? 'their places were' : 'its place was'} released.
        </Message>
      )}

      <section className="card">
        <h2>{many ? 'Tickets' : 'Ticket'} &amp; payment</h2>
        <dl className="summary">
          <div>
            <dt>Status</dt>
            <dd>
              <BookingStatusBadge status={status} />
            </dd>
          </div>
          <div>
            <dt>Tickets</dt>
            <dd>
              {tickets.length}
              {many && total > 0 && <span className="muted"> × {money(first.amount)}</span>}
            </dd>
          </div>
          <div className="total">
            <dt>{status === 'CONFIRMED' ? 'Total paid' : 'Total'}</dt>
            <dd>
              <strong>{total === 0 ? 'Free' : money(total)}</strong>
            </dd>
          </div>
          <div>
            <dt>Booked at</dt>
            <dd>{when(first.createdAt)}</dd>
          </div>
          {payBefore && (
            <div>
              <dt>Pay before</dt>
              <dd>{when(payBefore)}</dd>
            </div>
          )}
          {confirmedAt && (
            <div>
              <dt>Confirmed at</dt>
              <dd>{when(confirmedAt)}</dd>
            </div>
          )}
          {failureReason && status !== 'CONFIRMED' && (
            <div>
              <dt>Last payment attempt</dt>
              <dd>{failureReason}</dd>
            </div>
          )}
          {!many && (
            <div>
              <dt>Booking reference</dt>
              <dd className="mono">{first.id}</dd>
            </div>
          )}
        </dl>
      </section>

      {many && (
        <section className="card">
          <h2>Your {tickets.length} tickets</h2>
          <ol className="ticket-list">
            {tickets.map((ticket, i) => (
              <li key={ticket.id} className="ticket-row">
                <span className="ticket-number">
                  Ticket {i + 1} of {tickets.length}
                </span>
                <span className="mono ticket-ref">#{ticket.id}</span>
                <BookingStatusBadge status={ticket.status} />
                <span className="ticket-amount">{ticket.amount === 0 ? 'Free' : money(ticket.amount)}</span>
              </li>
            ))}
          </ol>
        </section>
      )}

      {unpaid.length > 0 && (
        <section className="card">
          <h2>Complete your payment</h2>
          <p className="muted">
            {unpaid.length > 1 ? 'Their places are' : 'Your place is'} held until {when(payBefore)}. Pay with another
            card to confirm {unpaid.length > 1 ? `all ${unpaid.length} tickets in one charge` : 'your ticket'}.
          </p>
          <StripeCardForm amount={unpaidTotal} onPaymentMethod={pay} />
          {showPaymentDetails && <TestCardHint />}
        </section>
      )}

      {showPaymentDetails &&
        orderPaymentIds(tickets).map((paymentId) => (
          <StripePaymentPanel key={paymentId} paymentId={paymentId} eventCancelled={event.status === 'CANCELLED'} />
        ))}
    </article>
  )
}
