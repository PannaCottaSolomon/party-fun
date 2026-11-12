import { describe, expect, it } from 'vitest'
import { explainRefundFailure } from './refundReason'

describe('refund failure reasons for the Admin', () => {
  it('explains a 400 without a Stripe code (usually already refunded in Stripe)', () => {
    const reason = explainRefundFailure('Stripe refund request failed (code=null, status=400)')
    expect(reason?.title).toBe('Stripe rejected the refund request')
    expect(reason?.detail).toContain('already refunded')
  })

  it('uses the Stripe error code when there is one', () => {
    expect(explainRefundFailure('Stripe refund request failed (code=charge_already_refunded, status=400)')?.title)
      .toBe('Already refunded in Stripe')
    expect(explainRefundFailure('Stripe refund request failed (code=resource_missing, status=404)')?.title)
      .toBe("Stripe can't find this payment")
    expect(explainRefundFailure('Stripe refund request failed (code=charge_disputed, status=400)')?.title)
      .toBe('The customer disputed this charge')
  })

  it('tells temporary problems apart from configuration ones', () => {
    expect(explainRefundFailure('Stripe refund request failed (code=null, status=null)')?.title).toBe("Couldn't reach Stripe")
    expect(explainRefundFailure('Stripe refund request failed (code=null, status=503)')?.title).toBe('Stripe had a server error')
    expect(explainRefundFailure('Stripe refund request failed (code=rate_limit, status=429)')?.title).toBe('Stripe was busy (rate limited)')
    expect(explainRefundFailure('Stripe refund request failed (code=null, status=401)')?.title).toBe('Stripe rejected the API key')
  })

  it('says when even checking Stripe failed', () => {
    expect(explainRefundFailure('Stripe refund lookup failed (code=null, status=503)')?.title)
      .toBe("Couldn't check Stripe: stripe had a server error")
  })

  it('explains refunds Stripe took but that then failed or were cancelled, and missing setup', () => {
    expect(explainRefundFailure('Stripe refund re_123 is failed')?.title).toBe('Stripe accepted the refund, but it then failed')
    expect(explainRefundFailure('Stripe refund re_123 is canceled')?.title).toBe('The refund was cancelled in Stripe')
    expect(explainRefundFailure('Payment has no Stripe PaymentIntent to refund')?.title).toBe('No Stripe payment to refund')
    expect(explainRefundFailure('Stripe is not configured (stripe_secret_key is empty)')?.title)
      .toBe("Stripe isn't set up on the Payment Service")
  })

  it('shows any other reason as it is, and nothing for no reason', () => {
    expect(explainRefundFailure('expired_or_canceled_card')).toEqual({ title: 'expired_or_canceled_card' })
    expect(explainRefundFailure(null)).toBeUndefined()
    expect(explainRefundFailure('  ')).toBeUndefined()
  })
})
