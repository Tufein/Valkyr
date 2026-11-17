# Valkyr — technical documentation

*This is the detailed documentation for developers and curious readers. For a simple overview, see the [README](README.md).*

Valkyr is an Android app for discovering, downloading and managing retro games. It indexes the contents of `.torrent` files, magnet links and web directories, tags every file by console, region and language, and downloads straight into your ROMs folder. The whole app works from a D-pad and buttons on Android handhelds as well as by touch, and it comes with **tools to look after the library you build with it**: a duplicate finder, a library overview, backup & restore, scan progress, and English, Spanish, Dutch, French, German, Italian and Portuguese.

> Until 3.9.0 the app was called Dogmatix+. 4.0.0 renamed it to Valkyr; the application id, the stored settings, the library and the downloads stayed the same, so the app keeps updating in place.

Download links and screenshots are in the [README](README.md).

## Library tools

Everything is under **Settings**, works with a gamepad and with touch, and is available in English, Spanish, Dutch, French, German, Italian and Portuguese.

### Duplicate games
*Settings → Duplicate games* finds games that are on disk more than once, per console, and frees the space.

- **Two kinds of group**: *the same file in several folders* (same name and size) and *different versions or formats of one title* — regions, revisions, translations, a ROM next to its `.zip`. Each group shows how much space you win by keeping only the largest copy, which is marked.
- **Deleting is deliberate**: pick a copy and a confirmation lists every file that will go; only then is it deleted. A game's companion files go as one unit — `.cue` + `.bin` + `(Track N)`, `.cue` + `.iso` + audio tracks — and a per-game folder holding one disc image (`disc.gdi` + `track01.bin`…) counts as one game and is removed once empty. The delete finishes even if you leave the screen.
- **Built to report too little rather than too much**, because it deletes files:
  - only known game formats (ROMs, disc images, archives) are compared; documents, saves in unknown formats, engine data, updates / DLC and BIOS files are counted but never offered;
  - discs, disk sides, tapes and parts (`Disc 2`, `Disc Two`, `Disc II`, `Tape 1 of 2`) are never compared against each other, and saves, states and patches next to a ROM are never touched;
  - program folders, folder-format games (PS3, Wii U, GameCube…) and anything deeper than one folder below the console folder are not compared;
  - outside console folders a `.zip` / `.chd` / `.iso` says nothing about the system, so it is only offered when name and size are identical;
  - the same file reached through two storage routes (internal storage, the Downloads provider, an SD card) is counted once, and copies that cannot be told apart from one file seen twice are not offered.
- **Limits worth knowing**: different games that share a title (for example *Star Wars* on the NES in Japan and the US) can show up as "versions" — the file names in the confirmation tell them apart; and a console that is not set up in Sources is treated as an unknown folder, so its games need an identical twin to be offered.
- Scanning uses one provider query per folder instead of one per file, so thousands of ROMs stay quick.

### Library overview
*Settings → Library overview* puts the scan next to what is on your disk.

- Totals: consoles, games indexed, games you own (and the percentage), games on disk with their size, and the free space.
- Per console: indexed, owned, on disk (count and size), the resolved download folder, **when it was last scanned**, and a badge — *OK*, *No sources*, *Nothing found* or *Not scanned*.
- Rescan one console or all sources from here; folders without a console and loose games are listed so nothing hides. The numbers refresh by themselves when a scan finishes.

### Backup & restore
*Settings → Back up* writes one JSON file with your settings, sources, favourites and downloads list; *Restore backup* brings it back.

- **It contains your API keys** (TorBox, Real-Debrid, RomM) — keep the file private.
- A restore **reads and checks the whole file first**, then applies it in one step that leaving the screen cannot cancel; the sources are replaced in a single database transaction, so an interruption never leaves them half replaced.
- It keeps what a backup cannot carry: uploaded `.torrent` sources stay, and folders this install has no access to are kept as they are (you are asked to pick them again). Settings with the wrong type are dropped instead of crashing the app, numbers are kept within what Settings offers, backup files with a newer format version are refused, and a restore is refused while a source scan runs.
- The format uses fixed field names, so a backup made by one build restores in another, including the minified release build.

### Save sync with RomM
*Settings → Save sync* makes your RomM server the place your games save to and load from: the emulator saves and save states on the handheld and on the server are kept the same, in both directions, so a game can be continued on another device or in RomM's web player (EmulatorJS uses the same `.srm` / `.state` names as RetroArch).

