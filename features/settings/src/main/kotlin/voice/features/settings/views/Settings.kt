package voice.features.settings.views

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.retain.retain
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.navigation3.runtime.NavEntry
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.BindingContainer
import dev.zacsweers.metro.ContributesTo
import dev.zacsweers.metro.IntoSet
import dev.zacsweers.metro.Provides
import voice.core.common.rootGraphAs
import voice.core.ui.BVoiceBadge
import voice.core.ui.VoiceTheme
import voice.core.ui.icons.VoiceIcons
import voice.features.settings.SettingsListener
import voice.features.settings.SettingsViewEffect
import voice.features.settings.SettingsViewModel
import voice.features.settings.SettingsViewState
import voice.features.settings.views.sleeptimer.AutoSleepTimerCard
import voice.navigation.Destination
import voice.navigation.NavEntryProvider
import voice.core.strings.R as StringsR

@Composable
@Preview
private fun SettingsPreview() {
  VoiceTheme {
    Settings(
      SettingsViewState.preview(),
      SettingsListener.noop(),
    )
  }
}

@Composable
private fun Settings(
  viewState: SettingsViewState,
  listener: SettingsListener,
  snackbarHostState: SnackbarHostState = remember { SnackbarHostState() },
) {
  val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()
  Scaffold(
    modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
    snackbarHost = {
      SnackbarHost(hostState = snackbarHostState)
    },
    topBar = {
      TopAppBar(
        scrollBehavior = scrollBehavior,
        title = {
          Text(stringResource(StringsR.string.settings_action_open))
        },
        navigationIcon = {
          IconButton(
            onClick = {
              listener.close()
            },
          ) {
            Icon(
              imageVector = VoiceIcons.Close,
              contentDescription = stringResource(StringsR.string.common_action_close),
            )
          }
        },
      )
    },
  ) { contentPadding ->
    LazyColumn(contentPadding = contentPadding) {
      if (viewState.showDeveloperMenu && !viewState.kioskMode) {
        item {
          DeveloperMenuItem(
            onClick = listener::openDeveloperMenu,
          )
        }
      }
      item {
        ListItem(
          modifier = Modifier.clickable { listener.openFolderPicker() },
          leadingContent = {
            Icon(
              imageVector = VoiceIcons.Book,
              contentDescription = stringResource(StringsR.string.library_folders_title),
            )
          },
          supportingContent = {
            Text(stringResource(StringsR.string.settings_library_folders_summary))
          },
        ) {
          Text(stringResource(StringsR.string.library_folders_title))
        }
      }
      item {
        FeatureRow(
          icon = VoiceIcons.Download,
          title = stringResource(StringsR.string.server_books_title),
          summary = stringResource(
            if (viewState.serverSetUp) StringsR.string.server_books_summary else StringsR.string.server_books_not_set_up,
          ),
          checked = viewState.serverOn,
          onOpen = listener::openServerBooks,
          onToggle = listener::toggleServer,
        )
      }
      item {
        FeatureRow(
          icon = VoiceIcons.Bedtime,
          title = stringResource(StringsR.string.pillow_speaker_title),
          summary = stringResource(StringsR.string.pillow_speaker_summary),
          checked = viewState.pillowSpeakerOn,
          onOpen = listener::openPillowSpeaker,
          onToggle = listener::togglePillowSpeaker,
        )
      }
      item {
        FeatureRow(
          icon = VoiceIcons.Tag,
          title = stringResource(StringsR.string.tags_title),
          summary = stringResource(StringsR.string.tags_summary),
          checked = viewState.tagsOn,
          onOpen = listener::openTags,
          onToggle = listener::toggleTags,
        )
      }
      item {
        ThemeModeRow(viewState.themeMode, listener::onThemeModeRowClick)
      }
      if (viewState.showThemeColorSchemePref) {
        item {
          ThemeColorSchemeRow(viewState.themeColorScheme, listener::onThemeColorSchemeRowClick)
        }
      }
      if (viewState.showAnalyticSetting && !viewState.kioskMode) {
        item {
          AnalyticsRow(analyticsEnabled = viewState.analyticsEnabled, toggle = listener::toggleAnalytics)
        }
      }
      item {
        ListItem(
          modifier = Modifier.clickable { listener.toggleGrid() },
          leadingContent = {
            val icon = if (viewState.useGrid) {
              VoiceIcons.GridView
            } else {
              VoiceIcons.ViewList
            }
            Icon(
              imageVector = icon,
              contentDescription = stringResource(StringsR.string.settings_library_use_grid_title),
            )
          },
          trailingContent = {
            Switch(
              checked = viewState.useGrid,
              onCheckedChange = {
                listener.toggleGrid()
              },
            )
          },
        ) {
          Text(stringResource(StringsR.string.settings_library_use_grid_title))
        }
      }

      item {
        SeekTimeRow(viewState.seekTimeInSeconds) {
          listener.onSeekAmountRowClick()
        }
      }

      item {
        AutoRewindRow(viewState.autoRewindInSeconds) {
          listener.onAutoRewindRowClick()
        }
      }

      item {
        AutoSleepTimerCard(viewState.autoSleepTimer, listener)
      }

      item {
        ListItem(
          modifier = Modifier.clickable { listener.openBugReport() },
          leadingContent = {
            Icon(
              imageVector = VoiceIcons.BugReport,
              contentDescription = stringResource(StringsR.string.settings_support_report_issue_title),
            )
          },
          supportingContent = {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
              BVoiceBadge()
              Text(stringResource(StringsR.string.settings_support_report_issue_summary), modifier = Modifier.weight(1F, fill = false))
            }
          },
        ) {
          Text(stringResource(StringsR.string.settings_support_report_issue_title))
        }
      }
      item {
        ListItem(
          modifier = Modifier.clickable { listener.openVoice() },
          leadingContent = {
            Icon(
              imageVector = VoiceIcons.Favorite,
              contentDescription = stringResource(StringsR.string.settings_about_voice_title),
            )
          },
          supportingContent = { Text(stringResource(StringsR.string.settings_about_voice_summary)) },
        ) {
          Text(stringResource(StringsR.string.settings_about_voice_title))
        }
      }
      item {
        AppVersion(
          appVersion = viewState.appVersion,
          onClick = listener::onAppVersionClick,
        )
      }
      if (viewState.kioskMode) {
        if (viewState.showAnalyticSetting) {
          item {
            AnalyticsRow(analyticsEnabled = viewState.analyticsEnabled, toggle = listener::toggleAnalytics)
          }
        }
        if (viewState.showDeveloperMenu) {
          item {
            DeveloperMenuItem(
              onClick = listener::openDeveloperMenu,
            )
          }
        }
      }
    }
    Dialog(viewState, listener)
  }
}

