# Roadmap

Where Valkyr is going. This is a direction, not a promise: releases are reordered when a
bug or a request matters more, and requests are welcome as
[issues](https://github.com/Tufein/Valkyr/issues).

**Now: 7.1.0** (Android build 79). The core loop is complete — find a game, download it into
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

## 7.2 — Bookkeeping (shipped in 7.2.0)

The documentation, the translations and the test gates. Nothing in the app behaves differently.

- The five READMEs and the technical notes name the build that is in the repository, and every
  translatable resource (4,222 of them) exists in all seven languages.
- Two tests keep it that way: `ReleaseDocsTest` and `StringResourceParityTest`.
- The roadmap below, and **[plans/7.2.md](plans/7.2.md)** with the remaining 7.x work written out
  as tickets.

## 7.3 — Finish the pass, then saves

The landscape work in 7.1 touched every screen; some corners did not get the same pass. These
three come first, with the screens, files and checks in **[plans/7.2.md](plans/7.2.md)**:

- **Landscape sweep** on a ~400 dp high screen with large text, beyond the dialogs that 7.1
  already made scroll: the screens behind them and the Tools graphs.
- **Empty states**: 31 of them use the shared `EmptyState`; check that the rest say what a
  screen is for before there is a library, a RomM server or a source.
- **Regression tests for the destructive paths**: delete, move, replace and playlist rewrite
  must fail a test if they ever bypass the preview or Trash.

Then save sync, which knows RomM, DuckStation memory cards, PPSSPP folders, and (optionally)
Dolphin and NetherSX2 memory cards and Wii saves. Every other emulator has to be found by hand.

- **More emulator mappings**, each with the folder and file shapes it really uses: melonDS
  and DraStic, Mupen64Plus FZ, Flycast and Redream, the Switch emulators, and the cores
  RetroArch actually writes.
- **Find the folders for you**: detect the installed emulator's save folder instead of asking
  for a path, with the detected path shown and editable.
- **One conflict screen**: the newer side first, both previews, and one choice for the whole
  batch, instead of a list you work through file by file.
- **Save state on the game page**: the newest save and its picture, with *continue here*.

*Done when* no screen is cut off at 400 dp and large text, the destructive-path tests fail if
recovery is skipped, and three emulators that store saves in three different shapes sync both
ways without typing a path.

## 7.4 — Emulator setup, not only the check

*Tools → Emulator check* says which emulator is missing and stops there. The next step is the
one that actually helps.

- **From "missing" to "set up"**: a guided step per console for the core, the BIOS folder, the
  save folder and (for multi-disc games) the `.m3u`, each with a preview of what changes.
- **Nothing is installed silently**: the app explains what to install, opens the page, and
  verifies afterwards.
- **A test launch** that reports what the emulator said, not just that it started.
- **Keep working when it cannot help**: an unknown emulator gets the manual instructions, not
  a dead end.

*Done when* a fresh handheld goes from Emulator check to playing a two-disc game in one guided
flow, with every written file listed beforehand.

## 7.5 — More places to keep your data

Backups and device sync speak WebDAV; RomM speaks its own protocol. A NAS without WebDAV, or
object storage, is out of reach.

- **SMB and SFTP** for backups and device sync.
- **S3-compatible storage** (Backblaze, Cloudflare R2, MinIO) with a bucket, a prefix and a
  key.
- **One Cloud hub**: every target with its last run, its result and a plain sentence when it
  failed, and one *Back up now* that covers all of them.
- **Never delete on the remote side**, and verify a restore by checksum before it replaces
  anything.

*Done when* a backup goes to a NAS over SMB, restores on a second device byte for byte, and a
failed target says why without blocking the others.

## 7.6 — Scale

A library of a few thousand games hides slow spots. A curated set of 100,000 does not.

- **Incremental scans**: reindex only folders whose contents changed, and budget background
  scans so they stop on a low battery like downloads do.
- **Search and filter within a fixed budget**, measured on the slowest supported device:
  typing never waits for the library.
- **Sizes and counts without walking every file** on every visit to Storage or Statistics.
- **Cover loading that stays smooth** in the cover grid on a large shelf.

*Done when* the Performance check on a mid-range handheld shows search, scroll and cover
decode still Fast with 100,000 games indexed, and a rescan of an unchanged library takes
seconds.

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
bar, and a failed provider reads as a sentence rather than an empty screen.

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
week is visible before it fails again.

## 7.9 — Series and discovery

- **Series grouping** as an optional view on top of *Group versions*: all *Mega Man* games as
  one shelf, without folding regional copies into it.
- **Better "More like this"**: from series, genres and what you actually play, always saying
  why a game was picked.
- **Search that finds a series by name** even when the games do not share a title.

*Done when* grouping is off by default, explains what it folded, and can be undone in one tap.

## 8.0 — One library on several handhelds

Two devices can already share favourites, wishlist and collections through WebDAV, and saves
through RomM. The rest of the library still lives on one device.

- **Shared progress**: Up next, play history, ratings and notes between your own devices.
- **Profiles that travel**, including a child profile and its PIN rules.
- **Conflicts you decide**, with the same preview and way back as save sync; nothing is
  overwritten silently.
- **Still no accounts**: your WebDAV folder is the account.

*Done when* two handhelds on the same WebDAV folder show the same Up next and progress after a
sync, and one device going offline for a week does not lose its week.

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
