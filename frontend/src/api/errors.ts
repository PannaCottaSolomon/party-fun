/** Something the user can act on (wrong password, sold out, card declined, …). `message` is shown as-is. */
export class ApiError extends Error {
  /** HTTP status when the error came from a backend service. */
  readonly status?: number

  constructor(message: string, status?: number) {
    super(message)
    this.status = status
  }
}