@Composable
private fun AnalyticsRow(
  analyticsEnabled: Boolean,
  toggle: () -> Unit,
) {
  ListItem(
    modifier = Modifier.clickable { toggle() },
    leadingContent = {
      Icon(
        imageVector = VoiceIcons.Analytics,
        contentDescription = null,
      )
    },
    supportingContent = {
      Text(text = stringResource(StringsR.string.settings_analytics_consent_description))
    },
    trailingContent = {
      Switch(
        checked = analyticsEnabled,
        onCheckedChange = { toggle() },
      )
    },
  ) {
    Text(text = stringResource(StringsR.string.settings_analytics_consent_title))
  }
}

@ContributesTo(AppScope::class)
interface SettingsGraph {
  val settingsViewModel: SettingsViewModel
}

@BindingContainer
@ContributesTo(AppScope::class)
object SettingsProvider {

  @Provides
  @IntoSet
  fun settingsNavEntryProvider(): NavEntryProvider<*> = NavEntryProvider<Destination.Settings> { key ->
    NavEntry(key) {
      Settings()
    }
  }
}

@Composable
fun Settings() {
  val viewModel = retain<SettingsViewModel> { rootGraphAs<SettingsGraph>().settingsViewModel }
  val snackbarHostState = remember { SnackbarHostState() }
  val viewState = viewModel.viewState()
  val currentDeveloperMenuUnlockedMessage = rememberUpdatedState("Developer Menu unlocked")
  LaunchedEffect(viewModel) {
    viewModel.viewEffects.collect { viewEffect ->
      when (viewEffect) {
        SettingsViewEffect.DeveloperMenuUnlocked -> {
          snackbarHostState.showSnackbar(currentDeveloperMenuUnlockedMessage.value)
        }
      }
    }
  }
  Settings(viewState, viewModel, snackbarHostState)
}

@Composable
private fun Dialog(
  viewState: SettingsViewState,
  listener: SettingsListener,
) {
  val dialog = viewState.dialog ?: return
  when (dialog) {
    SettingsViewState.Dialog.AutoRewindAmount -> {
      AutoRewindAmountDialog(
        currentSeconds = viewState.autoRewindInSeconds,
        onSecondsConfirm = listener::autoRewindAmountChang,
        onDismiss = listener::dismissDialog,
      )
    }
    SettingsViewState.Dialog.SeekTime -> {
      SeekAmountDialog(
        currentSeconds = viewState.seekTimeInSeconds,
        onSecondsConfirm = listener::seekAmountChanged,
        onDismiss = listener::dismissDialog,
      )
    }
    SettingsViewState.Dialog.Theme -> {
      ThemeModeDialog(
        selectedThemeMode = viewState.themeMode,
        onThemeModeSelect = listener::setThemeMode,
        onDismiss = listener::dismissDialog,
      )
    }
    SettingsViewState.Dialog.ColorScheme -> {
      ThemeColorSchemeDialog(
        selectedThemeColorScheme = viewState.themeColorScheme,
        onThemeColorSchemeSelect = listener::setThemeColorScheme,
        onDismiss = listener::dismissDialog,
      )
    }
  }
}

/**
 * One of bVoice's own features: the left part opens its page, the switch on
 * the right turns it on or off.
 */
@Composable
private fun FeatureRow(
  icon: ImageVector,
  title: String,
  summary: String,
  checked: Boolean,
  onOpen: () -> Unit,
  onToggle: () -> Unit,
) {
  Row(
    modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Min),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    ListItem(
      modifier = Modifier.weight(1F).clickable(onClick = onOpen),
      leadingContent = { Icon(imageVector = icon, contentDescription = null) },
      supportingContent = {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
          BVoiceBadge()
          Text(summary, modifier = Modifier.weight(1F, fill = false))
        }
      },
    ) {
      Text(title)
    }
    VerticalDivider(Modifier.padding(vertical = 16.dp))
    Switch(
      checked = checked,
      onCheckedChange = { onToggle() },
      // Three of these in a row: say which feature each one is.
      modifier = Modifier.padding(horizontal = 16.dp).semantics { contentDescription = title },
    )
  }
}
