package voice.core.tags

/** A tag as scanned: its UID in the canonical form, and what kind of tag it is. */
public data class ScannedTag(
  val uid: String,
  val tech: String,
) {
  /** A guess, not proof: ISO 15693 with the ICODE SLIX-L prefix every Tonie has (notes/tonie_rfid1.md). */
  val probablyTonie: Boolean get() = tech == "NfcV" && uid.startsWith("E00403")
}

public object TagUid {

  /**
   * The UID as uppercase hex. Android hands ISO 15693 (NfcV) UIDs over least
   * significant byte first; they are reversed, so a Tonie reads E00403…, as in
   * the tools and on the server's page. Other kinds are kept as given.
   */
  public fun canonical(
    id: ByteArray,
    tech: String,
  ): String {
    val bytes = if (tech == "NfcV") id.reversedArray() else id
    return bytes.joinToString("") { "%02X".format(it) }
  }

  /** The most specific kind of tag Android reported, by its short class name. */
  public fun tech(techList: Array<String>): String {
    val short = techList.map { it.substringAfterLast('.') }
    return listOf("NfcV", "MifareUltralight", "MifareClassic", "IsoDep", "NfcA", "NfcB", "NfcF")
      .firstOrNull { it in short } ?: short.firstOrNull().orEmpty()
  }
}
