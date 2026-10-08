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

**A listening log — deliberately very basic.** One tab-separated line per
start and stop — local time with offset, book, position — in one text file per
day (`Android/data/net.opendeved.bVoice/files/listening-log/`), uploaded to the
server after each sync. Code: `:core:listeninglog`.

Voice's author plans a listening log of his own: see the discussion on the
closed [PR #3503 "Feature/audiolog"](https://github.com/PaulWoitaschek/Voice/pull/3503),
where he describes it as a timeline or a joined bookmarks/audio-log screen,
recorded as typed events in the app's database. **When that arrives upstream,
bVoice will adopt it** and keep only what the server needs on top — the daily
file and its upload — fed from upstream's events. Until then, bVoice offers
this basic log.

For now the log also carries **temporary diagnostic lines**, used to get the
speaker behaviour right: `TAP` (each back/forward tap from a speaker — its
count in the gesture, what it added, the gap since the key before) and
`SPEAKER` (the system waking the companion service, connect events, and
whether auto-play played or why not). They will be removed once the speaker
behaviour is settled; the `START`/`STOP` lines stay.

## Building

As for Voice (`./gradlew :app:assembleFreeDebug`). Build with `--no-scan`:
Voice's build otherwise publishes a Gradle Build Scan.

The server's address and API key are **not in the code or the APK**. The
app keeps them in its own settings, which survive updates. Two ways to set
them on a phone:

- **Server books → cogwheel:** type the address and key.
- **The server's setup page** (`/r/NAME/bvoice`, linked from the server's
  page as "Set up bVoice on a phone"): scan its QR code with the phone's
  camera, then tap **Open in bVoice**. The link
  `bvoice://setup?url=…&key=…` opens `ServerSetupActivity`, which asks
  before saving. Any server can offer the same link.

Uninstalling the app (or clearing its data) forgets them; set up again from
the setup page.

**"Token" and "API key" are the same thing.** The app calls it the API key; the
build settings and the code (`VOICE_SERVER_TOKEN`, `ServerConfig.token`) keep
the older name, token. Either way it is the secret the server checks, sent as
`Authorization: Bearer KEY`.

**Plain HTTP is allowed**, since a home server usually has no certificate
(`app/src/main/res/xml/network_security_config.xml`); use HTTPS where you can.

## What the server must provide

Any HTTP server with these routes under one base address, with a bearer token
(`Authorization: Bearer KEY`):

| route | answer |
|---|---|
| `GET api/v1/items` (or `api/v1/episodes`) | `{"generation", "episodes": [{"group": "Author/Title", "kind": "audiobook", "title", "author", "parts", "duration", "bytes", "digest"}]}` |
| `GET api/v1/manifest` | `{"generation", "files": {"Author/Title/01.m4a": [size, sha256], …}}` |
| `GET content/PATH` | the file, honouring `Range` |
| `PUT api/v1/logs/DEVICE/YYYY-MM-DD.txt` | optional: stores a day's listening log; a 403/404 is taken as "not supported" |
| `GET api/v1/tags` | optional, for NFC tags: `{"tags": {UID: {"name", "tech", "group", "fromStart", "cover"}}}`; UID in hex, upper case; `group` is a book's group, absent for none |
| `GET api/v1/tags/UID/cover` | optional: the tag's picture (JPEG/PNG/WebP), 404 if none |
| `PUT api/v1/tags/UID` | optional: `{"name", "tech", "group", "fromStart"}`, any subset; `"group": null` clears the book |
| `PUT api/v1/devices/DEVICE/tags` | optional: the phone's own tag books, `{UID: {"title", "name", "fromStart", "server"}}`, the whole set; for showing, never for deciding |

A book is a folder of chapter files, or one `.m4b` with chapters inside.
Groups and paths are checked by the app: a path with empty, `.` or `..`
segments is ignored.

**Setting up a phone:** a server may offer the link
`bvoice://setup?url=BASE&key=KEY` (both URL-encoded), for example as a
button and a QR code on a page behind its login; bVoice asks before saving
them. The server bVoice is developed against (app-media-server) is not
published yet; any server with these routes works.

## Branches

`bvoice-main` is the published branch: upstream Voice's history plus one
squashed commit per update. `main` follows upstream Voice unchanged.

**Versions** (`gradle.properties`): `bvoice.versionName` and
`bvoice.versionCode` are bVoice's own, the code one higher with each release
(with `fastlane/metadata/android/en-US/changelogs/CODE.txt`).
`bvoice.basedOnVoice` names the Voice release underneath and how many
upstream commits past it (`git describe --tags upstream/main`, e.g.
`26.6.1+106`); update it with every upstream merge. Settings shows
"bVoice 0.5.0 · based on Voice 26.6.1+106".

## Licence

GPL-3.0, as Voice ([LICENSE.md](LICENSE.md)).
