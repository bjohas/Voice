package voice.core.listeninglog

import android.app.Application
import android.content.Context
import androidx.datastore.core.DataStore
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoSet
import dev.zacsweers.metro.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.dropWhile
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import voice.core.data.BookId
import voice.core.data.repo.BookRepository
import voice.core.data.store.CurrentBookStore
import voice.core.initializer.AppInitializer
import voice.core.logging.api.Logger
import voice.core.playback.playstate.PlayStateManager
import voice.core.playback.playstate.PlayStateManager.PlayState
import voice.core.playback.session.LogNote
import voice.core.playback.session.LogNotes
import java.io.File
import java.io.IOException
import java.time.Clock
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import kotlin.coroutines.CoroutineContext
import kotlin.time.Duration.Companion.milliseconds

/** What the log needs to know about the book at the moment of a start or stop. */
public data class LoggedBook(
  val title: String,
  val positionMs: Long,
  val durationMs: Long,
)

/**
 * A plain-text listening log: one line per start and per stop, with the local
 * time and its offset, the book's title, and where in the book it was. One file
 * per local date, in the app's external files directory under listening-log/,
 * so it can be pulled off the phone with adb or USB.
 */
@Inject
@ContributesIntoSet(AppScope::class)
public class ListeningLog(
  private val context: Context,
  private val playStateManager: PlayStateManager,
  @CurrentBookStore
  private val currentBookStore: DataStore<BookId?>,
  private val bookRepository: BookRepository,
  private val clock: Clock,
  private val scope: CoroutineScope,
  private val logNotes: LogNotes,
) : AppInitializer {

  override fun onAppStart(application: Application) {
    val directory = File(context.getExternalFilesDir(null) ?: context.filesDir, "listening-log")
    scope.launch {
      record(playStateManager.playStateFlow, directory, clock) { currentBook() }
    }
    scope.launch {
      logNotes.notes.collect { note ->
        val time = ZonedDateTime.ofInstant(java.time.Instant.ofEpochMilli(note.atMillis), clock.zone)
        append(directory, time, formatNote(time, note), Dispatchers.IO)
      }
    }
  }

  private suspend fun currentBook(): LoggedBook? {
    val id = currentBookStore.data.first() ?: return null
    val book = bookRepository.get(id) ?: return null
    return LoggedBook(title = book.content.name, positionMs = book.position, durationMs = book.duration)
  }

  internal companion object {
    internal suspend fun record(
      playStates: Flow<PlayState>,
      directory: File,
      clock: Clock,
      io: CoroutineContext = Dispatchers.IO,
      book: suspend () -> LoggedBook?,
    ) {
      playStates
        .distinctUntilChanged()
        .dropWhile { it != PlayState.Playing }
        .collect { state ->
          val now = ZonedDateTime.now(clock)
          // The position is flushed to the database when play/pause changes; give that a moment.
          if (state == PlayState.Paused) delay(STOP_SETTLE)
          val entry = book() ?: return@collect
          val action = if (state == PlayState.Playing) "START" else "STOP"
          append(directory, now, formatLine(now, action, entry), io)
        }
    }

    private suspend fun append(
      directory: File,
      time: ZonedDateTime,
      line: String,
      io: CoroutineContext,
    ) = withContext(io) {
      try {
        directory.mkdirs()
        File(directory, fileName(time)).appendText(line + "\n")
      } catch (e: IOException) {
        Logger.w(e, "Could not write the listening log")
      }
    }

    val STOP_SETTLE = 500.milliseconds

    private val timeFormat = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss xxx")

    fun fileName(time: ZonedDateTime): String = "${time.toLocalDate()}.txt"

    fun formatLine(
      time: ZonedDateTime,
      action: String,
      book: LoggedBook,
    ): String = listOf(
      time.format(timeFormat),
      action,
      book.title.replace('\t', ' '),
      "${clockTime(book.positionMs)} / ${clockTime(book.durationMs)}",
    ).joinToString("\t")

    fun formatNote(
      time: ZonedDateTime,
      note: LogNote,
    ): String = listOf(time.format(timeFormat), note.kind, note.text.replace('\t', ' ')).joinToString("\t")

    fun clockTime(ms: Long): String {
      val seconds = ms / 1000
      return "%d:%02d:%02d".format(seconds / 3600, seconds / 60 % 60, seconds % 60)
    }
  }
}
