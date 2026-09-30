package voice.core.sync

import dev.zacsweers.metro.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
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
    return json.decodeFromString<EpisodesResponse>(body).episodes
      .filter { it.kind == AUDIOBOOK_KIND }
  }

  /**
   * Every file the server offers, keyed by its server path. The whole manifest,
   * not `?group=`: the server splits that parameter on commas, and titles have them.
   */
  internal suspend fun manifest(config: ServerConfig): Map<String, RemoteFile> {
    val body = get(config, "api/v1/manifest")
    return json.decodeFromString<ManifestResponse>(body).files.mapValues { (_, value) ->
      RemoteFile(size = value[0].jsonPrimitive.long, sha256 = value[1].jsonPrimitive.content)
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

  private fun base(config: ServerConfig): HttpUrl {
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
