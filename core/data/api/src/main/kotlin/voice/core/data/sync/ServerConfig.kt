package voice.core.data.sync

import kotlinx.serialization.Serializable

/** Where books are synced from. */
@Serializable
public data class ServerConfig(
  val url: String = "",
  val token: String = "",
  /** No longer used: server paths are the phone's. Kept so settings saved with it still load. */
  val pathPrefix: String = "",
  /** How this phone names itself when it uploads its listening log; made up once if empty. */
  val deviceName: String = "",
  /** Switched off in Settings: nothing is fetched from or sent to the server. */
  val enabled: Boolean = true,
) {
  /** Switched on, with an address: books, tags and logs may use the server. */
  val active: Boolean get() = enabled && url.isNotBlank()
}
