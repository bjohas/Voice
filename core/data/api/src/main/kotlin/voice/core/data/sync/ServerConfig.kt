package voice.core.data.sync

import kotlinx.serialization.Serializable

/**
 * Where books are synced from.
 *
 * [pathPrefix] is stripped from the server's paths to get the local ones, so a
 * server that lists `content/Audiobooks/Author/Title/01.m4a` can land at
 * `books/Author/Title/01.m4a` on the device.
 */
@Serializable
public data class ServerConfig(
  val url: String = "",
  val token: String = "",
  val pathPrefix: String = "",
  /** How this phone names itself when it uploads its listening log; made up once if empty. */
  val deviceName: String = "",
  /** Switched off in Settings: nothing is fetched from or sent to the server. */
  val enabled: Boolean = true,
) {
  /** Switched on, with an address: books, tags and logs may use the server. */
  val active: Boolean get() = enabled && url.isNotBlank()
}
