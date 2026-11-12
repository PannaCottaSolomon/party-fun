import type { Booking, PartyEvent } from '../types'

/**
 * The bookings this browser made, per user, with a snapshot of each booking's event. The Event Service returns a
 * booking from signup and payment but has no endpoint to list a user's bookings yet, so My Bookings is built from
 * what is recorded here. Replace this with that endpoint once it exists (e.g. GET /events/bookings?userId=...).
 * Falls back to memory where localStorage doesn't exist (tests).
 */
const STORAGE_KEY = 'partyfun.bookings.v1'

export interface StoredBooking {
  booking: Booking
  /** The event when the booking was last saved: shown if the event is later cancelled (deleted). */
  event: PartyEvent
}

type Store = Record<string, StoredBooking[]>

let memory: Store = {}

function storage(): Storage | null {
  try {
    return typeof localStorage === 'undefined' ? null : localStorage
  } catch {
    return null
  }
}

function read(): Store {
  try {
    const raw = storage()?.getItem(STORAGE_KEY)
    return raw ? (JSON.parse(raw) as Store) : memory
  } catch {
    return memory
  }
}

function write(store: Store): void {
  memory = store
  try {
    storage()?.setItem(STORAGE_KEY, JSON.stringify(store))
  } catch {
    // Storage full or blocked: the in-memory copy still works for this session.
  }
}

/** This user's recorded bookings, newest first. */
export function storedBookings(userId: string): StoredBooking[] {
  return [...(read()[userId] ?? [])].sort((a, b) => b.booking.createdAt.localeCompare(a.booking.createdAt))
}

/** Records a booking the Event Service returned (replacing an older copy of it). */
export function storeBooking(booking: Booking, event: PartyEvent): void {
  const store = read()
  const others = (store[booking.userId] ?? []).filter((s) => s.booking.id !== booking.id)
  write({ ...store, [booking.userId]: [...others, { booking, event }] })
}

/** Replaces everything recorded for the user with the Event Service's own list (the source of truth). */
export function replaceBookings(userId: string, bookings: StoredBooking[]): void {
  write({ ...read(), [userId]: bookings })
}

/** Forgets every recorded booking (used by tests). */
export function resetBookings(): void {
  memory = {}
  storage()?.removeItem(STORAGE_KEY)
}
