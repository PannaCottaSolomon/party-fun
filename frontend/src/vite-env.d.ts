/// <reference types="vite/client" />

interface ImportMetaEnv {
  /**
   * Base URL of the API Gateway in front of the microservices, e.g. https://abc123.execute-api.ap-southeast-1.amazonaws.com
   * (or a path such as /api when CloudFront forwards it to the gateway). The Event Service is reached at `${base}/events`.
   */
  readonly VITE_API_BASE_URL?: string
  /** AWS region of the Cognito user pool, e.g. ap-southeast-1 */
  readonly VITE_COGNITO_REGION?: string
  /** The user pool's public app client id (no client secret, USER_PASSWORD_AUTH and refresh tokens enabled). */
  readonly VITE_COGNITO_CLIENT_ID?: string
  /**
   * Base URL of the Authentication (User) Service, for POST /user/me after sign-up, e.g. http://localhost:8082
   * locally. Empty = through the API Gateway (VITE_API_BASE_URL).
   */
  readonly VITE_USER_API_URL?: string
  /** Base URL of the Payment Service, e.g. http://localhost:8080 */
  readonly VITE_PAYMENT_API_URL?: string
}

interface ImportMeta {
  readonly env: ImportMetaEnv
}
