import { renderToString } from 'react-dom/server'
import { MemoryRouter } from 'react-router-dom'
import { describe, expect, it } from 'vitest'
import { App } from './App'
import { AuthContext } from './auth/authContext'
import { AuthProvider } from './auth/AuthProvider'
import type { User } from './types'

function render(path: string): string {
  return renderToString(
    <MemoryRouter initialEntries={[path]}>
      <AuthProvider>
        <App />
      </AuthProvider>
    </MemoryRouter>,
  )
}

function renderAs(user: User, path: string): string {
  return renderToString(
    <MemoryRouter initialEntries={[path]}>
      <AuthContext.Provider
        value={{ user, login: () => Promise.resolve(user), adminLogin: () => Promise.resolve(user), logout: () => {} }}
      >
        <App />
      </AuthContext.Provider>
    </MemoryRouter>,
  )
}

const admin: User = { id: 'u-admin', username: 'admin', email: 'admin@partyfun.sg', role: 'ADMIN' }
const participant: User = { id: 'u-participant', username: 'participant', email: 'p@x.sg', role: 'PARTICIPANT' }

describe('App', () => {
  it('shows the Login page with the role toggle', () => {
    const html = render('/login')
    expect(html).toContain('Welcome back to party.fun')
    expect(html).toContain('Participant')
    expect(html).toContain('Organiser')
    expect(html).toContain('Sign Up')
  })

  it('shows the Register page', () => {
    const html = render('/register')
    expect(html).toContain('Confirm Password')
  })

  it('links the Login page to the separate Admin login', () => {
    expect(render('/login')).toContain('Log in as an admin')
    const html = render('/admin/login')
    expect(html).toContain('Admin login')
    expect(html).not.toContain('Organiser')
  })

  it('shows the Admin the failed-refunds dashboard straight away, with no key to enter', () => {
    const html = renderAs(admin, '/admin/refunds')
    expect(html).toContain('Failed refunds')
    expect(html).toContain('Failed Refunds')
    expect(html).not.toContain('admin key')
    expect(html).not.toContain('Payments</a>')
  })

  it('gives the Admin the event management pages', () => {
    const html = renderAs(admin, '/admin/refunds')
    expect(html).toContain('Manage Events')
    expect(html).toContain('+ Create Event')
    const create = renderAs(admin, '/organiser/events/create')
    expect(create).toContain('Create Event')
    expect(create).toContain('listed as its organiser')
  })

  it('keeps participants out of the event management pages', () => {
    expect(renderAs(participant, '/organiser/events/create')).not.toContain('listed as its organiser')
    expect(renderAs(participant, '/events')).not.toContain('Manage Events')
  })

  it('keeps participants out of the admin pages', () => {
    expect(renderAs(participant, '/admin/refunds')).not.toContain('dead-letter queue')
  })

  it('has no Admin tools on the Profile page', () => {
    expect(renderAs(admin, '/profile')).not.toContain('Admin tools')
  })
})
