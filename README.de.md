# Valkyr

[English](README.md) · [Nederlands](README.nl.md) · [Français](README.fr.md) · **Deutsch** · [Español](README.es.md)

**Retro-Spiele auf deinem Android-Handy oder Handheld finden, herunterladen und ordnen.**

Valkyr führt eine große, durchsuchbare Liste mit den Spielen aus den Quellen, die *du* hinzufügst, lädt sie in die richtigen Ordner herunter und hilft dir, deine Sammlung in Ordnung zu halten. Es funktioniert per Touch *und* mit einem Game-Controller und passt deshalb gut zu Handhelds wie Retroid, Anbernic oder Kinhank.

> **Die App enthält keine Spiele.** Füge eigene Quellen hinzu oder wähle optionale Sammlungen unter **Quellen → Mehr Romsets**: Homebrew, Demos, Freeware-Spiele und Testsoftware vom Herausgeber. Du bleibst für deine Downloads verantwortlich.

<table>
  <tr>
    <td align="center" valign="top"><img src="docs/screenshots/home.png" width="210" alt="Start mit dem Spiel zum Weiterspielen und einem Regal pro Konsole"><br><sub>Start: spiel weiter, wo du aufgehört hast, mit einem Regal voller Cover pro Konsole</sub></td>
    <td align="center" valign="top"><img src="docs/screenshots/game.png" width="210" alt="Eine Spielseite"><br><sub>Jedes Spiel hat eine eigene Seite mit einem großen Spielen-Knopf</sub></td>
    <td align="center" valign="top"><img src="docs/screenshots/downloads.png" width="210" alt="Die Download-Warteschlange"><br><sub>Behalte deine Downloads und die ganze Warteschlange im Blick</sub></td>
    <td align="center" valign="top"><img src="docs/screenshots/more.png" width="210" alt="Der Bereich Mehr"><br><sub>Alles andere ist unter Mehr nur einen Tipp entfernt</sub></td>
  </tr>
</table>

<p align="center"><img src="docs/screenshots/home-landscape.png" width="720" alt="Start im Querformat auf einem Handheld"><br><sub>Auf einem Handheld im Querformat liegen die Bereiche in einer Leiste links</sub></p>

<p align="center"><img src="docs/screenshots/cloud-hub.png" width="460" alt="Die Cloud-Übersicht (Titel und Zahlen sind erfunden)"><br><sub>Die Cloud-Übersicht: RomM, Spielstand-Sync und Backups auf einen Blick (Namen und Zahlen sind erfunden)</sub></p>

*Die Screenshots verwenden erfundene Spieltitel und leere Platzhalterdateien und zeigen die App auf Englisch.*

