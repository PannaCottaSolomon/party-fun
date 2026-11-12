import { Elements, PaymentElement, useElements, useStripe } from '@stripe/react-stripe-js'
import { useState, type FormEvent } from 'react'
import { useAuth } from '../auth/authContext'
import { money } from '../format'
import { useLoad } from '../hooks/useLoad'
import { getStripe } from '../payments/stripe'
import { Message } from './Message'

interface Props {
  amount: number
  submitLabel?: string
  /**
   * Called with the Stripe PaymentMethod (`pm_...`) once the card details are complete; the Event Service charges it.
   * A thrown error is shown under the form, so the user can try again or with another card.
   */
  onPaymentMethod: (paymentMethodId: string) => Promise<void>
}

/**
 * Stripe's own card form (Payment Element). Card details go straight from the browser to Stripe, which turns them
 * into a PaymentMethod id; neither this app nor the services ever see the card. The Event Service then charges that
 * PaymentMethod through the Payment Service when the user signs up (or retries a declined payment).
 */
export function StripeCardForm({ amount, submitLabel, onPaymentMethod }: Props) {
  const { data: stripe, error } = useLoad('stripe', getStripe)
  if (error) return <Message kind="error">{error}</Message>
  if (!stripe) return <p className="muted">Loading the card form…</p>

  return (
    <Elements
      stripe={stripe}
      options={{
        mode: 'payment',
        amount: Math.round(amount * 100),
        currency: 'sgd',
        paymentMethodCreation: 'manual',
        allowedPaymentMethodTypes: ['card'],
        // Stripe's dark theme, to match the site (which is always dark).
        appearance: { theme: 'night' },
      }}
    >
      <CardForm amount={amount} submitLabel={submitLabel} onPaymentMethod={onPaymentMethod} />
    </Elements>
  )
}

function CardForm({ amount, submitLabel, onPaymentMethod }: Props) {
  const email = useAuth().user?.email || undefined
  const stripe = useStripe()
  const elements = useElements()
  const [paying, setPaying] = useState(false)
  const [error, setError] = useState<string>()

  async function submit(e: FormEvent) {
    e.preventDefault()
    if (!stripe || !elements) return
    setPaying(true)
    setError(undefined)
    try {
      const { error: invalid } = await elements.submit()
      if (invalid) throw new Error(invalid.message ?? 'Check your card details.')
      const { error: failed, paymentMethod } = await stripe.createPaymentMethod({ elements })
      if (failed || !paymentMethod) throw new Error(failed?.message ?? 'Stripe could not read this card. Try another card.')
      await onPaymentMethod(paymentMethod.id)
    } catch (err) {
      setError(err instanceof Error ? err.message : 'The payment did not go through. You have not been charged.')
    } finally {
      setPaying(false)
    }
  }

  return (
    <form onSubmit={submit} className="stripe-form">
      <PaymentElement
        options={{
          layout: 'tabs',
          // Prefill the email (e.g. Link's "save my information" box) with the logged-in user's profile email
          defaultValues: email ? { billingDetails: { email } } : undefined,
        }}
      />
      {error && <Message kind="error">{error}</Message>}
      <button type="submit" className="button block stripe" disabled={!stripe || !elements || paying}>
        {paying ? 'Processing payment…' : (submitLabel ?? `Pay ${money(amount)}`)}
      </button>
    </form>
  )
}

/** Stripe sandbox test cards: any future expiry date, any CVC, any postal code. */
export function TestCardHint() {
  return (
    <details className="test-cards">
      <summary>Stripe sandbox test cards (no real money)</summary>
      <p className="muted small">Use any future expiry date, any 3-digit CVC and any postal code.</p>
      <table>
        <tbody>
          <tr>
            <td className="mono">4242 4242 4242 4242</td>
            <td>Payment succeeds</td>
          </tr>
          <tr>
            <td className="mono">4000 0000 0000 0002</td>
            <td>Card declined (your place is held for 15 minutes: try another card)</td>
          </tr>
          <tr>
            <td className="mono">4000 0000 0000 9995</td>
            <td>Declined: insufficient funds</td>
          </tr>
          <tr>
            <td className="mono">4000 0000 0000 5126</td>
            <td>Payment succeeds; a later refund fails (dead-letter queue)</td>
          </tr>
        </tbody>
      </table>
    </details>
  )
}
