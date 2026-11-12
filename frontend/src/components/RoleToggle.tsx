import type { Role } from '../types'

/** The Participant | Organiser switch on the Login and Register pages. */
export function RoleToggle({ value, onChange }: { value: Role; onChange: (role: Role) => void }) {
  const option = (role: Role, label: string) => (
    <button
      type="button"
      role="radio"
      aria-checked={value === role}
      className={value === role ? 'active' : ''}
      onClick={() => onChange(role)}
    >
      {label}
    </button>
  )
  return (
    <div className="role-toggle" role="radiogroup" aria-label="Account type">
      {option('PARTICIPANT', 'Participant')}
      {option('ORGANISER', 'Organiser')}
    </div>
  )
}
