package voice.core.playback.session

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlin.time.Duration

/** One key-down as the session received it, and what a back/forward tap did. */
data class KeyPress(
  val key: String,
  /** Wall-clock time, for showing. */
  val atMillis: Long,
  /** Since the previous key-down arrived. */
  val gapMillis: Long?,
  /** For back/forward: the tap's place in its gesture, what it added, and the gesture's total. */
  val forward: Boolean? = null,
  val taps: Int? = null,
  val added: Duration? = null,
  val total: Duration? = null,
)

/** The last few key presses, newest first -- for the pillow speaker's tap tester. */
@SingleIn(AppScope::class)
@Inject
class MediaKeyLog {
  val presses: StateFlow<List<KeyPress>>
    field = MutableStateFlow(emptyList())

  internal fun add(press: KeyPress) {
    presses.update { (listOf(press) + it).take(20) }
  }

  fun clear() {
    presses.value = emptyList()
  }
}
