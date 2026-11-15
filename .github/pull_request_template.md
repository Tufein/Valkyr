# Pull request checklist

- [ ] `./gradlew --no-daemon testDebugUnitTest assembleDebug` passes (this is CI's job)
- [ ] `./gradlew --no-daemon lintDebug` adds no new issues to `app/lint-baseline.xml`
- [ ] `python3 -m unittest discover -s tools -p 'test_release_policy.py'` passes when release
      numbering, `CHANGELOG`-style docs or version fields changed
- [ ] All seven locales updated together (`values/` plus `de`, `es`, `fr`, `it`, `nl`, `pt`);
      `StringResourceParityTest` fails otherwise — use `<plurals>` for counts
- [ ] Every README (`README.md`, `.nl`, `.de`, `.fr`, `.es`) matches the version you are releasing;
      `ReleaseDocsTest` checks this
- [ ] Instrumented tests (`connectedDebugAndroidTest`) or a handheld check for controller,
      large-library or save-sync changes — say which, since CI's emulator run is not your device
- [ ] Folder access still through the Storage Access Framework only, deletion limited to files Valkyr
      created, no new cleartext host and no API key committed (see [SECURITY.md](../SECURITY.md))
