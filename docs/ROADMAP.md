# Roadmap

Where Valkyr is going. This is a direction, not a promise: releases are reordered when a
bug or a request matters more, and requests are welcome as
[issues](https://github.com/Tufein/Valkyr/issues).

**Now: 9.6.0** (Android build 100). The core loop is complete — find a game, download it into
the right folder, play it, keep the collection healthy, and move saves and backups around.
What is left is breadth (more emulators, clouds and catalogues), scale (much bigger
libraries), and finishing what 7.0 and 7.1 started. The 8.x line closed at 8.5.0; 9.0–9.6 were built under the rule below.

## How a release is chosen

1. **Does it remove a dead end?** A download that cannot be played, a save that cannot
   travel, a check that says "missing" and stops there.
2. **Does it work with a D-pad, offline, and without an account?** Everything stays usable
   with buttons and on a handheld in landscape.
3. **Can it be undone?** Anything that removes, moves or replaces keeps its preview and its
   way back through Trash and recovery.
4. **No telemetry and no games.** What leaves the device is what you send yourself.

Releases are `vX.Y.0`: the minor goes up one at a time (0–9), a big release may skip the rest
of a major, and Android's build number always goes up. There are no patch releases; a fix
ships in the next minor.

### What counts as a release

A release is a theme, not a ticket. One button or one screen is not a release — it waits on
`master` until its theme is complete.

1. **A theme in one sentence, with three to five visible features** that belong to it.
2. **Nothing half-done**: strings in all seven languages, the READMEs and the release notes, the
   tests that prove the new behaviour, and a migration when the schema changed.
3. **Two real devices** have run it: a phone and a handheld, in landscape, with buttons. Green
   CI is the floor, not the proof.
4. **Numbers, not impressions**: the Performance check is run, and its result is written down
   when the release claims speed.
5. **Destructive paths re-checked**: delete, move, replace and playlist rewrites still show
   their preview and keep the way back.
6. **A few days on a real device** before the tag. A release is soaked, not shipped the hour it
   compiles.

That gives the rhythm: **at most one release every one or two weeks**, and short of an emergency
the rule is not bent for a release that only adds one thing.

Every release below has its work written out as tickets in [plans/](plans/), with the files to
touch, the tests that prove it and what still needs a device or a real server.

## 7.2 — Bookkeeping (shipped in 7.2.0)

The documentation, the translations and the test gates. Nothing in the app behaves differently.

- The five READMEs and the technical notes name the build that is in the repository, and every
  translatable resource (4,222 of them) exists in all seven languages.
- Two tests keep it that way: `ReleaseDocsTest` and `StringResourceParityTest`.
- The roadmap below, and **[plans/7.2.md](plans/7.2.md)** with the remaining 7.x work written out
  as tickets.

## 7.3 — Saves (shipped in 7.3.0)

- **One choice for a batch of conflicts**: the newest of each, this device, the server or both,
  with the same safety copy per file and one listing of each side for the whole batch.
- **Find emulator folders**: one picked storage root is searched for the places DraStic, Pizza
  Boy, DuckStation, PPSSPP and Dolphin keep their saves; what was found is shown before it is
  added.
- **Last save on the game page**: the newest save or state with its date, also without a picture.

Still open from this plan, and picked up when there is a device to check them on: the landscape
sweep at 400 dp with large text, the empty-state audit, save shapes for more emulators (melonDS,
M64Plus FZ, Flycast, Redream, the Switch family), and folder mode for memory cards with its own
schedule. Tickets: **[plans/7.2.md](plans/7.2.md)** and **[plans/7.3.md](plans/7.3.md)**.

## 7.4 — Emulator setup, not only the check (shipped in 7.4.0)

- **From "missing" to "set up"**: a console in *Tools → Emulator check* opens into its steps —
  emulator, RetroArch core, BIOS files, save folder and disc lists — each done, to do, or for you
  to check.
- **Nothing is installed silently and every step says what it changes**: a button opens the
  screen that does it, or a web search for the app.
- **An unknown emulator is no dead end**: the manual way is named.

Still open from this plan: a test launch that reports what the emulator said, writing a missing
`.m3u` from the setup steps, and the new-handheld checklist turning green from the same plan.
Tickets: **[plans/7.4.md](plans/7.4.md)**.

## 7.5 — More places to keep your data (partly shipped in 7.5.0, 8.2.0)

Shipped: the *never delete on the remote side* choice (*Backups to keep → All*) and retries for
a failed automatic cloud backup with growing waits that skip final problems (7.5.0); and in 8.2.0
**S3-compatible storage** (Amazon S3, MinIO, Backblaze B2, Wasabi, Cloudflare R2) as the cloud
target, behind the same interface WebDAV uses, so backups, device sync, the shared wishlist and
game transfer all work on it. It is written as `s3://host/bucket` in the server field of the one
cloud connection. Backups are verified when they are restored: the encryption authenticates the
file, so a damaged copy is refused before anything local is replaced.

Still open, because each needs a new client library and a real server to prove it: **SMB and
SFTP** (a NAS without WebDAV), and a list of several targets at once (today there is one cloud
connection, which is WebDAV or S3). Tickets: **[plans/7.5.md](plans/7.5.md)**.

## 7.6 — Scale (shipped in 7.6.0 and 8.4.0)

A library of a few thousand games hides slow spots. A curated set of 100,000 does not.

- **Incremental scans**: reindex only folders whose contents changed, and budget background
  scans so they stop on a low battery like downloads do.
- **Search and filter within a fixed budget**, measured on the slowest supported device:
  typing never waits for the library.
- **Sizes and counts without walking every file** on every visit to Storage or Statistics.
- **Cover loading that stays smooth** in the cover grid on a large shelf.

Shipped: search that starts after a short typing pause, and one shared look at the disk for
Storage and the health check. Still open, because they change the database or need a 100,000-game
library on a real handheld to prove them: folder fingerprints and incremental scans, row-level
source diffs, a scan budget that pauses on a low battery, and the cover-grid measurement.

8.4.0 shipped the row-level source diff: a rescan of a changed source now writes only the games that
were added, removed or changed, and unchanged games keep their id and their search-index entry. The
background scan job already waits for a battery that is not low. Folder fingerprints for the *disk*
scan were looked at and left out on purpose: Android's storage access framework lists a folder with
one query whatever it holds, and a folder's own change time does not tell whether something changed
deeper down, so skipping a folder would either miss changes or still need the query it is meant to
save. Still open: a validator for RomM and Homebrew Hub listings, and the cover-grid measurement on a
100,000-game library.

*Done when* the Performance check on a mid-range handheld shows search, scroll and cover
decode still Fast with 100,000 games indexed, and a rescan of an unchanged library takes
seconds. Tickets: **[plans/7.6.md](plans/7.6.md)**.

## 7.7 — Metadata without RomM (shipped in 7.7.0)

RomM supplies manuals, screenshots and genres. Without it, a library looks plainer than it
needs to.

- **Manuals and screenshots from the cover sources** that offer them, in the same order you
  already chose for covers.
- **The same honesty about limits**: a daily cap or a failed login is named, and a source that
  was only unavailable is asked again later.
- **Cache once, keep it**: fetched metadata survives a rescan and is listed with its source.
- **Respect every provider's terms**: no scraping that a provider does not allow, and the
  provider is named wherever its data is shown.

Shipped: the game page names the provider of its stored cover, and manuals come from RomM,
ScreenScraper or the Internet Archive behind per-source switches (with a pasted link as a fourth
way in), each remembered with its provider and a month-long definite miss. Still open, because
they add a database table and need the provider accounts to prove them: screenshots and title
screens from the cover sources inside the app, and the source line on every other place art is shown.

*Done when* a library with no RomM gets covers, manuals and screenshots at the same quality
bar, and a failed provider reads as a sentence rather than an empty screen. Tickets:
**[plans/7.7.md](plans/7.7.md)**.

## 7.8 — Catalogue and sources (shipped in 7.8.0 and 8.5.0)

- **More content catalogues**, per request, each with clear attribution and console mapping.
  MAME's free-to-download games are a candidate once there is an arcade console in the app and
  their non-commercial terms can be shown before the download.
- **More Homebrew Hub systems** alongside the Game Boy content.
- **Source health over time**: which source actually delivers, which keeps failing, and what
  it last said — not only for the current scan.
- **Reserve addresses and background scans** that skip unchanged content, extended to the
  catalogues.

Shipped: source history — the last twelve scans of each source, and one sentence under the last
scan when it keeps failing. Still open, because each is a review of someone else's terms or needs
the provider to test against: more Homebrew Hub systems, MAME's free content, catalogue mirrors and
the provider line on every catalogue game.

8.5.0 made the arcade consoles complete as consoles: ROM-set zips are no longer unpacked, and MAME and
FBNeo have names, folder aliases and a BIOS check (covers for them are still open: each cover provider needs its arcade system id confirmed first). A downloadable MAME catalogue is still *not*
built: it needs the non-commercial terms shown before a download and a catalogue reviewed against
the provider's own list, and this project does not point at sources that exist to distribute games.

*Done when* every catalogue names its provider on the game page and a source that failed last
week is visible before it fails again. Tickets: **[plans/7.8.md](plans/7.8.md)**.

## 7.9 — Series and discovery (shipped in 7.9.0 and 8.1.0)

- **Series grouping** as an optional view on top of *Group versions*: all *Mega Man* games as
  one shelf, without folding regional copies into it.
- **Better "More like this"**: from series, genres and what you actually play, always saying
  why a game was picked.
- **Search that finds a series by name** even when the games do not share a title.

Shipped: every *More like this* card on the game page says why it was picked (7.9.0), and in 8.1.0
**Group by series** — a Library filter, off by default, that folds a franchise's games on one console
into one entry marked "Mega Man · 6 games" (known franchises from two games, other titles from
three that share their first two words), plus *All in this series* on the game page. The series is
worked out from the title each time and nothing is stored, so there is no database change. Still
open: a stored series column, so that search can find a series whose titles share no word.

*Done when* grouping is off by default, explains what it folded, and can be undone in one tap.
Tickets: **[plans/7.9.md](plans/7.9.md)**.

## 8.0 — One library on several handhelds (shipped in 8.0.0 and 8.3.0)

Two devices can already share favourites, wishlist and collections through WebDAV, and saves
through RomM. The rest of the library still lives on one device.

- **Shared progress**: Up next, play history, ratings and notes between your own devices.
- **Profiles that travel**, including a child profile and its PIN rules.
- **Conflicts you decide**, with the same preview and way back as save sync; nothing is
  overwritten silently.
- **Still no accounts**: your WebDAV folder is the account.

Shipped: **Up next** travels between handhelds as its own document in the WebDAV folder, merged
so a week offline loses nothing; the newest change of a game wins and taking a game off a list does
not travel. Still open, because each needs a database migration or a profile format that must be
proved on two real devices: play history, ratings and notes, profiles that travel (the Up next
list is matched by profile name until they do), and a screen for the conflicts.

Shipped in 8.3.0: a local **play status and star rating** per game and profile (no RomM needed), and
**status, ratings and notes travelling** as their own documents next to Up next, merged three-way
against what both handhelds last agreed on. A game changed differently on two handhelds keeps the
newer value and is listed under *Settings → Cloud → Sync conflicts* until you choose; the choice is
sent at the next sync. Notes keep the older text as a recovered note. Still open: play history,
profiles that travel (with a child profile and its PIN rules), and a conflict screen for the
library lists (favourites, wishlist, collections), which today merge as a union.

*Done when* two handhelds on the same WebDAV folder show the same Up next and progress after a
sync, and one device going offline for a week does not lose its week. Tickets:
**[plans/8.0.md](plans/8.0.md)**.

## 9.0 — One evening, one handheld (shipped in 9.0.0)

The first release under the rule above, and what the small ones were meant to add up to: a
wiped device becomes a playable handheld in one sitting, and the library starts to feel lived-in
on the way.

- **One flow from install to first game**: restore a backup from any target you configured
  (WebDAV, S3 or a folder), confirm the folders it needs, run the setup steps per console,
  verify a sample of the library, then launch. Closing the app halfway keeps the work done so
  far, and one page at the end says what is ready and which screen fixes what is missing.
- **Time played**: sessions measured around the launch, per game and per profile — time played,
  sessions, first and last played — so *Recap* and *Continue playing* finally say something.
- **Cheats, honestly**: a `.cht` imported per game, listed and switched on or off, refused with
  a sentence while RetroAchievements runs in hardcore, and never fetched from a cheat site.
- **Fix the match**: title, year, genre and cover corrected per game, a pinned cover above the
  provider chain, one tap back to automatic, and the override carried in backups.
- **Readable for everyone**: TalkBack sentences per cover and tile, text at 200% on the 400 dp
  screens, a colour-blind-safe palette, less motion, and an accessibility line in Health.

*Done when* a device straight out of the box reaches a playable two-disc game in one flow
without opening Settings, a week of use fills the recap with numbers that survive a restart, and
TalkBack can walk from the library to a game and back without a dead end. Tickets:
**[plans/9.0.md](plans/9.0.md)**.

## 9.1 — Sources tracker (shipped in 9.1.0)

Sources are the part of Valkyr that breaks without warning: a host goes away, a mirror slows down,
a tracker list goes stale. This release makes that visible, and fixable from one place.

- **Sources tracker**: every source of every console in one list, worst first, with its scan
  health, download track record (speed, success rate, 30-day failure trend) and a one-tap rescan
  of just that address. The record can be exported with addresses, keys and file names left out.
- **Pause failing sources**: switch a source off or on from its row, or pause every failing one
  after a preview. Nothing is removed, and a console always keeps a source that works.
- **Where a game came from**: downloads and the game page name the first source and the source a
  download was switched to, kept across restarts and in backups.
- **Torrent trackers**: your own announce addresses, switched on or off and tested (http and https),
  added to every torrent, magnet and debrid request.

*Done when* a source that keeps failing is visible in the list within one scan, can be paused in two
taps without losing the console's last working source, and a download that switched source says so
afterwards. Still to check on a device: the tracker list in landscape with buttons and at large
text, and the pause dialog with a D-pad. Not built: an automatic pause (the app only asks), and
testing a `udp://` announce.

## 9.4 — Played on every handheld (shipped in 9.4.0)

The sessions measured since 9.0 were local to one handheld. Now the history follows you.

- **Play history sync**: finished sessions travel as their own document next to Up next, merged as a
  union and named after the handheld they were played on.
- **Played on…** on the game page, a **play history card** in the Cloud hub, and the history
  **inside app backups** (a restore adds to what was played here).

*Done when* two handhelds on the same cloud folder show the same time played for a game after a
sync. Still to check on two real devices. Not built: profiles that travel, and a conflict-free
delete of a session (history is only ever added).

## 9.5 — A backup you cannot lose (shipped in 9.5.0)

One server is one point of failure. The backup is the one thing that must survive it.

- **Second copy**: every backup also goes to a second cloud (WebDAV or S3) with the same name and
  keep rule, even when the main upload fails.
- **Restore from either** cloud, with the same passphrase and checks, and a status card in the
  Cloud hub.

*Done when* a backup exists on both clouds after one *Back up now*, and a restore from the second
one works with the main server switched off. Still to check against two real servers. Not built:
SMB and SFTP, device sync to several clouds, and certificate pinning for the second cloud.

## 9.6 — Your screenshots (shipped in 9.6.0)

Emulators already write a picture beside every save state. Valkyr now shows them all.

- **Gallery per game** on the game page from the save state folder, newest first.
- **Full screen with a D-pad** (◀ ▶ to move, B to close) and **share one picture** at a time.

*Done when* a game with several save states shows its pictures, each opens and can be shared,
and nothing in the folder changes. Not built: deleting pictures or a size cap (the files sit beside
save states), and emulator screenshot folders.

## Later, if there is a reason

- **TV and remote depth**: every screen reachable with a remote, larger text, no touch-only
  action.
- **More languages**, including right-to-left, when there are reviewers for them.
- **Couch co-op without accounts**: two handhelds on one Wi-Fi, same core and a hash check
  before the session, through the emulators that support it. No relay, no account.
- **Your screenshots, more**: pictures from the emulators' own screenshot folders, and a size cap
  that trims with a preview.
- **More launcher layouts** and more folder names for other systems, from public
  documentation; tell us where your launcher reads covers from.
- **Streaming unpack**: start a game from a ZIP or 7z without unpacking the whole archive
  first, when the emulator can read it.

## Not planned

- Bundling games, or pointing the app at sources that only exist to distribute them.
- Accounts in the cloud, telemetry, or crash reporting that leaves the device on its own.
- Google Play, or anything that needs a signed-in store account.
- Features that change your library without a preview and a way back.

## Asking for something

Open an [issue](https://github.com/Tufein/Valkyr/issues) with what you did, what you expected
and what happened. For a bug, *Report a problem* in the app builds the details with your paths,
keys and tokens removed, so you can paste it in yourself — the app never sends it.
