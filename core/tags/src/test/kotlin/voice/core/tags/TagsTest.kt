package voice.core.tags

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import voice.core.data.sync.LocalTagEntry
import voice.core.data.sync.TagEntry

class TagUidTest {

  @Test
  fun `an NfcV UID is reversed, so a Tonie reads E00403`() {
    // As Android hands over a (made-up) Tonie UID, least significant byte first.
    val asAndroidGivesIt = byteArrayOf(0x01, 0xCC.toByte(), 0xBB.toByte(), 0xAA.toByte(), 0x50, 0x03, 0x04, 0xE0.toByte())
    assertEquals("E0040350AABBCC01", TagUid.canonical(asAndroidGivesIt, "NfcV"))
  }

  @Test
  fun `other tags keep their byte order`() {
    assertEquals(
      "04A1B2C3D4E5F6",
      TagUid.canonical(
        byteArrayOf(0x04, 0xA1.toByte(), 0xB2.toByte(), 0xC3.toByte(), 0xD4.toByte(), 0xE5.toByte(), 0xF6.toByte()),
        "MifareUltralight",
      ),
    )
  }

  @Test
  fun `the most specific kind of tag is named`() {
    assertEquals("NfcV", TagUid.tech(arrayOf("android.nfc.tech.NfcV")))
    assertEquals(
      "MifareUltralight",
      TagUid.tech(arrayOf("android.nfc.tech.NfcA", "android.nfc.tech.MifareUltralight", "android.nfc.tech.Ndef")),
    )
  }

  @Test
  fun `probably a Tonie is NfcV with the E00403 prefix`() {
    assertTrue(ScannedTag("E0040350AABBCC01", "NfcV").probablyTonie)
    assertFalse(ScannedTag("E0040350AABBCC01", "NfcA").probablyTonie)
    assertFalse(ScannedTag("E0071234567890AB", "NfcV").probablyTonie)
  }
}

class DecideTest {

  private val gruffalo = ScannedTag("E0040350AABBCC01", "NfcV")
  private val sticker = ScannedTag("04A1B2C3D4E5F6", "MifareUltralight")
  private val withBook = mapOf(gruffalo.uid to TagEntry(name = "Gruffalo", group = "Julia Donaldson/The Gruffalo"))
  private val phoneBook = LocalTagEntry(bookId = "content://tree/Stories%2FOwl%20Babies", name = "Owls")

  private fun decide(
    tag: ScannedTag,
    map: Map<String, TagEntry>,
    local: Map<String, LocalTagEntry> = emptyMap(),
    capturing: Boolean = false,
    last: Pair<String, Long>? = null,
    nowMillis: Long = 0,
  ) = TagPlayer.decide(tag, map, local, capturing, last, nowMillis)

  @Test
  fun `a known tag with a book plays it`() {
    assertEquals(TagAction.Play(withBook.getValue(gruffalo.uid)), decide(gruffalo, withBook))
  }

  @Test
  fun `an unknown Tonie does nothing`() {
    assertEquals(TagAction.Nothing, decide(gruffalo, emptyMap()))
  }

  @Test
  fun `an unknown tag of another kind offers to set it up`() {
    assertEquals(TagAction.Prompt, decide(sticker, emptyMap()))
  }

  @Test
  fun `a known tag without a book says so`() {
    val named = mapOf(gruffalo.uid to TagEntry(name = "The Gruffalo"))
    assertEquals(TagAction.NoBook("The Gruffalo"), decide(gruffalo, named))
  }

  @Test
  fun `on the tags page every tag is shown, not played`() {
    assertEquals(TagAction.Show, decide(gruffalo, withBook, capturing = true))
  }

  @Test
  fun `the same tag moments later is a re-read, later again plays`() {
    assertEquals(TagAction.Repeat, decide(gruffalo, withBook, last = gruffalo.uid to 1_000, nowMillis = 3_000))
    assertEquals(
      TagAction.Play(withBook.getValue(gruffalo.uid)),
      decide(gruffalo, withBook, last = gruffalo.uid to 1_000, nowMillis = 6_000),
    )
    assertEquals(TagAction.Play(withBook.getValue(gruffalo.uid)), decide(gruffalo, withBook, last = "OTHER" to 1_000, nowMillis = 2_000))
  }

  @Test
  fun `a phone book plays, with no server at all`() {
    assertEquals(TagAction.PlayLocal(phoneBook, "Owls"), decide(sticker, emptyMap(), mapOf(sticker.uid to phoneBook)))
  }

  @Test
  fun `a phone book wins over the server's book, and says it overrides`() {
    assertEquals(
      TagAction.PlayLocal(phoneBook, "Gruffalo", override = true),
      decide(gruffalo, withBook, mapOf(gruffalo.uid to phoneBook)),
    )
  }

  @Test
  fun `a tag only named on the phone says it has no book, even a Tonie`() {
    assertEquals(TagAction.NoBook("Bear"), decide(gruffalo, emptyMap(), mapOf(gruffalo.uid to LocalTagEntry(name = "Bear"))))
  }

  @Test
  fun `a name only on the phone is kept when the server has none`() {
    val server = mapOf(sticker.uid to TagEntry(name = sticker.uid, group = "A/B"))
    val local = mapOf(sticker.uid to LocalTagEntry(name = "Blue card"))
    assertEquals("Blue card", TagPlayer.nameOf(sticker.uid, server[sticker.uid], local[sticker.uid]))
    assertEquals(TagAction.Play(server.getValue(sticker.uid)), decide(sticker, server, local))
  }
}

class GroupOfTest {

  private val books = "file:///storage/emulated/0/Android/data/app/files/books"

  @Test
  fun `a book in the sync directory is a server book, its folder the group`() {
    assertEquals("Julia Donaldson/The Gruffalo", LocalTags.groupOf("$books/Julia%20Donaldson/The%20Gruffalo", books))
  }

  @Test
  fun `a book elsewhere is a phone book`() {
    assertNull(LocalTags.groupOf("content://com.android.externalstorage.documents/tree/primary%3AAudiobooks", books))
    assertNull(LocalTags.groupOf("file:///storage/emulated/0/Audiobooks/Owl%20Babies", books))
    assertNull(LocalTags.groupOf("${books}2/A/B", books))
    assertNull(LocalTags.groupOf(books, books))
  }
}