**Neueste Version: [6.0.0](https://github.com/Tufein/Valkyr/releases/tag/v6.0.0)** (Android-Build 70).

Bis 3.9.0 hieß die App Dogmatix+. Valkyr 6.0.0 hat eine eigene App-ID und wird daher neben der alten App installiert: Nutze *Einstellungen → Sichern* in der alten App und *Sicherung wiederherstellen* in Valkyr und entferne danach die alte App.

## Installation

1. Öffne die **[Releases-Seite](https://github.com/Tufein/Valkyr/releases)** auf deinem Handy oder Handheld, oder lade die Datei dort herunter und kopiere sie auf dein Gerät.
2. Lade eine der beiden Dateien herunter:
   - **`Valkyr-release.apk`** — *die normale Wahl.* Signierte Releases aktualisieren die App und behalten die App-Daten.
   - **`Valkyr-debug.apk`** — eine separate Test-App mit eigenen Daten, zum Ausprobieren neben deiner normalen App.
3. Öffne die Datei und erlaube **„Unbekannte Apps installieren“**, wenn Android danach fragt.

Du brauchst **Android 10 oder neuer**. Die App ist nicht bei Google Play erhältlich.

## Erster Start

1. Die Einführung erklärt die Grundlagen.
2. **Wähle deinen ROM-Ordner** — den Ordner, in den deine Spiele sollen.
3. **Importiere deine Quellen** (eine Datei mit deinen Spielelisten) — oder überspringe das und füge Quellen später im Tab **Quellen** hinzu.
4. Öffne die **Bibliothek** und tippe auf ein Spiel (oder drücke **A**), um seine Spielseite zu öffnen. Wähle dort **Herunterladen** oder **Spielen**.

## Was kann die App?

### Spiele finden
- **Eine Liste** mit den Spielen aus allen Quellen: Webverzeichnisse, direkte Links, Torrents und Magnet-Links sowie dein RomM-Server.
- **Suche, die Fehler verzeiht** (Akzente, Bindestriche, doppelte Buchstaben), mit letzten Suchen, **Überrasch mich** und **Mehr davon**. **Alles durchsuchen** findet auch Einstellungen, Werkzeuge und Bildschirme.
- **Filtern** nach Konsole, Region, Sprache, Typ, Genre, Jahrzehnt und *Neu*; **sortieren** nach Name, Größe oder Neuestem. Ein grünes **✓** zeigt, was du schon hast.
- **Eine Seite für jedes Spiel**: Bild, Herunterladen oder Spielen, die beste Version, Favorit, Sammlungen, Teilen, Notizen, Screenshots und Handbücher sowie ein Tab *Versionen*, der erklärt, wie jede Version bewertet wird.
- **Cover zuerst**: Home startet mit Regalen voller großer Cover (weiterspielen, neu für dich, ein Regal pro Konsole); die Navigation liegt in einer Leiste unten oder, auf einem Handheld, in einer schmalen Seitenleiste mit Spiele, Downloads, Quellen und Mehr. Die Spielseite beginnt mit einem großen Cover und einer großen Schaltfläche Spielen oder Download.
- **Cover aus mehreren Quellen**: RomM, libretro-thumbnails, [ScreenScraper](https://www.screenscraper.fr) (mit deinem eigenen Login), TheGamesDB, SteamGridDB und IGDB (jeweils mit deinem eigenen kostenlosen Schlüssel) und die zwischengespeicherten Spielbilder, in einer Reihenfolge, die du wählst. Die App meldet klar, wenn ein Login scheitert oder ein Tageslimit erreicht ist; eine nur kurz nicht erreichbare Quelle wird später erneut gefragt (*Einstellungen → Cover-Quellen*).
- **Zusätzliche Launcher-Bilder**: Titelbildschirme und Screenshots für den ES-DE-Cover-Ordner (*Tools → Cover für Launcher*), standardmäßig aus und ersetzen nie vorhandene Dateien.
- **Leistungsprüfung** (*Tools → Leistungsprüfung*): misst Start, Suchgeschwindigkeit, Cover-Dekodierung, Speichertempo und Scrollen auf deinem eigenen Gerät, mit Urteil Schnell/Okay/Langsam und einem Bericht nur mit Zahlen zum Teilen.
- **Als Nächstes und Für dich**: eine persönliche Spielliste pro Profil und Vorschläge aus deiner eigenen Bibliothek, die immer sagen, warum sie gewählt wurden.
- **Sammlungen und Ziele**: Favoriten, eigene Listen, regelbasierte Sammlungen (Konsole, Sprache, Genre, Jahr, gespielt), Sammlungsziele, Spielverlauf, ein Jahresrückblick und die besten Spiele je Konsole von RetroAchievements.
- **Wunschliste**: Spiele vormerken, Bescheid bekommen, wenn eines auftaucht, automatisch herunterladen und die Liste teilen.
- **Liste importieren**: Text einfügen oder Datei laden; gefundene Spiele werden geladen, der Rest landet auf der Wunschliste.

- **Vier Ansichten**: Übersicht, Liste, Cover und Konsolen (eine Kachel je Konsole mit Zahlen), Schnellfilter (heruntergeladen, Favoriten, durchgespielt, RetroAchievements besiegt/gemeistert), eine kompakte Suchleiste und optional *Versionen gruppieren*.
- **Handbücher**: Handbuch vom RomM-Server laden und in der App lesen (PDF, Bilder, Text), dazu ein *Offline*-Chip.

### Herunterladen
- **Direkte Links, Torrents und Magnet-Links**, dazu optional **TorBox** und **Real-Debrid**.
- **Mehrere Downloads gleichzeitig** mit Pause, Fortsetzen, Wiederholen, einem globalen Tempolimit (nachts auf Wunsch ohne) und einem Limit pro Server.
- **Gesamtfortschritt im Kreis** zählt fertige Downloads im Verhältnis zur ganzen Warteschlange: 25 von 100 = 25%. Nur vollständig abgeschlossene Downloads zählen, sodass der Kreis beim Abschluss eines Downloads weiter steigt.
- **Unterbrochene Downloads laufen weiter**, wo sie stehen geblieben sind, und die Warteschlange übersteht einen Neustart.
- **Laden, wenn es passt**: nur im WLAN, beim Laden, heute Nacht oder zu einer festen Zeit. Downloads pausieren bei schwachem Akku oder heißem Gerät und sagen, worauf sie warten.
- **Automatisch wiederholen** mit sichtbarem Countdown, klaren Fehlergründen, Wechsel zu einer anderen Quelle und einer Erfolgsbilanz pro Quelle.
- **Beste Version** wählt deine Region und Sprache und lässt Demos aus; **Alles Angezeigte laden** prüft zuerst den Platz.
- **Prüfungen**: Dateigrößen, Prüfsummen gegen die Quelle und sicheres Entpacken von ZIP und 7z (nichts wird außerhalb des eigenen Ordners geschrieben).
- **Überall**: Benachrichtigungsknöpfe (Pause, Fortsetzen, Stopp), eine Schnelleinstellungs-Kachel, ein Widget und eine Ansicht für den zweiten Bildschirm.

### Spielen
- **Spielen von der Spielseite** mit einem passenden Emulator, pro Konsole gemerkt oder pro Spiel gewählt, mit einer **Bereitschaftsprüfung** (Dateien, Discs, BIOS, Emulator), geführter Einrichtung und Teststart.
- **ES-DE, iiSU, Daijishō und Pegasus**: ein Eintrag „Mehr Spiele suchen“ je Konsole sowie Cover und Beschreibungen in deinem Launcher; für iiSU, Daijishō und jeden anderen Launcher landen die Cover in einem Medienordner, den du wählst, in der Dateiaufteilung, die der Launcher erwartet (zehn fertige Aufteilungen, oder schreibe selbst eine).
- **Spiele auf deinem Startbildschirm**: pinne ein Spiel von seiner Seite und es startet direkt in seinem Emulator; langes Drücken auf das App-Symbol zeigt außerdem deine drei zuletzt gespielten Spiele.
- **RetroArch-Playlists**: schreibt `.lpl`-Playlists, damit RetroArch deine Bibliothek zeigt; vorhandene Playlists werden nur ergänzt, und die alte Datei landet vorher im Papierkorb und Wiederherstellungscenter.
- **Auf einem anderen Handheld weiterspielen** von der Spielseite aus, wenn Emulator und Spielstände mitkommen können: zuerst wird der Rückweg gezeigt, der Spielstand geht über RomM.
- **Mehr-Disc-Spiele** bekommen automatisch ihre `.m3u`-Playlist.
- **Weiterspielen** auf der Startseite, aus Spielständen auf jedem Gerät oder aus ES-DE.

### Bibliothek gesund halten
- **Bibliothek reparieren**: findet leere oder abgebrochene Dateien, Disc-Sets mit fehlenden Tracks, tote oder doppelte Playlists, Download-Reste und veraltete Markierungen. Jede Korrektur wird vorab gezeigt, Entferntes landet im Papierkorb mit Wiederherstellung.
- **Doppelte Spiele** mit sicherer Bereinigung samt Vorschau; **Bessere Versionen** und **Speicher freigeben**.
- **DAT-Prüfung** gegen No-Intro, Redump und TOSEC; **Spielsets**-Prüfung; **BIOS-Prüfung**; **Switch-Updates und DLC**.
- **Bibliotheksübersicht, Speicher und Statistik** je Konsole; **Intelligenter Speicher** kann selten gespielte Konsolen auf eine SD-Karte und zurück verschieben.
- **Dateimanager** zum Ansehen, Umbenennen, Verschieben und Entpacken; **Export** der Sammlung als CSV oder Webseite.
- **Spiele kopieren oder senden**: ganze Spiele mit allen Dateien auf eine SD-Karte oder einen USB-Ordner, deinen RomM-Server oder dein NAS. Vorher siehst du jede Datei, den nötigen Platz und was schon da ist; jede Datei wird am Ziel geprüft, Spielstände bleiben zurück und nichts wird ersetzt. Für eine Karte, die in ein anderes System kommt, können die Konsolenordner die Namen von ES-DE, Batocera, Knulli, ArkOS, ROCKNIX, OnionOS oder muOS verwenden.
- **Gesundheitsprüfung** mit einer Lösung pro Problem und ein **Scanbericht** für fehlgeschlagene Quellen.
- **Quellen**: von Hand hinzufügen, importieren und exportieren, als QR-Codes teilen, optionaler Inhaltskatalog mit Libretro-Inhalten für 29 Systeme, den Freeware-Adventures von ScummVM und kostenlosen Game-Boy-Homebrews von Homebrew Hub, jeweils mit Anbieter, Reserveadressen und automatische Hintergrundscans, die Unverändertes überspringen.

### Sicher gebaut
- **Papierkorb und Wiederherstellungscenter**: entfernte Spiele, unterbrochene Verschiebungen, ersetzte Dateien und Spielstand-Backups lassen sich wiederherstellen, jeweils mit Vorschau. Kopien werden geprüft, bevor etwas entfernt wird.
- **Aktionsverlauf** mit Rückgängig und ein Journal, das einen Absturz übersteht.
- **Updates aus der App** werden auf Prüfsumme, Paket, Version und Signatur geprüft, bevor Android sie installiert.
- **Problem melden** erstellt einen privaten, bearbeitbaren Bericht, dessen Inhalt du wählst; Pfade, Serveradressen, Schlüssel und Tokens werden entfernt, und die App sendet nichts.

### Cloud und Sync
- **RomM**: fertige Downloads und ganze Spiele aus mehreren Dateien senden, als Quelle nutzen, Spiele, Genres und Screenshots sehen, Spielstatus, Bewertung und Favoriten abgleichen, fehlende BIOS-Dateien holen.
- **Spielstand-Sync und Cloud-Saves**: Spielstände und Savestates wandern zwischen Handhelds und RomM, auch DuckStation-Memory-Cards, PPSSPP-Speicherordner und, wenn du es wählst, ganze Dolphin- und NetherSX2-Memory-Cards, Dolphin-GameCube-Speicherordner und Wii-Saves; bei Konflikten wirst du gefragt, ersetzte Kopien bleiben 30 Tage erhalten.
- **Deine eigene Cloud (WebDAV)**: verschlüsselte Backups (Nextcloud, ownCloud, Synology und andere) und Geräte-Sync von Favoriten, Wunschliste und Sammlungen.
- **RetroAchievements**: Fortschritt und Abzeichen je Spiel, dein Profil und welche Spiele Erfolge haben.

- **RomM per kurzem Code koppeln**; von der RomM-Webseite gesendete Spiele landen in der Download-Warteschlange; *RomM verwenden* schaltet jeden RomM-Aufruf aus und an.
- **Sicherere Spielstände**: Backup vor jedem Überschreiben, Konflikte entscheidest du selbst, Speicherplätze auf einer Karte und ein *Ordnermodus (Syncthing)*.
- **DukeStation** ist ein bekannter PlayStation-Emulator, mit Fortsetzen ab Speicherplatz.

### Profile
- **Profile** blenden Konsolen oder Spiele mit gewählten Tags aus (ein Kinderprofil), mit optionaler PIN. Favoriten, Emulatoren, Notizen und Als Nächstes gelten pro Profil.

### Für Handhelds gemacht
- **Alles geht mit dem Gamepad** (Steuerkreuz, A/B/X/Y, Schultertasten); die Hinweise am Bildschirm passen zu Xbox, Nintendo oder PlayStation. **SELECT** gedrückt halten öffnet ein Schnellmenü.
- **Quer und hochkant**, eine Ansicht für den **zweiten Bildschirm** und ein **TV-Modus** mit größerer Schrift und Fernbedienungstasten.
- **Helles, dunkles oder tiefschwarzes** Design, zwölf Akzentfarben und Material You, Cover, einstellbare Schriftgröße und abschaltbare Animationen.
- **Sieben Sprachen**: Englisch, Niederländisch, Deutsch, Französisch, Spanisch, Italienisch und Portugiesisch.
- **Zweitbildschirm-Hub** mit Weiterspielen, letzten Spielen, Konsolen-Chips, Warteschlange und Akku-/Hitze-Chips, dazu eine kurze Tour durch die App.
- **Ein neues Handheld einrichten**: eine Checkliste von der frischen Installation bis zum startklaren Handheld (Einstellungen, Spieleordner, Emulatoren, fehlende Spiele, Spielstände, Launcher); jeder Schritt öffnet den Bildschirm, der ihn erledigt.
- **Sichern und Wiederherstellen** von Einstellungen, Quellen, Favoriten, Sammlungen und Downloadliste in einer Datei.

## Ein Gamepad benutzen

| Taste | Was sie macht |
|---|---|
| D-Pad | Sich bewegen |
| **A** | Auswählen / Spielseite öffnen; dort herunterladen oder spielen |
| **B** | Einen Schritt zurück |
| **X** | Spielinfos |
| **Y** | Suchen |
| **Select** | Ein Spiel mit einem Stern markieren oder den Stern entfernen |
| **LB / RB** | Zwischen den Filtern und der Liste wechseln |
| **ZL / ZR** | Voriger / nächster Bereich |
| **R3** | Filter ausblenden oder einblenden |

Alles funktioniert auch per Touch. Die Hinweise erscheinen nur, solange ein Controller angeschlossen ist.

## Gut zu wissen

- Entfernte Spiele kommen in den Papierkorb. Unter Werkzeuge → Papierkorb und Wiederherstellung wiederherstellen oder leeren. Speicher wird erst nach dem Leeren frei. Auch der Datei-Explorer verwendet standardmäßig Wiederherstellung; endgültiges Löschen muss in der Bestätigung gewählt werden.
- **Eine Sicherungsdatei enthält deine Kontoschlüssel** (TorBox, Real-Debrid, RomM). Gib sie nicht weiter.
- **Das Fenster mit den Spielinfos bleibt in den hier angebotenen Downloads leer**, weil es einen kostenlosen Schlüssel aus einer Spieldatenbank braucht, der erst beim Erstellen der App hinzugefügt wird.
- Valkyr sucht nicht von selbst nach Spielen. Es liest nur die Quellen, die **du** hinzufügst.
- **Etwas funktioniert nicht?** *Einstellungen → App und Updates → Problem melden* erstellt einen privaten Bericht für eine GitHub-Meldung; Pfade, Serveradressen, Schlüssel und Tokens werden vorher entfernt.

## Für Entwickler und Fortgeschrittene

- **[TECHNICAL.md](TECHNICAL.md)**: Aufbau der App, Entscheidungen der Doppelte-Spiele-Suche, Ordnerstruktur, Deep Links und Tech-Stack (auf Englisch).
- **[FRONTENDS.md](FRONTENDS.md)**: ES-DE, iiSU, Daijishō und Pegasus einrichten.
- **[Roadmap](docs/ROADMAP.md)**: Ideen für künftige Versionen.
- **[Releases](https://github.com/Tufein/Valkyr/releases)**: Downloads und Prüfsummen.

## Mitwirkende

Valkyr wird von [Tufein](https://github.com/Tufein) entwickelt. Es baut auf diesen Open-Source-Projekten und Datenquellen auf:

| Was | Wofür |
|---|---|
| [Jetpack Compose / AndroidX](https://developer.android.com/jetpack/androidx), [Kotlin coroutines](https://github.com/Kotlin/kotlinx.coroutines), [Dagger Hilt](https://github.com/google/dagger) | Die App selbst (Apache-2.0) |
| [Gson](https://github.com/google/gson), [Coil](https://github.com/coil-kt/coil), [jsoup](https://jsoup.org), [ZXing](https://github.com/zxing/zxing) | JSON, Bilder, Webverzeichnisse lesen, QR-Codes |
| [7-Zip-JBinding](https://github.com/borisbrodski/sevenzipjbinding), [libtorrent4j](https://github.com/aldenml/libtorrent4j) | Archive entpacken, Torrents |
| [Space Mono](https://fonts.google.com/specimen/Space+Mono), [Manrope](https://fonts.google.com/specimen/Manrope) | Schriften (SIL Open Font License) |
| [RetroAchievements](https://retroachievements.org), [RAWG](https://rawg.io), [TheGamesDB](https://thegamesdb.net), [SteamGridDB](https://www.steamgriddb.com), [IGDB](https://www.igdb.com), [ScreenScraper](https://www.screenscraper.fr), [libretro-thumbnails](https://github.com/libretro-thumbnails/libretro-thumbnails) | Erfolge, Spielinfos und Cover |
| [No-Intro](https://no-intro.org), [Redump](http://redump.org), [TOSEC](https://www.tosecdev.org) | DAT-Prüfungen |
| [RomM](https://github.com/rommapp/romm), [ES-DE](https://es-de.org), [Libretro](https://www.libretro.com), [ScummVM](https://www.scummvm.org), [Homebrew Hub](https://hh.gbdev.io) | Server, Launcher und freie Inhalte, mit denen die App arbeitet |

## Haftungsausschluss

Diese App dient nur zu Bildungszwecken. Du bist dafür verantwortlich, sicherzustellen, dass du rechtlich dazu berechtigt bist, jegliche Inhalte herunterzuladen.
