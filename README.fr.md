# Valkyr

[English](README.md) · [Nederlands](README.nl.md) · **Français** · [Deutsch](README.de.md) · [Español](README.es.md)

**Trouvez, téléchargez et organisez des jeux rétro sur votre téléphone Android ou votre console portable.**

Valkyr rassemble les jeux des sources que *vous* ajoutez dans une seule grande liste, où l’on peut faire des recherches. Il les télécharge dans les bons dossiers et vous aide à garder votre collection bien rangée. Il fonctionne au toucher *et* avec une manette de jeu. Il est donc à l’aise sur les consoles portables comme les Retroid, Anbernic ou Kinhank.

> **L’application ne contient aucun jeu.** Ajoutez vos sources ou sélectionnez des collections facultatives dans **Sources → Plus de romsets** : homebrews, démos, jeux freeware et logiciels de test proposés par leur éditeur. Vous restez responsable de ce que vous téléchargez.

<table>
  <tr>
    <td align="center" valign="top"><img src="docs/screenshots/home.png" width="210" alt="L’accueil avec le jeu à reprendre et une étagère par console"><br><sub>Accueil : reprenez là où vous vous êtes arrêté, avec une étagère de jaquettes par console</sub></td>
    <td align="center" valign="top"><img src="docs/screenshots/game.png" width="210" alt="La page d’un jeu"><br><sub>Chaque jeu a sa propre page avec un grand bouton Jouer</sub></td>
    <td align="center" valign="top"><img src="docs/screenshots/downloads.png" width="210" alt="La file de téléchargement"><br><sub>Suivez vos téléchargements et toute la file</sub></td>
    <td align="center" valign="top"><img src="docs/screenshots/more.png" width="210" alt="L’écran Plus"><br><sub>Tout le reste est à portée de main dans Plus</sub></td>
  </tr>
</table>

<p align="center"><img src="docs/screenshots/home-landscape.png" width="720" alt="L’accueil en mode paysage sur une console portable"><br><sub>Sur une console portable en mode paysage, les sections sont dans une barre à gauche</sub></p>

<p align="center"><img src="docs/screenshots/cloud-hub.png" width="460" alt="La vue d’ensemble du cloud (titres et chiffres inventés)"><br><sub>La vue d’ensemble du cloud : RomM, synchro des sauvegardes et copies de sécurité d’un coup d’œil (noms et chiffres inventés)</sub></p>

*Les captures d’écran utilisent des titres de jeux inventés et des fichiers vides de substitution, et montrent l’application en anglais.*

