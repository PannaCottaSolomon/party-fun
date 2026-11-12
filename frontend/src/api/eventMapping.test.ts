import { describe, expect, it } from 'vitest'
import type { EventInput, User } from '../types'
import { localToUtc, toBooking, toCreateRequest, toPartyEvent, toUpdateRequest, utcToLocal } from './eventMapping'
import type { BookingDto, EventDto } from './eventsApi'

const organiser: User = { id: '7', username: 'olivia', email: 'o@x.sg', role: 'ORGANISER' }

function dto(overrides: Partial<EventDto> = {}): EventDto {
  return {
    id: 12,
    title: 'CS302 Party',
    description: null,
    organiserId: 7,
    location: 'SCIS 1',
    startDateTime: '2026-11-20T11:00:00',
    endDateTime: null,
    capacity: 90,
    maxCapacity: 100,
    minSignUps: 20,
    expiresAt: '2026-11-13T15:59:00',
    ticketPrice: 15,
    currency: 'SGD',
    status: 'ACTIVE',
    createdAt: '2026-10-25T04:49:20.123456',
    updatedAt: null,
    ...overrides,
  }
}

const input: EventInput = {
  name: ' CS302 Party ',
  description: '',
  date: '2026-11-20',
  time: '19:00',
  location: 'SCIS 1',
  maxCapacity: 100,
  minTickets: 20,
  ticketPrice: 15,
  hypeDeadline: '2026-11-13T23:59',
}

describe('date-times', () => {
  it('converts between local form values and the UTC the service stores', () => {
    const utc = localToUtc('2026-11-20T19:00')
    expect(utc).toMatch(/^\d{4}-\d\d-\d\dT\d\d:\d\d:00$/)
    expect(utc).toBe(new Date(2026, 10, 20, 19, 0).toISOString().slice(0, 19))
    expect(utcToLocal(utc)).toBe('2026-11-20T19:00')
  })

  it('reads microseconds and missing values', () => {
    expect(utcToLocal('2026-10-25T04:49:20.123456')).toBe(utcToLocal('2026-10-25T04:49:20'))
    expect(utcToLocal(null)).toBe('')
  })
})

describe('events from the Event Service', () => {
  it('counts signups as maxCapacity − capacity and names the logged-in organiser', () => {
    const event = toPartyEvent(dto(), organiser)
    expect(event).toMatchObject({
      id: '12',
      name: 'CS302 Party',
      description: '',
      maxCapacity: 100,
      occupiedSpots: 10,
      hypeTickets: 10,
      minTickets: 20,
      ticketPrice: 15,
      organiserId: '7',
      organiserName: 'olivia',
      status: 'PENDING',
    })
    expect(event.dateTime).toBe(utcToLocal('2026-11-20T11:00:00'))
    expect(toPartyEvent(dto(), { ...organiser, id: '8' }).organiserName).toBe('Organiser #7')
  })

  it('is PENDING while ACTIVE below its minimum signups, and CONFIRMED once the hype meter is full', () => {
    expect(toPartyEvent(dto({ capacity: 81 })).status).toBe('PENDING')
    expect(toPartyEvent(dto({ capacity: 80 })).status).toBe('CONFIRMED')
    expect(toPartyEvent(dto({ capacity: 70 })).status).toBe('CONFIRMED')
    expect(toPartyEvent(dto({ minSignUps: null })).status).toBe('CONFIRMED')
    expect(toPartyEvent(dto({ status: 'confirmed' })).status).toBe('CONFIRMED')
    expect(toPartyEvent(dto({ status: 'CLOSED' })).status).toBe('CANCELLED')
  })

  it('defaults maxCapacity to capacity and a missing price to free', () => {
    expect(toPartyEvent(dto({ maxCapacity: null, capacity: 50, ticketPrice: null }))).toMatchObject({
      maxCapacity: 50,
      occupiedSpots: 0,
      ticketPrice: 0,
    })
  })
})

describe('requests to the Event Service', () => {
  it('creates an event with every place available, in UTC', () => {
    expect(toCreateRequest(input, organiser)).toEqual({
      title: 'CS302 Party',
      description: '',
      organiserId: 7,
      location: 'SCIS 1',
      startDateTime: localToUtc('2026-11-20T19:00'),
      capacity: 100,
      maxCapacity: 100,
      minSignUps: 20,
      expiresAt: localToUtc('2026-11-13T23:59'),
      ticketPrice: 15,
      currency: 'SGD',
    })
  })

  it('patches only what changed, keeping the places already taken', () => {
    const existing = toPartyEvent(dto({ startDateTime: localToUtc('2026-11-20T19:00'), expiresAt: localToUtc('2026-11-13T23:59') }), organiser)
    expect(toUpdateRequest(input, existing)).toEqual({})
    expect(toUpdateRequest({ ...input, maxCapacity: 120, location: 'SOE' }, existing)).toEqual({
      location: 'SOE',
      maxCapacity: 120,
      capacity: 110,
    })
    expect(toUpdateRequest({ ...input, time: '20:00' }, existing)).toEqual({ startDateTime: localToUtc('2026-11-20T20:00') })
  })
})

describe('bookings from the Event Service', () => {
  it('keeps the status, amount, deadline and the payment id', () => {
    const booking: BookingDto = {
      id: 5,
      userId: 42,
      eventId: 12,
      totalAmount: 15,
      currency: 'SGD',
      status: 'PENDING',
      createdAt: '2026-10-25T04:00:00.5',
      expiresAt: '2026-10-25T04:15:00',
      confirmedAt: null,
      failedAt: '2026-10-25T04:00:01',
      paymentBookingId: 9,
      paymentReference: null,
      failureReason: 'Card declined (generic_decline)',
    }
    expect(toBooking(booking)).toEqual({
      id: '5',
      eventId: '12',
      userId: '42',
      status: 'PENDING',
      amount: 15,
      createdAt: '2026-10-25T04:00:00.500Z',
      expiresAt: '2026-10-25T04:15:00.000Z',
      confirmedAt: undefined,
      failureReason: 'Card declined (generic_decline)',
      paymentId: undefined,
    })
    expect(toBooking({ ...booking, status: 'CONFIRMED', paymentReference: '31' }).paymentId).toBe(31)
  })
})
