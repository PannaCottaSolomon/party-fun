import { loadStripe, type Stripe } from '@stripe/stripe-js'
import { ApiError } from '../api/errors'
import { getStripeConfig } from '../api/paymentApi'

let stripePromise: Promise<Stripe | null> | null = null

/**
 * Loads Stripe.js once, with the publishable key the Payment Service hands out (GET /payments/config). Only sandbox
 * keys (pk_test_...) are used, so cards are test cards and no real money moves.
 */
export function getStripe(): Promise<Stripe | null> {
  stripePromise ??= getStripeConfig()
    .then(({ stripePublishableKey: key }) => {
      if (!key) {
        throw new ApiError(
          'The Payment Service has no Stripe publishable key. Set stripe_publishable_key (pk_test_...) in services/payment/dev.env and restart it.',
        )
      }
      if (!key.startsWith('pk_test_')) throw new ApiError('Only Stripe sandbox keys (pk_test_...) are allowed.')
      return loadStripe(key)
    })
    .catch((error: unknown) => {
      stripePromise = null // let the next attempt try again (e.g. once the Payment Service is up)
      throw error
    })
  return stripePromise
}
