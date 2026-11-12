import { ApiError } from '../api/errors'
import type { Role, User } from '../types'

/**
 * The role a login gets, from the account's roles (its Cognito groups). On the Login page (`wanted` = the toggle) the
 * account must have the chosen role; the Admin login page (`wanted` = 'ADMIN') only accepts admin accounts.
 */
export function pickRole(roles: Role[], wanted: Role): Role {
  if (roles.includes(wanted)) return wanted
  if (wanted === 'ADMIN') throw new ApiError('This is not an admin account.')
  if (roles.length === 0) {
    throw new ApiError("This account isn't a participant or organiser yet (it's in no party.fun Cognito group).")
  }
  if (!roles.includes('PARTICIPANT') && !roles.includes('ORGANISER')) {
    throw new ApiError('Admins log in on the Admin login page.')
  }
  throw new ApiError(`This account is registered as ${roles.includes('ORGANISER') ? 'an organiser' : 'a participant'}.`)
}

/**
 * Where a user may go:
 * - not logged in → the Login page
 * - a page only for one role (organisers or admins), but the user has another role → Browse Events. Admins may also
 *   use the organiser pages: they manage every event.
 * - otherwise → allowed
 */
export function resolveAccess(user: User | null, onlyFor?: Exclude<Role, 'PARTICIPANT'>): 'allow' | 'login' | 'events' {
  if (!user) return 'login'
  if (onlyFor && user.role !== onlyFor && !(onlyFor === 'ORGANISER' && user.role === 'ADMIN')) return 'events'
  return 'allow'
}

/** Whether the user may edit or cancel this event: its organiser, or an Admin (any event). */
export function canManageEvent(user: User, event: { organiserId: string }): boolean {
  return user.role === 'ADMIN' || event.organiserId === user.id
}

/** Where a user lands after logging in: Admins go to their dashboard, everyone else to Browse Events. */
export function homePath(user: User): string {
  return user.role === 'ADMIN' ? '/admin/refunds' : '/events'
}
