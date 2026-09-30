package voice.features.serverBooks

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.retain.retain
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.navigation3.runtime.NavEntry
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesTo
import dev.zacsweers.metro.IntoSet
import dev.zacsweers.metro.Provides
import voice.core.common.rootGraphAs
import voice.core.sync.ServerBook
import voice.core.ui.icons.VoiceIcons
import voice.features.serverBooks.ServerBooksViewState.Sync
import voice.navigation.Destination
import voice.navigation.NavEntryProvider
import voice.core.strings.R as StringsR

@ContributesTo(AppScope::class)
interface ServerBooksGraph {
  val serverBooksViewModel: ServerBooksViewModel
}

@ContributesTo(AppScope::class)
interface ServerBooksProvider {

  @Provides
  @IntoSet
  fun serverBooksNavEntryProvider(): NavEntryProvider<*> = NavEntryProvider<Destination.ServerBooks> { key ->
    NavEntry(key) {
      ServerBooks()
    }
  }
}

@Composable
fun ServerBooks() {
  val viewModel = retain<ServerBooksViewModel> { rootGraphAs<ServerBooksGraph>().serverBooksViewModel }
  ServerBooks(viewModel.viewState(), viewModel)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ServerBooks(
  viewState: ServerBooksViewState,
  viewModel: ServerBooksViewModel,
) {
  Scaffold(
    topBar = {
      TopAppBar(
        title = { Text(stringResource(StringsR.string.server_books_title)) },
        navigationIcon = {
          IconButton(onClick = viewModel::close) {
            Icon(VoiceIcons.Close, contentDescription = stringResource(StringsR.string.common_action_close))
          }
        },
        actions = {
          IconButton(onClick = viewModel::refresh) {
            Icon(VoiceIcons.History, contentDescription = stringResource(StringsR.string.server_books_action_refresh))
          }
          IconButton(onClick = viewModel::editServer) {
            Icon(VoiceIcons.Settings, contentDescription = stringResource(StringsR.string.server_books_action_server))
          }
        },
      )
    },
    bottomBar = {
      SyncBar(viewState.sync, onSync = viewModel::sync, onStop = viewModel::cancel)
    },
  ) { contentPadding ->
    val message = when {
      viewState.loading -> stringResource(StringsR.string.server_books_loading)
      viewState.error != null -> stringResource(StringsR.string.server_books_error, viewState.error)
      viewState.authors.isEmpty() -> stringResource(StringsR.string.server_books_empty)
      else -> null
    }
    LazyColumn(contentPadding = contentPadding) {
      if (message != null) {
        item {
          ListItem(
            supportingContent = { Text(viewState.serverUrl) },
          ) {
            Text(message)
          }
        }
      }
      viewState.authors.forEach { author ->
        item(key = "author:${author.name}") {
          Text(
            text = author.name,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 4.dp),
          )
        }
        items(author.books, key = { it.group }) { book ->
          BookRow(book, onToggle = { viewModel.toggle(book.group) })
        }
      }
    }
  }

  viewState.serverDialog?.let { dialog ->
    ServerDialog(dialog, onSave = viewModel::saveServer, onDismiss = viewModel::dismissServerDialog)
  }
}

@Composable
private fun BookRow(
  book: ServerBooksViewState.Book,
  onToggle: () -> Unit,
) {
  ListItem(
    modifier = Modifier.clickable(onClick = onToggle),
    leadingContent = {
      Checkbox(checked = book.selected, onCheckedChange = { onToggle() })
    },
    supportingContent = {
      Column {
        Text(
          stringResource(
            StringsR.string.server_books_detail,
            book.hours,
            book.megabytes,
            pluralStringResource(StringsR.plurals.server_books_parts, book.parts, book.parts),
          ),
        )
        when (book.status) {
          ServerBook.Status.OnDevice -> Text(
            stringResource(StringsR.string.server_books_status_on_device),
            color = MaterialTheme.colorScheme.primary,
          )
          ServerBook.Status.Partial -> Text(stringResource(StringsR.string.server_books_status_partial))
          ServerBook.Status.NotOnDevice -> Unit
        }
      }
    },
  ) {
    Text(book.title)
  }
}

