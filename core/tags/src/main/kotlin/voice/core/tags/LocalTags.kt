package voice.core.tags

import android.net.Uri
import androidx.datastore.core.DataStore
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import voice.core.data.BookId
import voice.core.data.repo.BookRepository
import voice.core.data.store.LocalTagMapStore
import voice.core.data.store.TagMapStore
import voice.core.data.sync.LocalTagEntry
import voice.core.data.sync.TagEntry
import voice.core.logging.api.Logger
import voice.core.sync.PhoneTagReport
import voice.core.sync.SyncDirectory
import voice.core.sync.TagMapSync
import java.io.File
import java.io.IOException
import java.net.URLDecoder

/** A book a tag can be given: a server book (by its group), or one only this phone has. */
public sealed interface TagBook {
  public data class Server(val group: String) : TagBook

  public data class Phone(val bookId: BookId) : TagBook
}

/**
 * Where a tag's book comes from (NFC-TAGS-LOCAL.md): the server, this phone,
 * or this phone overriding a book the server has for the tag.
 */
public enum class TagSource { Server, Phone, Override }

/**
 * Tags assigned on this phone (NFC-TAGS-LOCAL.md). A server book chosen here is
 * written to the server, so every phone agrees; any other book is kept here,
 * and overrides the server's book for the tag on this phone. With no server,
 * everything is kept here. The phone's own assignments are reported to the
 * server, whose tags page shows them.
 */
