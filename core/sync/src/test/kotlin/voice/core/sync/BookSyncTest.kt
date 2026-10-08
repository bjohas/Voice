package voice.core.sync

import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import voice.core.data.BookId
import voice.core.data.sync.ServerConfig
import java.io.File

class BookSyncTest {

  @get:Rule
  val temp = TemporaryFolder()

  private val server = FakeServer()
  private lateinit var books: File
  private lateinit var config: MemoryDataStore<ServerConfig>
  private val selection = MemoryDataStore(emptySet<String>())
  private val logEnabled = MemoryDataStore(true)
  private val currentBook = MemoryDataStore<BookId?>(null)
  private var rescans = 0
  private lateinit var sync: BookSync

  private val hexe = "Otfried-Preussler/Die kleine Hexe"
  private val broom = "Julia Donaldson/Room on the Broom"

  @Before
  fun setUp() {
    server.book(hexe, "01 - Kapitel.m4a" to "hexe one", "02 - Kapitel.m4a" to "hexe two")
    server.book(broom, "01 - Room on the Broom.m4a" to "broom one", "cover.jpg" to "jpeg")
    server.start()
    books = temp.newFolder("books")
    config = MemoryDataStore(ServerConfig(url = server.url, token = FakeServer.TOKEN, deviceName = "pixel-ab12"))
    sync = BookSync(
      client = ServerClient(OkHttpClient()),
      configStore = config,
      selectionStore = selection,
      currentBookStore = currentBook,
      directory = SyncDirectory(books = books, stateFile = File(temp.root, "state/state.json")),
      rescan = { rescans++ },
      logEnabled = logEnabled,
    )
  }

  @After
  fun tearDown() {
    server.server.close()
  }

  private fun file(path: String) = File(books, path)

  private suspend fun select(vararg groups: String) {
    selection.updateData { groups.toSet() }
  }

  @Test
  fun `fetches the selected books into Author and Title folders`() = runTest {
    select(hexe)
    sync.runSync()

    assertEquals("hexe one", file("$hexe/01 - Kapitel.m4a").readText())
    assertEquals("hexe two", file("$hexe/02 - Kapitel.m4a").readText())
    assertFalse("unselected book fetched", file(broom).exists())
    assertFalse("server's own top-level file fetched", file("content.json").exists())
    assertEquals(SyncProgress.Finished(fetched = 2, failed = 0, deleted = 0, keptWhilePlaying = emptySet()), sync.progress.value)
    assertEquals(1, rescans)
  }

  @Test
  fun `a second sync fetches nothing`() = runTest {
    select(hexe)
    sync.runSync()
    val fetchesBefore = server.requests.count { it.url.encodedPath.contains("/content/") }
    sync.runSync()

    assertEquals(fetchesBefore, server.requests.count { it.url.encodedPath.contains("/content/") })
    assertEquals(0, (sync.progress.value as SyncProgress.Finished).fetched)
  }

  @Test
  fun `unselecting a book removes its files and its empty folders`() = runTest {
    select(hexe, broom)
    sync.runSync()
    select(hexe)
    sync.runSync()

    assertFalse(file(broom).exists())
    assertFalse("empty author folder left behind", file("Julia Donaldson").exists())
    assertTrue(file("$hexe/01 - Kapitel.m4a").exists())
    assertEquals(2, (sync.progress.value as SyncProgress.Finished).deleted)
  }

  @Test
  fun `the book that is playing is not removed`() = runTest {
    select(hexe, broom)
    sync.runSync()
    currentBook.updateData { BookId(file(broom).toURI().toString()) }
    select(hexe)
    sync.runSync()

    assertTrue(file("$broom/01 - Room on the Broom.m4a").exists())
    assertEquals(setOf(broom), (sync.progress.value as SyncProgress.Finished).keptWhilePlaying)

    currentBook.updateData { null }
    sync.runSync()
    assertFalse("removed once no longer playing", file(broom).exists())
  }

  @Test
  fun `an interrupted download resumes with a range request`() = runTest {
    select(hexe)
    file(hexe).mkdirs()
    file("$hexe/01 - Kapitel.m4a.part").writeText("hexe")
    sync.runSync()

    assertEquals("hexe one", file("$hexe/01 - Kapitel.m4a").readText())
    assertFalse(file("$hexe/01 - Kapitel.m4a.part").exists())
    val ranged = server.requests.single { it.url.encodedPath.endsWith("01%20-%20Kapitel.m4a") }
    assertEquals("bytes=4-", ranged.headers["Range"])
  }

