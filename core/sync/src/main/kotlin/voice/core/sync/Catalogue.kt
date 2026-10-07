package voice.core.sync

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import voice.core.data.sync.TagEntry

/**
 * One row of the server's catalogue: a book, which is a folder of chapter files.
 *
 * The shape is Kiri and Lou's content server's `/api/v1/episodes` row, with the
 * audiobook fields added; anything unknown is ignored and anything missing has
 * a default, so the server can grow without breaking this.
 */
@Serializable
public data class CatalogueItem(
  val group: String,
  val kind: String = "episode",
  val title: String = group,
  val author: String = "",
  val parts: Int = 0,
  val duration: Long = 0,
  val bytes: Long = 0,
  val digest: String = "",
)

/** A file the server offers: its size, and the sha256 a download must match. */
public data class RemoteFile(
  val size: Long,
  val sha256: String,
)

@Serializable
internal data class EpisodesResponse(
  val generation: Long = 0,
  val episodes: List<CatalogueItem> = emptyList(),
)

@Serializable
internal data class ManifestResponse(
  val generation: Long = 0,
  val files: Map<String, JsonArray> = emptyMap(),
)

@Serializable
internal data class TagsResponse(val tags: Map<String, TagEntry> = emptyMap())

/** One of this phone's own tag assignments, as the server's tags page shows it. */
@Serializable
public data class PhoneTagReport(
  /** The book's title: the server never sees the phone's paths. */
  val title: String,
  val name: String = "",
  val fromStart: Boolean = false,
  /** The book is a server book: this overrides another book the server has for the tag. */
  val server: Boolean = false,
)

internal const val AUDIOBOOK_KIND = "audiobook"
