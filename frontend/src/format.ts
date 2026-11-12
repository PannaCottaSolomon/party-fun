/** Ticket prices are in SGD. */
export function money(amount: number): string {
  return `S$${amount.toFixed(2)}`
}

/** Dates and times show '–' when there is none (e.g. an event created without a hype deadline). */
export function formatDate(localDateTime: string): string {
  if (!localDateTime) return '–'
  return new Date(localDateTime).toLocaleDateString('en-SG', { weekday: 'short', day: 'numeric', month: 'short', year: 'numeric' })
}

export function formatTime(localDateTime: string): string {
  if (!localDateTime) return '–'
  return new Date(localDateTime).toLocaleTimeString('en-SG', { hour: 'numeric', minute: '2-digit' })
}

export function formatDateTime(localDateTime: string): string {
  if (!localDateTime) return '–'
  return `${formatDate(localDateTime)}, ${formatTime(localDateTime)}`
}

export function plural(count: number, word: string): string {
  return `${count} ${word}${count === 1 ? '' : 's'}`
}
