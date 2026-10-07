package voice.core.sync

import android.app.Application
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.BindingContainer
import dev.zacsweers.metro.ContributesTo
import dev.zacsweers.metro.Provides
import dev.zacsweers.metro.SingleIn
import voice.core.scanner.MediaScanTrigger
import java.io.File

/**
 * Where synced books live, and where the sync keeps its own notes.
 *
 * [books] is the app's own external files directory, laid out Author/Title/files
 * like the server; no other app can write there. [stateFile] is kept apart from
 * it, in internal storage, so the scanner never sees it.
 */
public class SyncDirectory(
  public val books: File,
  internal val stateFile: File,
  /** Where :core:listeninglog writes, one file per day; uploaded after each sync. */
  internal val logs: File = File(books.parentFile, "listening-log"),
  internal val uploadsFile: File = File(stateFile.parentFile, "uploads.json"),
  /** Tag pictures fetched from the server, one UID.jpg each; kept so they work offline. */
  internal val tagCovers: File = File(stateFile.parentFile, "tag-covers"),
)

/** Asks the library to look at the books directory again. */
public fun interface LibraryRescan {
  public fun rescan()
}

@BindingContainer
@ContributesTo(AppScope::class)
public object SyncModule {

  @Provides
  @SingleIn(AppScope::class)
  private fun syncDirectory(context: Application): SyncDirectory = SyncDirectory(
    books = File(context.getExternalFilesDir(null) ?: context.filesDir, "books"),
    stateFile = File(context.filesDir, "sync/state.json"),
  )

  @Provides
  private fun libraryRescan(trigger: MediaScanTrigger): LibraryRescan = LibraryRescan {
    trigger.scan(restartIfScanning = true)
  }
}
