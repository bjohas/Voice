package voice.core.sync

import org.junit.Assert.assertEquals
import org.junit.Test

class SyncPlannerTest {

  private val a = "Author/Book"
  private val remote = mapOf(
    "content/Audiobooks/$a/01.m4a" to RemoteFile(3, "aaa"),
    "content/Audiobooks/$a/02.m4a" to RemoteFile(4, "bbb"),
  )
  private val catalogue = listOf(CatalogueItem(group = "content/Audiobooks/$a", kind = "audiobook"))

  private fun plan(
    local: Map<String, LocalFile> = emptyMap(),
    selection: Set<String> = setOf(a),
    previouslySynced: Set<String> = emptySet(),
  ) = SyncPlanner.plan(
    remote = remote,
    catalogue = catalogue,
    selection = selection,
    local = local,
    previouslySynced = previouslySynced,
    playingGroup = null,
    pathPrefix = "content/Audiobooks/",
  )

  @Test
  fun `the path prefix is stripped from paths and groups`() {
    assertEquals(listOf("$a/01.m4a", "$a/02.m4a"), plan().fetch.map { it.localPath })
    assertEquals("content/Audiobooks/$a/01.m4a", plan().fetch.first().remotePath)
  }

  @Test
  fun `a file with the wrong size or hash is fetched again`() {
    val local = mapOf(
      "$a/01.m4a" to LocalFile(3, "aaa"),
      "$a/02.m4a" to LocalFile(4, "zzz"),
    )
    assertEquals(listOf("$a/02.m4a"), plan(local).fetch.map { it.localPath })
  }

  @Test
  fun `a partial download of a wanted file is kept, a stale one is not`() {
    val resuming = mapOf("$a/01.m4a.part" to LocalFile(1, null))
    assertEquals(emptyList<String>(), plan(resuming).delete)

    val stale = mapOf("$a/01.m4a" to LocalFile(3, "aaa"), "$a/01.m4a.part" to LocalFile(1, null))
    assertEquals(listOf("$a/01.m4a.part"), plan(stale).delete)
  }

  @Test
  fun `a book the server no longer offers is removed only if the sync put it there`() {
    val local = mapOf("Gone/Book/01.m4a" to LocalFile(1, "x"))
    assertEquals(emptyList<String>(), plan(local).delete)
    assertEquals(listOf("Gone/Book/01.m4a"), plan(local, previouslySynced = setOf("Gone/Book")).delete)
  }

  @Test
  fun `a selected group the server does not offer fetches nothing`() {
    assertEquals(emptyList<SyncPlan.Fetch>(), plan(selection = setOf("Not/Offered")).fetch)
  }
}