**Dernière version : [6.0.0](https://github.com/Tufein/Valkyr/releases/tag/v6.0.0)** (build Android 70).

Jusqu’à la 3.9.0, l’application s’appelait Dogmatix+. Valkyr 6.0.0 a son propre identifiant d’application et s’installe donc à côté de l’ancienne : utilisez *Réglages → Sauvegarder* dans l’ancienne appli et *Restaurer une sauvegarde* dans Valkyr, puis supprimez l’ancienne.

## Installation

1. Ouvrez la **[page Releases](https://github.com/Tufein/Valkyr/releases)** sur votre téléphone ou votre console portable, ou téléchargez-y le fichier et copiez-le sur l’appareil.
2. Téléchargez l’un des deux fichiers :
   - **`Valkyr-release.apk`** — *le choix normal.* Les versions signées mettent l’application à jour en conservant les données.
   - **`Valkyr-debug.apk`** — une application de test séparée avec ses propres données, pour essayer des choses à côté de votre application habituelle.
3. Ouvrez le fichier et autorisez **« Installer des applications inconnues »** si Android vous le demande.

Il vous faut **Android 10 ou plus récent**. L’application n’est pas sur Google Play.

## Premier démarrage

1. Le guide de bienvenue explique les bases.
2. **Choisissez votre dossier de ROM** — le dossier où vos jeux doivent aller.
3. **Importez vos sources** (un fichier avec vos listes de jeux) — ou passez cette étape et ajoutez des sources plus tard dans l’onglet **Sources**.
4. Ouvrez la **Bibliothèque** et touchez un jeu (ou appuyez sur **A**) pour ouvrir sa page. Choisissez ensuite **Télécharger** ou **Jouer**.

## Que peut faire l’application ?

### Trouver des jeux
- **Une seule liste** avec les jeux de toutes vos sources : répertoires web, liens directs, torrents et liens magnet, et votre serveur RomM.
- **Une recherche qui pardonne les fautes** (accents, tirets, lettres doublées), avec les recherches récentes, **Surprenez-moi** et **Plus comme celui-ci**. **Tout rechercher** trouve aussi les réglages, outils et écrans.
- **Filtrer** par console, région, langue, type, genre, décennie et *Nouveau* ; **trier** par nom, taille ou plus récent. Un **✓** vert marque ce que vous avez déjà.
- **Une page par jeu** : visuel, Télécharger ou Jouer, la meilleure version, favori, collections, partage, notes, captures et manuels, et un onglet *Versions* qui explique comment chaque version est notée.
- **Les jaquettes d’abord** : l’accueil s’ouvre sur des étagères de grandes jaquettes (continuer à jouer, nouveau pour vous, une étagère par console) ; la navigation est dans une barre en bas, ou une barre latérale fine sur une console portable, avec Jeux, Téléchargements, Sources et Plus. La page d’un jeu commence par une grande jaquette et un grand bouton Jouer ou Télécharger.
- **Jaquettes de plusieurs sources** : RomM, libretro-thumbnails, [ScreenScraper](https://www.screenscraper.fr) (avec votre propre compte), TheGamesDB, SteamGridDB et IGDB (chacune avec votre propre clé gratuite) et les images de jeux en cache, dans l’ordre de votre choix. L’appli dit clairement quand une connexion échoue ou qu’une limite quotidienne est atteinte, et une source simplement indisponible est réinterrogée plus tard (*Réglages → Sources de jaquettes*).
- **Images supplémentaires pour lanceurs** : écrans-titres et captures pour le dossier de jaquettes d’ES-DE (*Outils → Jaquettes pour lanceurs*), désactivé par défaut et sans jamais remplacer vos fichiers existants.
- **Test de performance** (*Outils → Test de performance*) : mesure le démarrage, la vitesse de recherche, le décodage des jaquettes, le stockage et le défilement sur votre propre appareil, avec un verdict Rapide/Correct/Lent et un rapport de simples chiffres à partager.
- **À suivre et Pour vous** : une liste de jeux personnelle par profil, et des suggestions tirées de votre propre bibliothèque qui disent toujours pourquoi elles ont été choisies.
- **Collections et objectifs** : favoris, listes personnelles, collections par règles (console, langue, genre, année, joué), objectifs de collection, historique de jeu, bilan annuel et meilleurs jeux par console selon RetroAchievements.
- **Liste de souhaits** : notez les jeux voulus, soyez prévenu quand l’un apparaît, téléchargez-les automatiquement et partagez la liste.
- **Importer une liste** : collez ou chargez un fichier texte ; les jeux trouvés sont téléchargés, les autres vont dans la liste de souhaits.

- **Quatre vues** : Aperçu, Liste, Jaquettes et Consoles (une tuile par console avec les nombres), filtres rapides (téléchargé, favoris, terminé, RetroAchievements battu/maîtrisé), une barre de recherche compacte et, en option, *Regrouper les versions*.
- **Manuels** : téléchargez le manuel depuis votre serveur RomM et lisez-le dans l’app (PDF, images, texte), avec une pastille *Hors ligne*.

### Télécharger
- **Liens directs, torrents et liens magnet**, plus **TorBox** et **Real-Debrid** en option.
- **Plusieurs téléchargements à la fois**, avec pause, reprise, nouvel essai, une limite de débit globale (sans limite la nuit si vous le voulez) et une limite par serveur.
- **Cercle de progression totale** : il compte les téléchargements terminés sur toute la file, soit 25 sur 100 = 25 %. Seuls les téléchargements entièrement terminés comptent, donc le cercle continue d’avancer quand l’un se termine.
- **Les téléchargements interrompus reprennent** là où ils se sont arrêtés, et la file survit à un redémarrage.
- **Télécharger quand cela convient** : seulement en Wi-Fi, pendant la charge, cette nuit ou à une heure fixée. Les téléchargements s’arrêtent si la batterie est faible ou l’appareil chaud, et disent ce qu’ils attendent.
- **Nouvel essai automatique** avec compte à rebours visible, causes d’erreur claires, bascule vers une autre source et historique de fiabilité par source.
- **Meilleure version** choisit votre région et votre langue et écarte les démos ; **Tout télécharger** vérifie d’abord la place.
- **Vérifications** : tailles de fichiers, sommes de contrôle face à la source et décompression sûre des ZIP et 7z (rien n’est écrit hors de son dossier).
- **Partout** : boutons de notification (pause, reprise, arrêt), une tuile des réglages rapides, un widget d’écran d’accueil et une vue pour le second écran.

### Jouer
- **Jouer depuis la page du jeu** avec un émulateur compatible, mémorisé par console ou choisi par jeu, avec une **vérification de préparation** (fichiers, disques, BIOS, émulateur), une configuration guidée et un lancement d’essai.
- **ES-DE, iiSU, Daijishō et Pegasus** : une entrée « chercher d’autres jeux » par console, plus jaquettes et descriptions dans votre lanceur ; pour iiSU, Daijishō et tout autre lanceur, les jaquettes vont dans un dossier de médias que vous choisissez, avec la structure de fichiers attendue par le lanceur (dix structures prêtes à l’emploi, ou écrivez la vôtre).
- **Des jeux sur votre écran d’accueil** : épinglez un jeu depuis sa page et il démarre directement dans son émulateur ; un appui long sur l’icône de l’appli affiche aussi vos trois derniers jeux joués.
- **Playlists RetroArch** : écrit des playlists `.lpl` pour que RetroArch affiche votre bibliothèque ; les playlists existantes sont seulement complétées et l’ancien fichier passe d’abord à la corbeille et au centre de récupération.
- **Continuer sur une autre console portable** depuis la page du jeu, quand votre émulateur et vos sauvegardes peuvent suivre : le chemin du retour est montré d’abord et la sauvegarde passe par RomM.
- **Les jeux à plusieurs disques** reçoivent automatiquement leur playlist `.m3u`.
- **Reprendre** sur l’accueil, depuis les sauvegardes de n’importe quel appareil ou depuis ES-DE.

### Garder la bibliothèque en bonne santé
- **Réparer la bibliothèque** : trouve les fichiers vides ou tronqués, les jeux multi-disques avec pistes manquantes, les playlists mortes ou en double, les restes de téléchargements et les repères périmés. Chaque correction est prévisualisée et ce qui est retiré va dans la corbeille avec récupération.
- **Jeux en double** avec un nettoyage sûr et prévisualisé ; **Meilleures versions** et **Libérer de la place**.
- **Vérification DAT** contre No-Intro, Redump et TOSEC ; contrôle des **jeux multi-fichiers** ; **contrôle du BIOS** ; **mises à jour et DLC Switch**.
- **Aperçu de la bibliothèque, Stockage et Statistiques** par console ; le **stockage intelligent** peut déplacer les consoles rarement jouées vers une carte SD et les rapatrier.
- **Explorateur de fichiers** pour regarder, renommer, déplacer et décompresser ; **export** de la collection en CSV ou page web.
- **Copier ou envoyer des jeux** : des jeux complets, avec tous leurs fichiers, vers une carte SD ou un dossier USB, votre serveur RomM ou votre NAS. Vous voyez d’abord chaque fichier, l’espace nécessaire et ce qui s’y trouve déjà ; chaque fichier est vérifié à l’arrivée, les sauvegardes restent sur l’appareil et rien n’est remplacé. Pour une carte destinée à un autre système, les dossiers de console peuvent suivre les noms d’ES-DE, Batocera, Knulli, ArkOS, ROCKNIX, OnionOS ou muOS.
- **Contrôle de santé** avec une solution par problème, et **rapport d’analyse** pour les sources en échec.
- **Sources** : ajout manuel, import et export, partage par codes QR, catalogue de contenus optionnel avec du contenu Libretro pour 29 systèmes, les jeux d’aventure freeware de ScummVM et des homebrews Game Boy gratuits de Homebrew Hub, chacun avec son fournisseur, adresses de secours et analyses automatiques en arrière-plan qui sautent ce qui n’a pas changé.

### Sûr par conception
- **Corbeille et centre de récupération** : jeux retirés, déplacements interrompus, fichiers remplacés et sauvegardes se restaurent, chacun avec un aperçu. Les copies sont vérifiées avant tout retrait.
- **Historique des actions** avec annulation, et un journal qui survit à un plantage.
- **Les mises à jour depuis l’application** sont vérifiées (somme de contrôle, paquet, version, signataire) avant l’installation par Android.
- **Signaler un problème** crée un rapport privé et modifiable dont vous choisissez le contenu ; chemins, adresses de serveur, clés et jetons sont retirés et l’application n’envoie rien.

### Cloud et synchronisation
- **RomM** : envoyer les téléchargements terminés et des jeux complets en plusieurs fichiers, l’utiliser comme source, voir jeux, genres et captures, synchroniser statut de jeu, note et favoris, récupérer les BIOS manquants.
- **Synchro des sauvegardes et sauvegardes cloud** : vos sauvegardes voyagent entre consoles et RomM, y compris les cartes mémoire DuckStation, les dossiers de sauvegarde PPSSPP et, si vous le choisissez, des cartes entières Dolphin et NetherSX2, dossiers de sauvegarde GameCube de Dolphin et sauvegardes Wii ; en cas de conflit on vous demande, et les copies remplacées sont gardées 30 jours.
- **Votre propre cloud (WebDAV)** : sauvegardes chiffrées (Nextcloud, ownCloud, Synology et autres) et synchro d’appareils pour favoris, liste de souhaits et collections.
- **RetroAchievements** : progression et badges par jeu, votre profil et les jeux qui ont des succès.

- **Associez RomM avec un code court** ; les jeux envoyés depuis la page web de RomM arrivent dans votre file de téléchargements ; *Utiliser RomM* coupe ou rétablit chaque appel à RomM.
- **Sauvegardes plus sûres** : copie avant chaque écrasement, conflits que vous tranchez, emplacements sur une carte et un *mode dossier (Syncthing)*.
- **DukeStation** est un émulateur PlayStation connu, avec reprise depuis un emplacement.

### Profils
- **Les profils** masquent des consoles ou des jeux portant certaines étiquettes (un profil enfant), avec un code PIN facultatif. Favoris, émulateurs, notes et À suivre sont gardés par profil.

### Pensé pour les consoles portables
- **Tout se fait à la manette** (croix directionnelle, A/B/X/Y, gâchettes) ; les indications à l’écran suivent Xbox, Nintendo ou PlayStation. Maintenez **SELECT** pour un menu rapide.
- **Paysage et portrait**, une vue **second écran** et un **mode TV** avec texte plus grand et touches de télécommande.
- **Thème clair, sombre ou noir pur**, douze couleurs d’accent et Material You, jaquettes, taille de texte réglable et animations désactivables.
- **Sept langues** : anglais, néerlandais, allemand, français, espagnol, italien et portugais.
- **Hub du second écran** avec reprise du jeu, jeux récents, puces de console, file de téléchargements et puces batterie/chaleur, et une courte visite guidée de l’app.
- **Configurer une nouvelle console portable** : une liste d’étapes de l’installation neuve à la console prête (réglages, dossier des jeux, émulateurs, jeux manquants, sauvegardes, lanceur) ; chaque étape ouvre l’écran qui s’en charge.
- **Sauvegarde et restauration** des réglages, sources, favoris, collections et liste de téléchargements dans un seul fichier.

## Utiliser une manette

| Bouton | Ce qu’il fait |
|---|---|
| D-pad | Se déplacer |
| **A** | Choisir / ouvrir la page du jeu ; télécharger ou jouer depuis cette page |
| **B** | Revenir en arrière d’une étape |
| **X** | Infos du jeu |
| **Y** | Rechercher |
| **Select** | Ajouter un jeu aux favoris ou l’en retirer |
| **LB / RB** | Passer des filtres à la liste, et inversement |
| **ZL / ZR** | Section précédente / suivante |
| **R3** | Masquer ou afficher le panneau de filtres |

Tout fonctionne aussi au toucher. Les indications ne s’affichent que lorsqu’une manette est connectée.

## Bon à savoir

- Les jeux retirés vont dans la corbeille. Restaurez ou videz via Outils → Corbeille et récupération. L’espace est libéré après vidage. L’explorateur utilise aussi la récupération par défaut ; la suppression définitive doit être choisie dans la confirmation.
- **Un fichier de sauvegarde contient vos clés de compte** (TorBox, Real-Debrid, RomM). Gardez-le privé.
- **La fenêtre d’infos du jeu reste vide dans les fichiers proposés au téléchargement ici**, car elle a besoin d’une clé gratuite, fournie par une base de données de jeux et ajoutée lors de la compilation de l’application.
- Valkyr ne cherche pas de jeux tout seul. Il lit seulement les sources que **vous** ajoutez.
- **Quelque chose ne marche pas ?** *Réglages → Application et mises à jour → Signaler un problème* crée un rapport privé à coller dans un ticket GitHub ; chemins, adresses de serveur, clés et jetons sont retirés d’abord.

## Pour les développeurs et utilisateurs avancés

- **[TECHNICAL.md](TECHNICAL.md)** : construction de l’application, décisions de la recherche de doublons, organisation des dossiers, liens profonds et technologies (en anglais).
- **[FRONTENDS.md](FRONTENDS.md)** : configurer ES-DE, iiSU, Daijishō et Pegasus.
- **[Feuille de route](docs/ROADMAP.md)** : idées pour les prochaines versions.
- **[Versions](https://github.com/Tufein/Valkyr/releases)** : téléchargements et sommes de contrôle.

## Crédits

Valkyr est réalisé par [Tufein](https://github.com/Tufein). Il s’appuie sur ces projets open source et sources de données :

| Quoi | Utilisé pour |
|---|---|
| [Jetpack Compose / AndroidX](https://developer.android.com/jetpack/androidx), [Kotlin coroutines](https://github.com/Kotlin/kotlinx.coroutines), [Dagger Hilt](https://github.com/google/dagger) | L’application elle-même (Apache-2.0) |
| [Gson](https://github.com/google/gson), [Coil](https://github.com/coil-kt/coil), [jsoup](https://jsoup.org), [ZXing](https://github.com/zxing/zxing) | JSON, images, lecture des répertoires web, codes QR |
| [7-Zip-JBinding](https://github.com/borisbrodski/sevenzipjbinding), [libtorrent4j](https://github.com/aldenml/libtorrent4j) | Décompression des archives, torrents |
| [Space Mono](https://fonts.google.com/specimen/Space+Mono), [Manrope](https://fonts.google.com/specimen/Manrope) | Polices (SIL Open Font License) |
| [RetroAchievements](https://retroachievements.org), [RAWG](https://rawg.io), [TheGamesDB](https://thegamesdb.net), [SteamGridDB](https://www.steamgriddb.com), [IGDB](https://www.igdb.com), [ScreenScraper](https://www.screenscraper.fr), [libretro-thumbnails](https://github.com/libretro-thumbnails/libretro-thumbnails) | Succès, informations sur les jeux et jaquettes |
| [No-Intro](https://no-intro.org), [Redump](http://redump.org), [TOSEC](https://www.tosecdev.org) | Vérifications DAT |
| [RomM](https://github.com/rommapp/romm), [ES-DE](https://es-de.org), [Libretro](https://www.libretro.com), [ScummVM](https://www.scummvm.org), [Homebrew Hub](https://hh.gbdev.io) | Serveur, lanceur et contenu gratuit avec lesquels l’application fonctionne |

## Avertissement

Cette application est uniquement destinée à un usage éducatif. Il vous incombe de vous assurer que vous avez le droit légal de télécharger tout contenu.
