import { describe, expect, it } from 'vitest'
import type { BookingStatus, BookingView, EventStatus, PartyEvent } from '../types'
import { bookingStats, organiserStats } from './profileStats'

const NOW = new Date('2026-11-04T12:00:00')

function event(overrides: Partial<PartyEvent> = {}): PartyEvent {
  return {
    id: '1',
    name: 'Party',
    description: '',
    dateTime: '2026-12-01T20:00',
    location: 'SMU',
    ticketPrice: 10,
    maxCapacity: 100,
    minTickets: 10,
    hypeTickets: 0,
    occupiedSpots: 0,
    hypeDeadline: '2026-11-20T12:00',
    status: 'PENDING',
    organiserId: '3',
    organiserName: 'Organiser #3',
    ...overrides,
  }
}

function booking(id: string, status: BookingStatus, amount: number, dateTime: string, eventStatus: EventStatus = 'PENDING'): BookingView {
  return {
    id,
    eventId: id,
    userId: '7',
    status,
    amount,
    createdAt: '2026-11-01T10:00:00Z',
    expiresAt: '2026-11-01T10:15:00Z',
    event: event({ id, dateTime, status: eventStatus }),
  }
}

describe('bookingStats', () => {
  it('counts paid tickets and what they cost, leaving out unpaid, expired and cancelled bookings', () => {
    const stats = bookingStats(
      [
        booking('a', 'CONFIRMED', 15, '2026-12-01T20:00'),
        booking('b', 'CONFIRMED', 0, '2026-12-05T20:00'),
        booking('c', 'PENDING', 20, '2026-12-03T20:00'),
        booking('d', 'EXPIRED', 30, '2026-12-04T20:00'),
        booking('e', 'CANCELLED', 40, '2026-12-06T20:00', 'CANCELLED'),
      ],
      NOW,
    )
    expect(stats.tickets).toBe(2)
    expect(stats.spent).toBe(15)
  })

  it('treats paid and awaiting-payment bookings for future, uncancelled events as upcoming, soonest first', () => {
    const stats = bookingStats(
      [
        booking('later', 'CONFIRMED', 10, '2026-12-20T20:00'),
        booking('past', 'CONFIRMED', 10, '2026-10-01T20:00'),
        booking('soon', 'PENDING', 10, '2026-11-10T20:00'),
        booking('cancelled', 'CONFIRMED', 10, '2026-11-08T20:00', 'CANCELLED'),
      ],
      NOW,
    )
    expect(stats.upcoming).toBe(2)
    expect(stats.next?.id).toBe('soon')
  })

  it('has nothing next when there are no bookings', () => {
    expect(bookingStats([], NOW)).toEqual({ tickets: 0, upcoming: 0, spent: 0, next: undefined })
  })
})

describe('organiserStats', () => {
  it('counts every event, but tickets sold and hype reached only for events still on', () => {
    const stats = organiserStats([
      event({ id: '1', occupiedSpots: 12, hypeTickets: 12, minTickets: 10 }),
      event({ id: '2', occupiedSpots: 4, hypeTickets: 4, minTickets: 10 }),
      event({ id: '3', occupiedSpots: 50, hypeTickets: 50, minTickets: 10, status: 'CANCELLED' }),
    ])
    expect(stats).toEqual({ events: 3, ticketsSold: 16, hypeReached: 1 })
  })
})
