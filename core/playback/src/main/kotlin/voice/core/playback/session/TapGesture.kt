package voice.core.playback.session

import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes

/**
 * Quick taps on a back or forward button, counted into one gesture
 * (BUTTON-PRESSES.md). Each tap acts at once; a tap that follows the last one
 * within [window], in the same direction, tops the jump up so the gesture's
 * total follows [totalAfter]: 1 and 2 taps are ordinary jumps, then 3 taps =
 * 1 min, 4 = 2 min, 5 = 5 min, 6 = 10 min, and 5 min more a tap after that.
 */
internal class TapGesture(var window: Duration = 400.milliseconds) {

  private var lastTapAt: Long? = null
  private var lastForward: Boolean? = null
  private var count = 0

  /** Registers a tap at [atMillis] (when it arrived, on a monotonic clock) and returns its place in the gesture, from 1. */
  fun tap(
    forward: Boolean,
    atMillis: Long,
  ): Int {
    val last = lastTapAt
    // A window of zero switches counting off: every tap is an ordinary jump.
    val continues = window > Duration.ZERO && last != null && lastForward == forward && (atMillis - last).milliseconds <= window
    count = if (continues) count + 1 else 1
    lastTapAt = atMillis
    lastForward = forward
    return count
  }

  companion object {
    private val totals = listOf(1.minutes, 2.minutes, 5.minutes, 10.minutes)

    /**
     * How far the gesture has gone after [taps] taps, given a single tap's
     * [seekTime]: two taps are two ordinary jumps, then 1, 2, 5 and 10 minutes,
     * then 5 minutes more a tap. Never less than the tap before.
     */
    fun totalAfter(
      taps: Int,
      seekTime: Duration,
    ): Duration = when {
      taps <= 1 -> seekTime
      taps == 2 -> seekTime * 2
      taps - 3 < totals.size -> totals[taps - 3]
      else -> totals.last() + 5.minutes * (taps - 2 - totals.size)
    }.coerceAtLeast(if (taps <= 1) Duration.ZERO else totalAfter(taps - 1, seekTime))

    /** What tap number [taps] adds on top of the ones before it. */
    fun extraFor(
      taps: Int,
      seekTime: Duration,
    ): Duration = if (taps <= 1) seekTime else totalAfter(taps, seekTime) - totalAfter(taps - 1, seekTime)
  }
}
