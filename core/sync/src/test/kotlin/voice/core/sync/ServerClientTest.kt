package voice.core.sync

import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import voice.core.data.sync.ServerConfig

class ServerClientTest {

  private val server = MockWebServer()
  private val client = ServerClient(OkHttpClient())
  private val config get() = ServerConfig(url = server.url("/r/Audiobooks/").toString(), token = "t")

  private fun fails(block: suspend () -> Any) {
    val thrown = runCatching { runBlocking { block() } }.exceptionOrNull()
    assertTrue("expected a ServerException, got $thrown", thrown is ServerException)
  }

  @Before
  fun setUp() {
    server.start()
  }

  @After
  fun tearDown() {
    server.close()
  }

  @Test
  fun `an HTML page instead of JSON is a ServerException, not a crash`() {
    repeat(3) { server.enqueue(MockResponse.Builder().body("<html>Log in to the Wi-Fi</html>").build()) }
    fails { client.catalogue(config) }
    fails { client.tags(config) }
    fails { client.manifest(config) }
  }

  @Test
  fun `a manifest row of the wrong shape is a ServerException`() {
    server.enqueue(MockResponse.Builder().body("""{"files": {"A/B/01.mp3": ["not a number", "abc"]}}""").build())
    fails { client.manifest(config) }
    server.enqueue(MockResponse.Builder().body("""{"files": {"A/B/01.mp3": []}}""").build())
    fails { client.manifest(config) }
  }
}
