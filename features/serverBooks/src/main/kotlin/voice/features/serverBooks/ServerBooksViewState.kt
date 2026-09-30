package voice.features.serverBooks

import voice.core.sync.ServerBook

data class ServerBooksViewState(
  val serverUrl: String,
  val authors: List<Author>,
  val loading: Boolean,
  val error: String?,
  val sync: Sync,
  val serverDialog: ServerDialog?,
) {

  data class Author(
    val name: String,
    val books: List<Book>,
  )

  data class Book(
    val group: String,
    val title: String,
    val hours: Double,
    val megabytes: Double,
    val parts: Int,
    val selected: Boolean,
    val status: ServerBook.Status,
  )

  sealed interface Sync {
    data object Idle : Sync
    data class Running(
      val progress: Float?,
      val filesDone: Int,
      val filesTotal: Int,
    ) : Sync

    data class Finished(
      val fetched: Int,
      val deleted: Int,
      val failed: Int,
      val keptWhilePlaying: Int,
      val logsSent: Int = 0,
      val logError: String? = null,
    ) : Sync

    data class Failed(val message: String) : Sync
  }

  data class ServerDialog(
    val url: String,
    val token: String,
    val deviceName: String,
  )
}
