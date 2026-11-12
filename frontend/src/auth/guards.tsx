import { Navigate, Outlet, useLocation } from 'react-router-dom'
import { Navbar } from '../components/Navbar'
import { homePath, resolveAccess } from './access'
import { useAuth } from './authContext'

/** Pages for logged-in users (both roles): the top navbar plus the page. */
export function ProtectedRoute() {
  const { user } = useAuth()
  const location = useLocation()
  if (resolveAccess(user) === 'login') return <Navigate to="/login" replace state={{ from: location.pathname }} />
  return (
    <>
      <Navbar />
      <main className="page">
        <Outlet />
      </main>
    </>
  )
}

/** Organiser-only pages. Participants are sent back to Browse Events. */
export function OrganiserRoute() {
  const { user } = useAuth()
  const access = resolveAccess(user, 'ORGANISER')
  if (access === 'login') return <Navigate to="/login" replace />
  if (access === 'events') return <Navigate to="/events" replace />
  return <Outlet />
}

/**
 * Admin-only pages (the failed-refunds dashboard), with the navbar. Logged-out visitors go to the Admin login;
 * participants and organisers are sent back to Browse Events.
 */
export function AdminRoute() {
  const { user } = useAuth()
  const access = resolveAccess(user, 'ADMIN')
  if (access === 'login') return <Navigate to="/admin/login" replace />
  if (access === 'events') return <Navigate to="/events" replace />
  return (
    <>
      <Navbar />
      <main className="page">
        <Outlet />
      </main>
    </>
  )
}

/** Login/Register: a logged-in user goes straight to their home page (Browse Events, or the Admin dashboard). */
export function PublicOnlyRoute() {
  const { user } = useAuth()
  return user ? <Navigate to={homePath(user)} replace /> : <Outlet />
}
