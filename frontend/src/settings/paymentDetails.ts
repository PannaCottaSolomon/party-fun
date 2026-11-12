import { useSyncExternalStore } from 'react'

/**
 * "Show payment details" (Profile page): whether the Stripe sandbox extras are shown (the test-card list, sandbox notes,
 * payment ids and the live Stripe payment panel). On by default; turning it off gives a clean checkout for demos.
 * Saved in this browser only, and read by every page through `useShowPaymentDetails`.
 */
const KEY = 'partyfun.showPaymentDetails'
const CHANGED = 'partyfun:payment-details-changed'

function read(): boolean {
  try {
    return localStorage.getItem(KEY) !== 'false'
  } catch {
    return true
  }
}

export function setShowPaymentDetails(show: boolean): void {
  try {
    localStorage.setItem(KEY, String(show))
  } catch {
    // Storage blocked: the setting just won't be remembered.
  }
  window.dispatchEvent(new Event(CHANGED))
}

function subscribe(onChange: () => void): () => void {
  // CHANGED covers this tab; 'storage' covers other tabs of the same site.
  window.addEventListener(CHANGED, onChange)
  window.addEventListener('storage', onChange)
  return () => {
    window.removeEventListener(CHANGED, onChange)
    window.removeEventListener('storage', onChange)
  }
}

export function useShowPaymentDetails(): boolean {
  return useSyncExternalStore(subscribe, read, () => true)
}
