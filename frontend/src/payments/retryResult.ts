import type { AdminRetryResult } from '../api/api'
import { plural } from '../format'

/** A banner or row message after an Admin action. */
export type ActionResult = { kind: 'success' | 'error' | 'info'; text: string }

/** Turns the Payment Service's answer into the banner shown after a retry. */
export function describeRetry(r: AdminRetryResult): ActionResult {
  const removed = `Removed ${plural(r.dlqMessagesRemoved, 'DLQ message')}; ${r.remainingInDlq} left in the queue.`
  switch (r.outcome) {
    case 'REFUNDED':
      return { kind: 'success', text: `Payment ${r.paymentId} refunded${r.stripeRefundId ? ` (${r.stripeRefundId})` : ''}. ${removed}` }
    case 'ALREADY_REFUNDED':
      return { kind: 'success', text: `Payment ${r.paymentId} was already refunded, nothing sent to Stripe. ${removed}` }
    case 'PENDING':
      return {
        kind: 'info',
        text: `Stripe accepted the refund of payment ${r.paymentId} and is still processing it; its webhook finishes it. ${removed}`,
      }
    case 'FAILED':
      return {
        kind: 'error',
        text: `Payment ${r.paymentId} still failed${r.message ? `: ${r.message}` : ''}. Its message stays in the dead-letter queue (${r.remainingInDlq} in total).`,
      }
  }
}
