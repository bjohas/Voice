package voice.core.sync

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

/**
 * What the sync remembers between runs: which books it put on the device, so
 * it may remove them later, and the hashes it has already paid for, keyed by
 * size and mtime so a changed file is hashed again.
 */
@Serializable
internal data class SyncState(
  val synced: Set<String> = emptySet(),
  val hashes: Map<String, CachedHash> = emptyMap(),
) {
  @Serializable
  data class CachedHash(
    val key: String,
    val sha256: String,
  )

  companion object {
    private val json = Json { ignoreUnknownKeys = true }

    fun read(file: File): SyncState = try {
      json.decodeFromString(serializer(), file.readText())
    } catch (_: Exception) {
      SyncState()
    }

    fun SyncState.write(file: File) {
      file.parentFile?.mkdirs()
      val tmp = File(file.path + ".tmp")
      tmp.writeText(json.encodeToString(serializer(), this))
      if (!tmp.renameTo(file)) {
        file.delete()
        tmp.renameTo(file)
      }
    }
  }
}

internal fun File.hashKey(): String = "${length()}:${lastModified()}"