@Composable
private fun SyncBar(
  sync: Sync,
  onSync: () -> Unit,
  onStop: () -> Unit,
) {
  Surface(tonalElevation = 3.dp) {
    Column(
      Modifier
        .fillMaxWidth()
        .navigationBarsPadding()
        .padding(16.dp),
      verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
      when (sync) {
        is Sync.Running -> {
          val progress = sync.progress
          if (progress ==
            null
          ) {
            LinearProgressIndicator(Modifier.fillMaxWidth())
          } else {
            LinearProgressIndicator({ progress }, Modifier.fillMaxWidth())
          }
          Text(stringResource(StringsR.string.server_books_sync_running, sync.filesDone, sync.filesTotal))
        }
        is Sync.Finished -> {
          Text(stringResource(StringsR.string.server_books_sync_finished, sync.fetched, sync.deleted))
          if (sync.failed > 0) Text(pluralStringResource(StringsR.plurals.server_books_sync_discarded, sync.failed, sync.failed))
          if (sync.keptWhilePlaying > 0) Text(stringResource(StringsR.string.server_books_sync_kept))
          if (sync.logsSent > 0) Text(pluralStringResource(StringsR.plurals.server_books_sync_logs_sent, sync.logsSent, sync.logsSent))
          sync.logError?.let {
            Text(stringResource(StringsR.string.server_books_sync_logs_failed, it), color = MaterialTheme.colorScheme.error)
          }
        }
        is Sync.Failed -> Text(
          stringResource(StringsR.string.server_books_sync_failed, sync.message),
          color = MaterialTheme.colorScheme.error,
        )
        Sync.Idle -> Unit
      }
      Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
        if (sync is Sync.Running) {
          OutlinedButton(onClick = onStop) { Text(stringResource(StringsR.string.server_books_action_stop)) }
        } else {
          Button(onClick = onSync) {
            Icon(VoiceIcons.Download, contentDescription = null)
            Text(stringResource(StringsR.string.server_books_action_sync), Modifier.padding(start = 8.dp))
          }
        }
      }
    }
  }
}

@Composable
private fun ServerDialog(
  initial: ServerBooksViewState.ServerDialog,
  onSave: (ServerBooksViewState.ServerDialog) -> Unit,
  onDismiss: () -> Unit,
) {
  var url by remember { mutableStateOf(initial.url) }
  var token by remember { mutableStateOf(initial.token) }
  var deviceName by remember { mutableStateOf(initial.deviceName) }
  AlertDialog(
    onDismissRequest = onDismiss,
    title = { Text(stringResource(StringsR.string.server_books_action_server)) },
    text = {
      Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(StringsR.string.server_books_dialog_saved), style = MaterialTheme.typography.bodySmall)
        OutlinedTextField(
          value = url,
          onValueChange = { url = it },
          label = { Text(stringResource(StringsR.string.server_books_dialog_url)) },
          singleLine = true,
          keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
        )
        OutlinedTextField(
          value = token,
          onValueChange = { token = it },
          label = { Text(stringResource(StringsR.string.server_books_dialog_token)) },
          singleLine = true,
          visualTransformation = PasswordVisualTransformation(),
          keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
        )
        OutlinedTextField(
          value = deviceName,
          onValueChange = { deviceName = it },
          label = { Text(stringResource(StringsR.string.server_books_dialog_device)) },
          supportingText = { Text(stringResource(StringsR.string.server_books_dialog_device_hint)) },
          singleLine = true,
        )
      }
    },
    confirmButton = {
      TextButton(onClick = { onSave(ServerBooksViewState.ServerDialog(url, token, deviceName)) }) {
        Text(stringResource(StringsR.string.server_books_dialog_save))
      }
    },
    dismissButton = {
      TextButton(onClick = onDismiss) { Text(stringResource(StringsR.string.common_dialog_cancel)) }
    },
  )
}
