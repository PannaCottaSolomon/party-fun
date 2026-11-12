import { describe, expect, it } from 'vitest'
import type { Booking, EventInput, PartyEvent } from '../types'
import {
  availableSpots,
  bookingView,
  currentBookingStatus,
  heldBooking,
  heldBookings,
  hypePercent,
  MAX_TICKETS_PER_SIGNUP,
  maxTicketsFor,
  purchaseBlocker,
  validateEvent,
} from './eventRules'

const NOW = new Date(2026, 9, 1, 12, 0)
const minutes = (n: number) => new Date(NOW.getTime() + n * 60_000).toISOString()

function event(overrides: Partial<PartyEvent> = {}): PartyEvent {
  return {
    id: 'e1',
    name: 'Test Party',
    description: '',
    dateTime: '2026-10-11T20:00',
    location: 'SMU Connexion',
    ticketPrice: 10,
    maxCapacity: 20,
    minTickets: 40,
    hypeTickets: 26,
    occupiedSpots: 13,
    hypeDeadline: '2026-10-06T23:59',
    status: 'PENDING',
    organiserId: 'org',
    organiserName: 'Org',
    ...overrides,
  }
}

function booking(overrides: Partial<Booking> = {}): Booking {
  return {
    id: 'b1',
    eventId: 'e1',
    userId: 'u1',
    status: 'CONFIRMED',
    amount: 10,
    createdAt: minutes(-5),
    expiresAt: minutes(10),
    paymentId: 41,
    ...overrides,
  }
}

describe('hype meter', () => {
  it('is signups / minimum signups × 100', () => {
    expect(hypePercent(event({ minTickets: 40, hypeTickets: 26 }))).toBe(65)
  })

  it('is capped at 100%', () => {
    expect(hypePercent(event({ minTickets: 10, hypeTickets: 25 }))).toBe(100)
  })

  it('counts the places left', () => {
    expect(availableSpots(event({ maxCapacity: 20, occupiedSpots: 13 }))).toBe(7)
  })

  it('offers up to 10 tickets per signup, or fewer when fewer spots are left', () => {
    expect(maxTicketsFor(event({ maxCapacity: 100, occupiedSpots: 0 }))).toBe(MAX_TICKETS_PER_SIGNUP)
    expect(maxTicketsFor(event({ maxCapacity: 20, occupiedSpots: 17 }))).toBe(3)
    expect(maxTicketsFor(event({ maxCapacity: 20, occupiedSpots: 20 }))).toBe(0)
  })
})

describe('booking status', () => {
  it('shows a PENDING booking past its payment deadline as EXPIRED', () => {
    expect(currentBookingStatus(booking({ status: 'PENDING', expiresAt: minutes(-1) }), NOW)).toBe('EXPIRED')
    expect(currentBookingStatus(booking({ status: 'PENDING', expiresAt: minutes(1) }), NOW)).toBe('PENDING')
    expect(currentBookingStatus(booking({ status: 'CONFIRMED', expiresAt: minutes(-1) }), NOW)).toBe('CONFIRMED')
  })

  it('a CONFIRMED booking, or a PENDING one still within its window, holds the place', () => {
    const bookings = [booking({ id: 'old', status: 'PENDING', expiresAt: minutes(-1) })]
    expect(heldBooking(bookings, 'e1', 'u1', NOW)).toBeUndefined()
    expect(heldBooking([...bookings, booking({ id: 'new', status: 'PENDING' })], 'e1', 'u1', NOW)?.id).toBe('new')
    expect(heldBooking([booking({ status: 'CANCELLED' })], 'e1', 'u1', NOW)).toBeUndefined()
  })

  it("lists every ticket of a multi-ticket signup that still holds a place, oldest first", () => {
    const bookings = [
      booking({ id: '7', createdAt: minutes(-1) }),
      booking({ id: '6', status: 'PENDING', createdAt: minutes(-1) }),
      booking({ id: '2', status: 'EXPIRED', createdAt: minutes(-30) }),
      booking({ id: '9', eventId: 'other' }),
    ]
    expect(heldBookings(bookings, 'e1', 'u1', NOW).map((b) => b.id)).toEqual(['6', '7'])
    expect(heldBooking(bookings, 'e1', 'u1', NOW)?.id).toBe('6')
  })

  it('shows a booking of a deleted (cancelled) event as CANCELLED, with the event as it was', () => {
    const snapshot = event({ status: 'CONFIRMED' })
    const view = bookingView(booking(), undefined, snapshot, NOW)
    expect(view.status).toBe('CANCELLED')
    expect(view.event).toMatchObject({ name: 'Test Party', status: 'CANCELLED' })
    expect(bookingView(booking({ status: 'PENDING', expiresAt: minutes(-1) }), undefined, snapshot, NOW).status).toBe('EXPIRED')
  })

  it('uses the live event while it exists', () => {
    const live = event({ occupiedSpots: 14 })
    expect(bookingView(booking(), live, event(), NOW)).toMatchObject({ status: 'CONFIRMED', event: live })
  })
})

