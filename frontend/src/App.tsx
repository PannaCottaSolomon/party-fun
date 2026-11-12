import { Navigate, Route, Routes } from 'react-router-dom'
import { AdminRoute, OrganiserRoute, ProtectedRoute, PublicOnlyRoute } from './auth/guards'
import { AdminLoginPage } from './pages/AdminLoginPage'
import { AdminRefundsPage } from './pages/AdminRefundsPage'
import { BookingDetailsPage } from './pages/BookingDetailsPage'
import { BrowseEventsPage } from './pages/BrowseEventsPage'
import { CheckoutPage } from './pages/CheckoutPage'
import { CreateEventPage } from './pages/CreateEventPage'
import { EditEventPage } from './pages/EditEventPage'
import { EventDetailsPage } from './pages/EventDetailsPage'
import { LoginPage } from './pages/LoginPage'
import { ManageEventPage } from './pages/ManageEventPage'
import { MyBookingsPage } from './pages/MyBookingsPage'
import { MyOrganisedEventsPage } from './pages/MyOrganisedEventsPage'
import { ProfilePage } from './pages/ProfilePage'
import { RegisterPage } from './pages/RegisterPage'

/**
 * The 12 views, plus the Admin login and the Admin's failed-refunds dashboard. Browse Events is the home page for
 * participants and organisers; the dashboard is the Admin's.
 */
export function App() {
  return (
    <Routes>
      <Route element={<PublicOnlyRoute />}>
        <Route path="/login" element={<LoginPage />} />
        <Route path="/register" element={<RegisterPage />} />
        <Route path="/admin/login" element={<AdminLoginPage />} />
      </Route>

      <Route element={<AdminRoute />}>
        <Route path="/admin/refunds" element={<AdminRefundsPage />} />
      </Route>

      <Route element={<ProtectedRoute />}>
        <Route path="/events" element={<BrowseEventsPage />} />
        <Route path="/events/:eventId" element={<EventDetailsPage />} />
        <Route path="/events/:eventId/checkout" element={<CheckoutPage />} />
        <Route path="/bookings" element={<MyBookingsPage />} />
        <Route path="/bookings/:bookingId" element={<BookingDetailsPage />} />
        <Route path="/profile" element={<ProfilePage />} />

        <Route element={<OrganiserRoute />}>
          <Route path="/organiser/events" element={<MyOrganisedEventsPage />} />
          <Route path="/organiser/events/create" element={<CreateEventPage />} />
          <Route path="/organiser/events/:eventId" element={<ManageEventPage />} />
          <Route path="/organiser/events/:eventId/edit" element={<EditEventPage />} />
        </Route>
      </Route>

      <Route path="*" element={<Navigate to="/events" replace />} />
    </Routes>
  )
}
