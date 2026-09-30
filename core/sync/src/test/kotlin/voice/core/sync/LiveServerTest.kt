package voice.core.sync

import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import voice.core.data.BookId
import voice.core.data.sync.ServerConfig
import java.io.File
import kotlin.time.Duration.Companion.minutes

/**
 * Syncs one real book from a real server. Skipped unless VOICE_LIVE_TOKEN is
 * set; VOICE_LIVE_URL and VOICE_LIVE_BOOK override the defaults.
 */
class LiveServerTest {

  @get:Rule
  val temp = TemporaryFolder()

  @Test
  fun `syncs a real book from the live server`() = runTest(timeout = 10.minutes) {
    val token = System.getenv("VOICE_LIVE_TOKEN").orEmpty()
    assumeTrue("VOICE_LIVE_TOKEN not set", token.isNotBlank())
    val url = System.getenv("VOICE_LIVE_URL") ?: "http://127.0.0.1:8300/r/Audiobooks/"
    val group = System.getenv("VOICE_LIVE_BOOK") ?: "Julia Donaldson/Room on the Broom"

    val books = temp.newFolder("books")
    val sync = BookSync(
      client = ServerClient(OkHttpClient()),
      configStore = MemoryDataStore(ServerConfig(url = url, token = token)),
      selectionStore = MemoryDataStore(setOf(group)),
      currentBookStore = MemoryDataStore<BookId?>(null),
      directory = SyncDirectory(books = books, stateFile = File(temp.root, "state.json")),
      rescan = {},
    )
    sync.runSync()

    val finished = sync.progress.value as SyncProgress.Finished
    val book = (sync.catalogue.value as CatalogueState.Loaded).books.single { it.group == group }
    println("live: $finished; ${File(books, group).list()?.sorted()}")
    assertEquals(0, finished.failed)
    assertEquals(ServerBook.Status.OnDevice, book.status)
  }
}
