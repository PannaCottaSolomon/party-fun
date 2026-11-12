import type { BookingStatus, EventStatus } from '../types'

const LABELS: Record<EventStatus, string> = {
  PENDING: 'Pending',
  CONFIRMED: 'Confirmed',
  CANCELLED: 'Cancelled',
}

export function StatusBadge({ status }: { status: EventStatus }) {
  return <span className={`badge badge-${status.toLowerCase()}`}>{LABELS[status]}</span>
}

const BOOKING_LABELS: Record<BookingStatus, [label: string, badge: EventStatus]> = {
  PENDING: ['Awaiting payment', 'PENDING'],
  CONFIRMED: ['Paid', 'CONFIRMED'],
  EXPIRED: ['Expired', 'CANCELLED'],
  CANCELLED: ['Cancelled', 'CANCELLED'],
}

/** A booking's status, in the event badges' colours. */
export function BookingStatusBadge({ status }: { status: BookingStatus }) {
  const [label, badge] = BOOKING_LABELS[status]
  return <span className={`badge badge-${badge.toLowerCase()}`}>{label}</span>
}
