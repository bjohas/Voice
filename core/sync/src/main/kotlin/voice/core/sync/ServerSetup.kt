package voice.core.sync

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.datastore.core.DataStore
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesTo
import dev.zacsweers.metro.Inject
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import voice.core.common.rootGraphAs
import voice.core.data.store.ServerConfigStore
import voice.core.data.sync.ServerConfig
import java.net.URI
import java.net.URLDecoder
import voice.core.strings.R as StringsR

/**
 * A server's address and API key, as the server's setup page hands them over:
 * `bvoice://setup?url=…&key=…`. Nothing is built into the app any more.
 */
public data class ServerSetupLink(
  val url: String,
  val key: String,
) {
  public companion object {
    /** The link's parts, or null if it is not a usable setup link. */
    public fun parse(link: String): ServerSetupLink? {
      val uri = runCatching { URI(link) }.getOrNull() ?: return null
      if (!uri.scheme.equals("bvoice", ignoreCase = true) || uri.host != "setup") return null
      val query = uri.rawQuery ?: return null
      val params = query.split('&').mapNotNull { part ->
        val (name, value) = part.split('=', limit = 2).takeIf { it.size == 2 } ?: return@mapNotNull null
        name to URLDecoder.decode(value, "UTF-8")
      }.toMap()
      val url = params["url"]?.trim().orEmpty()
      val key = params["key"]?.trim().orEmpty()
      val web = url.startsWith("http://", ignoreCase = true) || url.startsWith("https://", ignoreCase = true)
      if (!web || key.isEmpty()) return null
      return ServerSetupLink(url = url, key = key)
    }
  }
}

@Inject
public class ServerSetup(
  @ServerConfigStore
  private val configStore: DataStore<ServerConfig>,
) {

  /** True if the phone already uses exactly this server and key. */
  public suspend fun isCurrent(link: ServerSetupLink): Boolean {
    val config = configStore.data.first()
    return config.url == link.url && config.token == link.key && config.enabled
  }

  /** Saves the server, switched on; the device name stays. */
  public suspend fun apply(link: ServerSetupLink) {
    configStore.updateData { it.copy(url = link.url, token = link.key, enabled = true) }
  }
}

@ContributesTo(AppScope::class)
public interface ServerSetupGraph {
  public val serverSetup: ServerSetup
}

/**
 * Opened by the server's "Open in bVoice" button (or its QR code): asks before
 * replacing the phone's server, since any web page could send such a link.
 */
public class ServerSetupActivity : Activity() {

  private val scope = MainScope()

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    val link = intent?.dataString?.let(ServerSetupLink::parse)
    if (link == null) {
      Toast.makeText(this, StringsR.string.server_setup_bad_link, Toast.LENGTH_LONG).show()
      finish()
      return
    }
    val setup = rootGraphAs<ServerSetupGraph>().serverSetup
    scope.launch {
      if (setup.isCurrent(link)) {
        Toast.makeText(this@ServerSetupActivity, StringsR.string.server_setup_already, Toast.LENGTH_LONG).show()
        openServerBooks()
        return@launch
      }
      AlertDialog.Builder(this@ServerSetupActivity)
        .setTitle(StringsR.string.server_setup_title)
        .setMessage(getString(StringsR.string.server_setup_message, link.url))
        .setPositiveButton(StringsR.string.server_setup_use) { _, _ ->
          scope.launch {
            setup.apply(link)
            Toast.makeText(this@ServerSetupActivity, StringsR.string.server_setup_done, Toast.LENGTH_LONG).show()
            openServerBooks()
          }
        }
        .setNegativeButton(StringsR.string.common_dialog_cancel) { _, _ -> finish() }
        .setOnCancelListener { finish() }
        .show()
    }
  }

  override fun onDestroy() {
    scope.cancel()
    super.onDestroy()
  }

  private fun openServerBooks() {
    packageManager.getLaunchIntentForPackage(packageName)?.let { launch ->
      startActivity(
        launch.setAction(OPEN_SERVER_BOOKS)
          .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK),
      )
    }
    finish()
  }

  public companion object {
    /** Matched in the app's StartDestinationProvider. */
    public const val OPEN_SERVER_BOOKS: String = "voice.action.OPEN_SERVER_BOOKS"
  }
}