- **Folders**: pick the emulator's saves folder and its save-states folder (RetroArch: `RetroArch/saves`, `RetroArch/states`; one folder for both works too — `.state`, `.state1`…, `.state.auto` are states, everything else a save). Files up to three folder levels deep are synced (RetroArch's *sort by core / by content folder*); thumbnails, configs and temporary files are skipped, and files over 64 MB are not sent.
- **Which game a file belongs to**: the file name without extension must equal the ROM's file name without extension (`Pokemon Emerald (USA).srm` ↔ `Pokemon Emerald (USA).gba`). Valkyr asks RomM's search and checks the names exactly; when the same name exists on several platforms, a folder in the save's path named like the platform (`saves/gb/…`) decides, otherwise the file is left alone. A first-level folder (`saves/mGBA/…`, the RetroArch core) is sent as RomM's *emulator*, and a device that has a folder of that name gets the file in it.
- **What moves**: after each sync Valkyr remembers what both sides looked like (size and time of the device file; time, size and hash of the server file). Next time, only the side that changed is copied; when **both** changed, nothing is overwritten and the file is listed with *◀ Device* / *RomM ▶* to keep one. The first time a file exists on both sides, equal contents are simply recorded and different contents are listed the same way. Device clocks never have to agree with the server's.
- **Safety**: a device file replaced by a download is first copied to the app's private storage (`files/save-backups/`, kept 30 days); downloads are written to a temporary file and only then replace the old one. Deletions are not synced: a file missing on one side is copied back from the other. Slot saves (RomM 5's dated history) are left alone.
- **Standalone emulators**: next to RetroArch's folders, *Add an emulator's saves folder* takes the saves folder of DraStic, melonDS, mGBA, Pizza Boy GBA / GBC, GBA.emu, GBC.emu, Snes9x EX+, NES.emu or MD.emu (emulators that name their saves after the ROM). The sync sees it as a folder named after the emulator inside the saves folder: that name goes to RomM as *emulator*, server saves of that emulator come down into it, and the emulator's consoles decide between games of the same name on several platforms. A folder of the same name in RetroArch's saves folder stops the sync with a message instead of mixing the two. In-game saves only, no save states. With only emulator folders set up, server saves of other emulators stay on the server.
- **When**: *Sync now*, and — when switched on — automatically when Valkyr opens or comes back to the front (at most every two minutes), with a short message when something moved or needs a choice. Close the game before syncing: an emulator that is still running can write its older in-memory save over a freshly downloaded one.
- Works with RomM 3.10, 4 and 5, with an `rmm_…` client token (scopes *assets* read/write and *roms* read) or `user:password`.

### Favourites sync
A sources export (*Sources → Export*) now carries your ★ favourites as a `_favourites` list next to the consoles; importing such a file on another device **adds** them to the favourites there (a game starred on both keeps the earliest date; un-starring is not carried over). Favourites are keyed by console and file name, so they light up as soon as the rescan has indexed the games. Older versions ignore the list. Backups keep carrying favourites on their own.

### Scan progress
While sources are scanned the indicator in the top bar fills up and shows a **percentage**, counted per source. The overview adds *x of y sources done*, an estimate of the **time left** and a progress bar (the estimate is rough while torrents are involved).

### Dutch, French, German, Italian and Portuguese
The whole app — including scan messages, errors, the download notification and the update notice that used to be fixed English — is available in **English, Spanish, Dutch, French, German, Italian and Portuguese**. Pick it in *Settings → Language*, or let it follow the system; Android's per-app language setting lists them too.

## Design goals

Valkyr is made for handhelds with a small landscape screen and a controller, without making the touchscreen second-rate:

- **Gamepad first**: every action reachable from D-pad, A/B/X/Y and shoulder buttons, with a visible focus ring and an on-screen legend.
- **Landscape first**: fixed filter panel next to the list, tabs on top, no wasted vertical space. Portrait still works, with a bottom bar and modal sheets.
- **Flat, minimal look**: a single accent colour, light/dark theme, Manrope typeface, no cards-inside-cards.
- **Works with or without a controller**: every flow is designed for D-pad/buttons first and then checked with touch.

## Interface and features

### First run
- Onboarding wizard: what Valkyr does → pick the root ROMs folder → import a sources JSON. Every step can be skipped; B goes one step back; it never shows again once finished.
- The app ships **without default sources** (`consoles.json` is empty). Import a JSON (see *Sources*) or add consoles by hand.

