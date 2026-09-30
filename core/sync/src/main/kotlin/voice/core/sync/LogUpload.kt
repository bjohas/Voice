package voice.core.sync

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.long
import voice.core.data.sync.ServerConfig
import voice.core.logging.api.Logger
import java.io.File
import java.io.IOException
import kotlin.random.Random

/**
 * Uploads the listening log's day files whose size has changed since they were
 * last handed over, oldest first. Never fails the sync it follows: a problem is
 * logged and the next sync tries again.
 */
internal class LogUpload(
  private val client: ServerClient,
  private val directory: SyncDirectory,
) {

  /** How many day files went up, and why the rest did not, if something went wrong. */
  data class Result(
    val sent: Int = 0,
    val error: String? = null,
  )

  suspend fun run(config: ServerConfig): Result {
    val days = directory.logs.listFiles { file -> dayFile.matches(file.name) }?.sortedBy { it.name }.orEmpty()
    if (days.isEmpty() || config.deviceName.isBlank()) return Result()
    val uploaded = readUploaded().toMutableMap()
    var sent = 0
    try {
      for (day in days) {
        val size = day.length()
        if (uploaded[day.name] == size) continue
        if (!client.putLog(config, config.deviceName, day.name, day.readBytes())) {
          // A server without the route: not a problem to report.
          Logger.i("The server does not take listening logs yet")
          return Result(sent)
        }
        uploaded[day.name] = size
        writeUploaded(uploaded)
        sent++
      }
    } catch (e: IOException) {
      Logger.w(e, "Could not upload the listening log")
      return Result(sent, e.message ?: e.toString())
    }
    return Result(sent)
  }

  private fun readUploaded(): Map<String, Long> = try {
    Json.parseToJsonElement(directory.uploadsFile.readText()).jsonObject.mapValues { (it.value as JsonPrimitive).long }
  } catch (_: Exception) {
    emptyMap()
  }

  private fun writeUploaded(uploaded: Map<String, Long>) {
    directory.uploadsFile.parentFile?.mkdirs()
    directory.uploadsFile.writeText(JsonObject(uploaded.mapValues { JsonPrimitive(it.value) }).toString())
  }

  companion object {
    private val dayFile = Regex("""\d{4}-\d{2}-\d{2}\.txt""")

    /** A name the server's pattern accepts: `^[A-Za-z0-9][A-Za-z0-9-]{0,63}$`. */
    fun deviceName(
      model: String?,
      random: Random = Random.Default,
    ): String {
      val base = model.orEmpty().replace(Regex("[^A-Za-z0-9]+"), "-").trim('-').take(50).ifEmpty { "phone" }
      val suffix = (1..4).map { "abcdefghijklmnopqrstuvwxyz0123456789"[random.nextInt(36)] }.joinToString("")
      return "$base-$suffix"
    }
  }
}
