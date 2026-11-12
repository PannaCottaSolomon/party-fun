import { useState, type FormEvent } from 'react'
import { Link, useNavigate } from 'react-router-dom'
import * as api from '../api/api'
import { meetsPasswordPolicy, PASSWORD_REQUIREMENTS } from '../auth/cognito'
import { CredentialsDialog } from '../components/CredentialsDialog'
import { Message } from '../components/Message'
import { PasswordInput } from '../components/PasswordInput'
import { RoleToggle } from '../components/RoleToggle'
import type { Role } from '../types'

const EMAIL = /^[^@\s]+@[^@\s]+\.[^@\s]+$/

type Field = 'fullName' | 'username' | 'email' | 'password' | 'confirm'

/** Fields whose label gets a red asterisk. */
const MARKED_REQUIRED: Field[] = ['fullName', 'username', 'email', 'password', 'confirm']

/**
 * View 2: Register. Creates the account in Cognito, which emails a code to confirm it. Once confirmed, the profile is
 * created with the chosen role (participant or organiser; the Authentication Service adds the account to that Cognito
 * group) and the user goes to Login to log in.
 */
export function RegisterPage() {
  const navigate = useNavigate()
  const [role, setRole] = useState<Role>('PARTICIPANT')
  const [values, setValues] = useState<Record<Field, string>>({ fullName: '', username: '', email: '', password: '', confirm: '' })
  const [errors, setErrors] = useState<Partial<Record<Field, string>>>({})
  const [submitError, setSubmitError] = useState<string>()
  const [busy, setBusy] = useState(false)
  /** Set once Cognito created the account and emailed the confirmation code. */
  const [confirming, setConfirming] = useState<{ destination?: string }>()
  const [code, setCode] = useState('')
  /** Set once the account exists: the sign-in details are shown, then this is passed on to Login. */
  const [created, setCreated] = useState<Record<string, string>>()

  if (created) {
    return (
      <div className="auth-page">
        <CredentialsDialog
          username={values.username.trim()}
          email={values.email.trim()}
          password={values.password}
          onContinue={() => navigate('/login', { state: created })}
        />
      </div>
    )
  }

  function validate(): Partial<Record<Field, string>> {
    const found: Partial<Record<Field, string>> = {}
    if (!values.fullName.trim()) found.fullName = 'Full name is required.'
    if (!values.username.trim()) found.username = 'Username is required.'
    if (!values.email.trim()) found.email = 'Email is required.'
    else if (!EMAIL.test(values.email.trim())) found.email = 'Enter a valid email address, like jandoe@email.com.'
    if (!values.password) found.password = 'Password is required.'
    else if (!meetsPasswordPolicy(values.password)) found.password = PASSWORD_REQUIREMENTS
    if (!values.confirm) found.confirm = 'Confirm your password.'
    else if (values.confirm !== values.password) found.confirm = 'Passwords do not match.'
    return found
  }

  async function submit(e: FormEvent) {
    e.preventDefault()
    const found = validate()
    setErrors(found)
    setSubmitError(undefined)
    if (Object.keys(found).length > 0) return
    setBusy(true)
    try {
      const result = await api.register({ username: values.username, email: values.email, password: values.password, role })
      if (result.confirmed) {
        await finish()
      } else if (!(await api.emailVerificationEnabled())) {
        // Email verification is switched off: confirm the account without the emailed code.
        await api.confirmWithoutCode(values.username)
        await finish()
      } else {
        setConfirming({ destination: result.destination })
      }
    } catch (err) {
      setSubmitError(err instanceof Error ? err.message : 'Registration failed.')
    } finally {
      setBusy(false)
    }
  }

  /**
   * Creates the confirmed account's profile with the chosen role (a sign-in and sign-out behind the scenes), then
   * sends the user to Login to log in themselves.
   */
  async function finish() {
    const registered = values.username.trim()
    try {
      await api.completeRegistration({ username: values.username, password: values.password, role, fullName: values.fullName })
      setCreated({ registered, role })
    } catch (err) {
      // The account exists and is confirmed; only the profile setup failed. The first login retries it.
      const setupError = err instanceof Error ? err.message : 'Please try again.'
      setCreated({ registered, role, fullName: values.fullName.trim(), setupError })
    }
  }

  async function confirm(e: FormEvent) {
    e.preventDefault()
    if (!code.trim()) {
      setSubmitError('Enter the code from the email.')
      return
    }
    setBusy(true)
    setSubmitError(undefined)
    try {
      await api.confirmRegistration(values.username, code, role)
    } catch (err) {
      setSubmitError(err instanceof Error ? err.message : 'Could not confirm your account.')
      setBusy(false)
      return
    }
    await finish()
  }

  const input = (name: Field, label: string, type = 'text', autoComplete?: string) => (
    <label className={`field${errors[name] ? ' has-error' : ''}`}>
      <span>
        {label}
        {MARKED_REQUIRED.includes(name) && (
          <span className="required-mark" aria-hidden="true">
            {' '}*
          </span>
        )}
      </span>
      {type === 'password' ? (
        <PasswordInput
          required={MARKED_REQUIRED.includes(name)}
          value={values[name]}
          autoComplete={autoComplete}
          onChange={(e) => setValues((v) => ({ ...v, [name]: e.target.value }))}
        />
      ) : (
        <input
          type={type}
          required={MARKED_REQUIRED.includes(name)}
          value={values[name]}
          autoComplete={autoComplete}
          onChange={(e) => setValues((v) => ({ ...v, [name]: e.target.value }))}
        />
      )}
      {errors[name] && <small className="error-text">{errors[name]}</small>}
    </label>
  )

  if (confirming) {
    return (
      <div className="auth-page">
        <form className="card auth-card" onSubmit={confirm} noValidate>
          <p className="brand brand-large">
            party<span>.fun</span>
          </p>
          <h1>Confirm your email</h1>
          <Message kind="info">
            We emailed a confirmation code to {confirming.destination ?? values.email.trim()}. Enter it to finish
            creating your account.
          </Message>
          {submitError && <Message kind="error">{submitError}</Message>}
          <label className="field">
            <span>Confirmation code</span>
            <input value={code} onChange={(e) => setCode(e.target.value)} autoComplete="one-time-code" inputMode="numeric" />
          </label>
          <button type="submit" className="button block" disabled={busy}>
            {busy ? 'Confirming…' : 'Confirm account'}
          </button>
        </form>
      </div>
    )
  }

  return (
    <div className="auth-page">
      <form className="card auth-card" onSubmit={submit} noValidate>
        <p className="brand brand-large">
          party<span>.fun</span>
        </p>
        <h1>Join party.fun</h1>
        <RoleToggle value={role} onChange={setRole} />
        {submitError && <Message kind="error">{submitError}</Message>}
        {input('fullName', 'Full Name', 'text', 'name')}
        {input('username', 'Username', 'text', 'username')}
        {input('email', 'Email', 'email', 'email')}
        {input('password', 'Password', 'password', 'new-password')}
        {input('confirm', 'Confirm Password', 'password', 'new-password')}
        <button type="submit" className="button block" disabled={busy}>
          {busy ? 'Creating account…' : `Register as ${role === 'ORGANISER' ? 'Organiser' : 'Participant'}`}
        </button>
        <p className="auth-switch">
          Already have an account? <Link to="/login">Log in</Link>
        </p>
      </form>
    </div>
  )
}
