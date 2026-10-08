package voice.features.tags

import android.content.Context
import android.widget.Toast
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.core.net.toUri
import androidx.datastore.core.DataStore
import dev.zacsweers.metro.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import voice.core.common.DispatcherProvider
import voice.core.common.MainScope
import voice.core.data.Book
import voice.core.data.BookId
import voice.core.data.repo.BookRepository
import voice.core.data.store.ServerConfigStore
import voice.core.data.store.TagMapStore
import voice.core.data.store.TagsEnabledStore
import voice.core.data.sync.LocalTagEntry
import voice.core.data.sync.ServerConfig
import voice.core.data.sync.TagEntry
import voice.core.sync.BookSync
import voice.core.sync.CatalogueState
import voice.core.sync.ServerBook
import voice.core.sync.TagMapSync
import voice.core.tags.LocalTags
import voice.core.tags.ScannedTag
import voice.core.tags.TagBook
import voice.core.tags.TagPlayer
import voice.core.tags.TagSource
import voice.navigation.Navigator
import java.io.File
import java.io.IOException
import voice.core.strings.R as StringsR

/** One tag as the page shows it: where its book comes from, and what it is. */
data class TagRowState(
  val uid: String,
  val name: String,
  /** Null: no book yet. */
  val source: TagSource?,
  val book: String?,
  /** For an override: the server's book, which this phone does not play. */
  val serverBook: String?,
  val fromStart: Boolean,
  val picture: File?,
)

/** A book the picker offers. */
data class BookOption(
  val title: String,
  val author: String?,
  val server: Boolean,
  val onPhone: Boolean,
  val target: TagBook,
)

/** The server, as far as tags are concerned. */
sealed interface ServerStatus {
  data class On(val host: String) : ServerStatus

  data object Off : ServerStatus

  data object NotSetUp : ServerStatus
}

data class TagsViewState(
  val lastScan: ScannedTag?,
  /** The tag just read, if it is known in any way. */
  val lastScanRow: TagRowState?,
  val tags: List<TagRowState>,
  /** A server is set up: names go there, and server books are chosen there. */
  val configured: Boolean,
  val server: ServerStatus,
  val tagsOn: Boolean,
  val books: List<BookOption>,
  val sending: Boolean,
  val message: String?,
  val failed: Boolean,
)

