package voice.core.sync

import org.junit.Assert.assertEquals
import org.junit.Test
import voice.core.data.sync.ServerConfig

class SeedServerConfigTest {

  @Test
  fun `empty settings take the build's address and key`() {
    assertEquals(
      ServerConfig(url = "http://built.in/", token = "key"),
      SeedServerConfig.seed(ServerConfig(), url = "http://built.in/", apiKey = "key"),
    )
  }

  @Test
  fun `what the phone has saved is kept`() {
    val saved = ServerConfig(url = "http://mine/", token = "my-key", deviceName = "phone-1")
    assertEquals(saved, SeedServerConfig.seed(saved, url = "http://built.in/", apiKey = "key"))
  }

  @Test
  fun `a build without defaults changes nothing`() {
    val saved = ServerConfig(url = "http://mine/", token = "my-key")
    assertEquals(saved, SeedServerConfig.seed(saved, url = "", apiKey = ""))
    assertEquals(ServerConfig(), SeedServerConfig.seed(ServerConfig(), url = "", apiKey = ""))
  }
}
