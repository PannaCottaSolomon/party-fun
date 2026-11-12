import { useCallback, useEffect, useMemo, useState, type ReactNode } from 'react'
import * as api from '../api/api'
import type { Role } from '../types'
import { AuthContext } from './authContext'
import { currentSession, onSessionEnded } from './session'

/**
 * The logged-in user, from the Cognito session (see session.ts). When the session ends anywhere (logout, or tokens
 * that can no longer be refreshed), the user becomes null and the route guards send them back to Login.
 */
export function AuthProvider({ children }: { children: ReactNode }) {
  const [user, setUser] = useState(() => currentSession()?.user ?? null)

  useEffect(() => onSessionEnded(() => setUser(null)), [])

  const login = useCallback(async (identifier: string, password: string, role: Role, fullName?: string) => {
    const loggedIn = await api.login(identifier, password, role, fullName)
    setUser(loggedIn)
    return loggedIn
  }, [])

  const adminLogin = useCallback(async (identifier: string, password: string) => {
    const loggedIn = await api.adminLogin(identifier, password)
    setUser(loggedIn)
    return loggedIn
  }, [])

  const logout = useCallback(() => {
    api.logout()
    setUser(null)
  }, [])

  const value = useMemo(() => ({ user, login, adminLogin, logout }), [user, login, adminLogin, logout])
  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>
}
