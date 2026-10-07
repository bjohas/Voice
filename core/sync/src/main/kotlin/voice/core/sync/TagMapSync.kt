package voice.core.sync

import androidx.datastore.core.DataStore
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import voice.core.data.store.ServerConfigStore
import voice.core.data.store.TagMapStore
import voice.core.data.sync.ServerConfig
import voice.core.data.sync.TagEntry
import voice.core.logging.api.Logger
import java.io.File
import java.io.IOException

/**
 * Keeps the phone's copy of the server's NFC tag map, names tags and chooses
 * their books on the server, and reports the phone's own assignments to it.
 */
@SingleIn(AppScope::class)
@Inject
public class TagMapSync(
  private val client: ServerClient,
  @ServerConfigStore
  private val configStore: DataStore<ServerConfig>,
  @TagMapStore
  private val tagMap: DataStore<Map<String, TagEntry>>,
  private val directory: SyncDirectory,
) {

  /**
   * Fetches the map, and the pictures the phone does not have yet ([forcePictures]:
   * all of them again). False if the server could not be reached, keeping the copy.
   */
  public suspend fun refresh(forcePictures: Boolean = false): Boolean {
    val config = configStore.data.first()
    if (!config.active) return false
    val tags = try {
      safe(client.tags(config))
    } catch (e: IOException) {
      Logger.w(e, "Could not fetch the tag map")
      return false
    }
    // Pictures first: a screen redraws when the map changes, and should find them there.
    withContext(Dispatchers.IO) { syncPictures(config, tags, forcePictures) }
    tagMap.updateData { tags }
    return true
  }

  /** The server is on and has an address: tags may be named and given books there. */
  public val configured: Flow<Boolean> = configStore.data.map { it.active }.distinctUntilChanged()

  /** The picture fetched for this tag, if any. */
  public fun cover(uid: String): File? = File(directory.tagCovers, "$uid.jpg").takeIf { it.isFile }

  private suspend fun syncPictures(
    config: ServerConfig,
    tags: Map<String, TagEntry>,
    force: Boolean,
  ) {
    val folder = directory.tagCovers
    folder.mkdirs()
    // Pictures of tags that are gone, or no longer have one.
    folder.listFiles()?.filter { it.nameWithoutExtension !in tags.filterValues { e -> e.cover }.keys }?.forEach { it.delete() }
    for ((uid, entry) in tags) {
      val target = File(folder, "$uid.jpg")
      if (!entry.cover || (target.isFile && !force)) continue
      try {
        val bytes = client.tagCover(config, uid) ?: continue
        val partial = File(folder, "$uid.jpg.part")
        partial.writeBytes(bytes)
        if (!partial.renameTo(target)) partial.delete()
      } catch (e: IOException) {
        Logger.w(e, "Could not fetch the picture for $uid")
      }
    }
  }

  /** Names a tag on the server, then refreshes the copy. Throws what the server said. */
  public suspend fun name(
    uid: String,
    name: String,
    tech: String,
  ) {
    put(
      uid,
      buildJsonObject {
        put("name", name)
        put("tech", tech)
      },
    )
  }

  /**
   * Chooses a tag's book on the server -- a server [group], or null for none --
   * then refreshes the copy. Throws what the server said.
   */
  public suspend fun assign(
    uid: String,
    group: String?,
    fromStart: Boolean,
    tech: String,
    name: String? = null,
  ) {
    put(
      uid,
      buildJsonObject {
        put("group", group)
        put("fromStart", fromStart)
        if (tech.isNotEmpty()) put("tech", tech)
        if (!name.isNullOrBlank()) put("name", name)
      },
    )
  }

  /**
   * The map as far as it is safe to use: UIDs that are hex (they name picture
   * files), and books whose group is a safe path (it names a folder).
   */
  internal fun safe(tags: Map<String, TagEntry>): Map<String, TagEntry> = tags
    .filterKeys { TAG_UID.matches(it) }
    .mapValues { (_, entry) ->
      val group = entry.group
      if (group == null || SyncPlanner.isSafePath(group)) entry else entry.copy(group = null)
    }

  /** Hands the server this phone's own assignments, the whole set. False if it could not. */
  public suspend fun report(tags: Map<String, PhoneTagReport>): Boolean {
    if (!configStore.data.first().active) return false
    val config = configStore.updateData { config ->
      if (config.deviceName.isBlank()) config.copy(deviceName = LogUpload.deviceName(android.os.Build.MODEL)) else config
    }
    return try {
      client.putPhoneTags(config, config.deviceName, tags)
      true
    } catch (e: IOException) {
      Logger.w(e, "Could not report this phone's tags")
      false
    }
  }

  private suspend fun put(
    uid: String,
    fields: JsonObject,
  ) {
    client.putTag(configStore.data.first(), uid, fields)
    if (!refresh()) Logger.w("Changed $uid, but could not fetch the map back")
  }
}

private val TAG_UID = Regex("[0-9A-F]{8,32}")
