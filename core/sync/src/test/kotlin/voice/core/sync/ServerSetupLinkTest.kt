package voice.core.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ServerSetupLinkTest {

  @Test
  fun `a setup link gives the address and key`() {
    assertEquals(
      ServerSetupLink(url = "http://media.example:8300/r/Audiobooks/", key = "a+b/c&d"),
      ServerSetupLink.parse("bvoice://setup?url=http%3A%2F%2Fmedia.example%3A8300%2Fr%2FAudiobooks%2F&key=a%2Bb%2Fc%26d"),
    )
  }

  @Test
  fun `anything else is refused`() {
    assertNull(ServerSetupLink.parse("https://setup?url=http%3A%2F%2Fa%2F&key=k"))
    assertNull(ServerSetupLink.parse("bvoice://other?url=http%3A%2F%2Fa%2F&key=k"))
    assertNull(ServerSetupLink.parse("bvoice://setup?url=http%3A%2F%2Fa%2F"))
    assertNull(ServerSetupLink.parse("bvoice://setup?url=file%3A%2F%2F%2Fsdcard%2F&key=k"))
    assertNull(ServerSetupLink.parse("bvoice://setup"))
    assertNull(ServerSetupLink.parse("not a link at all"))
  }
}
