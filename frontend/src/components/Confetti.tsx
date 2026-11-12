import type { CSSProperties } from 'react'

const COLORS = ['#a78bfa', '#f472b6', '#fcd34d', '#86efac', '#7dd3fc', '#fca5a5']
const PIECE_COUNT = 90

/** Small deterministic pseudo-random sequence, so every burst looks the same and rendering stays pure. */
function sequence(seed: number): () => number {
  let state = seed
  return () => {
    state = (state * 1664525 + 1013904223) % 4294967296
    return state / 4294967296
  }
}

const random = sequence(302)
const PIECES = Array.from({ length: PIECE_COUNT }, (_, i) => ({
  left: random() * 100,
  delay: random() * 0.8,
  duration: 2.4 + random() * 1.8,
  drift: (random() - 0.5) * 240,
  spin: 360 + random() * 720,
  wide: random() > 0.5,
  color: COLORS[i % COLORS.length],
}))

/**
 * One burst of confetti falling over the page, for a successful booking. Decorative only: hidden from screen readers,
 * it doesn't catch clicks, and it isn't shown at all to people who prefer reduced motion (styles.css).
 */
export function Confetti() {
  return (
    <div className="confetti" aria-hidden="true">
      {PIECES.map((piece, i) => (
        <span
          key={i}
          className={piece.wide ? 'wide' : undefined}
          style={
            {
              left: `${piece.left}%`,
              background: piece.color,
              animationDelay: `${piece.delay}s`,
              animationDuration: `${piece.duration}s`,
              '--drift': `${piece.drift}px`,
              '--spin': `${piece.spin}deg`,
            } as CSSProperties
          }
        />
      ))}
    </div>
  )
}
