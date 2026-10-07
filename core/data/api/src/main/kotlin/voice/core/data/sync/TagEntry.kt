package voice.core.data.sync

import kotlinx.serialization.Serializable

/**
 * One NFC tag as the server knows it (NFC-TAGS.md): a name, the kind of tag,
 * and -- once chosen on the server's tags page -- the book it plays, as the
 * server's group (`Author/Title`).
 */
@Serializable
public data class TagEntry(
  val name: String = "",
  val tech: String = "",
  val group: String? = null,
  val fromStart: Boolean = false,
  /** The server has a picture for this tag (GET api/v1/tags/UID/cover). */
  val cover: Boolean = false,
)
