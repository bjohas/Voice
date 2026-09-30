package voice.core.speaker

import android.app.Application
import androidx.datastore.core.DataStore
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoSet
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.dropWhile
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import voice.core.data.speaker.SpeakerSettings
import voice.core.data.store.SpeakerSettingsStore
import voice.core.initializer.AppInitializer
import voice.core.logging.api.Logger
import voice.core.playback.playstate.PlayStateManager
import voice.core.playback.playstate.PlayStateManager.PlayState
import java.time.Clock
import kotlin.time.Duration.Companion.minutes

/**
 * After any pause that lasts [SpeakerSettings.disconnectDelayMinutes], drop
 * the pillow speaker so its own auto-off can power it down (AUDIOBOOKS.md §6:
 * silence alone keeps the link up, and some speakers only count a lost link).
 * Playing again within the delay cancels it. Only a pause that follows
 * playing counts, so opening the app paused never disconnects anything.
 */
@Inject
@ContributesIntoSet(AppScope::class)
public class AutoDisconnect(
  @SpeakerSettingsStore
  private val settingsStore: DataStore<SpeakerSettings>,
  private val playStateManager: PlayStateManager,
  private val link: SpeakerLink,
  private val clock: Clock,
  private val scope: CoroutineScope,
  private val schedule: DisconnectSchedule,
) : AppInitializer {

  override fun onAppStart(application: Application) {
    scope.launch { run(playStateManager.playStateFlow) }
  }

  internal suspend fun run(playStates: Flow<PlayState>) {
    playStates
      .dropWhile { it != PlayState.Playing }
      .collectLatest { state ->
        if (state != PlayState.Paused) return@collectLatest
        val settings = settingsStore.data.first()
        val address = settings.address
        if (!settings.disconnectAfterPause || address == null) return@collectLatest
        val wait = settings.disconnectDelayMinutes.coerceAtLeast(0).minutes
        schedule.at.value = clock.millis() + wait.inWholeMilliseconds
        try {
          delay(wait)
        } finally {
          // Played again (collectLatest cancels the wait) or about to disconnect: nothing pending.
          schedule.at.value = null
        }
        val result = link.disconnect(address)
        Logger.i("Paused ${settings.disconnectDelayMinutes} min: disconnect pillow speaker -> $result")
        if (result == DisconnectResult.Disconnected) {
          settingsStore.updateData { it.copy(lastOwnDisconnectMillis = clock.millis()) }
        }
      }
  }
}

/** When auto-disconnect will drop the speaker (epoch millis), or null when nothing is pending. */
@Inject
@SingleIn(AppScope::class)
public class DisconnectSchedule {
  public val at: MutableStateFlow<Long?> = MutableStateFlow(null)
}
