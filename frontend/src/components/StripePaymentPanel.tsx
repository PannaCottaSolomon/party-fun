import * as api from '../api/api'
import type { ServicePayment } from '../api/api'
import { money } from '../format'
import { useLoad } from '../hooks/useLoad'
import { Message } from './Message'

const LABELS: Record<ServicePayment['status'], string> = {
  PENDING: 'Waiting for the card',
  PAID: 'Paid',
  FAILED: 'Failed',
  REFUND_PENDING: 'Refund in progress',
  REFUNDED: 'Refunded',
  REFUND_FAILED: 'Refund failed (waiting for an admin)',
}

export function PaymentStatusTag({ status }: { status: ServicePayment['status'] }) {
  return <span className={`tag pay-${status.toLowerCase().replace('_', '-')}`}>{LABELS[status]}</span>
}

function when(value: string | null): string {
  return value ? new Date(value).toLocaleString('en-SG') : '–'
}

/** The live state of a booking's real Stripe sandbox payment, straight from the Payment Service. */
export function StripePaymentPanel({ paymentId, eventCancelled }: { paymentId: number; eventCancelled: boolean }) {
  const { data: payment, error, loading, reload } = useLoad(`payment:${paymentId}`, () => api.getPayment(paymentId))

  return (
    <section className="card">
      <h2>Stripe sandbox payment</h2>
      {error && <Message kind="error">{error}</Message>}
      {!payment && !error && <p className="muted">Loading…</p>}
      {payment && (
        <dl className="summary">
          <div>
            <dt>Status</dt>
            <dd>
              <PaymentStatusTag status={payment.status} />
            </dd>
          </div>
          <div>
            <dt>Amount</dt>
            <dd>{money(Number(payment.amount))}</dd>
          </div>
          <div>
            <dt>Payment ID</dt>
            <dd className="mono">{payment.paymentId}</dd>
          </div>
          <div>
            <dt>Stripe payment</dt>
            <dd className="mono">{payment.stripePaymentIntentId ?? '–'}</dd>
          </div>
          {payment.stripeRefundId && (
            <div>
              <dt>Stripe refund</dt>
              <dd className="mono">{payment.stripeRefundId}</dd>
            </div>
          )}
          <div>
            <dt>Paid at</dt>
            <dd>{when(payment.paidAt)}</dd>
          </div>
          {payment.refundedAt && (
            <div>
              <dt>Refunded at</dt>
              <dd>{when(payment.refundedAt)}</dd>
            </div>
          )}
        </dl>
      )}
      {payment && eventCancelled && (payment.status === 'PAID' || payment.status === 'REFUND_FAILED') && (
        <Message kind="warning">
          The event is cancelled but this payment isn&apos;t refunded yet. If its refund failed, an Admin retries it
          from the failed-refunds dashboard.
        </Message>
      )}
      <button type="button" className="button secondary" onClick={reload} disabled={loading}>
        Refresh
      </button>
    </section>
  )
}
