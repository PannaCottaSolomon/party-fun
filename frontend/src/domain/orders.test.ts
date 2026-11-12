import { describe, expect, it } from 'vitest'
import type { Booking } from '../types'
import { groupOrders, orderPaymentIds, orderStatus, orderTotal, ticketsOfOrder } from './orders'

function ticket(id: string, extra: Partial<Booking> = {}): Booking {
  return {
    id,
    eventId: '12',
    userId: '7',
    orderId: 'order-1',
    status: 'CONFIRMED',
    amount: 15,
    createdAt: '2026-11-05T10:00:00Z',
    expiresAt: '2026-11-05T10:15:00Z',
    paymentId: 31,
    ...extra,
  }
}

describe('orders', () => {
  it('puts the tickets of one signup into one order, sorted by id, keeping other orders apart', () => {
    const orders = groupOrders([
      ticket('12'),
      ticket('5'),
      ticket('40', { orderId: 'order-2', eventId: '13' }),
      ticket('6'),
    ])
    expect(orders.map((o) => o.map((t) => t.id))).toEqual([['5', '6', '12'], ['40']])
  })

  it('groups bookings without an order id by event and creation time', () => {
    const legacy = { orderId: undefined }
    const orders = groupOrders([
      ticket('1', legacy),
      ticket('2', legacy),
      ticket('3', { ...legacy, createdAt: '2026-11-06T09:00:00Z' }),
    ])
    expect(orders.map((o) => o.length)).toEqual([2, 1])
  })

  it('sorts tickets recovered from a payment numerically', () => {
    const recovered = ['payment-16-10', 'payment-16-2', 'payment-16-1'].map((id) => ticket(id, { orderId: 'payment-16' }))
    expect(groupOrders(recovered)[0].map((t) => t.id)).toEqual(['payment-16-1', 'payment-16-2', 'payment-16-10'])
  })

  it('finds the other tickets of a booking’s order', () => {
    const all = [ticket('5'), ticket('6'), ticket('40', { orderId: 'order-2' })]
    expect(ticketsOfOrder(all[1], all).map((t) => t.id)).toEqual(['5', '6'])
  })

  it('adds up the order total and lists its payment once', () => {
    const tickets = Array.from({ length: 10 }, (_, i) => ticket(String(i + 1), { amount: 12.5 }))
    expect(orderTotal(tickets)).toBe(125)
    expect(orderPaymentIds(tickets)).toEqual([31])
  })

  it('shows the status the user must act on when the tickets disagree', () => {
    expect(orderStatus([ticket('1'), ticket('2')])).toBe('CONFIRMED')
    expect(orderStatus([ticket('1'), ticket('2', { status: 'PENDING' })])).toBe('PENDING')
    expect(orderStatus([ticket('1', { status: 'EXPIRED' }), ticket('2', { status: 'CANCELLED' })])).toBe('EXPIRED')
  })
})
