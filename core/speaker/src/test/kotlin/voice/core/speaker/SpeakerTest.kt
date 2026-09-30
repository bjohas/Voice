package voice.core.speaker

import androidx.datastore.core.DataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.updateAndGet
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import voice.core.data.speaker.SpeakerSettings
import voice.core.playback.playstate.PlayStateManager
import voice.core.playback.playstate.PlayStateManager.PlayState
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

private const val SPEAKER = "AA:BB:CC:DD:EE:FF"

class ShouldAutoPlayTest {

  private val paired = SpeakerSettings(address = SPEAKER)
  private val now = 10_000_000L

  @Test
  fun `the paired speaker connecting plays`() {
    assertTrue(SpeakerAutoPlay.shouldAutoPlay(paired, SPEAKER, now))
    assertTrue("address case is not significant", SpeakerAutoPlay.shouldAutoPlay(paired, SPEAKER.lowercase(), now))
    assertTrue("API 36 reports no address", SpeakerAutoPlay.shouldAutoPlay(paired, null, now))
  }

  @Test
  fun `another device, no pairing, or auto-play off does not play`() {
    assertFalse(SpeakerAutoPlay.shouldAutoPlay(paired, "11:22:33:44:55:66", now))
    assertFalse(SpeakerAutoPlay.shouldAutoPlay(SpeakerSettings(), SPEAKER, now))
    assertFalse(SpeakerAutoPlay.shouldAutoPlay(paired.copy(autoPlay = false), SPEAKER, now))
  }

  @Test
  fun `a reconnect just after bVoice dropped the speaker does not play`() {
    val justDropped = paired.copy(lastOwnDisconnectMillis = now - 30.seconds.inWholeMilliseconds)
    assertFalse(SpeakerAutoPlay.shouldAutoPlay(justDropped, SPEAKER, now))
    val longAgo = paired.copy(lastOwnDisconnectMillis = now - 3.minutes.inWholeMilliseconds)
    assertTrue(SpeakerAutoPlay.shouldAutoPlay(longAgo, SPEAKER, now))
  }
}

class AutoDisconnectTest {

  private class FakeLink : SpeakerLink {
    val disconnects = mutableListOf<String>()
    override fun hasPermission() = true
    override fun pairedDevices() = emptyList<PairedDevice>()
    override suspend fun isConnected(address: String) = true
    override suspend fun disconnect(address: String): DisconnectResult {
      disconnects += address
      return DisconnectResult.Disconnected
    }
  }

  private class MemoryDataStore<T>(initial: T) : DataStore<T> {
    val value = MutableStateFlow(initial)
    override val data: Flow<T> get() = value
    override suspend fun updateData(transform: suspend (t: T) -> T): T = value.updateAndGet { transform(it) }
  }

  private val link = FakeLink()
  private val settings = MemoryDataStore(SpeakerSettings(address = SPEAKER, disconnectDelayMinutes = 5))
  private val states = MutableStateFlow(PlayState.Paused)
  private val clock = Clock.fixed(Instant.ofEpochMilli(123_456), ZoneOffset.UTC)

  private val schedule = DisconnectSchedule()

  private fun autoDisconnect(scope: kotlinx.coroutines.CoroutineScope) =
    AutoDisconnect(settings, PlayStateManager(), link, clock, scope, schedule)

  @Test
  fun `a pause that lasts the delay disconnects, and is remembered`() = runTest {
    val job = backgroundScope.launch { autoDisconnect(this).run(states) }
    states.value = PlayState.Playing
    runCurrent()
    states.value = PlayState.Paused
    advanceTimeBy(5.minutes - 1.seconds)
    assertEquals(emptyList<String>(), link.disconnects)
    assertEquals("the pending disconnect is published", 123_456L + 5.minutes.inWholeMilliseconds, schedule.at.value)
    advanceTimeBy(2.seconds)
    assertEquals(listOf(SPEAKER), link.disconnects)
    assertEquals(123_456L, settings.value.value.lastOwnDisconnectMillis)
    assertEquals("nothing pending once done", null, schedule.at.value)
    job.cancel()
  }

  @Test
  fun `playing again within the delay cancels it`() = runTest {
    backgroundScope.launch { autoDisconnect(this).run(states) }
    states.value = PlayState.Playing
    runCurrent()
    states.value = PlayState.Paused
    advanceTimeBy(3.minutes)
    states.value = PlayState.Playing
    advanceTimeBy(10.minutes)
    assertEquals(emptyList<String>(), link.disconnects)
    assertEquals("playing again clears it", null, schedule.at.value)
  }

  @Test
  fun `opening the app paused never disconnects`() = runTest {
    backgroundScope.launch { autoDisconnect(this).run(states) }
    advanceTimeBy(60.minutes)
    assertEquals(emptyList<String>(), link.disconnects)
  }

  @Test
  fun `switched off, nothing is disconnected`() = runTest {
    settings.value.value = settings.value.value.copy(disconnectAfterPause = false)
    backgroundScope.launch { autoDisconnect(this).run(states) }
    states.value = PlayState.Playing
    runCurrent()
    states.value = PlayState.Paused
    advanceTimeBy(60.minutes)
    assertEquals(emptyList<String>(), link.disconnects)
  }
}

class WaitForMediaTest {

  @Test
  fun `returns how long it took for media to connect`() = runTest {
    var polls = 0
    val waited = SpeakerAutoPlay.waitForMedia(timeoutMs = 15_000, pollMs = 500) { ++polls > 3 }
    assertEquals(1_500L, waited)
  }

  @Test
  fun `gives up after the timeout`() = runTest {
    assertEquals(null, SpeakerAutoPlay.waitForMedia(timeoutMs = 2_000, pollMs = 500) { false })
  }

  @Test
  fun `already connected is no wait at all`() = runTest {
    assertEquals(0L, SpeakerAutoPlay.waitForMedia { true })
  }
}
