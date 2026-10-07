package voice.core.tags

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.datastore.core.DataStore
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import voice.core.data.BookId
import voice.core.data.repo.BookRepository
import voice.core.data.store.CurrentBookStore
import voice.core.data.store.LocalTagMapStore
import voice.core.data.store.ServerSelectionStore
import voice.core.data.store.TagMapStore
import voice.core.data.sync.LocalTagEntry
import voice.core.data.sync.TagEntry
import voice.core.logging.api.Logger
import voice.core.playback.PlayerController
import voice.core.playback.session.LogNotes
import voice.core.sync.BookSync
import voice.core.sync.SyncDirectory
import voice.core.sync.SyncProgress
import voice.core.sync.TagMapSync
import java.io.File
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import voice.core.strings.R as StringsR

/** What one scan should do. */
public sealed interface TagAction {
  /** The Settings → Tags page is open: show the tag, play nothing. */
  public data object Show : TagAction

  /** The same tag again, moments after the last read: a phone re-reads a tag held to it. */
  public data object Repeat : TagAction

  /** An unknown Tonie: nothing happens -- Tonies belong to the Toniebox until set up. */
  public data object Nothing : TagAction

  /** An unknown tag of another kind: offer to set it up in Settings → Tags. */
  public data object Prompt : TagAction

  /** Known (named), but no book chosen for it yet: say so. */
  public data class NoBook(val name: String) : TagAction

  /** The server's book for the tag. */
  public data class Play(val entry: TagEntry) : TagAction

  /** This phone's book for the tag: a phone book, or an override (NFC-TAGS-LOCAL.md). */
  public data class PlayLocal(
    val entry: LocalTagEntry,
    val name: String,
    /** The server has another book for the tag. */
    val override: Boolean = false,
  ) : TagAction
}

/**
 * Turns a scanned tag into playback (NFC-TAGS.md, NFC-TAGS-LOCAL.md): this
 * phone's book for the tag if it has one, else the server's -- resumed, or
 * from the beginning if set -- fetching a server book first if it is not on
 * the phone. An unknown tag does nothing, except on the Settings → Tags page,
 * which captures scans to show them.
 */
