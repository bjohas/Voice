package voice.core.data.sync

import kotlinx.serialization.Serializable

/**
 * A tag as this phone alone knows it (NFC-TAGS-LOCAL.md): a book chosen on the
 * phone that is not a server book, or anything at all when no server is set
 * up. Never synced; reported to the server for its tags page to show.
 */
@Serializable
public data class LocalTagEntry(
  /** The book's `BookId.value`; null for a tag that is only named. */
  val bookId: String? = null,
  val fromStart: Boolean = false,
  /** Shown when the server has no name for the tag, or there is no server. */
  val name: String = "",
  val tech: String = "",
  /**
   * Made while no server was set up. The first time a server is reached, such
   * an entry is handed to it if it is about a server book (or is only a name),
   * and kept as an override if the server already has another book for the tag.
   */
  val offline: Boolean = false,
)
