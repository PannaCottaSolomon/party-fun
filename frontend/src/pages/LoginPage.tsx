import { useState, type FormEvent } from 'react'
import { Link, useLocation, useNavigate } from 'react-router-dom'
import { homePath } from '../auth/access'
import { useAuth } from '../auth/authContext'
import { Message } from '../components/Message'
import { PasswordInput } from '../components/PasswordInput'
import { RoleToggle } from '../components/RoleToggle'
import type { Role } from '../types'

/** View 1: Login, for participants and organisers. The selected role must match the account's role. */
export function LoginPage() {
  const { login } = useAuth()
  const navigate = useNavigate()
  const location = useLocation()
  const { registered, role: registeredRole, fullName, setupError } =
    (location.state as { registered?: string; role?: Role; fullName?: string; setupError?: string } | null) ?? {}

  const [role, setRole] = useState<Role>(registeredRole ?? 'PARTICIPANT')
  const [identifier, setIdentifier] = useState(registered ?? '')
  const [password, setPassword] = useState('')
  const [error, setError] = useState<string>()
  const [busy, setBusy] = useState(false)

  async function submit(e: FormEvent) {
    e.preventDefault()
    if (!identifier.trim() || !password) {
      setError('Enter your email or username and your password.')
      return
    }
    setBusy(true)
    setError(undefined)
    try {
      const user = await login(identifier, password, role, fullName)
      navigate(homePath(user), { replace: true })
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Login failed.')
      setBusy(false)
    }
  }

  return (
    <div className="auth-page">
      <form className="card auth-card" onSubmit={submit} noValidate>
        <p className="brand brand-large">
          party<span>.fun</span>
        </p>
        <h1>Welcome back to party.fun</h1>
        <RoleToggle value={role} onChange={setRole} />
        {registered && !error && !setupError && <Message kind="success">Account created. Log in to continue.</Message>}
        {registered && !error && setupError && (
          <Message kind="info">
            Your account is confirmed, but setting up your profile didn&apos;t finish ({setupError}). Log in to finish it.
          </Message>
        )}
        {error && <Message kind="error">{error}</Message>}
        <label className="field">
          <span>Email or Username</span>
          <input value={identifier} onChange={(e) => setIdentifier(e.target.value)} autoComplete="username" />
        </label>
        <label className="field">
          <span>Password</span>
          <PasswordInput
            value={password}
            onChange={(e) => setPassword(e.target.value)}
            autoComplete="current-password"
          />
        </label>
        <button type="submit" className="button block" disabled={busy}>
          {busy ? 'Logging in…' : 'Login'}
        </button>
        <p className="auth-switch">
          Don&apos;t have an account? <Link to="/register">Sign Up</Link>
        </p>
        <p className="auth-switch">
          Admin? <Link to="/admin/login">Log in as an admin</Link>
        </p>
      </form>
    </div>
  )
}
