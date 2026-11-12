import { useState } from 'react'
import { Link, NavLink } from 'react-router-dom'
import { useUser } from '../auth/authContext'

/**
 * Top navbar. Organisers get two extra links; Admins get the failed-refunds dashboard and the event management pages
 * (every event) instead of browsing and bookings.
 * Collapses into a menu button on small screens.
 */
export function Navbar() {
  const user = useUser()
  const [open, setOpen] = useState(false)
  const close = () => setOpen(false)
  const isOrganiser = user.role === 'ORGANISER'
  const isAdmin = user.role === 'ADMIN'
  const initial = (user.username || user.email || '?').charAt(0).toUpperCase()

  return (
    <header className="navbar">
      <div className="navbar-inner">
        <Link to={isAdmin ? '/admin/refunds' : '/events'} className="brand" onClick={close}>
          party<span>.fun</span>
        </Link>
        <button
          type="button"
          className="menu-button"
          aria-label="Menu"
          aria-expanded={open}
          aria-controls="main-nav"
          onClick={() => setOpen((o) => !o)}
        >
          <span />
          <span />
          <span />
        </button>
        <nav id="main-nav" className={open ? 'nav-links open' : 'nav-links'}>
          {isAdmin ? (
            <>
              <NavLink to="/admin/refunds" onClick={close}>
                Failed Refunds
              </NavLink>
              <NavLink to="/organiser/events" end onClick={close}>
                Manage Events
              </NavLink>
              <NavLink to="/organiser/events/create" className="nav-cta" onClick={close}>
                + Create Event
              </NavLink>
            </>
          ) : (
            <>
              <NavLink to="/events" end onClick={close}>
                Browse Events
              </NavLink>
              <NavLink to="/bookings" onClick={close}>
                My Bookings
              </NavLink>
            </>
          )}
          {isOrganiser && (
            <>
              <NavLink to="/organiser/events" end onClick={close}>
                My Organised Events
              </NavLink>
              <NavLink to="/organiser/events/create" className="nav-cta" onClick={close}>
                + Create Event
              </NavLink>
            </>
          )}
          <NavLink to="/profile" className="nav-profile" onClick={close}>
            <span className="avatar" aria-hidden="true">
              {initial}
            </span>
            Profile
          </NavLink>
        </nav>
      </div>
    </header>
  )
}
