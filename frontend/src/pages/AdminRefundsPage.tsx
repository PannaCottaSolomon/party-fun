import { useState } from 'react'
import * as api from '../api/api'
import type { FailedRefund, Profile } from '../api/api'
import { ConfirmDialog } from '../components/ConfirmDialog'
import { Message } from '../components/Message'
import { money } from '../format'
import { useLoad } from '../hooks/useLoad'
import { explainRefundFailure } from '../payments/refundReason'
import { describeRetry, type ActionResult } from '../payments/retryResult'

function when(value: string | null): string {
  return value ? new Date(value).toLocaleString('en-SG') : '–'
}

/** Who the refund is owed to: their name and email when the account is known, else the services' numeric user id. */
function UserCell({ userId, account }: { userId: number; account?: Profile }) {
  if (!account) return <span className="mono">User #{userId}</span>
  return (
    <>
      <strong>{account.fullName || account.email}</strong>
      {account.fullName && <div className="small muted">{account.email}</div>}
      <div className="small muted mono">#{userId}</div>
    </>
  )
}

/** Why the refund failed, in plain words with what to do; the Payment Service's raw text is kept on hover. */
function ReasonCell({ raw }: { raw: string | null }) {
  const reason = explainRefundFailure(raw)
  if (!reason) return <>–</>
  return (
    <div title={raw ?? undefined}>
      <strong>{reason.title}</strong>
      {reason.detail && <div className="muted">{reason.detail}</div>}
    </div>
  )
}

/**
 * Admin dashboard for the refund dead-letter queue (payments.refunds.dlq). Shows how many failed refunds wait in the
 * queue and which payments they are, and retries them one at a time: Payment checks Stripe first (it never refunds
 * twice), refunds again if needed, and removes the payment's DLQ message only when the refund succeeded.
 * The frontend never talks to RabbitMQ; every call goes to the Payment Service's /admin/* through the API Gateway with
 * the Admin's own access token, which the Payment Service checks (a Cognito user in the admin group). The browser
 * never holds a key.
 */
export function AdminRefundsPage() {
  const [version, setVersion] = useState(0)
  const [pending, setPending] = useState<FailedRefund>()
  const [busy, setBusy] = useState(false)
  const [result, setResult] = useState<ActionResult>()

  const { data, error, loading } = useLoad(`admin-refunds:${version}`, async () => {
    // The accounts only put names to user ids: their lookup never fails the page (it falls back to the id).
    const [count, failed, accounts] = await Promise.all([
      api.getDlqCount(),
      api.listFailedRefunds(),
      api.accountsByServiceId(),
    ])
    return { count, failed, accounts }
  })

  async function retry() {
    if (!pending) return
    setBusy(true)
    try {
      setResult(describeRetry(await api.retryFailedRefund(pending.paymentId)))
    } catch (err) {
      setResult({ kind: 'error', text: err instanceof Error ? err.message : 'The retry failed.' })
    } finally {
      setBusy(false)
      setPending(undefined)
      setVersion((v) => v + 1)
    }
  }

  const count = data?.count
  const failed = data?.failed
  const accounts = data?.accounts

  return (
    <div className="page">
      <div className="page-head">
        <h1>Failed refunds</h1>
        <button type="button" className="button secondary" onClick={() => setVersion((v) => v + 1)} disabled={loading}>
          Refresh
        </button>
      </div>
      <p className="muted">
        Refunds that still failed after every attempt are kept in the refund dead-letter queue until an Admin retries
        them. A retry checks Stripe first, refunds again only if needed (Stripe sandbox, no real money), and removes the
        payment&apos;s message from the queue when the refund succeeds.
      </p>

      {result && <Message kind={result.kind}>{result.text}</Message>}
      {error && <Message kind="error">{error}</Message>}
      {!data && !error && <p className="muted">Loading…</p>}

      {count && (
        <section className="card">
          <h2>Refund dead-letter queue</h2>
          {count.total > 0 ? (
            <Message kind="warning">
              ⚠ Failed refunds: {count.total}. {count.total === 1 ? 'It needs' : 'They need'} an Admin to retry.
            </Message>
          ) : (
            <Message kind="success">No failed refunds require attention.</Message>
          )}
          <dl className="facts">
            <div>
              <dt>Queue</dt>
              <dd className="mono">{count.queue}</dd>
            </div>
            <div>
              <dt>Waiting</dt>
              <dd>{count.ready}</dd>
            </div>
            <div>
              <dt>Being processed</dt>
              <dd>{count.unacknowledged ?? '–'}</dd>
            </div>
            <div>
              <dt>Total</dt>
              <dd>{count.total}</dd>
            </div>
          </dl>
        </section>
      )}

      {failed && failed.failedRefunds.length > 0 && (
        <div className="card table-wrap">
          <h2>Payments with a failed refund ({failed.count})</h2>
          <table className="data-table">
            <thead>
              <tr>
                <th>Payment</th>
                <th>Booking</th>
                <th>Event</th>
                <th>User</th>
                <th>Amount</th>
                <th>Reason</th>
                <th>Attempts</th>
                <th>Failed at</th>
                <th>In DLQ</th>
                <th>Action</th>
              </tr>
            </thead>
            <tbody>
              {failed.failedRefunds.map((f) => (
                <tr key={f.paymentId}>
                  <td className="mono">{f.paymentId}</td>
                  <td className="mono">{f.bookingId}</td>
                  <td className="mono">{f.eventId}</td>
                  <td>
                    <UserCell userId={f.userId} account={accounts?.get(f.userId)} />
                  </td>
                  <td>{money(Number(f.amount))}</td>
                  <td className="small">
                    <ReasonCell raw={f.failureReason} />
                  </td>
                  <td>{f.refundAttempts}</td>
                  <td className="small">{when(f.failedAt)}</td>
                  <td>
                    {f.dlqMessages}
                    {f.dlqMessages === 0 && <div className="small muted">Recorded in MySQL only</div>}
                  </td>
                  <td>
                    <button
                      type="button"
                      className="button small-button"
                      disabled={busy || pending !== undefined}
                      onClick={() => setPending(f)}
                    >
                      Retry refund
                    </button>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}

      <ConfirmDialog
        open={pending !== undefined}
        title={`Retry the refund of payment ${pending?.paymentId}?`}
        confirmLabel="Retry refund"
        busy={busy}
        onConfirm={retry}
        onCancel={() => setPending(undefined)}
      >
        <p>
          The Payment Service checks Stripe first and refunds {pending ? money(Number(pending.amount)) : 'the payment'}{' '}
          only if no refund has gone through. On success the payment&apos;s message is removed from the dead-letter
          queue; if it still fails, the message stays.
        </p>
      </ConfirmDialog>
    </div>
  )
}
