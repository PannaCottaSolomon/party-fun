/**
 * Turns the Payment Service's technical refund failure text into something an Admin can act on. The service keeps
 * only Stripe's error code and HTTP status (never the request or keys), e.g.
 * "Stripe refund request failed (code=null, status=400)", so the explanation is worked out from those.
 */
export interface RefundReason {
  /** One-line summary, e.g. "Already refunded in Stripe". */
  title: string
  /** What it means and what to do about it. */
  detail?: string
}

const RETRY_CHECKS_STRIPE = 'A retry checks Stripe first, so no money is ever refunded twice.'

const BY_CODE: Record<string, RefundReason> = {
  charge_already_refunded: {
    title: 'Already refunded in Stripe',
    detail: `Stripe says this payment was already refunded (for example directly in the Stripe dashboard). Retry to record it as refunded. ${RETRY_CHECKS_STRIPE}`,
  },
  resource_missing: {
    title: "Stripe can't find this payment",
    detail:
      "The payment doesn't exist in the Stripe account the Payment Service uses (for example it was made with other test or live keys). Check it in the Stripe dashboard before retrying.",
  },
  charge_disputed: {
    title: 'The customer disputed this charge',
    detail: "Stripe doesn't allow a refund while a dispute is open. Resolve the dispute in the Stripe dashboard.",
  },
  amount_too_large: {
    title: 'Refund is larger than what is left on the charge',
    detail: 'Part of this payment may already have been refunded. Check its refunds in the Stripe dashboard.',
  },
  balance_insufficient: {
    title: 'Not enough balance in the Stripe account',
    detail: 'Stripe needs enough available balance to pay the refund. Top up the balance, then retry.',
  },
  rate_limit: {
    title: 'Stripe was busy (rate limited)',
    detail: 'This is temporary. Retry in a moment.',
  },
}

function byStatus(status: number | null): RefundReason {
  if (status === null) {
    return { title: "Couldn't reach Stripe", detail: 'A network problem stopped the request. This is usually temporary: retry.' }
  }
  if (status === 429) return BY_CODE.rate_limit
  if (status >= 500) return { title: 'Stripe had a server error', detail: 'This is temporary on Stripe’s side. Retry in a moment.' }
  if (status === 401) {
    return { title: 'Stripe rejected the API key', detail: "The Payment Service's stripe_secret_key is wrong or revoked. Fix it, then retry." }
  }
  if (status === 403) {
    return { title: "The Stripe key isn't allowed to refund", detail: 'Use a Stripe key with permission to create refunds, then retry.' }
  }
  if (status === 404) return BY_CODE.resource_missing
  return {
    title: 'Stripe rejected the refund request',
    detail: `Most often this payment was already refunded directly in Stripe. ${RETRY_CHECKS_STRIPE} If it still fails, check the payment in the Stripe dashboard.`,
  }
}

export function explainRefundFailure(raw: string | null | undefined): RefundReason | undefined {
  const text = raw?.trim()
  if (!text) return undefined

  // "Stripe refund request failed (code=..., status=...)" / "Stripe refund lookup failed (code=..., status=...)"
  const call = /Stripe refund (request|lookup) failed \(code=([^,)]*), status=([^)]*)\)/i.exec(text)
  if (call) {
    const code = call[2].trim()
    const status = /^\d+$/.test(call[3].trim()) ? Number(call[3].trim()) : null
    const reason = (code && code !== 'null' && BY_CODE[code]) || byStatus(status)
    return call[1].toLowerCase() === 'lookup' ? { ...reason, title: `Couldn't check Stripe: ${reason.title.charAt(0).toLowerCase()}${reason.title.slice(1)}` } : reason
  }

  // "Stripe refund re_123 is failed" / "... is canceled": Stripe took the refund, then it didn't go through
  const settled = /Stripe refund (\S+) is (failed|canceled)/i.exec(text)
  if (settled) {
    return settled[2].toLowerCase() === 'failed'
      ? {
          title: 'Stripe accepted the refund, but it then failed',
          detail: `The card may no longer accept refunds (for example it was closed). Check refund ${settled[1]} in the Stripe dashboard; Stripe often returns the money another way.`,
        }
      : { title: 'The refund was cancelled in Stripe', detail: `Refund ${settled[1]} was cancelled. Retry to refund again.` }
  }

  if (/no Stripe PaymentIntent/i.test(text)) {
    return {
      title: 'No Stripe payment to refund',
      detail: "This payment never reached Stripe, so there's nothing to refund there. Check whether the customer was actually charged.",
    }
  }
  if (/Stripe is not configured/i.test(text)) {
    return {
      title: "Stripe isn't set up on the Payment Service",
      detail: 'Its stripe_secret_key is empty. Set it, then retry.',
    }
  }

  // Anything else (e.g. a reason Stripe sent with a webhook) is shown as it is.
  return { title: text }
}
