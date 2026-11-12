/**
 * The Event and Payment Services store `userId` (and `organiserId`) as positive INT ids, while a Cognito user's id is
 * its `sub` (a UUID). Until the services take the user from the token, the `sub` is mapped to a stable positive
 * integer (FNV-1a hash, 1 … 2³¹−1): the same string always gives the same number, on every device.
 */
export function toServiceId(id: string): number {
  let hash = 0x811c9dc5
  for (let i = 0; i < id.length; i++) {
    hash ^= id.charCodeAt(i)
    hash = Math.imul(hash, 0x01000193)
  }
  return (hash >>> 1) || 1
}
