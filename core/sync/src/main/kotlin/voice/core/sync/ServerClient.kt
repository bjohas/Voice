package voice.core.sync

import dev.zacsweers.metro.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import voice.core.data.sync.ServerConfig
import voice.core.data.sync.TagEntry
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.TimeUnit

/** Talks to the content server: the catalogue, the manifest, and single files. */
@Inject
public class ServerClient(client: OkHttpClient) {

  // Chapters are tens of megabytes over a VPN: a slow read is not a dead one.
  private val client = client.newBuilder()
    .readTimeout(60, TimeUnit.SECONDS)
    .build()

  private val json = Json { ignoreUnknownKeys = true }

  internal suspend fun catalogue(config: ServerConfig): List<CatalogueItem> {
    // items is the catalogue's name; episodes is what Kiri and Lou's server called it first.
    val body = try {
      get(config, "api/v1/items")
    } catch (e: ServerException) {
      if (e.status != 404) throw e
      get(config, "api/v1/episodes")
    }
    return decoding("catalogue") { json.decodeFromString<EpisodesResponse>(body) }.episodes
      .filter { it.kind == AUDIOBOOK_KIND }
  }

  /**
   * Every file the server offers, keyed by its server path. The whole manifest,
   * not `?group=`: the server splits that parameter on commas, and titles have them.
   */
  internal suspend fun manifest(config: ServerConfig): Map<String, RemoteFile> {
    val body = get(config, "api/v1/manifest")
    return decoding("manifest") {
      json.decodeFromString<ManifestResponse>(body).files.mapValues { (_, value) ->
        RemoteFile(size = value[0].jsonPrimitive.long, sha256 = value[1].jsonPrimitive.content)
      }
    }
  }

  /**
   * Fetches [remotePath] into [target], continuing from whatever [target] already
   * holds. A server that ignores the range sends the whole file, which is then
   * written from the start.
   */
  internal suspend fun download(
    config: ServerConfig,
    remotePath: String,
    target: File,
    onBytes: (Long) -> Unit,
  ) {
    withContext(Dispatchers.IO) {
      val offset = if (target.exists()) target.length() else 0L
      val url = base(config).newBuilder()
        .addPathSegment("content")
        .apply { remotePath.split('/').forEach { addPathSegment(it) } }
        .build()
      val request = request(config, url).newBuilder()
        .apply { if (offset > 0) header("Range", "bytes=$offset-") }
        .build()
      client.newCall(request).execute().use { response ->
        val append = when {
          response.code == 206 -> true
          response.code == 416 -> return@withContext
          response.isSuccessful -> false
          else -> throw response.failure()
        }
        if (append) onBytes(offset)
        FileOutputStream(target, append).use { out ->
          val source = response.body.byteStream()
          val buffer = ByteArray(64 * 1024)
          while (true) {
            val read = source.read(buffer)
            if (read < 0) break
            out.write(buffer, 0, read)
            onBytes(read.toLong())
          }
        }
      }
    }
  }

  /** The server's NFC tag map, UID -> entry (NFC-TAGS.md). */
  internal suspend fun tags(config: ServerConfig): Map<String, TagEntry> {
    val body = get(config, "api/v1/tags")
    return decoding("tag map") { json.decodeFromString<TagsResponse>(body) }.tags
  }

  /** A tag's picture, or null if the server has none. */
  internal suspend fun tagCover(
    config: ServerConfig,
    uid: String,
  ): ByteArray? = withContext(Dispatchers.IO) {
    val url = base(config).newBuilder()
      .addPathSegments("api/v1/tags")
      .addPathSegment(uid)
      .addPathSegment("cover")
      .build()
    client.newCall(request(config, url)).execute().use { response ->
      when {
        response.isSuccessful -> response.body.bytes()
        response.code == 404 -> null
        else -> throw response.failure()
      }
    }
  }

