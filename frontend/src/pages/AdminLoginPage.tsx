import { useState, type FormEvent } from 'react'
import { Link, useNavigate } from 'react-router-dom'
import { useAuth } from '../auth/authContext'
import { Message } from '../components/Message'
import { PasswordInput } from '../components/PasswordInput'

/** Admin login: its own page, without the Participant | Organiser toggle. Only admin accounts can log in here. */
export function AdminLoginPage() {
  const { adminLogin } = useAuth()
  const navigate = useNavigate()
  const [identifier, setIdentifier] = useState('')
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
      await adminLogin(identifier, password)
      navigate('/admin/refunds', { replace: true })
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
        <h1>Admin login</h1>
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
          {busy ? 'Logging in…' : 'Log in as admin'}
        </button>
        <p className="auth-switch">
          Participant or organiser? <Link to="/login">Log in here</Link>
        </p>
      </form>
    </div>
  )
}
