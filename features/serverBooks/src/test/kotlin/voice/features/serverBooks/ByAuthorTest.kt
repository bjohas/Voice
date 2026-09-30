package voice.features.serverBooks

import org.junit.Assert.assertEquals
import org.junit.Test
import voice.core.sync.CatalogueItem
import voice.core.sync.ServerBook

class ByAuthorTest {

  private fun book(
    group: String,
    author: String = "",
    status: ServerBook.Status = ServerBook.Status.NotOnDevice,
  ) = ServerBook(
    item = CatalogueItem(
      group = group,
      kind = "audiobook",
      title = group.substringAfterLast('/'),
      author = author,
      duration = 7200,
      bytes = 104_857_600,
      parts = 27,
    ),
    group = group,
    status = status,
  )

  @Test
  fun `books are grouped under authors, both in alphabetical order`() {
    val authors = listOf(
      book("Otfried-Preussler/Die kleine Hexe", author = "Otfried-Preussler"),
      book("Julia Donaldson/Room on the Broom", author = "Julia Donaldson"),
      book("Otfried-Preussler/Das kleine Gespenst", author = "Otfried-Preussler"),
    ).byAuthor(emptySet())

    assertEquals(listOf("Julia Donaldson", "Otfried-Preussler"), authors.map { it.name })
    assertEquals(listOf("Das kleine Gespenst", "Die kleine Hexe"), authors[1].books.map { it.title })
  }

  @Test
  fun `a row without an author is filed under its first folder`() {
    assertEquals("Michael Buckley", listOf(book("Michael Buckley/The Weirdies")).byAuthor(emptySet()).single().name)
  }

  @Test
  fun `selection, hours and size come through`() {
    val row = listOf(book("A/B", status = ServerBook.Status.Partial)).byAuthor(setOf("A/B")).single().books.single()

    assertEquals(true, row.selected)
    assertEquals(2.0, row.hours, 0.001)
    assertEquals(100.0, row.megabytes, 0.001)
    assertEquals(ServerBook.Status.Partial, row.status)
  }
}

class CleanDeviceNameTest {
  @Test
  fun `a typed device name is kept within the server's pattern`() {
    assertEquals("Anna-s-Pixel-9", "Anna's Pixel 9!".cleanDeviceName())
    assertEquals("", "  ".cleanDeviceName())
  }
}
