package voice.core.playback.playstate

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

@SingleIn(AppScope::class)
@Inject
class PlayStateManager {

  val playStateFlow: StateFlow<PlayState>
    field = MutableStateFlow(PlayState.Paused)

  /**
   * The last pause was asked for (a button, the app, a controller), not the
   * system's doing (the audio route changing, focus lost).
   */
  var pausedOnRequest: Boolean = false

  var playState: PlayState
    set(value) {
      playStateFlow.value = value
    }
    get() = playStateFlow.value

  enum class PlayState {
    Playing,
    Paused,
  }
}
