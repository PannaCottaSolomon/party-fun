import { useEffect, useRef, useState } from 'react'

interface Props {
  username: string
  email: string
  password: string
  onContinue: () => void
}

/** One credential with a copy button (and, for the password, a show/hide toggle). */
function Credential({ label, value, secret }: { label: string; value: string; secret?: boolean }) {
  const [shown, setShown] = useState(!secret)
  const [copied, setCopied] = useState(false)

  async function copy() {
    try {
      await navigator.clipboard.writeText(value)
      setCopied(true)
      setTimeout(() => setCopied(false), 1500)
    } catch {
      // Clipboard blocked (e.g. not HTTPS): the user can still select the text
    }
  }

  return (
    <div className="credential">
      <dt>{label}</dt>
      <dd>
        <span className="mono credential-value">{shown ? value : '•'.repeat(Math.min(value.length, 16))}</span>
        {secret && (
          <button type="button" className="button secondary small-button" onClick={() => setShown((s) => !s)}>
            {shown ? 'Hide' : 'Show'}
          </button>
        )}
        <button type="button" className="button secondary small-button" onClick={copy} aria-label={`Copy ${label.toLowerCase()}`}>
          {copied ? 'Copied!' : 'Copy'}
        </button>
      </dd>
    </div>
  )
}

/**
 * Shown once, right after an account is created: the sign-in details the user just chose, so they can save them
 * (e.g. in a password manager) before going to Login. Nothing is stored; the password only lives in this page's
 * memory until the user continues.
 */
export function CredentialsDialog({ username, email, password, onContinue }: Props) {
  const ref = useRef<HTMLDialogElement>(null)

  useEffect(() => {
    const dialog = ref.current
    if (dialog && !dialog.open) dialog.showModal()
  }, [])

  return (
    <dialog
      ref={ref}
      className="dialog"
      aria-labelledby="credentials-title"
      onCancel={(e) => {
        e.preventDefault()
        onContinue()
      }}
    >
      <h2 id="credentials-title">Your account is ready 🎉</h2>
      <div className="dialog-body">
        <p>Save these sign-in details somewhere safe, like a password manager. You can log in with your username or email.</p>
        <dl className="credentials">
          <Credential label="Username" value={username} />
          <Credential label="Email" value={email} />
          <Credential label="Password" value={password} secret />
        </dl>
        <p className="muted small">We won&apos;t show your password again.</p>
      </div>
      <div className="dialog-actions">
        <button type="button" className="button" onClick={onContinue}>
          I&apos;ve saved them, continue to login
        </button>
      </div>
    </dialog>
  )
}