@Inject
class TagsViewModel(
  private val context: Context,
  private val tagPlayer: TagPlayer,
  private val tagMapSync: TagMapSync,
  private val localTags: LocalTags,
  @TagMapStore
  private val tagMap: DataStore<Map<String, TagEntry>>,
  private val bookRepository: BookRepository,
  private val bookSync: BookSync,
  @ServerConfigStore
  private val serverConfigStore: DataStore<ServerConfig>,
  @TagsEnabledStore
  private val tagsEnabledStore: DataStore<Boolean>,
  private val navigator: Navigator,
  dispatcherProvider: DispatcherProvider,
) {

  private val scope = MainScope(dispatcherProvider)
  private var sending by mutableStateOf(false)
  private var message by mutableStateOf<String?>(null)
  private var failed by mutableStateOf(false)

  @Composable
  fun viewState(): TagsViewState {
    val map by remember { tagMap.data }.collectAsState(emptyMap())
    val local by remember { localTags.map }.collectAsState(emptyMap())
    val library by remember { bookRepository.flow() }.collectAsState(emptyList())
    val catalogue by bookSync.catalogue.collectAsState()
    val configured by remember { tagMapSync.configured }.collectAsState(false)
    val lastScan by tagPlayer.lastScan.collectAsState()
    // Off the main thread, and again only when the map changes (pictures come with it).
    val covers by produceState(emptyMap<String, File>(), map) {
      value = withContext(Dispatchers.IO) { tagMapSync.covers() }
    }
    val serverConfig by remember { serverConfigStore.data }.collectAsState(ServerConfig())
    val tagsOn by remember { tagsEnabledStore.data }.collectAsState(true)
    val books = library.filter { it.content.isActive }.associateBy { it.id }
    val serverBooks = (catalogue as? CatalogueState.Loaded)?.books.orEmpty()
    val rows = (map.keys + local.keys).map { uid -> row(uid, map[uid], local[uid], books, serverBooks, covers) }
    return TagsViewState(
      lastScan = lastScan,
      lastScanRow = lastScan?.let { tag -> rows.firstOrNull { it.uid == tag.uid } },
      tags = rows.sortedBy { it.name.lowercase() },
      configured = configured,
      server = when {
        serverConfig.url.isBlank() -> ServerStatus.NotSetUp
        !serverConfig.enabled -> ServerStatus.Off
        else -> ServerStatus.On(serverConfig.url.toUri().host ?: serverConfig.url)
      },
      tagsOn = tagsOn,
      books = options(books.values, if (configured) serverBooks else emptyList(), configured),
      sending = sending,
      message = message,
      failed = failed,
    )
  }

  private fun row(
    uid: String,
    entry: TagEntry?,
    mine: LocalTagEntry?,
    books: Map<BookId, Book>,
    serverBooks: List<ServerBook>,
    covers: Map<String, File>,
  ): TagRowState {
    val name = entry?.name?.takeIf { it.isNotBlank() && it != uid } ?: mine?.name?.takeIf { it.isNotBlank() } ?: uid
    val serverGroup = entry?.group?.takeIf { it.isNotBlank() }
    val mineId = mine?.bookId?.let(::BookId)
    return when {
      mineId != null -> TagRowState(
        uid = uid,
        name = name,
        source = if (serverGroup != null) TagSource.Override else TagSource.Phone,
        book = books[mineId]?.content?.name ?: localTags.groupOf(mineId)?.let { title(it, books, serverBooks) } ?: mineId.value,
        serverBook = serverGroup?.let { title(it, books, serverBooks) },
        fromStart = mine.fromStart,
        picture = books[mineId]?.content?.cover,
      )
      serverGroup != null -> TagRowState(
        uid = uid,
        name = name,
        source = TagSource.Server,
        book = title(serverGroup, books, serverBooks),
        serverBook = null,
        fromStart = entry.fromStart,
        picture = covers[uid] ?: books[localTags.bookOf(serverGroup)]?.content?.cover,
      )
      else -> TagRowState(uid, name, null, null, null, false, covers[uid])
    }
  }

  private fun title(
    group: String,
    books: Map<BookId, Book>,
    serverBooks: List<ServerBook>,
  ): String = books[localTags.bookOf(group)]?.content?.name
    ?: serverBooks.firstOrNull { it.item.group == group }?.item?.title
    ?: group

  private fun options(
    books: Collection<Book>,
    serverBooks: List<ServerBook>,
    configured: Boolean,
  ): List<BookOption> {
    val onPhone = books.map { book ->
      val group = localTags.groupOf(book.id)
      BookOption(
        title = book.content.name,
        author = book.content.author,
        server = group != null && configured,
        onPhone = true,
        target = if (group != null && configured) TagBook.Server(group) else TagBook.Phone(book.id),
      )
    }
    val groupsOnPhone = onPhone.mapNotNull { (it.target as? TagBook.Server)?.group }.toSet()
    val notYet = serverBooks.filter { it.item.group !in groupsOnPhone }.map {
      BookOption(it.item.title, it.item.author.ifBlank { null }, server = true, onPhone = false, target = TagBook.Server(it.item.group))
    }
    return (onPhone + notYet).sortedBy { it.title.lowercase() }
  }

  fun setTagsOn(on: Boolean) {
    scope.launch { tagsEnabledStore.updateData { on } }
  }

  /** While the page is open, tags are shown here instead of played. */
  fun setCapturing(on: Boolean) {
    tagPlayer.capturing.value = on
  }

  /** The picker lists server books not yet on the phone too: fetch the catalogue once. */
  fun openPicker() {
    if (bookSync.catalogue.value !is CatalogueState.Loaded) bookSync.refresh()
  }

  fun send(
    tag: ScannedTag,
    name: String,
  ) {
    if (name.isBlank()) return
    act { localTags.name(tag, name.trim()) }
  }

  fun choose(
    tag: ScannedTag,
    book: BookOption,
    fromStart: Boolean,
  ) = act { localTags.choose(tag, book.target, fromStart) }

  fun setFromStart(
    tag: ScannedTag,
    fromStart: Boolean,
  ) = act { localTags.setFromStart(tag, fromStart) }

  fun removeBook(tag: ScannedTag) = act { localTags.remove(tag) }

  private fun act(action: suspend () -> Unit) {
    if (sending) return
    sending = true
    message = null
    scope.launch {
      try {
        action()
        failed = false
        message = null
      } catch (e: IOException) {
        failed = true
        message = e.message ?: e.toString()
      } finally {
        sending = false
      }
    }
  }

  /** Fetches the server's tags and all their pictures again, and says how that went. */
  fun refresh() {
    scope.launch {
      val text = when {
        !tagMapSync.configured.first() -> context.getString(StringsR.string.tags_refresh_no_server)
        tagMapSync.refresh(forcePictures = true) -> {
          localTags.adopt()
          val count = tagMap.data.first().size
          context.resources.getQuantityString(StringsR.plurals.tags_refreshed, count, count)
        }
        else -> context.getString(StringsR.string.tags_refresh_failed)
      }
      Toast.makeText(context, text, Toast.LENGTH_SHORT).show()
    }
  }

  fun close() {
    navigator.goBack()
  }
}