  /**
   * Names a tag, or chooses its book: only the fields in [fields] change
   * (`name`, `tech`, `group` -- JSON null clears it -- and `fromStart`).
   */
  internal suspend fun putTag(
    config: ServerConfig,
    uid: String,
    fields: JsonObject,
  ): Unit = withContext(Dispatchers.IO) {
    val url = base(config).newBuilder().addPathSegments("api/v1/tags").addPathSegment(uid).build()
    put(config, url, fields.toString())
  }

  /** This phone's own tag assignments, the whole set, for the server's tags page to show. */
  internal suspend fun putPhoneTags(
    config: ServerConfig,
    device: String,
    tags: Map<String, PhoneTagReport>,
  ): Unit = withContext(Dispatchers.IO) {
    val url = base(config).newBuilder()
      .addPathSegments("api/v1/devices")
      .addPathSegment(device)
      .addPathSegment("tags")
      .build()
    put(config, url, json.encodeToString(MapSerializer(String.serializer(), PhoneTagReport.serializer()), tags))
  }

  private fun put(
    config: ServerConfig,
    url: HttpUrl,
    body: String,
  ) {
    val request = request(config, url).newBuilder()
      .put(body.toRequestBody("application/json; charset=utf-8".toMediaType()))
      .build()
    client.newCall(request).execute().use { response ->
      if (!response.isSuccessful) throw response.failure()
    }
  }

  /**
   * Hands over one day's listening log (LOG-UPLOAD-REQUEST.md): the whole file,
   * replacing what the server has. False when the server has no such route yet.
   */
  internal suspend fun putLog(
    config: ServerConfig,
    device: String,
    fileName: String,
    body: ByteArray,
  ): Boolean = withContext(Dispatchers.IO) {
    val url = base(config).newBuilder()
      .addPathSegments("api/v1/logs")
      .addPathSegment(device)
      .addPathSegment(fileName)
      .build()
    val request = request(config, url).newBuilder()
      .put(body.toRequestBody("text/plain; charset=utf-8".toMediaType()))
      .build()
    client.newCall(request).execute().use { response ->
      when {
        response.isSuccessful -> true
        response.code in listOf(403, 404, 405) -> false
        else -> throw response.failure()
      }
    }
  }

  private suspend fun get(
    config: ServerConfig,
    path: String,
  ): String = withContext(Dispatchers.IO) {
    val url = base(config).newBuilder().addPathSegments(path).build()
    client.newCall(request(config, url)).execute().use { response ->
      if (!response.isSuccessful) throw response.failure()
      response.body.string()
    }
  }

  /**
   * A body that is not what the server should send -- an HTML page from a
   * captive portal, say -- is a [ServerException] like any other failure, not
   * a crash: callers handle IOException only.
   */
  private inline fun <T> decoding(
    what: String,
    block: () -> T,
  ): T = try {
    block()
  } catch (e: IllegalArgumentException) {
    // SerializationException and NumberFormatException are both IllegalArgumentExceptions.
    throw ServerException("The server's $what is not readable: ${e.message?.take(80)}")
  } catch (e: IndexOutOfBoundsException) {
    throw ServerException("The server's $what is not readable: ${e.message?.take(80)}")
  }

  private fun base(config: ServerConfig): HttpUrl {
    if (!config.enabled) throw ServerException("The server is switched off in Settings")
    if (config.url.isBlank()) throw ServerException("No server address yet: set it with the cogwheel")
    return config.url.trim().trimEnd('/').plus("/").toHttpUrlOrNull()
      ?: throw ServerException("Not a server address: ${config.url}")
  }

  private fun request(
    config: ServerConfig,
    url: HttpUrl,
  ): Request = Request.Builder()
    .url(url)
    .apply {
      val token = config.token.trim()
      if (token.isNotEmpty()) header("Authorization", "Bearer $token")
    }
    .build()

  private fun Response.failure(): ServerException = when (code) {
    401 -> ServerException("The server refused the token", code)
    404 -> ServerException("Not found on the server: ${request.url.encodedPath}", code)
    else -> ServerException("The server answered $code", code)
  }
}

public class ServerException(
  message: String,
  public val status: Int = 0,
) : IOException(message)