describe('signing up', () => {
  it('refuses cancelled events, own events, sold-out events and a second booking', () => {
    expect(purchaseBlocker(event({ status: 'CANCELLED' }), 'u1')).toMatch(/cancelled/)
    expect(purchaseBlocker(event(), 'org')).toMatch(/organise/)
    expect(purchaseBlocker(event({ occupiedSpots: 20 }), 'u1')).toMatch(/sold out/)
    expect(purchaseBlocker(event(), 'u1', [booking()])).toMatch(/already have tickets/)
    expect(purchaseBlocker(event(), 'u1', [booking({ status: 'EXPIRED' })])).toBeNull()
  })
})

describe('event form validation', () => {
  const valid: EventInput = {
    name: 'Party',
    description: '',
    date: '2026-10-20',
    time: '20:00',
    location: 'SMU Connexion',
    maxCapacity: 50,
    minTickets: 20,
    ticketPrice: 10,
    hypeDeadline: '2026-10-15T23:59',
  }

  it('accepts a valid event', () => {
    expect(validateEvent(valid, NOW)).toEqual({})
  })

  it('requires name, date, time, location and hype deadline', () => {
    const errors = validateEvent({ ...valid, name: ' ', date: '', time: '', location: '', hypeDeadline: '' }, NOW)
    expect(Object.keys(errors).sort()).toEqual(['date', 'hypeDeadline', 'location', 'name', 'time'])
  })

  it('checks capacity, minimum and price', () => {
    expect(validateEvent({ ...valid, maxCapacity: 0 }, NOW).maxCapacity).toBeDefined()
    expect(validateEvent({ ...valid, minTickets: 0 }, NOW).minTickets).toBeDefined()
    expect(validateEvent({ ...valid, minTickets: 60 }, NOW).minTickets).toMatch(/more than the maximum/)
  })

  it('allows a free event (price 0) and checks paid prices', () => {
    expect(validateEvent({ ...valid, ticketPrice: 0 }, NOW).ticketPrice).toBeUndefined()
    expect(validateEvent({ ...valid, ticketPrice: 0.5 }, NOW).ticketPrice).toBeUndefined()
    expect(validateEvent({ ...valid, ticketPrice: 12.34 }, NOW).ticketPrice).toBeUndefined()
    expect(validateEvent({ ...valid, ticketPrice: -1 }, NOW).ticketPrice).toMatch(/0 \(free\) or more/)
    expect(validateEvent({ ...valid, ticketPrice: NaN }, NOW).ticketPrice).toMatch(/0 \(free\) or more/)
    expect(validateEvent({ ...valid, ticketPrice: 0.2 }, NOW).ticketPrice).toMatch(/at least S\$0\.50/)
    expect(validateEvent({ ...valid, ticketPrice: 1.234 }, NOW).ticketPrice).toMatch(/2 decimal places/)
    expect(validateEvent({ ...valid, ticketPrice: 100_000_000 }, NOW).ticketPrice).toMatch(/more than/)
  })

  it('needs the hype deadline before the event', () => {
    expect(validateEvent({ ...valid, hypeDeadline: '2026-10-20T20:00' }, NOW).hypeDeadline).toMatch(/before the event/)
    expect(validateEvent({ ...valid, hypeDeadline: '2026-10-21T10:00' }, NOW).hypeDeadline).toMatch(/before the event/)
  })

  it('on edit, keeps capacity above taken spots and the price fixed once tickets are sold', () => {
    const existing = event({ occupiedSpots: 30, hypeTickets: 30, ticketPrice: 10 })
    expect(validateEvent({ ...valid, maxCapacity: 25 }, NOW, existing).maxCapacity).toMatch(/already taken/)
    expect(validateEvent({ ...valid, ticketPrice: 12 }, NOW, existing).ticketPrice).toMatch(/fixed/)
  })
})
