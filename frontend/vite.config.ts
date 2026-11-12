/// <reference types="vitest/config" />
import { readFileSync } from 'node:fs'
import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'

/**
 * The keys the dev server adds when it forwards the Admin dashboard's and the cancellation refunds' calls to the
 * Payment Service (as nginx does in Docker). Read from this folder's .env.local or .env (the same .env that
 * compose.yaml uses) by the dev server only: they have no VITE_ prefix, so they never reach the bundle or the browser.
 */
function paymentKeys(): { admin: string; service: string } {
  const values: Record<string, string> = {}
  for (const file of ['./.env.local', './.env']) {
    try {
      for (const line of readFileSync(new URL(file, import.meta.url), 'utf-8').split(/\r?\n/)) {
        const match = /^\s*([A-Za-z_][A-Za-z0-9_]*)\s*=(.*)$/.exec(line)
        if (match) values[match[1]] ??= match[2].trim()
      }
    } catch {
      // File missing: try the next one. With neither, the forwarded calls are refused by the Payment Service (401).
    }
  }
  return { admin: values.payment_admin_api_key ?? '', service: values.payment_service_api_key ?? '' }
}

const keys = paymentKeys()
const payment = process.env.PAYMENT_UPSTREAM ?? 'http://localhost:8080'
// The Event Service, reached as /gw/... like nginx does in Docker (it has no CORS settings of its own).
const events = process.env.EVENTS_UPSTREAM ?? 'http://localhost:8083'
// The Authentication (User) Service, reached as /gw/user/... and /gw/auth/... (like the API Gateway in AWS).
const auth = process.env.AUTH_UPSTREAM ?? 'http://localhost:8082'

export default defineConfig({
  plugins: [react()],
  server: {
    port: 5173, // must match cors_allowed_origins on the Payment Service
    proxy: {
      // /gw/user/me → the Authentication Service's /user/me, /gw/auth/config → its /auth/config. Listed before '/gw'
      // so they win: the first matching key is used.
      '/gw/user': {
        target: auth,
        rewrite: (path) => path.replace(/^\/gw/, ''),
      },
      '/gw/auth/': {
        target: auth,
        rewrite: (path) => path.replace(/^\/gw/, ''),
      },
      // The Admin dashboard's calls (/gw/admin/... → the Payment Service's /admin/...). In AWS the API Gateway sends
      // them on with the Admin's Cognito token; locally the Payment Service usually has no user pool configured, so
      // this stand-in adds the admin key instead (as nginx.conf.template does). Listed before '/gw' so it wins.
      '^/gw/admin/refunds/(failed|dlq/count|[0-9]+/retry)$': {
        target: payment,
        rewrite: (path) => path.replace(/^\/gw/, ''),
        headers: { 'X-Admin-Api-Key': keys.admin, 'X-Service-Api-Key': '', Authorization: '' },
      },
      // VITE_API_BASE_URL=/gw: /gw/events → the Event Service's /events, Authorization header passed through.
      '/gw': {
        target: events,
        rewrite: (path) => path.replace(/^\/gw/, ''),
      },
      // Same route as nginx.conf.template: exactly this call, with the key added here.
      '^/api/payment-internal/payments/[0-9]+/refund$': {
        target: payment,
        rewrite: (path) => path.replace(/^\/api\/payment-internal/, ''),
        headers: { 'X-Service-Api-Key': keys.service, 'X-Admin-Api-Key': '', Authorization: '' },
      },
    },
  },
  test: {
    environment: 'node',
  },
})
