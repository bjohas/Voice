package voice.core.sync

import androidx.datastore.core.DataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.updateAndGet
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okio.Buffer
import java.net.URLDecoder
import java.security.MessageDigest

/**
 * The content server as HOSTING.md describes it, for one root: a catalogue at
 * api/v1/items, a manifest, files under content/ with Range, and a bearer token.
 */
internal class FakeServer(
  private val root: String = "/r/Audiobooks/",
  private val token: String = TOKEN,
) : Dispatcher() {

  val server = MockWebServer()
  val files = mutableMapOf<String, ByteArray>()
  val books = mutableListOf<CatalogueItem>()
  val requests = mutableListOf<RecordedRequest>()
  var itemsRoute = true
  var corrupt = setOf<String>()
  var acceptsLogs = true
  var logsBroken = false
  val logs = mutableMapOf<String, String>()

  val url: String get() = server.url(root).toString()

  fun start() {
    server.dispatcher = this
    server.start()
  }

  fun book(
    group: String,
    vararg parts: Pair<String, String>,
  ) {
    parts.forEach { (name, content) -> files["$group/$name"] = content.toByteArray() }
    books += CatalogueItem(group = group, kind = "audiobook", title = group.substringAfterLast('/'), parts = parts.size)
  }

  override fun dispatch(request: RecordedRequest): MockResponse {
    requests += request
    if (request.headers["Authorization"] != "Bearer $token") return MockResponse.Builder().code(401).build()
    val path = request.url.encodedPath.removePrefix(root)
    if (request.method == "PUT") {
      if (!acceptsLogs || !path.startsWith("api/v1/logs/")) return MockResponse.Builder().code(403).build()
      if (logsBroken) return MockResponse.Builder().code(500).build()
      logs[URLDecoder.decode(path.removePrefix("api/v1/logs/"), "UTF-8")] = request.body?.utf8().orEmpty()
      return json("""{"saved": "$path"}""")
    }
    return when {
      path == "api/v1/items" && !itemsRoute -> MockResponse.Builder().code(404).build()
      path == "api/v1/items" || path == "api/v1/episodes" -> json(
        """{"generation": 3, "episodes": [${books.joinToString {
          """{"group": "${it.group}", "kind": "audiobook", "title": "${it.title}", "parts": ${it.parts}}"""
        }},
          {"group": "s01e01", "title": "An episode"}]}""",
      )
      path == "api/v1/manifest" -> json(
        """{"generation": 3, "files": {"content.json": [2, "${sha("{}".toByteArray())}"],
          ${files.entries.joinToString { (k, v) -> """"$k": [${v.size}, "${sha(v)}"]""" }}}}""",
      )
      path.startsWith("content/") -> file(URLDecoder.decode(path.removePrefix("content/").replace("+", "%2B"), "UTF-8"), request)
      else -> MockResponse.Builder().code(404).build()
    }
  }

  private fun file(
    path: String,
    request: RecordedRequest,
  ): MockResponse {
    val bytes = files[path]?.let { if (path in corrupt) it.reversedArray() else it }
      ?: return MockResponse.Builder().code(404).build()
    val range = request.headers["Range"]?.removePrefix("bytes=")?.removeSuffix("-")?.toInt()
    return if (range != null) {
      if (range >= bytes.size) return MockResponse.Builder().code(416).build()
      MockResponse.Builder().code(206)
        .addHeader("Content-Range", "bytes $range-${bytes.size - 1}/${bytes.size}")
        .body(Buffer().write(bytes.copyOfRange(range, bytes.size)))
        .build()
    } else {
      MockResponse.Builder().code(200).body(Buffer().write(bytes)).build()
    }
  }

  private fun json(body: String) = MockResponse.Builder().code(200)
    .addHeader("Content-Type", "application/json")
    .body(body)
    .build()

  companion object {
    const val TOKEN = "secret"
  }
}

internal fun sha(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes)
  .joinToString("") { "%02x".format(it) }

internal class MemoryDataStore<T>(initial: T) : DataStore<T> {
  private val value = MutableStateFlow(initial)
  override val data: Flow<T> get() = value
  override suspend fun updateData(transform: suspend (t: T) -> T): T = value.updateAndGet { transform(it) }
}
