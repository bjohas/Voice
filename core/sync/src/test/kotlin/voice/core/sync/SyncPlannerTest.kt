package voice.core.sync

import org.junit.Assert.assertEquals
import org.junit.Test

class SyncPlannerTest {

  private val a = "Author/Book"
  private val remote = mapOf(
    "$a/01.m4a" to RemoteFile(3, "aaa"),
    "$a/02.m4a" to RemoteFile(4, "bbb"),
  )
  private val catalogue = listOf(CatalogueItem(group = a, kind = "audiobook"))

  private fun plan(
    local: Map<String, LocalFile> = emptyMap(),
    selection: Set<String> = setOf(a),
    previouslySynced: Set<String> = emptySet(),
    remote: Map<String, RemoteFile> = this.remote,
    catalogue: List<CatalogueItem> = this.catalogue,
  ) = SyncPlanner.plan(
    remote = remote,
    catalogue = catalogue,
    selection = selection,
    local = local,
    previouslySynced = previouslySynced,
    playingGroup = null,
  )

  @Test
  fun `a server path that would leave the books folder is never fetched`() {
    val remote = remote + mapOf(
      "$a/../../escape.m4a" to RemoteFile(1, "x"),
      "/abs.m4a" to RemoteFile(1, "x"),
    )
    assertEquals(listOf("$a/01.m4a", "$a/02.m4a"), plan(remote = remote).fetch.map { it.localPath })
    assertEquals(false, SyncPlanner.isSafePath("A/./b"))
    assertEquals(false, SyncPlanner.isSafePath("A\\b"))
    assertEquals(true, SyncPlanner.isSafePath("Julia Donaldson/Room on the Broom/01.m4a"))
  }

  @Test
  fun `an empty catalogue or manifest deletes nothing`() {
    val local = mapOf("$a/01.m4a" to LocalFile(3, "aaa"), "Other/Book/01.m4a" to LocalFile(1, "x"))
    val synced = setOf(a, "Other/Book")
    assertEquals(emptyList<String>(), plan(local, previouslySynced = synced, catalogue = emptyList()).delete)
    assertEquals(emptyList<String>(), plan(local, previouslySynced = synced, remote = emptyMap()).delete)
  }

  @Test
  fun `a chosen book whose files are missing from the manifest is kept`() {
    val other = "Other/Book"
    val local = mapOf("$other/01.m4a" to LocalFile(1, "x"))
    val catalogue = catalogue + CatalogueItem(group = other, kind = "audiobook")
    assertEquals(
      emptyList<String>(),
      plan(local, selection = setOf(a, other), previouslySynced = setOf(other), catalogue = catalogue).delete,
    )
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
