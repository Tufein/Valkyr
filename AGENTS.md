# AGENTS.md — Valkyr

Instructions for coding agents (OpenCode, Claude Code, Codex and similar). `CONTRIBUTING.md` is
loaded alongside this file through `opencode.json`. Read `TECHNICAL.md` before touching the data
model, download routing or library tools.

## Project

Android app (phone and handheld, touch and gamepad) to find, download and organise retro games from
user-added sources (torrents, magnet links, web directories, RomM). Formerly Dogmatix+; this repo is a fork
whose default branch is **`master`**.

- Package / application id: `com.tufein.valkyr` (debug adds `.debug`)
- Version: `app/build.gradle.kts`, currently 9.3.0 / build 97
- minSdk 29, targetSdk 36, JDK 21
- Language: Kotlin, Gradle Kotlin DSL; UI in 7 locales (en, es, nl, fr, de, it, pt)

## Layout

| Path | What lives there |
| --- | --- |
| `app/src/main/java/com/tufein/valkyr/` | App code: `data/` (Room, services, queries), `di/`, `ui/` (screens, navigation, common), `util/`, `widget/` |
| `app/src/test/` | JVM unit tests (Robolectric where needed), fixtures in `app/src/test/resources` |
| `app/src/androidTest/` | Instrumented tests (need an emulator or device) |
| `tools/` | `release_policy.py` + tests (release gate), `benchmark/` |
| `docs/` | `ROADMAP.md`, `release-notes/`, `guides/`, `plans/`, `screenshots/` |
| `README*.md` | Five READMEs (en, nl, fr, de, es) |

## Build and test

```sh
./gradlew --no-daemon testDebugUnitTest assembleDebug   # what CI runs on every push
./gradlew --no-daemon lintDebug
python3 -m unittest discover -s tools -p 'test_release_policy.py'
./gradlew connectedDebugAndroidTest                      # optional, needs emulator/device
```

## Rules that tests enforce

- **Version numbers:** `ReleaseDocsTest` compares the version in all five READMEs with `app/build.gradle.kts`.
  Bump them together in the same commit.
- **Translations:** `StringResourceParityTest` requires every string in all seven locales. Add translations
  in the same commit; use `<plurals>` for counts.
- Prefer small, pure, testable classes next to the UI (e.g. `SearchNormalizer`, `GameTitleCleaner`).
- The app ships no games. Screenshots use made-up titles.

## Release process

1. Bump version in `app/build.gradle.kts` and all READMEs, add `docs/release-notes/<version>.md`.
2. Push to `master` (with release notes) or push tag `vX.Y.Z`. `.github/workflows/release.yml` checks
   `tools/release_policy.py`, runs tests + lint, builds debug/release APKs and publishes the GitHub release.
3. The owner always wants work shipped as a GitHub release with notes that explain what changed in the code.
