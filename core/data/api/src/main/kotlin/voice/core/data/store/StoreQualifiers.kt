package voice.core.data.store

import dev.zacsweers.metro.Qualifier

@Qualifier
public annotation class OnboardingCompletedStore

@Qualifier
public annotation class CurrentBookStore

@Qualifier
public annotation class AutoRewindAmountStore

@Qualifier
public annotation class SeekTimeStore

@Qualifier
public annotation class SleepTimerPreferenceStore

@Qualifier
public annotation class GridModeStore

@Qualifier
public annotation class ThemeModeStore

@Qualifier
public annotation class ThemeColorSchemeStore

@Qualifier
public annotation class FadeOutStore

@Qualifier
public annotation class AmountOfBatteryOptimizationRequestedStore

@Qualifier
public annotation class ReviewDialogShownStore

@Qualifier
public annotation class FolderPickerMovedDialogShownStore

@Qualifier
public annotation class AnalyticsConsentStore

@Qualifier
public annotation class DeveloperMenuUnlockedStore

@Qualifier
public annotation class FeatureFlagOverridesStore

@Qualifier
public annotation class ServerConfigStore

/** The server book groups chosen for this device. The selection lives here, never on the server. */
@Qualifier
public annotation class ServerSelectionStore

@Qualifier
public annotation class SpeakerSettingsStore

/** The phone's copy of the server's NFC tag map, UID -> entry, so tags work offline. */
@Qualifier
public annotation class TagMapStore

/** Tags this phone alone knows, UID -> entry (NFC-TAGS-LOCAL.md); never synced. */
@Qualifier
public annotation class LocalTagMapStore

/** Tags switched on in Settings: off, the phone does not read tags at all. */
@Qualifier
public annotation class TagsEnabledStore