@SingleIn(AppScope::class)
@Inject
public class TagPlayer(
  private val context: Context,
  @TagMapStore
  private val tagMap: DataStore<Map<String, TagEntry>>,
  @LocalTagMapStore
  private val localMap: DataStore<Map<String, LocalTagEntry>>,
  private val tagMapSync: TagMapSync,
  @ServerSelectionStore
  private val selectionStore: DataStore<Set<String>>,
  @CurrentBookStore
  private val currentBookStore: DataStore<BookId?>,
  private val bookRepository: BookRepository,
  private val bookSync: BookSync,
  private val directory: SyncDirectory,
  private val playerController: PlayerController,
  private val logNotes: LogNotes,
  private val scope: CoroutineScope,
) {

  /** True while the Settings → Tags page is open. */
  public val capturing: MutableStateFlow<Boolean> = MutableStateFlow(false)

  /** The last tag read while capturing, for that page to show. */
  public val lastScan: StateFlow<ScannedTag?>
    field = MutableStateFlow<ScannedTag?>(null)

  private var last: Pair<String, Long>? = null

  /** A tag read while a bVoice screen is in front (reader mode). */
  public fun onTag(tag: ScannedTag) {
    scope.launch {
      if (handle(tag) == TagAction.Prompt) TagPromptActivity.show(context, tag)
    }
  }

  /**
   * Decides and acts, except for the prompt, which needs a screen to open
   * from: the caller shows it ([TagScanActivity] while it is still visible).
   */
  public suspend fun handle(tag: ScannedTag): TagAction {
    val now = System.currentTimeMillis()
    val previous = last
    var action = decide(tag, tagMap.data.first(), localMap.data.first(), capturing.value, previous, now)
    // Before the refresh below: the phone re-reads a tag held to it meanwhile, which is a repeat.
    if (action != TagAction.Repeat) last = tag.uid to now
    val settled = action is TagAction.Play || action is TagAction.PlayLocal || action == TagAction.Show || action == TagAction.Repeat
    if (!settled && tagMapSync.refresh()) {
      // Named or given a book on the server a moment ago? Ask once more.
      action = decide(tag, tagMap.data.first(), localMap.data.first(), capturing.value, previous, now)
    }
    when (action) {
      TagAction.Show -> lastScan.value = tag
      TagAction.Repeat, TagAction.Nothing, TagAction.Prompt -> Logger.i("Tag ${tag.uid}: $action")
      is TagAction.NoBook -> toast(context.getString(StringsR.string.tags_toast_no_book, action.name))
      is TagAction.Play -> scope.launch { play(tag, action.entry) }
      is TagAction.PlayLocal -> scope.launch { playLocal(tag, action) }
    }
    return action
  }

  private suspend fun play(
    tag: ScannedTag,
    entry: TagEntry,
  ) {
    val group = entry.group ?: return
    playServerBook(tag, group, entry.name.ifBlank { tag.uid }, entry.fromStart, "")
  }

  private suspend fun playLocal(
    tag: ScannedTag,
    action: TagAction.PlayLocal,
  ) {
    val id = BookId(action.entry.bookId ?: return)
    val how = if (action.override) " (override)" else " (phone)"
    val group = LocalTags.groupOf(id.value, Uri.fromFile(directory.books).toString())
    if (group != null) {
      playServerBook(tag, group, action.name, action.entry.fromStart, how)
      return
    }
    if (!onPhone(id)) {
      // Not the server's book instead: removing the phone's assignment does that.
      toast(context.getString(StringsR.string.tags_toast_not_on_phone, action.name))
      logNotes.note("TAG", "${tag.uid} ${action.name} -> a phone book: not on the phone$how")
      return
    }
    // The book's title, not its path: the listening log goes to the server.
    val title = bookRepository.get(id)?.content?.name ?: "a phone book"
    start(tag, id, action.name, action.entry.fromStart, title + how)
  }

  /** A server book: fetched first if it is not on the phone. [how] marks the listening log's note. */
  private suspend fun playServerBook(
    tag: ScannedTag,
    group: String,
    name: String,
    fromStart: Boolean,
    how: String,
  ) {
    val id = BookId(Uri.fromFile(File(directory.books, group)))
    if (!onPhone(id)) {
      toast(context.getString(StringsR.string.tags_toast_fetching, name))
      logNotes.note("TAG", "${tag.uid} $name -> $group: not on the phone, syncing")
      selectionStore.updateData { it + group }
      // A run of its own, after any running one: that one may have read the selection before the book was added.
      val done = withTimeoutOrNull(30.minutes) { bookSync.syncAndAwait() }
      // The sync asks for a rescan as it ends; give the library a moment to see the book.
      val arrived = done is SyncProgress.Finished && withTimeoutOrNull(60.seconds) {
        while (!onPhone(id)) delay(1_000)
        true
      } == true
      if (!arrived) {
        toast(context.getString(StringsR.string.tags_toast_fetch_failed, name))
        logNotes.note("TAG", "${tag.uid} $name -> $group: fetching failed")
        return
      }
    }
    start(tag, id, name, fromStart, group + how)
  }

  private suspend fun start(
    tag: ScannedTag,
    id: BookId,
    name: String,
    fromStart: Boolean,
    what: String,
  ) {
    val book = bookRepository.get(id) ?: return
    playerController.pauseIfCurrentBookDifferentFrom(id)
    currentBookStore.updateData { id }
    if (fromStart) playerController.setPosition(0, book.chapters.first().id)
    playerController.play()
    toast(context.getString(StringsR.string.tags_toast_playing, name))
    logNotes.note("TAG", "${tag.uid} $name -> $what${if (fromStart) ", from the beginning" else ""}")
  }

  private suspend fun onPhone(id: BookId): Boolean = bookRepository.get(id)?.content?.isActive == true

  private fun toast(text: String) {
    Handler(Looper.getMainLooper()).post { Toast.makeText(context, text, Toast.LENGTH_SHORT).show() }
  }

  internal companion object {
    const val REPEAT_WITHIN_MS = 4_000L

    fun decide(
      tag: ScannedTag,
      map: Map<String, TagEntry>,
      local: Map<String, LocalTagEntry>,
      capturing: Boolean,
      last: Pair<String, Long>?,
      nowMillis: Long,
    ): TagAction {
      if (capturing) return TagAction.Show
      if (last != null && last.first == tag.uid && nowMillis - last.second < REPEAT_WITHIN_MS) return TagAction.Repeat
      val entry = map[tag.uid]
      val mine = local[tag.uid]
      val name = nameOf(tag.uid, entry, mine)
      return when {
        mine?.bookId != null -> TagAction.PlayLocal(mine, name, override = !entry?.group.isNullOrBlank())
        !entry?.group.isNullOrBlank() -> TagAction.Play(entry)
        entry != null || mine != null -> TagAction.NoBook(name)
        tag.probablyTonie -> TagAction.Nothing
        else -> TagAction.Prompt
      }
    }

    /** The server's name for a tag, else this phone's, else its UID. */
    fun nameOf(
      uid: String,
      entry: TagEntry?,
      mine: LocalTagEntry?,
    ): String = entry?.name?.takeIf { it.isNotBlank() && it != uid } ?: mine?.name?.takeIf { it.isNotBlank() } ?: uid
  }
}
