# bVoice

A fork of [Voice](https://github.com/PaulWoitaschek/Voice) by Paul Woitaschek,
made for one use: a child listening to audiobooks in bed through a pillow
speaker, with the books kept on a home server. It is a personal project, not
affiliated with Voice, and not published in any store. Voice's own features
are all still here; see its [documentation](https://voice.woitaschek.de).

## What bVoice adds

**Its own identity.** Application id `net.opendeved.bVoice`, name "bVoice", a
dark orange icon — so it installs beside Voice rather than over it. The icon is
an override in `app/src/free/res`; Voice's files are untouched.

**Server books** (Settings → Server books, or the Server button in onboarding).
The server offers a catalogue of audiobooks; the phone shows it by author,
ticks the books wanted, and syncs them into the app's own folder
(`Android/data/net.opendeved.bVoice/files/books/Author/Title/`), which Voice
reads as an Author folder. The **selection lives on the phone**, never on the
server. Downloads resume after an interruption and are checked against the
server's sha256; unticked books are removed, except the one playing, and a
removed book keeps its listening position. Only books the server offers, or
the sync put there, are ever removed. Code: `:core:sync`, `:features:serverBooks`.

**The pillow speaker** (Settings → Pillow speaker). Pair a Bluetooth speaker
through Android's companion-device confirmation, then:

- **Play when it connects** — the current book starts, whether or not bVoice is
  running (via `CompanionDeviceService`). bVoice waits for the speaker's media
  connection before playing, and retries once if playback stops at once.
- **Disconnect after a pause** of a set length (1–30 min), so the speaker can
  switch itself off — `BluetoothA2dp.disconnect` by reflection, which needs
  only `BLUETOOTH_CONNECT`. Only the media connection is dropped: a speaker that
  also connects for calls needs "Phone calls" turned off in Android's
  Bluetooth settings.
- A **force-stopped** app is not woken by Android; an automation that opens
  bVoice on connect (Bixby Routines, MacroDroid, Tasker) covers that.
- **Check**, **Disconnect now** and a **tap tester** for trying it out.

Code: `:core:speaker`, `:features:pillowSpeaker`.

**Speaker-button taps jump further.** Quick taps on a speaker's or headset's
back/forward add up: two ordinary jumps (the Seek time), then 1, 2, 5 and
10 minutes, then 5 minutes more a tap. Each tap acts at once. What counts as
quick is the **Tap spacing** setting (400 ms; 0 turns it off). On-screen and
notification buttons are unchanged. Forward jumps now cross several chapters,
as backward ones already did. Code: `TapGesture`, `LibrarySessionCallback`.

**A listening log.** One tab-separated line per start and stop — local time
with offset, book, position — in one text file per day
(`Android/data/net.opendeved.bVoice/files/listening-log/`), uploaded to the
server after each sync. Code: `:core:listeninglog`.

## Building

As for Voice (`./gradlew :app:assembleFreeDebug`). Build with `--no-scan`:
Voice's build otherwise publishes a Gradle Build Scan.

The server's address and API key are **not in the code**. The app keeps them
in its own settings (Server books → cogwheel). A build can carry defaults,
which the app copies into its settings on first start, from:

| | environment | Gradle property |
|---|---|---|
| server address | `VOICE_CONTENT_SERVER` | `voice.serverUrl` |
| API key | `VOICE_SERVER_TOKEN` | `voice.serverToken` |
| API key, from a JSON file with `credentials.password` | `VOICE_SERVER_TOKEN_FILE` | `voice.serverTokenFile` |

A build with defaults has the key inside the APK — do not share it. Without
them, the build says so and the app asks.

**Plain HTTP** is allowed only for hosts ending in `.nord` (a mesh VPN); for
another host, use HTTPS or edit `app/src/main/res/xml/network_security_config.xml`.

## What the server must provide

Any HTTP server with these routes under one base address, with a bearer token
(`Authorization: Bearer KEY`):

| route | answer |
|---|---|
| `GET api/v1/items` (or `api/v1/episodes`) | `{"generation", "episodes": [{"group": "Author/Title", "kind": "audiobook", "title", "author", "parts", "duration", "bytes", "digest"}]}` |
| `GET api/v1/manifest` | `{"generation", "files": {"Author/Title/01.m4a": [size, sha256], …}}` |
| `GET content/PATH` | the file, honouring `Range` |
| `PUT api/v1/logs/DEVICE/YYYY-MM-DD.txt` | optional: stores a day's listening log; a 403/404 is taken as "not supported" |

A book is a folder of chapter files, or one `.m4b` with chapters inside.

## Branches

`bvoice-main` is the published branch: upstream Voice's history plus one
squashed commit per update. `main` follows upstream Voice unchanged.

## Licence

GPL-3.0, as Voice ([LICENSE.md](LICENSE.md)).