  @Test
  fun `a download that does not match the manifest is discarded`() = runTest {
    server.corrupt = setOf("$hexe/02 - Kapitel.m4a")
    select(hexe)
    sync.runSync()

    assertTrue(file("$hexe/01 - Kapitel.m4a").exists())
    assertFalse(file("$hexe/02 - Kapitel.m4a").exists())
    assertFalse(file("$hexe/02 - Kapitel.m4a.part").exists())
    assertEquals(1, (sync.progress.value as SyncProgress.Finished).failed)
  }

  @Test
  fun `a folder copied in by hand is left alone`() = runTest {
    file("Someone/Own Book").mkdirs()
    file("Someone/Own Book/01.mp3").writeText("mine")
    sync.runSync()

    assertTrue(file("Someone/Own Book/01.mp3").exists())
  }

  @Test
  fun `a matching file copied in by hand is hashed rather than fetched again`() = runTest {
    file(hexe).mkdirs()
    file("$hexe/01 - Kapitel.m4a").writeText("hexe one")
    select(hexe)
    sync.runSync()

    assertEquals(1, (sync.progress.value as SyncProgress.Finished).fetched)
    assertFalse(server.requests.any { it.url.encodedPath.endsWith("01%20-%20Kapitel.m4a") })
  }

  @Test
  fun `the catalogue lists only audiobooks, with what is on the device`() = runTest {
    select(hexe)
    sync.runSync()
    sync.loadCatalogue()

    val loaded = sync.catalogue.value as CatalogueState.Loaded
    assertEquals(
      mapOf(hexe to ServerBook.Status.OnDevice, broom to ServerBook.Status.NotOnDevice),
      loaded.books.associate { it.group to it.status },
    )
  }

  @Test
  fun `a server without the items route is asked for episodes`() = runTest {
    server.itemsRoute = false
    sync.loadCatalogue()

    assertEquals(2, (sync.catalogue.value as CatalogueState.Loaded).books.size)
  }

  private fun logDay(
    name: String,
    text: String,
  ) {
    File(temp.root, "listening-log").apply { mkdirs() }.resolve(name).writeText(text)
  }

  @Test
  fun `with the listening log switched off, no logs are uploaded`() = runTest {
    logEnabled.updateData { false }
    logDay("2026-09-28.txt", "one\n")
    sync.runSync()
    assertEquals(emptyMap<String, String>(), server.logs)
  }

  @Test
  fun `listening logs are uploaded after a sync, and only when they changed`() = runTest {
    logDay("2026-09-28.txt", "one\n")
    logDay("2026-09-29.txt", "two\n")
    sync.runSync()
    assertEquals(mapOf("pixel-ab12/2026-09-28.txt" to "one\n", "pixel-ab12/2026-09-29.txt" to "two\n"), server.logs)
    assertEquals(2, (sync.progress.value as SyncProgress.Finished).logsSent)

    server.logs.clear()
    logDay("2026-09-29.txt", "two\nthree\n")
    sync.runSync()
    assertEquals(mapOf("pixel-ab12/2026-09-29.txt" to "two\nthree\n"), server.logs)
    assertEquals(1, (sync.progress.value as SyncProgress.Finished).logsSent)

    server.logs.clear()
    sync.runSync()
    assertEquals("nothing changed, nothing said", 0, (sync.progress.value as SyncProgress.Finished).logsSent)
  }

  @Test
  fun `a server without the log route is not an error`() = runTest {
    server.acceptsLogs = false
    logDay("2026-09-29.txt", "two\n")
    select(hexe)
    sync.runSync()

    val finished = sync.progress.value as SyncProgress.Finished
    assertEquals(2, finished.fetched)
    assertEquals(emptyMap<String, String>(), server.logs)
    assertEquals("not supported is not an error", null, finished.logError)
  }

  @Test
  fun `a failed log upload is reported, and the book sync still finishes`() = runTest {
    server.logsBroken = true
    logDay("2026-09-29.txt", "two\n")
    select(hexe)
    sync.runSync()

    val finished = sync.progress.value as SyncProgress.Finished
    assertEquals(2, finished.fetched)
    assertEquals(0, finished.logsSent)
    assertEquals("The server answered 500", finished.logError)
  }

  @Test
  fun `a made-up device name fits the server's pattern`() {
    val pattern = Regex("^[A-Za-z0-9][A-Za-z0-9-]{0,63}$")
    listOf("Pixel 9 Pro", "SM-S918B", "  ", null, "Ünïcødé phone/+").forEach { model ->
      val name = LogUpload.deviceName(model)
      assert(pattern.matches(name)) { "$model -> $name" }
    }
  }

  @Test
  fun `a wrong token is reported, not crashed on`() = runTest {
    config.updateData { it.copy(token = "wrong") }
    select(hexe)
    sync.runSync()

    assertEquals(SyncProgress.Failed("The server refused the token"), sync.progress.value)
  }
}
