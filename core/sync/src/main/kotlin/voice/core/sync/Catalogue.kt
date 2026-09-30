package voice.core.sync

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray

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

internal const val AUDIOBOOK_KIND = "audiobook"
