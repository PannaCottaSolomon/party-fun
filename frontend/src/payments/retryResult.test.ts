import { describe, expect, it } from 'vitest'
import type { AdminRetryResult } from '../api/api'
import { describeRetry } from './retryResult'

const base: AdminRetryResult = {
  paymentId: 25,
  outcome: 'REFUNDED',
  paymentStatus: 'REFUNDED',
  stripeRefundId: 're_1',
  dlqMessagesFound: 1,
  dlqMessagesRemoved: 1,
  remainingInDlq: 0,
  message: 'Refunded',
}

describe('describeRetry', () => {
  it('reports a successful retry and the DLQ message it removed', () => {
    expect(describeRetry(base)).toEqual({
      kind: 'success',
      text: 'Payment 25 refunded (re_1). Removed 1 DLQ message; 0 left in the queue.',
    })
  })

  it('says the message stays in the queue when the refund still fails', () => {
    const result = describeRetry({
      ...base,
      outcome: 'FAILED',
      paymentStatus: 'REFUND_FAILED',
      stripeRefundId: null,
      dlqMessagesRemoved: 0,
      remainingInDlq: 1,
      message: 'charge_already_refunded',
    })
    expect(result.kind).toBe('error')
    expect(result.text).toContain('still failed: charge_already_refunded')
    expect(result.text).toContain('stays in the dead-letter queue (1 in total)')
  })
})
