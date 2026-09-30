package voice.features.pillowSpeaker

import org.junit.Assert.assertTrue
import org.junit.Test
import voice.core.playback.session.KeyPress
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

class PressLineTest {

  @Test
  fun `a tap line shows direction, count, what it added, the total and the gap`() {
    val line =
      pressLine(KeyPress("PREVIOUS", atMillis = 0, gapMillis = 410, forward = false, taps = 3, added = 40.seconds, total = 1.minutes))
    assertTrue(line, line.endsWith("PREVIOUS  back ×3  +40 s → 1:00  gap 410 ms"))
  }

  @Test
  fun `another key shows just its name and the gap`() {
    val line = pressLine(KeyPress("PAUSE", atMillis = 0, gapMillis = null))
    assertTrue(line, line.endsWith("PAUSE"))
  }
}
