# Security and maintenance

Valkyr stores service keys you typed (Debrid, TorBox, RomM, ScreenScraper) and manages large media
libraries. Never paste those keys into an issue; export a settings file only when you intend to
share it, and treat that file as a credential bundle.

Use GitHub private vulnerability reporting **if the repository owner has enabled it**. No separate
confidential contact is configured in this repository. If private reporting is unavailable, open a
minimal issue asking for a private contact rather than describing the exploit.

## What this app trusts

- **Android cloud backup includes your keys.** `allowBackup="true"` is set and
  `res/xml/backup_rules.xml` / `data_extraction_rules.xml` exclude only `dav_device` and
  `new_handheld`; the main `user_settings` datastore still holds `torbox_api_key`,
  `realdebrid_api_key` and `romm_token`. Moving keys to their own excluded datastore file is the
  intended fix — until then, the in-app warning about *exported* backup files understates the risk.
- **Cleartext is allowed for all hosts** (`usesCleartextTraffic="true"`), not only for a LAN RomM
  server. A per-domain network security configuration would keep HTTP where it is actually needed.
- **Everything you add is trusted input.** Sources, torrent trackers and archive contents come from
  lists you configure; entry names are validated against the destination before writing, and torrent
  deletion is limited to files Valkyr created. Report any path that escapes its folder as a security
  issue, not as a bug.
- **TLS is trust-on-first-use, not blind.** `data/service/TlsTrust.kt` pins per host after your
  confirmation; key requests do not follow cross-host redirects, and WebDAV XML parsing rejects
  external entities.

## Maintenance procedure

- CI (`test.yml`) runs the JVM unit tests, Android lint and instrumented tests; `assembleRelease`
  with R8 rules and the macrobenchmarks in `tools/benchmark/` only run at release time, so build a
  release variant before publishing and keep the checksums.
- Re-test the Wi-Fi transfer flow (PIN, five-attempt lock, auto stop) after any change to
  `WifiReceiveService`, and the database migrations after any entity change — `exportSchema` is
  currently off, so schema diffs are not machine-checked.
- Keep all five README translations and `TECHNICAL.md` in step with `app/build.gradle.kts`; the
  advertised release number and build must match what the release workflow publishes.

This documents the current design, not an audit. No response SLA is promised.