### Shell and navigation
- The floating "Menu" button is gone. Landscape shows numbered tabs on top (Library · Downloads · Sources · Settings); portrait shows a bottom bar with a downloads badge.
- Full-screen immersive mode with swipe-to-reveal system bars; status bar icons follow the theme.
- Free space of the download volume shown in the shell; a rescan indicator next to the tabs.

### Gamepad support
- Controller detection via `InputManager`; the legend at the bottom only appears when a gamepad is connected and is contextual to the focused area.
- **ZL / ZR** (L2/R2, button or axis) switch section · **LB / RB** switch panel (filters ⇄ list) · **R3** folds / unfolds the filter panel (opens / closes the filter sheet in portrait) · **X** opens the game details popup on the focused row (B or X closes it) · **Y** focuses search · **A** confirms · **B** undoes one layer at a time (sheet/dropdown → search → text → filters → focus back to tabs) and never closes the app.
- The legend is kept short on the list and filter panels (B and LB / RB are left out) so it fits on one line even on 4:3 screens; it scrolls sideways if it still does not fit.
- Every interactive control has a visible focus ring; when changing section the focus parks and the first D-pad press lands on the active tab.

### Library
- Fixed filter panel (Console, Region, Language, Tag, Favourites, Source, Sort) with ◀ ▶ quick change and multi-select dropdowns; portrait uses console chips and a modal filter sheet. Sort offers A → Z, Z → A and size in both directions (ties broken by name, so paging never repeats a game).
- The panel folds into a thin rail (arrow at its bottom-right corner, or R3) to give the list the full width; the slide is animated at 60 fps on the K56, with names, search box and focus ring following the panel edge and ellipses appearing progressively.
- Rows show a short console chip (NES, SNES, PS2…), tags and file extension. When the list is narrower than 560 dp (4:3 screens, or the panel open on a narrow one) rows stack the name above the tags instead of the single-line table, so names are never squeezed out.
- ✓ mark on games that are already in the download folder (`LibraryIndexService`, matched by name with and without extension).
- ★ **Favourites**: Select (or the ★ button in the details popup) stars a game; a "Favourites" filter row shows only starred games. Stored in their own Room table keyed by console + file name, so they survive rescans.
- Lenient search: accents, punctuation and doubled letters are ignored, so "yugioh" finds *Yu-Gi-Oh!* and "virtual tenis" finds *Virtua Tennis* (`SearchNormalizer`).
- Favorite languages (set in Settings) appear first in the language filter.
- Game details popup (X on a row): cover art, year, developer, genres and synopsis from RAWG, falling back to TheGamesDB; results (hits and misses) are cached in Room. API keys go in a git-ignored `.env` (`RAWG_API_KEY`, `THEGAMESDB_API_KEY`); without them the popup just says "no data".

### Downloads
- Each row shows the same tags as the library, so repeated versions of a game can be told apart.
- The downloads list is persisted in Room (`DownloadHistory`), so it survives app restarts; in-flight items come back as *Stopped* and can be retried (debrid downloads resume where they left off, see below).
- Rows can also show the RomM upload state (*↑ RomM n%*, *Uploaded*, *failed* + retry).
- **Multi-selection**: Select (long press with touch) ticks rows and turns the summary line into an action bar with only the actions the ticked rows accept — retry, pause, stop, delete — with a single confirmation for the lot. While selecting, A ticks, Y ticks/unticks everything, X deletes and B drops the selection.
- **Pause / resume for torrents**: the pause button parks a download keeping its data; play resumes from the pieces already on disk, even after the app was closed in between.
- Direct HTTP and RomM downloads keep their partial file when they stop or fail and the retry continues it with a `Range` request instead of starting over.

