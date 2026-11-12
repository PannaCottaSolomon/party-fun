import type { CSSProperties } from 'react'
import { hypePercent } from '../domain/eventRules'
import { formatDateTime } from '../format'
import type { PartyEvent } from '../types'

/** Flame heights (% of the flame layer) and flicker timings, varied so the fire doesn't pulse in lockstep. */
const FLAME_HEIGHTS = [70, 95, 60, 100, 80, 65, 90, 75, 100, 60, 85, 70, 95, 65]
const FLAME_SPEEDS = [0.7, 0.9, 0.8, 1.05, 0.75, 0.95, 0.85]

/**
 * The fire on a fully hyped meter: flames along the bar and embers drifting up. Decorative only (aria-hidden), and
 * frozen for users who ask for reduced motion (see styles.css).
 */
function HypeFire({ large }: { large: boolean }) {
  const flames = large ? 14 : 8
  const embers = large ? 8 : 4
  return (
    <div className="hype-fire" aria-hidden="true">
      {Array.from({ length: flames }, (_, i) => (
        <span
          key={`f${i}`}
          className="hype-flame"
          style={
            {
              left: `${((i + 0.5) / flames) * 100}%`,
              height: `${FLAME_HEIGHTS[i % FLAME_HEIGHTS.length]}%`,
              '--speed': `${FLAME_SPEEDS[i % FLAME_SPEEDS.length]}s`,
              '--delay': `${-(i * 0.13) % 1}s`,
            } as CSSProperties
          }
        />
      ))}
      {Array.from({ length: embers }, (_, i) => (
        <span
          key={`e${i}`}
          className="hype-ember"
          style={
            {
              left: `${((i * 37 + 11) % 100)}%`,
              '--drift': `${i % 2 === 0 ? 6 : -6}px`,
              '--delay': `${(i * 0.45) % 1.8}s`,
            } as CSSProperties
          }
        />
      ))}
    </div>
  )
}

/**
 * Hype % = signups / minimum tickets × 100 (capped at 100). Reaching 100% saves the event from being cancelled at the
 * hype deadline and shows it as Confirmed. A meter at 100% catches fire.
 */
export function HypeMeter({ event, large = false }: { event: PartyEvent; large?: boolean }) {
  const percent = hypePercent(event)
  const reached = event.hypeTickets >= event.minTickets
  const onFire = reached && event.status !== 'CANCELLED'
  return (
    <div className={['hype', large && 'hype-large', onFire && 'hype-maxed'].filter(Boolean).join(' ')}>
      <div className="hype-head">
        <span className="hype-label">{onFire ? 'Hype · on fire' : 'Hype'}</span>
        <strong>{percent}%</strong>
      </div>
      <div className="hype-bar">
        {onFire && <HypeFire large={large} />}
        <div
          className={`hype-track hype-${event.status.toLowerCase()}`}
          role="progressbar"
          aria-label="Hype meter"
          aria-valuemin={0}
          aria-valuemax={100}
          aria-valuenow={percent}
        >
          <div className="hype-fill" style={{ width: `${percent}%` }} />
        </div>
      </div>
      <p className="hype-caption">
        {event.hypeTickets} / {event.minTickets} tickets needed
        {large && event.status === 'PENDING' && <> · deadline {formatDateTime(event.hypeDeadline)}</>}
        {large && reached && event.status !== 'CANCELLED' && <> · minimum reached</>}
      </p>
    </div>
  )
}
