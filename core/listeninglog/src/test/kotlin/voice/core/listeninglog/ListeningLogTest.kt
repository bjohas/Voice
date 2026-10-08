package voice.core.listeninglog

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import voice.core.listeninglog.ListeningLog.Companion.clockTime
import voice.core.listeninglog.ListeningLog.Companion.fileName
import voice.core.listeninglog.ListeningLog.Companion.formatLine
import voice.core.playback.playstate.PlayStateManager.PlayState
import java.io.File
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.time.Duration.Companion.seconds

class ListeningLogTest {

  @get:Rule
  val temp = TemporaryFolder()

  private val london = ZoneId.of("Europe/London")

  @Test
  fun `a line has local time with offset, action, title and book time`() {
    val time = ZonedDateTime.of(2026, 9, 29, 22, 14, 3, 0, london)
    val line = formatLine(time, "START", LoggedBook("Die kleine Hexe", positionMs = 2_530_000, durationMs = 7_260_000))
    assertEquals("2026-09-29 22:14:03 +01:00\tSTART\tDie kleine Hexe\t0:42:10 / 2:01:00", line)
  }

  @Test
  fun `files roll over by local date`() {
    assertEquals("2026-09-29.txt", fileName(ZonedDateTime.of(2026, 9, 29, 23, 59, 0, 0, london)))
    assertEquals("2026-09-30.txt", fileName(ZonedDateTime.of(2026, 9, 30, 0, 1, 0, 0, london)))
    assertEquals("30:00:05", clockTime(108_005_000))
  }

  @Test
  fun `switched off, nothing is written`() = runTest {
    val clock = Clock.fixed(Instant.parse("2026-09-29T21:14:03Z"), london)
    val directory = temp.newFolder("off")
    val states = MutableStateFlow(PlayState.Paused)
    backgroundScope.launch {
      ListeningLog.record(
        states,
        directory,
        clock,
        io = kotlinx.coroutines.test.StandardTestDispatcher(testScheduler),
        enabled = { false },
      ) { LoggedBook("Room on the Broom", 0, 1_500_000) }
    }
    runCurrent()
    states.value = PlayState.Playing
    runCurrent()
    states.value = PlayState.Paused
    advanceTimeBy(ListeningLog.STOP_SETTLE + 1.seconds)
    runCurrent()
    assertFalse(File(directory, "2026-09-29.txt").exists())
  }

  @Test
  fun `records starts and stops, not the paused state the app opens in`() = runTest {
    val clock = Clock.fixed(Instant.parse("2026-09-29T21:14:03Z"), london)
    val directory = temp.newFolder("log")
    val states = MutableStateFlow(PlayState.Paused)
    var position = 1_000L
    backgroundScope.launch {
      ListeningLog.record(states, directory, clock, io = kotlinx.coroutines.test.StandardTestDispatcher(testScheduler)) {
        LoggedBook("Room on the Broom", position, 1_500_000)
      }
    }
    runCurrent()
    assertFalse("nothing logged for the initial pause", File(directory, "2026-09-29.txt").exists())

    states.value = PlayState.Playing
    runCurrent()
    position = 61_000
    states.value = PlayState.Paused
    advanceTimeBy(ListeningLog.STOP_SETTLE + 1.seconds)
    runCurrent()

    assertEquals(
      listOf(
        "2026-09-29 22:14:03 +01:00\tSTART\tRoom on the Broom\t0:00:01 / 0:25:00",
        "2026-09-29 22:14:03 +01:00\tSTOP\tRoom on the Broom\t0:01:01 / 0:25:00",
      ),
      File(directory, "2026-09-29.txt").readLines(),
    )
  }
}
