# Contributing to Valkyr

Valkyr is a game-library manager for Android phones and handhelds: sources and romsets, downloads,
covers, save sync and controller-friendly navigation.

## Get oriented

- [TECHNICAL.md](TECHNICAL.md) describes the data model, download routing and where the tests live.
- [FRONTENDS.md](FRONTENDS.md) covers launcher integration (ES-DE, EmuStation-style frontends).
- [docs/ROADMAP.md](docs/ROADMAP.md) lists open work; [docs/release-notes/](docs/release-notes) shows
  what shipped per version.

## Build and test

```sh
./gradlew --no-daemon testDebugUnitTest assembleDebug   # JVM tests + debug APK (what CI runs)
./gradlew --no-daemon lintDebug                         # lint, with app/lint-baseline.xml
python3 -m unittest discover -s tools -p 'test_release_policy.py'
```

Instrumented tests (`./gradlew connectedDebugAndroidTest`) need an emulator or a device and run in a
separate CI job. Benchmarks in `tools/benchmark/` are not part of CI; run them when a change touches
startup, scrolling or cover loading, and say so in the pull request.

## Conventions that are machine-checked

- **Release numbers**: `ReleaseDocsTest` compares the version advertised in every README with
  `app/build.gradle.kts`. When you bump a version, update all five READMEs
  (`README.md`, `.nl`, `.de`, `.fr`, `.es`).
- **Translations**: `StringResourceParityTest` checks that the seven locales (`values/` plus `de`,
  `es`, `fr`, `it`, `nl`, `pt`) carry the same keys. Add a string to `values/` and to every locale in
  the same commit, or the parity test fails. Use `<plurals>` for counts rather than `%d` phrasing.
- **Lint**: new issues should be fixed, not baselined. If a baseline entry has to grow, explain why.

## Other rules

- The app ships no games and no keys. Anything that downloads or deletes does so only inside folders
  the user granted through the Storage Access Framework, and only for files Valkyr created.
- New metadata or debrid providers take a user-supplied key in Settings; never bake a key into the
  APK or into this repository.
- Prefer a pure, testable class next to the UI (`SearchNormalizer`, `GameTitleCleaner`,
  `DuplicateFinder`, `SaveSyncPlanner`) over adding logic to a `*ViewModel` or `*Service`.