@SingleIn(AppScope::class)
@Inject
public class LocalTags(
  @LocalTagMapStore
  private val store: DataStore<Map<String, LocalTagEntry>>,
  @TagMapStore
  private val tagMap: DataStore<Map<String, TagEntry>>,
  private val tagMapSync: TagMapSync,
  private val bookRepository: BookRepository,
  private val directory: SyncDirectory,
) {

  public val map: Flow<Map<String, LocalTagEntry>> = store.data

  /** The server group of a book that lives in the sync directory; null for a phone book. */
  public fun groupOf(id: BookId): String? = groupOf(id.value, Uri.fromFile(directory.books).toString())

  /** A server group's book on this phone, whether or not it is there yet. */
  public fun bookOf(group: String): BookId = BookId(Uri.fromFile(File(directory.books, group)))

  private suspend fun configured() = tagMapSync.configured.first()

  /**
   * Gives a tag a book. A server book goes to the server (and a phone
   * override for the tag is dropped: the newest choice wins); throws if the
   * server cannot be reached. A phone book is kept here.
   */
  public suspend fun choose(
    tag: ScannedTag,
    book: TagBook,
    fromStart: Boolean,
  ) {
    val server = configured()
    when {
      book is TagBook.Server && server -> {
        // A name typed on the phone goes along, unless the server already has a real one.
        val localName = store.data.first()[tag.uid]?.name?.takeIf { it.isNotBlank() }
        val serverName = tagMap.data.first()[tag.uid]?.name?.takeIf { it.isNotBlank() && it != tag.uid }
        tagMapSync.assign(tag.uid, book.group, fromStart, tag.tech, name = localName.takeIf { serverName == null })
        store.updateData { it - tag.uid }
      }
      else -> {
        val id = when (book) {
          is TagBook.Phone -> book.bookId
          is TagBook.Server -> bookOf(book.group)
        }
        store.updateData { map ->
          val entry = map[tag.uid] ?: LocalTagEntry(tech = tag.tech)
          map + (tag.uid to entry.copy(bookId = id.value, fromStart = fromStart, offline = !server))
        }
      }
    }
    report()
  }

  /** Switches "from the beginning" for whichever book the tag plays on this phone. */
  public suspend fun setFromStart(
    tag: ScannedTag,
    fromStart: Boolean,
  ) {
    if (store.data.first()[tag.uid]?.bookId != null) {
      store.updateData { map ->
        val local = map[tag.uid]?.takeIf { it.bookId != null } ?: return@updateData map
        map + (tag.uid to local.copy(fromStart = fromStart))
      }
      report()
      return
    }
    val group = tagMap.data.first()[tag.uid]?.group ?: return
    tagMapSync.assign(tag.uid, group, fromStart, tag.tech)
  }

  /**
   * Takes the tag's book away: this phone's book if it has one (an override
   * falls back to the server's book), else the server's.
   */
  public suspend fun remove(tag: ScannedTag) {
    if (store.data.first()[tag.uid]?.bookId != null) {
      val server = configured()
      store.updateData { map ->
        val local = map[tag.uid] ?: return@updateData map
        if (local.name.isBlank() || server) map - tag.uid else map + (tag.uid to local.copy(bookId = null, fromStart = false))
      }
      report()
      return
    }
    if (tagMap.data.first()[tag.uid]?.group != null) tagMapSync.assign(tag.uid, null, false, tag.tech)
  }

  /** Names a tag on the server, or here when there is no server. */
  public suspend fun name(
    tag: ScannedTag,
    name: String,
  ) {
    if (configured()) {
      tagMapSync.name(tag.uid, name, tag.tech)
      if (store.data.first()[tag.uid]?.bookId != null) {
        store.updateData { map -> map[tag.uid]?.let { map + (tag.uid to it.copy(name = name)) } ?: map }
        report()
      }
    } else {
      store.updateData { map ->
        val entry = map[tag.uid] ?: LocalTagEntry(tech = tag.tech, offline = true)
        map + (tag.uid to entry.copy(name = name))
      }
    }
  }

  /**
   * After the server map is fetched: entries made while there was no server
   * are handed to it -- a server book or a name the server lacks -- or, if the
   * server has another book for the tag, kept as an override. Then this
   * phone's assignments are reported.
   */
  public suspend fun adopt() {
    val server = tagMap.data.first()
    for ((uid, entry) in store.data.first().filterValues { it.offline }) {
      val known = server[uid]
      val group = entry.bookId?.let { groupOf(BookId(it)) }
      val serverName = known?.name?.takeIf { it.isNotBlank() && it != uid }
      val hand = when {
        entry.bookId == null -> true
        group == null -> false
        known?.group == null || known.group == group -> true
        else -> false
      }
      try {
        if (hand) {
          when {
            group != null && known?.group == null ->
              tagMapSync.assign(uid, group, entry.fromStart, entry.tech, name = entry.name.takeIf { serverName == null })
            serverName == null && entry.name.isNotBlank() -> tagMapSync.name(uid, entry.name, entry.tech)
          }
          // Only if nobody changed the entry meanwhile (the network took a while).
          store.updateData { map -> if (map[uid] == entry) map - uid else map }
        } else {
          store.updateData { map -> if (map[uid] == entry) map + (uid to entry.copy(offline = false)) else map }
        }
      } catch (e: IOException) {
        Logger.w(e, "Could not hand tag $uid to the server; trying again next time")
      }
    }
    report()
  }

  /** This phone's books for tags, by title, to the server's tags page. */
  public suspend fun report() {
    if (!configured()) return
    val report = store.data.first().mapNotNull { (uid, entry) ->
      val id = entry.bookId?.let(::BookId) ?: return@mapNotNull null
      uid to PhoneTagReport(
        title = bookRepository.get(id)?.content?.name ?: id.value.substringAfterLast('/'),
        name = entry.name,
        fromStart = entry.fromStart,
        server = groupOf(id) != null,
      )
    }.toMap()
    if (!tagMapSync.report(report)) Logger.i("Phone tags not reported; again next time")
  }

  internal companion object {
    /**
     * The folder of a book under the books directory -- its server group -- or
     * null if it is elsewhere. [booksUri] is that directory as a file URI, as
     * book IDs spell it (percent-encoded).
     */
    fun groupOf(
      bookId: String,
      booksUri: String,
    ): String? {
      val root = booksUri.trimEnd('/') + "/"
      if (!bookId.startsWith(root)) return null
      return URLDecoder.decode(bookId.removePrefix(root), "UTF-8").trim('/').ifEmpty { null }
    }
  }
}
