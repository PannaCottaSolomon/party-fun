import { createContext, useContext } from 'react'
import type { Role, User } from '../types'

export interface AuthContextValue {
  user: User | null
  /** `fullName` is only used the first time, to create a new account's profile. */
  login: (identifier: string, password: string, role: Role, fullName?: string) => Promise<User>
  /** The Admin login page's login: only admin accounts. */
  adminLogin: (identifier: string, password: string) => Promise<User>
  logout: () => void
}

export const AuthContext = createContext<AuthContextValue | null>(null)

export function useAuth(): AuthContextValue {
  const value = useContext(AuthContext)
  if (!value) throw new Error('useAuth must be used inside <AuthProvider>')
  return value
}

/** The logged-in user. Only for components rendered behind ProtectedRoute. */
export function useUser(): User {
  const { user } = useAuth()
  if (!user) throw new Error('useUser needs a logged-in user (render it behind ProtectedRoute)')
  return user
}
