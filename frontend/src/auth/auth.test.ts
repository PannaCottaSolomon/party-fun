import { describe, expect, it } from 'vitest'
import type { User } from '../types'
import { canManageEvent, homePath, pickRole, resolveAccess } from './access'
import { decodeJwt, displayName, rolesFromGroups } from './cognito'

const participant: User = { id: '101', username: 'participant', email: 'p@x.sg', role: 'PARTICIPANT' }
const organiser: User = { id: '102', username: 'organiser', email: 'o@x.sg', role: 'ORGANISER' }
const admin: User = { id: '103', username: 'admin', email: 'a@x.sg', role: 'ADMIN' }

/** An unsigned JWT with these claims (the frontend only reads tokens; the gateway and services verify them). */
function jwt(claims: object): string {
  const part = (value: object) =>
    btoa(String.fromCharCode(...new TextEncoder().encode(JSON.stringify(value))))
      .replace(/\+/g, '-')
      .replace(/\//g, '_')
      .replace(/=+$/, '')
  return `${part({ alg: 'RS256' })}.${part(claims)}.signature`
}

describe('Cognito login roles', () => {
  it('maps the Cognito groups participant, organiser and admin (any case) to roles, ignoring others', () => {
    expect(rolesFromGroups(['organiser', 'Participant', 'staff'])).toEqual(['PARTICIPANT', 'ORGANISER'])
    expect(rolesFromGroups(['ADMIN'])).toEqual(['ADMIN'])
    expect(rolesFromGroups(['organizer'])).toEqual([])
    expect(rolesFromGroups(undefined)).toEqual([])
  })

  it('gives the role chosen on the Login page when the account is in that group', () => {
    expect(pickRole(['PARTICIPANT'], 'PARTICIPANT')).toBe('PARTICIPANT')
    expect(pickRole(['PARTICIPANT', 'ORGANISER'], 'ORGANISER')).toBe('ORGANISER')
  })

  it('rejects an account that does not match the selected role', () => {
    expect(() => pickRole(['ORGANISER'], 'PARTICIPANT')).toThrow('This account is registered as an organiser.')
    expect(() => pickRole(['PARTICIPANT'], 'ORGANISER')).toThrow('This account is registered as a participant.')
    expect(() => pickRole([], 'PARTICIPANT')).toThrow(/no party.fun Cognito group/)
  })

  it('sends admins to the Admin login, which only accepts admin accounts', () => {
    expect(() => pickRole(['ADMIN'], 'PARTICIPANT')).toThrow('Admins log in on the Admin login page.')
    expect(pickRole(['ADMIN'], 'ADMIN')).toBe('ADMIN')
    expect(() => pickRole(['PARTICIPANT'], 'ADMIN')).toThrow('This is not an admin account.')
  })

  it('reads the ID token claims, including non-ASCII names', () => {
    const claims = decodeJwt<{ sub: string; name: string; 'cognito:groups': string[] }>(
      jwt({ sub: 'abc', name: 'Zoë', 'cognito:groups': ['participant'] }),
    )
    expect(claims).toEqual({ sub: 'abc', name: 'Zoë', 'cognito:groups': ['participant'] })
    expect(() => decodeJwt('not-a-jwt')).toThrow(/could not read/)
  })

  it('shows a readable name, never the sub', () => {
    expect(displayName({ sub: 's1', 'cognito:username': 'alice', email: 'a@x.sg' })).toBe('alice')
    expect(displayName({ sub: 's1', 'cognito:username': 's1', email: 'a@x.sg' })).toBe('a@x.sg')
    expect(displayName({ sub: 's1', preferred_username: 'Al', 'cognito:username': 'alice' })).toBe('Al')
  })
})

describe('route access', () => {
  it('sends logged-out users to Login', () => {
    expect(resolveAccess(null)).toBe('login')
    expect(resolveAccess(null, 'ORGANISER')).toBe('login')
    expect(resolveAccess(null, 'ADMIN')).toBe('login')
  })

  it('keeps participants out of organiser pages', () => {
    expect(resolveAccess(participant)).toBe('allow')
    expect(resolveAccess(participant, 'ORGANISER')).toBe('events')
  })

  it('keeps everyone but admins out of the admin pages', () => {
    expect(resolveAccess(participant, 'ADMIN')).toBe('events')
    expect(resolveAccess(organiser, 'ADMIN')).toBe('events')
    expect(resolveAccess(admin, 'ADMIN')).toBe('allow')
  })

  it('lets admins use the organiser pages, to manage every event', () => {
    expect(resolveAccess(admin, 'ORGANISER')).toBe('allow')
  })

  it('lets an organiser manage only their own events, and an admin any event', () => {
    const theirs = { organiserId: organiser.id }
    const someoneElses = { organiserId: 'someone-else' }
    expect(canManageEvent(organiser, theirs)).toBe(true)
    expect(canManageEvent(organiser, someoneElses)).toBe(false)
    expect(canManageEvent(participant, someoneElses)).toBe(false)
    expect(canManageEvent(admin, someoneElses)).toBe(true)
  })

  it('sends admins to their dashboard after login, everyone else to Browse Events', () => {
    expect(homePath(admin)).toBe('/admin/refunds')
    expect(homePath(participant)).toBe('/events')
    expect(homePath(organiser)).toBe('/events')
  })

  it('lets organisers use every page', () => {
    expect(resolveAccess(organiser)).toBe('allow')
    expect(resolveAccess(organiser, 'ORGANISER')).toBe('allow')
  })
})
