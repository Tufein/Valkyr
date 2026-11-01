# Valkyr

**English** · [Nederlands](README.nl.md) · [Français](README.fr.md) · [Deutsch](README.de.md) · [Español](README.es.md)

**Find, download and organise retro games on your Android phone or handheld.**

Valkyr keeps one big, searchable list of the games from the sources *you* add, downloads them into the right folders, and helps you keep your collection tidy. It works with touch *and* with a game controller, so it feels at home on handhelds such as the Retroid, Anbernic or Kinhank.

The app includes no games. **Sources → More romsets** offers optional free content collections (Libretro homebrew and demos, ScummVM freeware games, Game Boy homebrew from Homebrew Hub) alongside your own sources.

<table>
  <tr>
    <td align="center" valign="top"><img src="docs/screenshots/home.png" width="210" alt="Home with the game to continue and a shelf per console"><br><sub>Home: carry on where you left off, with a shelf of covers per console</sub></td>
    <td align="center" valign="top"><img src="docs/screenshots/game.png" width="210" alt="A game page"><br><sub>Every game has its own page with a big Play button</sub></td>
    <td align="center" valign="top"><img src="docs/screenshots/downloads.png" width="210" alt="The download queue"><br><sub>Follow your downloads and the whole queue</sub></td>
    <td align="center" valign="top"><img src="docs/screenshots/more.png" width="210" alt="The More screen"><br><sub>Everything else is one tap away under More</sub></td>
  </tr>
</table>

<p align="center"><img src="docs/screenshots/home-landscape.png" width="720" alt="Home in landscape on a handheld"><br><sub>On a handheld in landscape the sections sit in a rail on the left</sub></p>

<p align="center"><img src="docs/screenshots/cloud-hub.png" width="460" alt="The Cloud hub (titles and numbers are made up)"><br><sub>The Cloud hub: RomM, save sync and backups at a glance (names and numbers are made up)</sub></p>

*The screenshots use made-up game titles and empty placeholder files.*