### Sources
- Flat cards and tonal buttons, navigable with the controller: dialogs open with the field focused, B closes them, deleting a console or URL asks for confirmation (focus starts on *Cancel*).
- URLs can be edited in place, not only added and removed. Deleting a manufacturer also deletes its consoles.
- **Export / import**: the share icon writes a `valkyr-sources.json` (`dogmatix-sources.json` before 4.0) and opens the Android share sheet; the import icon picks a JSON, replaces every source, rescans everything and re-indexes the library. The format is the one of the bundled `consoles.json` plus display names, a `short` label and folder `aliases` per console — export from any device to get one; no sources file is shipped in this repository.
- Each console stores its **short name** (the chip label: GBA, PS2…) and its **folder aliases** (names that count as its download folder). Empty values fall back to the built-in tables, and both are editable in the console dialog, so imported or hand-made consoles behave like the built-in ones.
- If a newer build ships additions in `consoles.json`, they are merged into the existing sources (nothing the user changed is touched) and only the new entries are scraped.
- Download-folder card with the resolved path per console.
- When "separate subfolders by console" is on and the chosen directory already contains a folder used by popular frontends (`gb`, `psx`, `snes`… — ES-DE, RetroArch, Batocera, EmulationStation naming), Valkyr reuses it instead of creating a new one (`ConsoleFolderAliases`, `ConsoleDownloadPathResolver`).
- Merge folders dialog: when several folders for the same console live side by side (e.g. `gba` and `Gameboy Advance`), move everything into the one you pick.

