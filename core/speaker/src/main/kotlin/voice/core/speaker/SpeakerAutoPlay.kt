package voice.core.speaker

import androidx.datastore.core.DataStore
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import voice.core.data.speaker.SpeakerSettings
import voice.core.data.store.SpeakerSettingsStore
import voice.core.logging.api.Logger
import voice.core.playback.PlayerController
import voice.core.playback.playstate.PlayStateManager
import voice.core.playback.session.LogNotes
import java.time.Clock
import kotlin.time.Duration.Companion.minutes

/**
 * The speaker appeared: play the current book, every time (the choice made
 * for bVoice) -- except straight after bVoice dropped the speaker itself,
 * because a speaker that reconnects on its own (AUDIOBOOKS.md §6, second
 * caveat) would otherwise restart the book it was just stopped for.
 */
@Inject
@SingleIn(AppScope::class)
public class SpeakerAutoPlay(
  @SpeakerSettingsStore
  private val settingsStore: DataStore<SpeakerSettings>,
  private val playerController: PlayerController,
  private val playStateManager: PlayStateManager,
  private val link: SpeakerLink,
  private val clock: Clock,
  private val logNotes: LogNotes,
  private val scope: CoroutineScope,
) {

  private var job: Job? = null

  /**
   * The speaker appeared. The event comes when the Bluetooth link does, before
   * the speaker's media connection is ready -- playing then was stopped at once
   * -- so wait for media, give the route a second, then play; and if playback
   * stops again within a few seconds, try once more. A newer event replaces
   * one still waiting.
   */
  public fun onSpeakerConnected(
    address: String?,
    why: String = "connected",
  ) {
    job?.cancel()
    job = scope.launch { connect(address, why) }
  }

  /**
   * The system woke the companion service with no connect event. If that is
   * a fresh start of the app (an automation opening it after a force stop, when
   * the connect itself was missed) and the speaker's media is already
   * connected, treat it as the connect. A wake of an app already running is not
   * a request to play: it happens after a pause, too.
   */
  public fun onServiceWoken(processAgeMillis: Long) {
    scope.launch {
      val settings = settingsStore.data.first()
      val address = settings.address ?: return@launch
      if (!settings.autoPlay || processAgeMillis > FRESH_START_MS) return@launch
      if (playStateManager.playState == PlayStateManager.PlayState.Playing) return@launch
      if (link.isConnected(address)) {
        onSpeakerConnected(address, "fresh start with media already connected")
      }
    }
  }

  private suspend fun connect(
    address: String?,
    why: String,
  ) {
    val settings = settingsStore.data.first()
    val target = settings.address
    if (!shouldAutoPlay(settings, address, clock.millis()) || target == null) {
      val reason = when {
        !settings.autoPlay -> "auto-play is off"
        target == null -> "no speaker chosen"
        address != null && !address.equals(target, ignoreCase = true) -> "another device"
        else -> "just disconnected by bVoice"
      }
      logNotes.note("SPEAKER", "$why (${address ?: "no address"}): not playing, $reason")
      return
    }
    val waited = waitForMedia { link.isConnected(target) }
    logNotes.note(
      "SPEAKER",
      if (waited != null) {
        "$why: media connected after ${waited}ms"
      } else {
        "$why: media not connected after ${MEDIA_TIMEOUT_MS}ms, playing anyway"
      },
    )
    delay(ROUTE_SETTLE_MS)
    Logger.i("Pillow speaker: playing")
    logNotes.note("SPEAKER", "$why: playing")
    playerController.play()

    // Playback stopped straight away is the route changing under it: once more.
    val started = withTimeoutOrNull(START_TIMEOUT_MS) {
      playStateManager.playStateFlow.first { it == PlayStateManager.PlayState.Playing }
    }
    if (started == null) {
      logNotes.note("SPEAKER", "$why: did not start within ${START_TIMEOUT_MS}ms, retrying")
    } else {
      val stopped = withTimeoutOrNull(STOPPED_WITHIN_MS) {
        playStateManager.playStateFlow.first { it == PlayStateManager.PlayState.Paused }
      }
      if (stopped == null) return
      logNotes.note("SPEAKER", "$why: stopped within ${STOPPED_WITHIN_MS}ms, retrying")
    }
    delay(RETRY_AFTER_MS)
    if (link.isConnected(target)) {
      logNotes.note("SPEAKER", "$why: playing again")
      playerController.play()
    } else {
      logNotes.note("SPEAKER", "$why: media gone, not retrying")
    }
  }

  internal companion object {
    val ownDisconnectGrace = 2.minutes
    const val MEDIA_TIMEOUT_MS = 15_000L
    const val ROUTE_SETTLE_MS = 1_000L
    const val START_TIMEOUT_MS = 5_000L
    const val STOPPED_WITHIN_MS = 5_000L
    const val RETRY_AFTER_MS = 2_000L
    const val FRESH_START_MS = 15_000L

    /** Polls [connected] until true or the timeout; how long it took, or null. */
    suspend fun waitForMedia(
      timeoutMs: Long = MEDIA_TIMEOUT_MS,
      pollMs: Long = 500,
      connected: suspend () -> Boolean,
    ): Long? {
      var waited = 0L
      while (waited <= timeoutMs) {
        if (connected()) return waited
        delay(pollMs)
        waited += pollMs
      }
      return null
    }

    fun shouldAutoPlay(
      settings: SpeakerSettings,
      address: String?,
      nowMillis: Long,
    ): Boolean {
      if (!settings.autoPlay || settings.address == null) return false
      if (address != null && !address.equals(settings.address, ignoreCase = true)) return false
      return nowMillis - settings.lastOwnDisconnectMillis > ownDisconnectGrace.inWholeMilliseconds
    }
  }
}
