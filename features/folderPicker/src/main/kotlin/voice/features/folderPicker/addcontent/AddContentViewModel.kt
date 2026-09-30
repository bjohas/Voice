package voice.features.folderPicker.addcontent

import android.net.Uri
import androidx.datastore.core.DataStore
import dev.zacsweers.metro.Assisted
import dev.zacsweers.metro.AssistedFactory
import dev.zacsweers.metro.AssistedInject
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch
import voice.core.data.folders.AudiobookFolders
import voice.core.data.folders.FolderType
import voice.core.data.store.OnboardingCompletedStore
import voice.core.sync.SyncDirectory
import voice.features.folderPicker.folderPicker.FileTypeSelection
import voice.navigation.Destination
import voice.navigation.Destination.OnboardingCompletion
import voice.navigation.Destination.SelectFolderType
import voice.navigation.Navigator
import voice.navigation.Origin

@AssistedInject
class AddContentViewModel(
  private val audiobookFolders: AudiobookFolders,
  private val navigator: Navigator,
  private val syncDirectory: SyncDirectory,
  @OnboardingCompletedStore
  private val onboardingCompletedStore: DataStore<Boolean>,
  @Assisted
  private val origin: Origin,
) {

  internal fun add(
    uri: Uri,
    type: FileTypeSelection,
  ) {
    when (type) {
      FileTypeSelection.File -> {
        audiobookFolders.add(uri, FolderType.SingleFile)
        when (origin) {
          Origin.Default -> {
            navigator.setRoot(Destination.BookOverview)
          }
          Origin.Onboarding -> {
            navigator.goTo(OnboardingCompletion)
          }
        }
      }
      FileTypeSelection.Folder -> {
        navigator.goTo(
          SelectFolderType(
            uri = uri,
            origin = origin,
          ),
        )
      }
    }
  }

  private val scope = MainScope()

  /**
   * Books from the server live in the app's own directory, laid out as
   * Author/Title/files -- which is [FolderType.Author] -- and no other app can
   * write there, so there is nothing to pick. Registering it is idempotent.
   */
  internal fun addServer() {
    val books = syncDirectory.books
    books.mkdirs()
    audiobookFolders.add(Uri.fromFile(books), FolderType.Author)
    // The library underneath, the books to choose on top: the completion
    // screen would say a book is ready before one is.
    scope.launch {
      onboardingCompletedStore.updateData { true }
    }
    navigator.setRoot(Destination.BookOverview)
    navigator.goTo(Destination.ServerBooks)
  }

  internal fun back() {
    navigator.goBack()
  }

  @AssistedFactory
  interface Factory {
    fun create(origin: Origin): AddContentViewModel
  }
}
