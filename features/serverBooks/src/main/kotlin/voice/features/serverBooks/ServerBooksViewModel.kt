package voice.features.serverBooks

import android.content.Context
import android.widget.Toast
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.datastore.core.DataStore
import dev.zacsweers.metro.Inject
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import voice.core.common.DispatcherProvider
import voice.core.common.MainScope
import voice.core.data.store.ServerConfigStore
import voice.core.data.store.ServerSelectionStore
import voice.core.data.sync.ServerConfig
import voice.core.sync.BookSync
import voice.core.sync.CatalogueState
import voice.core.sync.ServerBook
import voice.core.sync.SyncProgress
import voice.features.serverBooks.ServerBooksViewState.Sync
import voice.navigation.Navigator
import voice.core.strings.R as StringsR

@Inject
class ServerBooksViewModel(
  private val context: Context,
  private val bookSync: BookSync,
  @ServerConfigStore
  private val configStore: DataStore<ServerConfig>,
  @ServerSelectionStore
  private val selectionStore: DataStore<Set<String>>,
  private val navigator: Navigator,
  dispatcherProvider: DispatcherProvider,
) {

  private val scope = MainScope(dispatcherProvider)
  private var serverDialog by mutableStateOf<ServerBooksViewState.ServerDialog?>(null)

  @Composable
  fun viewState(): ServerBooksViewState {
    val config by remember { configStore.data }.collectAsState(ServerConfig())
    val selection by remember { selectionStore.data }.collectAsState(emptySet())
    val catalogue by bookSync.catalogue.collectAsState()
    val progress by bookSync.progress.collectAsState()

    LaunchedEffect(Unit) {
      if (catalogue == CatalogueState.NotLoaded) bookSync.refresh()
    }

    val books = (catalogue as? CatalogueState.Loaded)?.books.orEmpty()
    return ServerBooksViewState(
      serverUrl = config.url,
      serverOn = config.enabled,
      authors = books.byAuthor(selection),
      loading = catalogue == CatalogueState.Loading,
      error = (catalogue as? CatalogueState.Failed)?.message,
      sync = progress.toViewState(),
      serverDialog = serverDialog,
    )
  }

  fun toggle(group: String) {
    scope.launch {
      selectionStore.updateData { if (group in it) it - group else it + group }
    }
  }

  fun sync() {
    bookSync.sync()
  }

  fun cancel() {
    bookSync.cancel()
  }

  /** Reloads the list of the server's books, and says so: a quick reload otherwise looks like nothing. */
  fun refresh() {
    scope.launch {
      val text = when (val state = bookSync.reload()) {
        is CatalogueState.Loaded -> context.resources.getQuantityString(
          StringsR.plurals.server_books_refreshed,
          state.books.size,
          state.books.size,
        )
        is CatalogueState.Failed -> context.getString(StringsR.string.server_books_refresh_failed, state.message)
        else -> return@launch
      }
      Toast.makeText(context, text, Toast.LENGTH_SHORT).show()
    }
  }

  fun editServer() {
    scope.launch {
      val config = configStore.data.first()
      serverDialog = ServerBooksViewState.ServerDialog(config.url, config.token, config.deviceName)
    }
  }

  fun dismissServerDialog() {
    serverDialog = null
  }

  fun saveServer(dialog: ServerBooksViewState.ServerDialog) {
    serverDialog = null
    scope.launch {
      configStore.updateData {
        it.copy(url = dialog.url.trim(), token = dialog.token.trim(), deviceName = dialog.deviceName.cleanDeviceName())
      }
      bookSync.refresh()
    }
  }

  fun setServerOn(on: Boolean) {
    scope.launch {
      configStore.updateData { it.copy(enabled = on) }
      if (on) bookSync.refresh()
    }
  }

  fun close() {
    navigator.goBack()
  }
}

private fun SyncProgress.toViewState(): Sync = when (this) {
  SyncProgress.Idle -> Sync.Idle
  is SyncProgress.Running -> Sync.Running(
    progress = if (totalBytes > 0) (doneBytes.toFloat() / totalBytes).coerceIn(0F, 1F) else null,
    filesDone = filesDone,
    filesTotal = filesTotal,
  )
  is SyncProgress.Finished -> Sync.Finished(
    fetched = fetched,
    deleted = deleted,
    failed = failed,
    keptWhilePlaying = keptWhilePlaying.size,
    logsSent = logsSent,
    logError = logError,
  )
  is SyncProgress.Failed -> Sync.Failed(message)
}

/** Books under their authors, both alphabetical; the author falls back to the group's first folder. */
internal fun List<ServerBook>.byAuthor(selection: Set<String>): List<ServerBooksViewState.Author> =
  groupBy { it.item.author.ifBlank { it.group.substringBefore('/', "") } }
    .toSortedMap(String.CASE_INSENSITIVE_ORDER)
    .map { (author, rows) ->
      ServerBooksViewState.Author(
        name = author,
        books = rows.sortedBy { it.item.title.lowercase() }.map { book ->
          ServerBooksViewState.Book(
            group = book.group,
            title = book.item.title,
            hours = book.item.duration / 3600.0,
            megabytes = book.item.bytes / 1_048_576.0,
            parts = book.item.parts,
            selected = book.group in selection,
            status = book.status,
          )
        },
      )
    }

/** Keeps a typed name within what the server accepts; empty means "make one up at the next sync". */
internal fun String.cleanDeviceName(): String = replace(Regex("[^A-Za-z0-9-]+"), "-").trim('-').take(64)