**Latest release: [6.0.0](https://github.com/Tufein/Valkyr/releases/tag/v6.0.0)** (Android build 70).

Until 3.9.0 the app was called Dogmatix+. Valkyr 6.0.0 has its own app id, so it installs next to the old app: use *Settings → Back up* in the old app and *Restore backup* in Valkyr, then remove the old app.

## Install

1. Open the **[Releases page](https://github.com/Tufein/Valkyr/releases)** on your phone or handheld, or download there and copy the file over.
2. Download one of the two files:
   - **`Valkyr-release.apk`** — *the normal choice.* Signed releases update the app in place and keep your app data.
   - **`Valkyr-debug.apk`** — a separate test app with its own data, for trying things out next to your normal app.
3. Open the file and allow **"install unknown apps"** if Android asks.

You need **Android 10 or newer**. The app is not on Google Play.

## First start

1. The welcome guide explains the basics.
2. **Pick your ROMs folder** — the folder where your games should go.
3. **Import your sources** (a file with your game lists) — or skip and add sources later in the **Sources** tab.
4. Open the **Library**, find a game and tap it (or press **A**) to open its game page. Choose **Download** or **Play** there.

## What can it do?

### Find games
- **One list** with the games from all your sources: web directories, direct links, torrents and magnet links, and your RomM server.
- **Search that forgives mistakes** (accents, dashes, doubled letters), with recent searches, **Surprise me** and **More like this**. **Search everything** also finds settings, tools and screens.
- **Filter** by console, region, language, type, genre, decade and *New*; **sort** by name, size or newest. A green **✓** marks what you already have.
- **A page for every game**: art, Download or Play, the best version, favourite, collections, share, notes, screenshots and manuals, and a *Versions* tab that explains how each version scores.
- **Covers first**: Home opens on shelves of big covers (continue playing, new for you, one shelf per console), navigation sits in a bottom bar, or a slim side bar on a handheld, with Games, Downloads, Sources and More. The game page starts with a large cover and one big Play or Download button.
- **Covers from several sources**: RomM, libretro-thumbnails, [ScreenScraper](https://www.screenscraper.fr) (with your own login), TheGamesDB, SteamGridDB and IGDB (each with your own free key) and the cached game images, in an order you choose. The app says plainly when a login fails or a daily limit is reached, and a source that was only unavailable is asked again later (*Settings → Cover sources*).
- **Extra launcher art**: title screens and screenshots for the ES-DE covers folder (*Tools → Covers for launchers*), off by default and never replacing files you already have.
- **Performance check** (*Tools → Performance check*): measures start-up, search speed, cover decoding, storage speed and scrolling on your own device, with a Fast/Fine/Slow verdict and a numbers-only report you can share.
- **Up next and For you**: a personal play list per profile, and suggestions from your own library that always say why they were picked.
- **Collections and goals**: favourites, your own lists, rule-based collections (console, language, genre, year, played), collection goals, play history, a yearly recap and the best games per console from RetroAchievements.
- **Wishlist**: note games you want, get told when one appears, download them automatically and share the list.
- **Import a list**: paste or load a text file; found games download, the rest go to the wishlist.

- **Four ways to browse**: Overview, List, Covers and Consoles (a tile per console with counts), quick filters (downloaded, favourites, completed, RetroAchievements beaten/mastered), a compact search bar, and optional *Group versions* that folds regional copies into one entry.
- **Manuals**: download a game's manual from your RomM server and read it in the app (PDF, pictures, text), plus an *Offline* chip that shows what still works.

### Download
- **Direct links, torrents and magnet links**, plus optional **TorBox** and **Real-Debrid**.
- **Several downloads at once**, with pause, resume, retry, a global speed limit (optionally none at night) and a limit per server.
- **Total completion circle** counts finished downloads out of the whole queue: 25 of 100 = 25%. Only fully completed downloads count, so finishing one keeps the circle moving forwards.
- **Interrupted downloads continue** where they stopped, and the queue survives a restart or reboot.
- **Download when it suits**: only on Wi-Fi, while charging, tonight or at a set time. Downloads pause on a low battery or a hot device and say what they wait for.
- **Automatic retry** with a visible countdown, clear error reasons, a switch to another source and a track record per source.
- **Best version** picks your region and language and skips demos; **Download everything shown** checks the space first.
- **Checks**: file sizes, checksums against the source, and a safe unpack of ZIP and 7z (nothing is written outside its folder).
- **Everywhere**: notification buttons (pause, resume, stop), a Quick Settings tile, a home-screen widget and a second-screen view.

### Play
- **Play from the game page** with a compatible emulator, remembered per console or chosen per game, with a **readiness check** (files, discs, BIOS, emulator), a guided setup and a test launch.
- **ES-DE, iiSU, Daijishō and Pegasus**: a "search for more games" entry per console, plus covers and descriptions written into your launcher; for iiSU, Daijishō and any other launcher, covers go into a media folder you pick, in the file layout that launcher expects (ten ready-made layouts, or write your own).
- **Games on your home screen**: pin a game from its page and it starts straight in its emulator; a long press on the app icon also shows your last three played games.
- **RetroArch playlists**: writes `.lpl` playlists so RetroArch shows your library; existing playlists are only added to, and the old file goes to Trash and recovery first.
- **Continue on another handheld** from the game page, when your emulator and saves can travel: it shows the way back first and the save goes through RomM.
- **Multi-disc games** get their `.m3u` playlist automatically.
- **Continue playing** on Home, from saves on any device or from ES-DE.

### Keep your library healthy
- **Library repair**: finds empty or cut-off files, disc sets with missing tracks, dead or duplicate playlists, leftover download data and out-of-date library marks. Every fix is previewed and removals go to Trash and recovery.
- **Duplicate games** with a safe, previewed clean-up; **Better versions** and **Free up space**.
- **DAT check** against No-Intro, Redump and TOSEC; **Game sets** check; **BIOS check**; **Switch updates and DLC**.
- **Library overview, Storage and Statistics** per console; **Smart storage** can move rarely played consoles to an SD card and back.
- **File explorer** to look, rename, move and unpack; **export** your collection as CSV or a web page.
- **Copy or send games**: whole games with every file to an SD card or USB folder, your RomM server or your NAS. You first see every file, the space needed and what is already there; every file is checked at the other end, save files stay behind and nothing is replaced. For a card that goes into another system, the console folders can follow ES-DE, Batocera, Knulli, ArkOS, ROCKNIX, OnionOS or muOS names.
- **Health check** with a fix per problem, and a **scan report** for sources that failed.
- **Sources**: add by hand, import and export, share as QR codes, optional content catalogue with Libretro content for 29 systems, ScummVM's freeware adventure games and free Game Boy homebrew from Homebrew Hub, the provider named for each, reserve addresses and automatic background scans that skip what did not change.

### Safe by design
- **Trash and recovery center**: removed games, interrupted moves, replaced files and save backups can be restored, each with a preview. Copies are verified before anything is removed.
- **Action history** with undo, and a journal that survives a crash.
- **Updates from inside the app** are checked for checksum, package, version and signer before Android installs them.
- **Report a problem** builds a private, editable report you choose what goes into; paths, server addresses, keys and tokens are removed and nothing is sent by the app.

### Cloud and sync
- **RomM**: send finished downloads and whole multi-file games, use it as a source, see games, genres and screenshots, sync play status, rating and favourites, fetch missing BIOS files.
- **Save sync and cloud saves**: your saves and save states travel between handhelds and RomM, including DuckStation memory cards, PPSSPP save folders and, if you choose, wwhole Dolphin and NetherSX2 memory cards, Dolphin GameCube save folders and Wii saves; conflicts ask you, and replaced copies are kept for 30 days.
- **Your own cloud (WebDAV)**: encrypted backups (Nextcloud, ownCloud, Synology and others) and device sync of favourites, wishlist and collections.
- **RetroAchievements**: progress and badges per game, your profile, and which games have achievements.

- **Pair RomM with a short code** and let games sent from the RomM web page land in your download queue; *Use RomM* switches every RomM call off and on.
- **Safer saves**: a backup before every overwrite, conflicts you decide (this device, server or both), save slots on one card, and a *Folder mode (Syncthing)* that mirrors saves into a folder Syncthing syncs.
- **DukeStation** is a known PlayStation emulator, with resume from a save slot.

### Profiles
- **Profiles** hide consoles or games with chosen tags (a child's profile), with an optional PIN. Favourites, emulators, notes and Up next are kept per profile.

### Made for handhelds
- **Everything works with a gamepad** (D-pad, A/B/X/Y, shoulders); on-screen hints match Xbox, Nintendo or PlayStation. Hold **SELECT** for a quick menu.
- **Landscape and portrait**, a **second-screen** view, and a **TV mode** with larger text and remote keys.
- **Light, dark or pure black** themes, twelve accent colours and Material You, covers, adjustable text size, animations that can be switched off.
- **Seven languages**: English, Dutch, German, French, Spanish, Italian and Portuguese.
- **Second-screen hub**: continue playing, recent games, console chips, the download queue and battery/heat chips on a second display, plus a short tour of the app.
- **Set up a new handheld**: a checklist from a fresh install to a ready handheld (settings, games folder, emulators, missing games, saves, launcher); every step opens the screen that does it.
- **Back up and restore** settings, sources, favourites, collections and the download list in one file.

## Using a gamepad

| Button | What it does |
|---|---|
| D-pad | Move around |
| **A** | Choose / open the game page; download or play on that page |
| **B** | Go back one step |
| **X** | Game info |
| **Y** | Search |
| **Select** | Star or unstar a game |
| **LB / RB** | Switch between the filters and the list |
| **ZL / ZR** | Previous / next section |
| **R3** | Fold or unfold the filter panel |

Everything also works by touch. The hints only show while a controller is connected.

## Good to know

- Removed games go to recovery trash. Restore or permanently empty them in Tools → Trash and recovery. Space is freed only after emptying. The file explorer also uses recovery by default; permanent deletion requires selecting that option in its confirmation.
- **A backup file contains your account keys** (TorBox, Real-Debrid, RomM). Keep it private.
- **The game info window stays empty in the downloads here**, because it needs a free key from a game database that is added when the app is built.
- Valkyr does not look for games on its own. It only reads the sources **you** add.
- **Something not working?** *Settings → App and updates → Report a problem* builds a private report you can paste into a GitHub issue; paths, server addresses, keys and tokens are removed first.

## For developers and advanced users

- **[TECHNICAL.md](TECHNICAL.md)**: how the app is built, how the duplicate finder decides, the folder layout, deep links and the tech stack (in English).
- **[FRONTENDS.md](FRONTENDS.md)**: setting up ES-DE, iiSU, Daijishō and Pegasus.
- **[Roadmap](docs/ROADMAP.md)**: ideas for future versions.
- **[Releases](https://github.com/Tufein/Valkyr/releases)**: downloads and checksums.

## Credits

Valkyr is made by [Tufein](https://github.com/Tufein). It is built with these open-source projects and data sources:

| What | Used for |
|---|---|
| [Jetpack Compose / AndroidX](https://developer.android.com/jetpack/androidx), [Kotlin coroutines](https://github.com/Kotlin/kotlinx.coroutines), [Dagger Hilt](https://github.com/google/dagger) | The app itself (Apache-2.0) |
| [Gson](https://github.com/google/gson), [Coil](https://github.com/coil-kt/coil), [jsoup](https://jsoup.org), [ZXing](https://github.com/zxing/zxing) | JSON, images, reading web directories, QR codes |
| [7-Zip-JBinding](https://github.com/borisbrodski/sevenzipjbinding), [libtorrent4j](https://github.com/aldenml/libtorrent4j) | Unpacking archives, torrents |
| [Space Mono](https://fonts.google.com/specimen/Space+Mono), [Manrope](https://fonts.google.com/specimen/Manrope) | Fonts (SIL Open Font License) |
| [RetroAchievements](https://retroachievements.org), [RAWG](https://rawg.io), [TheGamesDB](https://thegamesdb.net), [SteamGridDB](https://www.steamgriddb.com), [IGDB](https://www.igdb.com), [ScreenScraper](https://www.screenscraper.fr), [libretro-thumbnails](https://github.com/libretro-thumbnails/libretro-thumbnails) | Achievements, game information and covers |
| [No-Intro](https://no-intro.org), [Redump](http://redump.org), [TOSEC](https://www.tosecdev.org) | DAT checks |
| [RomM](https://github.com/rommapp/romm), [ES-DE](https://es-de.org), [Libretro](https://www.libretro.com), [ScummVM](https://www.scummvm.org), [Homebrew Hub](https://hh.gbdev.io) | Server, launcher and free content the app works with |

## Disclaimer

This app is for educational purposes only. You are responsible for making sure you have the legal right to download any content.