### Debrid services (TorBox, Real-Debrid)
- Settings → *Debrid service* (Off / TorBox / Real-Debrid) + the matching *API key* row (with a *Test* button that reports the account). When a service is selected, torrent rows are handed to it: the magnet is submitted, the row shows *TorBox n%* / *Real-Debrid n%* while the service fetches it (cached torrents finish at once), then the file is downloaded over plain HTTP through the usual path (speed limit, per-console folder, auto-unzip). Cancelling deletes it from the account; the file is matched by name and size (`DebridMatcher`). Retrying a failed torrent re-reads the setting, so the service can be switched between attempts.
- [TorBox](https://torbox.app): key from *Settings → API*. [Real-Debrid](https://real-debrid.com/apitoken): needs a **premium** account (free accounts can validate the key but `addMagnet` answers `permission_denied`, which the row reports as *Failed*); the wanted file is selected with `selectFiles` before the service fetches it, and the link goes through `unrestrict/link`.
- **Resumable**: the service's torrent/file ids are kept in the download history, the partial file stays on disk, and a retry after the app was killed continues with an HTTP `Range` request instead of starting over (debrid links serve ranges; plain HTTP sources still restart from zero).
- **Limitation**: TorBox zips torrents with 100+ files and its cache is shared, so for the big Myrient/No-Intro sets it can only hand back one huge `.zip`, not single ROMs (`allow_zip=false` only affects torrents nobody has cached yet). When that happens Valkyr removes the torrent from the account and silently falls back to the direct torrent download for that file, so the toggle is safe to leave on.

### RomM
- Settings → *Save sync* keeps emulator saves and save states in sync with the server (see [Save sync with RomM](#save-sync-with-romm-110-beta)).
- Settings → *RomM server*: URL, API token (`rmm_…` client token or `user:password`), *Test connection*, *Upload finished downloads*, and one stepper per console to pick the RomM platform it uploads to. Suggestions come from the same folder-alias table used for frontend folders (`gba`, `psx`, `snes`…); *Apply suggestions* maps every unmapped console at once.
- Every download that finishes while the switch is on (and whose console is mapped) is uploaded with RomM's chunked API (`/api/roms/upload/start` → chunks → `complete`); extracted archives upload each extracted file. The Downloads row shows *↑ RomM n%*, *Uploaded* or *failed* with a retry button. A cut-short upload resumes from the chunk it stopped at (also after the app was killed). Plain `http://` servers are allowed (cleartext traffic is enabled for the app); self-signed HTTPS works after you confirm the certificate's fingerprint once (*RomM server → Server certificate*).
- **RomM as a source**: in Sources → *Add URL*, pick one of the server's platforms (or type `romm://<slug>`) and the console indexes every ROM RomM has for it; downloads go through RomM's `/api/roms/{id}/content/…` with the account credentials, and files that came from RomM are never uploaded back.
- RomM only lists a ROM once it has scanned it, and scanning is not exposed through its REST API: either run a scan from the RomM web UI after uploading, or start RomM with `ENABLE_RESCAN_ON_FILESYSTEM_CHANGE=true` so uploaded files are picked up automatically.

### Settings
- Theme (System / Light / Dark / **True black**, a pure `#000000` background for AMOLED screens) and accent colour (5 presets), persisted in DataStore.
- Language (System / English / Spanish / Dutch / French / German / Italian / Portuguese).
- Download directory, concurrent downloads and speed limit as steppers (◀ ▶ with the controller), switches for auto-unzip and per-console subfolders, favorite languages picker, "About & contact".
- *Maximum search results* stepper (50 / 100 / 250 / 500 / Unlimited, default 100): how many games a library search loads at once; *Load more* fetches the next batch, *Unlimited* lists everything the filters match.
- *Metadata timeout* stepper (10–180 s, default 20): how long a rescan or a direct torrent download waits for a magnet's file list before giving up — raise it on slow trackers/DHT.
- *Debrid service* stepper (Off / TorBox / Real-Debrid) with a single *API key* row for the selected service (dialog with *Test*); the key is masked in the row and stored only on the device.
- *Gamepad layout* stepper (Xbox / Nintendo / PlayStation): draws the button legend the way your pad is printed — Xbox A/B/X/Y with `LB · RB` / `LT · RT`, Nintendo the same letters in Super Famicom colours (A red, B yellow, X blue, Y green) with `L · R` / `ZL · ZR`, PlayStation ✕ ○ □ △ each in its own colour with `L1 · R1` / `L2 · R2`. Names and colours only: A (✕) always accepts and B (○) always goes back.
- *Swap A/B and X/Y* switch, for pads that report their face buttons the other way round: it moves the actions and leaves the legend untouched, dialogs and the filter sheet included.
- **Frontends**: *Frontend shortcuts* drops a `.dgmtx` shortcut into every console folder; *Configure ES-DE* and *Configure iiSU* do the whole frontend setup in one button, and *Set up Daijishō* hands over the values its emulator form needs — see [FRONTENDS.md](FRONTENDS.md) and the *Deep links* section below.
- Two-column layout in landscape.
- **Library rows**: *Library overview*, *Duplicate games*, *Back up* and *Restore backup* (see [Library tools](#library-tools)).

### Under the hood
- The Kotlin package is `com.tufein.valkyr`; the application id is `com.tufein.valkyr` (kept through the rename to Valkyr so installed apps keep updating).
- Room schema at v17 with explicit migrations (search key, download history, game metadata cache, per-console short name and folder aliases, favourites, debrid resume ids, per-game metadata overrides).
- All source handling lives in `SourcesRepository`; the JSON format is read and written by one pure `SourcesJson` object shared by the bundled asset, the Room `urls` column and export/import. Sources are routed by URL: `magnet:`/`.torrent` → libtorrent, `romm://` → the RomM API, anything else → HTML directory scraping.
- Downloads are routed in `DownloadService.perform()`: debrid service (when selected; `DebridClient` implemented by `TorBoxClient` and `RealDebridClient`) → HTTP with resume; torrent → libtorrent; HTTP otherwise (with the RomM credentials when the file comes from the RomM server). Integrations talk to their APIs with a tiny `JsonHttp` helper over `HttpURLConnection` + Gson — no OkHttp.
- Secrets (TorBox / Real-Debrid keys, RomM token) live only in the app's DataStore; nothing is baked into the APK or the repository.
- The library tools live in `DuplicateFinder`, `DiskScanner`, `LibraryScanService`, `BackupJson` and `BackupService` (see [Library tools](#library-tools)).
- Unit tests for the pure logic: search/parsing (`SearchNormalizer`, `ConsoleFolderAliases`, `GameTitleCleaner`, `LibraryKeys`), sources (`SourcesJson`), deep links and shortcuts (`DeepLinkParser`, `DeepLinkResolver`, `DgmtxFile`), frontends (`EsdeXml`, `IisuJson`, `DaijishoSetup`), debrid and RomM (`DebridMatcher`, `RommPlatformMapper`, `RommSource`), save sync (`SaveSyncPlanner`, `SaveSyncEngine` with an in-memory device and server, plus `RommSaveSyncLiveTest` against a real server), versions (`VersionUtils`), the gamepad legend (`GamepadLayout`), and the documentation itself (`ReleaseDocsTest` for the version in the READMEs, `StringResourceParityTest` for the seven languages).
- Removed: FAB, `SearchSection`, old filter overlays/dropdowns, `RomList`, `SmallButtons`, `CommonButton`, `Spacing`/`Layout`.

## How it works

A file called **Burnout Paradise (En,Es,Fr) (NTSC).zip** in a torrent becomes
**Name: Burnout Paradise · Tags: Languages [En, Es, Fr], Region [NTSC], Console: Xbox 360, Manufacturer: Microsoft, Extension: .zip**.

1. **First launch**: the app starts with no sources (`consoles.json` is empty). Add consoles by hand or import a sources JSON from the Sources tab (export one from a device that already has sources; source files are deliberately not part of this repository).
2. **Search**: filter by console, region, language, type or extension.
3. **Download**: press A / tap on a game; torrents and direct HTTP links are both supported.
4. **Manage**: follow progress in Downloads; archives are extracted automatically.
5. **Configure**: download folder, per-console paths, speed limit, concurrency, theme.

## Building

Requirements: JDK 17 or newer (the releases were built with JDK 21), Android SDK platform 36, build-tools 36.0.0.

```bash
git clone https://github.com/Tufein/Valkyr.git
cd Valkyr

./gradlew assembleDebug      # debug APK (applicationId com.tufein.valkyr.debug)
./gradlew assembleRelease    # release APK (minified, signed with the debug keystore)
./gradlew test               # JVM unit tests
./gradlew lint
```

The debug build uses the `.debug` application-id suffix so it can be installed next to a release build. The Kotlin package is `com.tufein.valkyr`.

### Project structure
```
app/src/main/java/com/tufein/valkyr/
├── data/        Room entities/DAOs, repositories, services (scraping, torrents, downloads, extraction, library scan, backup)
├── di/          Hilt modules
├── ui/
│   ├── common/      Gamepad detection and shortcut bus
│   ├── components/  AppShell, TagChip, FocusHighlight, Stepper, GamepadLegend
│   ├── screens/     onboarding, home (Library), download, sources, settings, tools (overview, duplicates), contact
│   └── theme/       DogmatixTheme, palettes, ThemeMode, accent presets
└── util/        File-name parsing, folder aliases, search normalization, storage helpers
app/src/main/assets/consoles.json   Default sources (empty)
app/src/test/                       JVM unit tests
```

## Tech stack

### The app
- **Language**: Kotlin 2.3.20 — all of the code (Java 11 source / target), Android `minSdk` 29, `compileSdk` / `targetSdk` 36
- **UI**: Jetpack Compose (BOM 2026.03.01), Material 3, Navigation Compose 2.9.7, Coil 2.7.0 (cover art)
- **DI**: Hilt 2.59.2 (KSP)
- **Persistence**: Room 2.8.4 + FTS, DataStore Preferences 1.2.1
- **Files**: Storage Access Framework (`DocumentsContract`, `DocumentFile`) for the ROM folders — one provider query per folder when scanning
- **Torrents**: libtorrent4j 2.1.0-39 (arm64-v8a, armeabi-v7a, x86_64) · **HTTP**: `HttpURLConnection`, Jsoup 1.22.1
- **JSON**: Gson 2.13.2, plus hand-written readers and writers for sources and backups (they stay readable after R8 renames fields)
- **Archives**: 7-Zip-JBinding-4Android (ZIP/7z)
- **Concurrency**: Kotlin Coroutines 1.10.2 + Flow

### How Valkyr is built
- **Build**: Gradle 9.3.1 (wrapper) with Android Gradle Plugin 9.1.0, JDK 21 (OpenJDK), Android SDK platform 36 and build-tools 36.0.0 from the command-line tools; **R8** minification and resource shrinking for the release build
- **Tests**: JUnit 4 — over 2,200 JVM unit tests for the pure logic — and Android Lint
- **Trying it out**: the Android Emulator (an Android 15 / API 35 arm64 Google APIs image) driven with `adb` — installing, taps and swipes, logcat, screenshots, and `truncate` to create made-up ROM files — in portrait and landscape, in the debug and in the minified release build
- **Checking the APKs**: `aapt2` (package, version, languages) and `apksigner` (signature) on every APK before it was released
- **Test data**: small Python 3 scripts (not part of this repository) ran a local web server with made-up game indexes, so scans, the progress display and the duplicate finder could be tried without any real sources
- **Releases**: git and the GitHub REST API for commits, tags and the release with its APKs and checksums

## Deep links

Other apps and frontends can open the library with filters already applied:

```
dogmatix://library?console=nintendo_snes&region=USA,Europe&lang=En&type=GAME&q=mario&fav=1
```

| Parameter | Meaning |
|---|---|
| `console` | Console ids as shown in Sources (comma-separated) |
| `region`, `lang`, `type`, `filetype`, `tag` | Library tags (comma-separated); the kind is inferred from the value |
| `q` | Search text |
| `fav` | `1`/`true` shows favourites only, `0`/`false` everything |

Try it with `adb shell am start -a android.intent.action.VIEW -d "dogmatix://library?console=nintendo_snes&q=mario"`. A link opened while the app is on another tab switches to the Library; one opened during onboarding is applied once onboarding finishes.

Valkyr also opens `.dgmtx` shortcut files (a text file carrying one of these links), and
Settings → *Frontend shortcuts* drops one into every platform folder — that's how it shows up
as an "emulator" inside ES-DE, iiSU or Daijishō. ES-DE and iiSU also get a one-button setup
in Settings ("Configure ES-DE" / "Configure iiSU"); Daijishō has no importable emulator
configuration, so "Set up Daijishō" hands over the values to type into it. See
[FRONTENDS.md](FRONTENDS.md) for the frontend configuration.

## Controller cheat sheet

| Button | Action |
|---|---|
| D-pad | Move focus |
| A | Select / download |
| B | Back one layer (close sheet, clear search, back to tabs) |
| X | Game details popup (B or X closes) |
| Y | Focus search |
| Select | Star / unstar the focused game · in Downloads, tick rows for multi-selection |
| LB / RB | Switch panel |
| R3 | Fold / unfold the filter panel |
| ZL / ZR | Previous / next section |

Everything is also reachable by touch; the legend only appears while a controller is connected, and button names and colours follow the *Gamepad layout* setting (Xbox / Nintendo / PlayStation).

## Roadmap

Ideas for future versions are in [docs/ROADMAP.md](docs/ROADMAP.md). Ideas and requests are welcome as [issues](https://github.com/Tufein/Valkyr/issues).

## Disclaimer

This app is for educational purposes only. Users are responsible for ensuring they have the legal right to download any content.

## Credits

Valkyr is made by [Tufein](https://github.com/Tufein).

| Project | Used for |
|---|---|
| [Jetpack Compose / AndroidX](https://developer.android.com/jetpack/androidx) (Compose, Material 3, Navigation, Room, DataStore, DocumentFile) | UI, persistence and storage access — Apache-2.0 |
| [Kotlin coroutines](https://github.com/Kotlin/kotlinx.coroutines), [Dagger Hilt](https://github.com/google/dagger) | Concurrency, dependency injection — Apache-2.0 |
| [Gson](https://github.com/google/gson), [Coil](https://github.com/coil-kt/coil), [ZXing](https://github.com/zxing/zxing) | JSON, cover images, QR codes — Apache-2.0 |
| [jsoup](https://jsoup.org) | Reading web directories — MIT |
| [libtorrent4j](https://github.com/aldenml/libtorrent4j) | Torrents and magnet links — MIT |
| [7-Zip-JBinding](https://github.com/borisbrodski/sevenzipjbinding) | ZIP / 7z extraction — LGPL-2.1 |
| [Space Mono](https://fonts.google.com/specimen/Space+Mono), [Manrope](https://fonts.google.com/specimen/Manrope) | Fonts — SIL Open Font License 1.1 |
| [RetroAchievements](https://retroachievements.org) | Achievements, badges and best games per console |
| [RAWG](https://rawg.io), [TheGamesDB](https://thegamesdb.net) | Game information in the details popup |
| [libretro-thumbnails](https://github.com/libretro-thumbnails/libretro-thumbnails) | Covers for launchers and RetroArch playlists |
| [No-Intro](https://no-intro.org), [Redump](http://redump.org), [TOSEC](https://www.tosecdev.org) | DAT checks |
| [RomM](https://github.com/rommapp/romm), [ES-DE](https://es-de.org), [Libretro](https://www.libretro.com), [ScummVM](https://www.scummvm.org), [Homebrew Hub](https://hh.gbdev.io) | The server, launcher and free content catalogues the app works with |


## Library recovery

`VerifiedDocumentCopy` stages a new SAF document and verifies SHA-256 by re-reading the source and copy. A different existing target is refused. `OperationHistoryService` writes an atomic durable journal before destructive changes; a corrupt journal prevents destructive library operations. `TrashService` keeps verified copies beside the original files in hidden `.dogmatix-trash` directories. Ordinary scans exclude these directories; strict deletion checks include them so recursive cleanup cannot erase recovery data.

The Recovery tool resumes library-move cleanup and restores trash to the original path. Fixed game versions are kept in the shared DataStore and carried by regular backups. Native game launching grants read access to the content URI and remembers only handlers compatible with that URI type.
