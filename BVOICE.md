# bVoice

A fork of [Voice](https://github.com/PaulWoitaschek/Voice) by Paul Woitaschek,
for Bluetooth bedtime listening. I've used Voice for a while, and these are
tweaks made over time. They are probably not something everybody wants, but
here they are. It is a personal project, not affiliated with Voice, and not in
any store. Everything Voice does is still here; see its
[documentation](https://voice.woitaschek.de). A note about bVoice for Voice's
author: [Discussion #3841](https://github.com/PaulWoitaschek/Voice/discussions/3841).

bVoice has its own application ID (`net.opendeved.bVoice`) and name, so it
installs beside Voice. It credits Voice in its Settings ("Built on Voice", and
"bVoice 0.5.0 · based on Voice 26.6.1+106") and points people to Voice for
everything else. Each of the features below can be switched off in Settings,
and its row carries an orange **bVoice** pill.

## Starting, stopping and navigating audio as simply as possible

**Stopping attached devices reliably: Bluetooth speakers that need a disconnect
to go to standby** (Settings → Pillow speaker). Units like the J207 won't go to
sleep when the audio stops; they need to be disconnected. So when playback has
been paused for a configurable time (for example after the sleep timer ends),
bVoice disconnects the speaker, which then goes into its own standby. It also
works the other way: when the speaker is switched on and connects, the book
starts.

Why this matters: the power consumption is much lower. The J207 lasts a long
time if it is only used for about 30 minutes of listening a day, rather than
staying on all day and night. Doing it other ways, with MacroDroid and the
like, was fragile and sometimes unreliable; built into the player, it works
well.

- **Pairing:** pair the speaker through Android's companion-device
  confirmation.
- **Auto-play:** bVoice is woken through `CompanionDeviceService` presence
  events. It waits for the speaker's media (A2DP) connection before playing,
  and retries once if the route change pauses playback. A pause the user asks
  for is left alone.
- **Disconnect:** after a pause of 1–30 min, `BluetoothA2dp.disconnect` by
  reflection, which needs only `BLUETOOTH_CONNECT`. Only the media connection
  is dropped: a speaker that also connects for calls needs "Phone calls"
  turned off in Android's Bluetooth settings.
- **After a full shutdown:** if the app has been shut down completely
  (force-stopped), Android doesn't always wake it on connect. An automation
  that opens bVoice when the speaker connects (Bixby Routines, MacroDroid,
  Tasker) covers that, and bVoice treats such a fresh start as the connect.
- **Testing:** **Check**, **Disconnect now** and a **tap tester**.
- **Code:** `:core:speaker`, `:features:pillowSpeaker`.

**Speaker tap gestures.** Quick taps on a speaker's or headset's back/forward
button add up:

- The first two taps are ordinary jumps (the Seek time); then 1, 2, 5 and
  10 minutes, then 5 minutes more a tap. Each tap acts at once.
- What counts as quick is the **Tap spacing** setting (400 ms; 0 turns it off).
- On-screen and notification buttons are unchanged. Forward jumps cross
  chapters, as backward ones already did.

Useful for moving back and forth without reaching for the phone. Code:
`TapGesture`, `LibrarySessionCallback`.

## Loading and offloading books

**Books from a home server** (Settings → Server books). Choose audiobooks from
a small self-hosted HTTP server and keep them on the phone. Easier than
Syncthing and the like, and it saves space, since books can be offloaded
again.

- **Choosing:** the phone shows the server's catalogue by author. You tick
  books and sync them into the app's own folder
  (`Android/data/net.opendeved.bVoice/files/books/Author/Title/`). The
  selection lives on the phone.
- **Downloading:** downloads resume after an interruption and are checked
  against the server's sha256.
- **Removing:** unticked books are removed, except the one playing, and keep
  their listening position. A server answer that looks broken (an empty
  catalogue, say) removes nothing.
- **Setting up a phone:** scan a QR code on the server's page. No server
  address or key is built into the app.
- **The server's API:** below, under "What the server must provide".
- **Code:** `:core:sync`, `:features:serverBooks`.

## NFC as a Tonie/Yoto-style player

Perhaps the most niche one (Settings → Tags). Hold a tag to the phone to start
a book: plain NTAG cards and stickers, or things that have tags, like Tonie
figures (only the tag's ID is read).

- **Setting up:** any tag, Tonies included, is set up by scanning it on
  Settings → Tags and choosing its book there.
- **Unknown tags elsewhere:** an unknown card or sticker offers to open that
  page. An unknown Tonie does nothing, so a figure still played on its
  Toniebox causes no prompts.
- **Known tags without a book:** a tag that is known but has no book yet says
  so.
- **Phone books:** tags are given books on the phone, and work without any
  server.
- **With a server:** a server book chosen on the phone is saved on the server,
  and tags can also be assigned on the server's tags page. A book only the
  phone has overrides the server's for that tag.
- **Labels:** each tag shows *server*, *phone* or *override*.
- **Code:** `:core:tags`, `:features:tags`.

## Listening log

A plain-text file per day (`Android/data/net.opendeved.bVoice/files/listening-log/`),
with one tab-separated line per start and stop: local time with offset, book,
position. If a server is used, it is handed over after each sync. It can be
switched off. Code: `:core:listeninglog`.

Voice's author plans a listening log of his own (see the closed
[PR #3503 "Feature/audiolog"](https://github.com/PaulWoitaschek/Voice/pull/3503)),
so this is an interim feature. When that arrives upstream, bVoice will adopt
it and keep only what the server needs on top: the daily file and its upload.

For now the log also carries **temporary diagnostic lines**, used to get the
speaker behaviour right:

- `TAP`: each back/forward tap, its count in the gesture, what it added and
  the gap since the last key.
- `SPEAKER`: the service being woken, connect events, and whether auto-play
  played, or why not.
- `TAG`: tags scanned, and what they played.

They will go once the behaviour is settled. The `START`/`STOP` lines stay.

## Its own look

The icon and the default colour scheme are dark orange ("bVoice orange"; Voice
blue and Dynamic are still offered). The icon is an override in
`app/src/free/res`; Voice's files are untouched. Settings' support rows point
to bVoice (problem reports) or to Voice ("Built on Voice"), not to Voice's
donation, translation and FAQ pages.

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
