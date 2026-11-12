import { toEventDateTime } from '../domain/eventRules'
import type { Booking, EventInput, EventStatus, PartyEvent, User } from '../types'
import type { BookingDto, EventDto, EventRequest } from './eventsApi'

/**
 * Translation between the Event Service's JSON and the views' types. The service stores date-times as UTC without a
 * zone ("2026-11-20T11:00:00"); the views use the browser's local time ("2026-11-20T19:00").
 */

/** A UTC date-time from the service (no zone, maybe with microseconds) as a Date. */
function parseUtc(value: string): Date {
  const millis = value.replace(/(\.\d{3})\d+/, '$1')
  return new Date(/(Z|[+-]\d\d:?\d\d)$/i.test(millis) ? millis : `${millis}Z`)
}

/** UTC from the service → local "yyyy-mm-ddTHH:mm" for the views; '' when there is none. */
export function utcToLocal(value: string | null): string {
  if (!value) return ''
  const d = parseUtc(value)
  const pad = (n: number) => String(n).padStart(2, '0')
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}T${pad(d.getHours())}:${pad(d.getMinutes())}`
}

/** Local "yyyy-mm-ddTHH:mm" from a form → UTC "yyyy-mm-ddTHH:mm:ss" for the service. */
export function localToUtc(local: string): string {
  return new Date(local).toISOString().slice(0, 19)
}

/** UTC from the service → ISO instant ("...Z"). */
export function utcToInstant(value: string): string {
  return parseUtc(value).toISOString()
}

/**
 * The service's status as the views show it. ACTIVE (open for signups) is PENDING until its signups reach the
 * minimum (a full hype meter), then CONFIRMED; the service keeps it ACTIVE, so signups stay open. A status the service
 * sets to CONFIRMED is shown as CONFIRMED; any other status blocks signups and is shown as CANCELLED.
 */
function toEventStatus(status: string | null, signups: number, minTickets: number): EventStatus {
  switch (status?.toUpperCase()) {
    case 'ACTIVE':
      return signups >= minTickets ? 'CONFIRMED' : 'PENDING'
    case 'CONFIRMED':
      return 'CONFIRMED'
    default:
      return 'CANCELLED'
  }
}

/**
 * The service's event as the views see it. Signups so far = maxCapacity − capacity; they drive the hype meter.
 * `viewer` names the organiser when it's the logged-in user (the service only has their id).
 */
export function toPartyEvent(dto: EventDto, viewer?: User | null): PartyEvent {
  const maxCapacity = dto.maxCapacity ?? dto.capacity
  const signups = Math.max(0, maxCapacity - dto.capacity)
  const minTickets = dto.minSignUps ?? 0
  const organiserId = dto.organiserId == null ? '' : String(dto.organiserId)
  return {
    id: String(dto.id),
    name: dto.title,
    description: dto.description ?? '',
    dateTime: utcToLocal(dto.startDateTime),
    location: dto.location ?? '',
    ticketPrice: Number(dto.ticketPrice ?? 0),
    maxCapacity,
    minTickets,
    hypeTickets: signups,
    occupiedSpots: signups,
    hypeDeadline: utcToLocal(dto.expiresAt),
    status: toEventStatus(dto.status, signups, minTickets),
    organiserId,
    organiserName: viewer && viewer.id === organiserId ? viewer.username : organiserId ? `Organiser #${organiserId}` : '–',
  }
}

/** The Create Event form as a `POST /events` body. Every place starts available. */
export function toCreateRequest(input: EventInput, organiser: User): EventRequest {
  return {
    title: input.name.trim(),
    description: input.description.trim(),
    organiserId: Number(organiser.id),
    location: input.location.trim(),
    startDateTime: localToUtc(toEventDateTime(input)),
    capacity: input.maxCapacity,
    maxCapacity: input.maxCapacity,
    minSignUps: input.minTickets,
    expiresAt: localToUtc(input.hypeDeadline),
    ticketPrice: input.ticketPrice,
    currency: 'SGD',
  }
}

/**
 * The Edit Event form as a `PATCH /events/{id}` body with only the changed fields. A new maximum capacity also sets
 * the places still available (the new maximum minus the places already taken).
 */
export function toUpdateRequest(input: EventInput, existing: PartyEvent): EventRequest {
  const changes: EventRequest = {}
  if (input.name.trim() !== existing.name) changes.title = input.name.trim()
  if (input.description.trim() !== existing.description) changes.description = input.description.trim()
  if (input.location.trim() !== existing.location) changes.location = input.location.trim()
  const dateTime = toEventDateTime(input)
  if (dateTime !== existing.dateTime) changes.startDateTime = localToUtc(dateTime)
  if (input.maxCapacity !== existing.maxCapacity) {
    changes.maxCapacity = input.maxCapacity
    changes.capacity = input.maxCapacity - existing.occupiedSpots
  }
  if (input.minTickets !== existing.minTickets) changes.minSignUps = input.minTickets
  if (input.hypeDeadline !== existing.hypeDeadline) changes.expiresAt = localToUtc(input.hypeDeadline)
  if (input.ticketPrice !== existing.ticketPrice) changes.ticketPrice = input.ticketPrice
  return changes
}

export function toBooking(dto: BookingDto): Booking {
  const paymentId = dto.paymentReference && /^\d+$/.test(dto.paymentReference) ? Number(dto.paymentReference) : undefined
  return {
    id: String(dto.id),
    eventId: String(dto.eventId),
    userId: String(dto.userId),
    orderId: dto.orderId ?? undefined,
    status: dto.status,
    amount: Number(dto.totalAmount ?? 0),
    createdAt: utcToInstant(dto.createdAt),
    expiresAt: utcToInstant(dto.expiresAt),
    confirmedAt: dto.confirmedAt ? utcToInstant(dto.confirmedAt) : undefined,
    failureReason: dto.failureReason ?? undefined,
    paymentId,
  }
}
