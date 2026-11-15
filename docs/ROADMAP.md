# Roadmap

Where Valkyr is going. This is a direction, not a promise: releases are reordered when a
bug or a request matters more, and requests are welcome as
[issues](https://github.com/Tufein/Valkyr/issues).

**Now: 7.6.0** (Android build 84). The core loop is complete — find a game, download it into
the right folder, play it, keep the collection healthy, and move saves and backups around.
What is left is breadth (more emulators, clouds and catalogues), scale (much bigger
libraries), and finishing what 7.0 and 7.1 started.

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

## 7.5 — More places to keep your data (partly shipped in 7.5.0)

Shipped: the *never delete on the remote side* choice (*Backups to keep → All*) and retries for
a failed automatic cloud backup with growing waits that skip final problems. Backups are
verified when they are restored: the encryption authenticates the file, so a damaged copy is
refused before anything local is replaced.

Still open, because each needs a new client library or a protocol written by hand and a real
server to prove it: **SMB and SFTP** (a NAS without WebDAV), **S3-compatible storage**, and the
**one Cloud hub** listing every target once there is more than one. Tickets:
**[plans/7.5.md](plans/7.5.md)**.

## 7.6 — Scale (shipped in 7.6.0)

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

*Done when* the Performance check on a mid-range handheld shows search, scroll and cover
decode still Fast with 100,000 games indexed, and a rescan of an unchanged library takes
seconds. Tickets: **[plans/7.6.md](plans/7.6.md)**.

## 7.7 — Metadata without RomM

RomM supplies manuals, screenshots and genres. Without it, a library looks plainer than it
needs to.

- **Manuals and screenshots from the cover sources** that offer them, in the same order you
  already chose for covers.
- **The same honesty about limits**: a daily cap or a failed login is named, and a source that
  was only unavailable is asked again later.
- **Cache once, keep it**: fetched metadata survives a rescan and is listed with its source.
- **Respect every provider's terms**: no scraping that a provider does not allow, and the
  provider is named wherever its data is shown.

*Done when* a library with no RomM gets covers, manuals and screenshots at the same quality
bar, and a failed provider reads as a sentence rather than an empty screen. Tickets:
**[plans/7.7.md](plans/7.7.md)**.

## 7.8 — Catalogue and sources

- **More content catalogues**, per request, each with clear attribution and console mapping.
  MAME's free-to-download games are a candidate once there is an arcade console in the app and
  their non-commercial terms can be shown before the download.
- **More Homebrew Hub systems** alongside the Game Boy content.
- **Source health over time**: which source actually delivers, which keeps failing, and what
  it last said — not only for the current scan.
- **Reserve addresses and background scans** that skip unchanged content, extended to the
  catalogues.

*Done when* every catalogue names its provider on the game page and a source that failed last
week is visible before it fails again. Tickets: **[plans/7.8.md](plans/7.8.md)**.

## 7.9 — Series and discovery

- **Series grouping** as an optional view on top of *Group versions*: all *Mega Man* games as
  one shelf, without folding regional copies into it.
- **Better "More like this"**: from series, genres and what you actually play, always saying
  why a game was picked.
- **Search that finds a series by name** even when the games do not share a title.

*Done when* grouping is off by default, explains what it folded, and can be undone in one tap.
Tickets: **[plans/7.9.md](plans/7.9.md)**.

## 8.0 — One library on several handhelds

Two devices can already share favourites, wishlist and collections through WebDAV, and saves
through RomM. The rest of the library still lives on one device.

- **Shared progress**: Up next, play history, ratings and notes between your own devices.
- **Profiles that travel**, including a child profile and its PIN rules.
- **Conflicts you decide**, with the same preview and way back as save sync; nothing is
  overwritten silently.
- **Still no accounts**: your WebDAV folder is the account.

*Done when* two handhelds on the same WebDAV folder show the same Up next and progress after a
sync, and one device going offline for a week does not lose its week. Tickets:
**[plans/8.0.md](plans/8.0.md)**.

## Later, if there is a reason

- **TV and remote depth**: every screen reachable with a remote, larger text, no touch-only
  action.
- **More languages**, including right-to-left, when there are reviewers for them.
- **Accessibility**: TalkBack labels on the gamepad-driven screens, contrast and font scaling
  checked on every screen.
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
