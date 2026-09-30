package voice.core.playback.session

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow

/** A line for the listening log from somewhere other than play/pause: `KIND` and free text. */
data class LogNote(
  val kind: String,
  val text: String,
  val atMillis: Long = System.currentTimeMillis(),
)

/**
 * Notes for the listening log -- tap results, the pillow speaker waking the app
 * -- so they can be read on the server after a sync. Replays a few, because a
 * process started by the companion service may note something before the log
 * has started listening.
 */
@SingleIn(AppScope::class)
@Inject
class LogNotes {
  private val flow = MutableSharedFlow<LogNote>(replay = 16, extraBufferCapacity = 64)
  val notes: SharedFlow<LogNote> get() = flow

  fun note(
    kind: String,
    text: String,
  ) {
    flow.tryEmit(LogNote(kind, text))
  }
}
