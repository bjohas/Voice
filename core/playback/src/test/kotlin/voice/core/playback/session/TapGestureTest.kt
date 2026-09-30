package voice.core.playback.session

import org.junit.Assert.assertEquals
import org.junit.Test
import voice.core.playback.session.TapGesture.Companion.extraFor
import voice.core.playback.session.TapGesture.Companion.totalAfter
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

class TapGestureTest {

  @Test
  fun `quick taps in one direction count up, a pause or the other direction starts again`() {
    val gesture = TapGesture(window = 700.milliseconds)
    assertEquals(1, gesture.tap(forward = false, atMillis = 0))
    assertEquals(2, gesture.tap(forward = false, atMillis = 500))
    assertEquals("the gap is from the last tap, not the first", 3, gesture.tap(forward = false, atMillis = 1_100))
    assertEquals("too slow", 1, gesture.tap(forward = false, atMillis = 3_000))
    assertEquals("other direction", 1, gesture.tap(forward = true, atMillis = 3_300))
    assertEquals(2, gesture.tap(forward = true, atMillis = 3_600))
  }

  @Test
  fun `the spacing setting decides what counts as quick`() {
    val gesture = TapGesture()
    assertEquals(1, gesture.tap(forward = false, atMillis = 0))
    assertEquals("500 ms is too slow for the default 400", 1, gesture.tap(forward = false, atMillis = 500))
    gesture.window = 600.milliseconds
    assertEquals(2, gesture.tap(forward = false, atMillis = 1_000))
  }

  @Test
  fun `a spacing of zero switches counting off`() {
    val gesture = TapGesture(window = 0.milliseconds)
    listOf(0L, 0, 10, 20).forEach { assertEquals(1, gesture.tap(forward = false, atMillis = it)) }
  }

  @Test
  fun `deliberate presses two seconds apart stay single`() {
    val gesture = TapGesture()
    listOf(0L, 2_000, 4_000, 6_000).forEach { assertEquals(1, gesture.tap(forward = false, atMillis = it)) }
  }

  @Test
  fun `the gesture's totals are two ordinary jumps, then 1, 2, 5, 10 minutes, then 5 more a tap`() {
    val seek = 10.seconds
    assertEquals(
      listOf(10.seconds, 20.seconds, 1.minutes, 2.minutes, 5.minutes, 10.minutes, 15.minutes, 20.minutes),
      (1..8).map { totalAfter(it, seek) },
    )
  }

  @Test
  fun `each tap adds only the difference, so acting at once lands on the totals`() {
    val seek = 10.seconds
    assertEquals(listOf(10.seconds, 10.seconds, 40.seconds, 1.minutes, 3.minutes, 5.minutes, 5.minutes), (1..7).map { extraFor(it, seek) })
    assertEquals(5.minutes, seek + (2..5).map { extraFor(it, seek) }.reduce { a, b -> a + b })
  }

  @Test
  fun `a long seek time never makes a tap go backwards`() {
    val seek = 45.seconds
    assertEquals(90.seconds, totalAfter(2, seek))
    assertEquals("1 min is less than two jumps: stays", 0.seconds, extraFor(3, seek))
    assertEquals(30.seconds, extraFor(4, seek))
  }
}
