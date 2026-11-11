# Valkyr

[English](README.md) · [Nederlands](README.nl.md) · [Français](README.fr.md) · [Deutsch](README.de.md) · **Español**

**Encuentra, descarga y organiza juegos retro en tu móvil Android o en tu consola portátil.**

Valkyr mantiene una gran lista, con buscador, de los juegos de las fuentes que *tú* añades, los descarga en las carpetas correctas y te ayuda a mantener ordenada tu colección. Funciona con la pantalla táctil *y* con un mando de juegos, así que se siente como en casa en consolas portátiles como Retroid, Anbernic o Kinhank.

> **La app no incluye juegos.** Añade tus fuentes o elige colecciones opcionales en **Fuentes → Más romsets**: homebrew, demos, juegos freeware y programas de prueba ofrecidos por su editor. Tú sigues siendo responsable de lo que descargas.

<table>
  <tr>
    <td align="center" valign="top"><img src="docs/screenshots/home.png" width="210" alt="Inicio con el juego para seguir jugando y un estante por consola"><br><sub>Inicio: sigue donde lo dejaste, con un estante de portadas por consola</sub></td>
    <td align="center" valign="top"><img src="docs/screenshots/game.png" width="210" alt="La página de un juego"><br><sub>Cada juego tiene su propia página con un gran botón Jugar</sub></td>
    <td align="center" valign="top"><img src="docs/screenshots/downloads.png" width="210" alt="La cola de descargas"><br><sub>Sigue tus descargas y toda la cola</sub></td>
    <td align="center" valign="top"><img src="docs/screenshots/more.png" width="210" alt="La pantalla Más"><br><sub>Todo lo demás está a un toque, en Más</sub></td>
  </tr>
</table>

<p align="center"><img src="docs/screenshots/home-landscape.png" width="720" alt="Inicio en horizontal en una consola portátil"><br><sub>En una consola portátil en horizontal, las secciones van en una barra a la izquierda</sub></p>

<p align="center"><img src="docs/screenshots/cloud-hub.png" width="460" alt="El resumen de la nube (títulos y cifras inventados)"><br><sub>El resumen de la nube: RomM, sincronización de partidas y copias de seguridad de un vistazo (nombres y cifras inventados)</sub></p>

*Las capturas de pantalla usan títulos de juegos inventados y archivos vacíos de relleno. Las capturas muestran la app en inglés.*

**Última versión: [6.0.0](https://github.com/Tufein/Valkyr/releases/tag/v6.0.0)** (build de Android 70).

Hasta la 3.9.0 la app se llamaba Dogmatix+. Valkyr 6.0.0 tiene su propio ID de aplicación y se instala junto a la app antigua: usa *Ajustes → Copia de seguridad* en la app antigua y *Restaurar copia* en Valkyr, y luego elimina la app antigua.

## Instalar

1. Abre la **[página de Releases](https://github.com/Tufein/Valkyr/releases)** en tu móvil o en tu consola portátil, o descarga el archivo allí y cópialo al dispositivo.
2. Descarga uno de los dos archivos:
   - **`Valkyr-release.apk`** — *la opción normal.* Las versiones firmadas actualizan la app y conservan sus datos.
   - **`Valkyr-debug.apk`** — una app de prueba separada con sus propios datos, para probar cosas junto a tu app normal.
3. Abre el archivo y permite **«Instalar aplicaciones desconocidas»** si Android te lo pide.

Necesitas **Android 10 o más reciente**. La app no está en Google Play.

## Primer inicio

1. La guía de bienvenida explica lo básico.
2. **Elige tu carpeta de ROMs**: la carpeta donde deben ir tus juegos.
3. **Importa tus fuentes** (un archivo con tus listas de juegos), o sáltate este paso y añade las fuentes más tarde en la pestaña **Fuentes**.
4. Abre la **Biblioteca** y toca un juego (o pulsa **A**) para abrir su página. Allí puedes **Descargar** o **Jugar**.

## ¿Qué puede hacer?

### Encontrar juegos
- **Una sola lista** con los juegos de todas tus fuentes: directorios web, enlaces directos, torrents y enlaces magnet, y tu servidor RomM.
- **Una búsqueda que perdona errores** (acentos, guiones, letras repetidas), con búsquedas recientes, **Sorpréndeme** y **Más como este**. **Buscar todo** también encuentra ajustes, herramientas y pantallas.
- **Filtra** por consola, región, idioma, tipo, género, década y *Nuevo*; **ordena** por nombre, tamaño o novedad. Un **✓** verde marca lo que ya tienes.
- **Una página para cada juego**: imagen, Descargar o Jugar, la mejor versión, favorito, colecciones, compartir, notas, capturas y manuales, y una pestaña *Versiones* que explica cómo puntúa cada versión.
- **Primero las carátulas**: Inicio se abre con un mosaico por consola (sus juegos y cuántos hay en el dispositivo, con una barra), arriba una tarjeta de seguir jugando, y los estantes de carátulas grandes a un toque en el selector de vista; la navegación está en una barra inferior, o en una barra lateral estrecha en una portátil, con Juegos, Descargas, Fuentes y Más. La página del juego empieza con una carátula grande y un botón grande de Jugar o Descargar.
- **Carátulas de varias fuentes**: RomM, libretro-thumbnails, [ScreenScraper](https://www.screenscraper.fr) (con tu propio acceso), TheGamesDB, SteamGridDB e IGDB (cada una con tu propia clave gratuita) y las imágenes de juegos en caché, en el orden que elijas. La app avisa con claridad si falla el acceso o se alcanza un límite diario, y una fuente que solo no estaba disponible se vuelve a consultar más tarde (*Ajustes → Fuentes de carátulas*).
- **Imágenes extra para lanzadores**: pantallas de título y capturas para la carpeta de carátulas de ES-DE (*Herramientas → Portadas para lanzadores*), desactivado por defecto y sin reemplazar nunca archivos que ya tienes.
- **Prueba de rendimiento** (*Herramientas → Prueba de rendimiento*): mide el arranque, la velocidad de búsqueda, la decodificación de carátulas, el almacenamiento y el desplazamiento en tu propio dispositivo, con veredicto Rápido/Bien/Lento y un informe solo con números para compartir.
- **Revisión de emuladores** (*Herramientas → Revisión de emuladores*): qué emuladores conocidos hay instalados, en qué versión, y si para cada consola de tu biblioteca algo instalado puede iniciar sus juegos, con lo que falta. Solo mira las apps instaladas y no instala nada.
- **A continuación y Para ti**: una lista de juegos personal por perfil y sugerencias de tu propia biblioteca que siempre dicen por qué se eligieron.
- **Colecciones y objetivos**: favoritos, listas propias, colecciones por reglas (consola, idioma, género, año, jugado, sin jugar en 3, 6 o 12 meses), objetivos de colección, historial de juego, un resumen anual y los mejores juegos por consola según RetroAchievements.
- **Lista de deseos**: anota los juegos que quieres, recibe un aviso cuando aparezca uno, descárgalos automáticamente y comparte la lista.
- **Importar una lista**: pega o carga un archivo de texto; los juegos encontrados se descargan y el resto va a la lista de deseos.

- **Cuatro vistas**: Resumen, Lista, Portadas y Consolas (una ficha por consola con recuentos), filtros rápidos (descargado, favoritos, completado, RetroAchievements superado/dominado), una barra de búsqueda compacta y opcionalmente *Agrupar versiones*.
- **Manuales**: descarga el manual desde tu servidor RomM y léelo en la app (PDF, imágenes, texto), con un indicador *Sin conexión*.

### Descargar
- **Enlaces directos, torrents y enlaces magnet**, más **TorBox** y **Real-Debrid** opcionales.
- **Varias descargas a la vez**, con pausa, reanudar, reintentar, un límite de velocidad global (sin límite por la noche si quieres) y un límite por servidor.
- **Círculo de progreso total**: cuenta las descargas completadas de toda la cola, de modo que 25 de 100 = 25 %. Solo cuentan las descargas totalmente terminadas, por lo que el círculo sigue avanzando al completarse una.
- **Las descargas interrumpidas continúan** donde se quedaron, y la cola sobrevive a un reinicio.
- **Descargar cuando convenga**: solo con Wi-Fi, mientras carga, esta noche o a una hora fijada. Las descargas se pausan con batería baja o dispositivo caliente y dicen qué esperan.
- **Reintento automático** con cuenta atrás visible, causas de error claras, cambio a otra fuente y un historial de fiabilidad por fuente.
- **Mejor versión** elige tu región e idioma y omite demos; **Descargar todo lo mostrado** comprueba antes el espacio.
- **Comprobaciones**: tamaños de archivo, sumas de comprobación frente a la fuente y descompresión segura de ZIP y 7z (nada se escribe fuera de su carpeta).
- **En todas partes**: botones en la notificación (pausa, reanudar, detener), un mosaico de Ajustes rápidos, un widget de inicio y una vista para la segunda pantalla.

### Jugar
- **Jugar desde la página del juego** con un emulador compatible, recordado por consola o elegido por juego, con una **comprobación de preparación** (archivos, discos, BIOS, emulador), una configuración guiada y un lanzamiento de prueba.
- **ES-DE, iiSU, Daijishō y Pegasus**: una entrada «buscar más juegos» por consola, además de carátulas y descripciones en tu lanzador; para iiSU, Daijishō y cualquier otro lanzador, las carátulas van a una carpeta de medios que eliges tú, con la estructura de archivos que ese lanzador espera (diez estructuras listas, o escribe la tuya).
- **Juegos en tu pantalla de inicio**: fija un juego desde su página y arranca directamente en su emulador; al mantener pulsado el icono de la app también ves tus tres últimos juegos jugados.
- **Listas de RetroArch**: escribe listas `.lpl` para que RetroArch muestre tu biblioteca; las listas existentes solo se completan y el archivo anterior pasa antes a la papelera y el centro de recuperación. Puedes elegir un núcleo predeterminado por sistema para que RetroArch deje de preguntar. Tras mover tu biblioteca a otra tarjeta, un toque reescribe las rutas de tus listas. La página del juego muestra la imagen de tu estado guardado más reciente de RetroArch.
- **Seguir en otra consola portátil** desde la página del juego, cuando tu emulador y tus partidas pueden viajar: primero se muestra el camino de vuelta y la partida pasa por RomM.
- **Los juegos de varios discos** reciben automáticamente su lista `.m3u`.
- **Continuar jugando** en el inicio, desde partidas guardadas de cualquier dispositivo o desde ES-DE.

### Mantener sana la biblioteca
- **Reparar biblioteca**: encuentra archivos vacíos o cortados, juegos de discos con pistas que faltan, listas muertas o duplicadas, restos de descargas y marcas desactualizadas. Cada arreglo se previsualiza y lo que se retira va a la papelera con recuperación.
- **Juegos duplicados** con una limpieza segura y previsualizada; **Mejores versiones** y **Liberar espacio**.
- **Comprobación DAT** con No-Intro, Redump y TOSEC; revisión de **conjuntos de juego**; **comprobación de BIOS**; **actualizaciones y DLC de Switch**.
- **Resumen de la biblioteca, Almacenamiento y Estadísticas** por consola; el **almacenamiento inteligente** puede mover a una tarjeta SD las consolas que casi no juegas y devolverlas.
- **Explorador de archivos** para mirar, renombrar, mover y descomprimir; **exporta** tu colección como CSV o página web.
- **Copiar o enviar juegos**: juegos completos con todos sus archivos a una tarjeta SD o carpeta USB, tu servidor RomM o tu NAS. Antes ves cada archivo, el espacio necesario y lo que ya hay; cada archivo se comprueba en el destino, las partidas se quedan en el dispositivo y no se reemplaza nada. Para una tarjeta que va a otro sistema, las carpetas de consola pueden seguir los nombres de ES-DE, Batocera, Knulli, ArkOS, ROCKNIX, OnionOS o muOS.
- **Recibir juegos por Wi-Fi** (apagado hasta que lo actives): envía tus propios archivos de juego desde un navegador de tu red a la carpeta de consola correcta, con PIN, solo en la red local, con cada archivo comprobado y sin reemplazar nada.
- **Comprobación de salud** con una solución por problema e **informe de escaneo** de las fuentes que fallaron.
- **Fuentes**: añadir a mano, importar y exportar, compartir como códigos QR, catálogo de contenido opcional con contenido de Libretro para 29 sistemas, las aventuras freeware de ScummVM y homebrew gratuito de Game Boy de Homebrew Hub, cada uno con su proveedor, direcciones de reserva y escaneos automáticos en segundo plano que omiten lo que no cambió.

### Seguro por diseño
- **Papelera y centro de recuperación**: juegos retirados, movimientos interrumpidos, archivos reemplazados y copias de partidas se pueden restaurar, cada uno con vista previa. Las copias se verifican antes de retirar nada.
- **Historial de acciones** con deshacer y un diario que sobrevive a un fallo.
- **Las actualizaciones desde la app** se comprueban (suma de comprobación, paquete, versión y firmante) antes de que Android las instale.
- **Informar de un problema** crea un informe privado y editable cuyo contenido eliges; se quitan rutas, direcciones de servidor, claves y tokens, y la app no envía nada.

### Nube y sincronización
- **RomM**: enviar descargas terminadas y juegos completos de varios archivos, usarlo como fuente, ver juegos, géneros y capturas, sincronizar estado de juego, valoración y favoritos, y traer los BIOS que faltan.
- **Sincronización de partidas y partidas en la nube**: tus partidas y estados viajan entre consolas y RomM, incluidas las tarjetas de memoria de DuckStation, las carpetas de guardado de PPSSPP y, si lo eliges, tarjetas completas de Dolphin y NetherSX2, carpetas de guardado de GameCube de Dolphin y partidas de Wii; si hay conflicto se te pregunta y las copias reemplazadas se guardan 30 días.
- **Tu propia nube (WebDAV)**: copias cifradas (Nextcloud, ownCloud, Synology y otras) y sincronización entre dispositivos de favoritos, lista de deseos y colecciones.
- **RetroAchievements**: progreso e insignias por juego, tu perfil y qué juegos tienen logros.

- **Vincula RomM con un código corto**; los juegos enviados desde la web de RomM llegan a tu cola de descargas; *Usar RomM* apaga y enciende cada llamada a RomM.
- **Partidas más seguras**: copia antes de cada sobrescritura, conflictos que decides tú, ranuras en una tarjeta y un *modo carpeta (Syncthing)*.
- **DukeStation** es un emulador de PlayStation conocido, con reanudar desde una ranura.

### Perfiles
- **Los perfiles** ocultan consolas o juegos con ciertas etiquetas (un perfil infantil), con PIN opcional. Favoritos, emuladores, notas y A continuación se guardan por perfil.

### Hecho para consolas portátiles
- **Todo funciona con mando** (cruceta, A/B/X/Y, gatillos); las pistas en pantalla se ajustan a Xbox, Nintendo o PlayStation. Mantén **SELECT** para un menú rápido.
- **Horizontal y vertical**, una vista de **segunda pantalla** y un **modo TV** con texto más grande y teclas de mando a distancia.
- **Tema claro, oscuro o negro puro**, doce colores de acento y Material You, carátulas, tamaño de texto ajustable y animaciones desactivables.
- **Siete idiomas**: inglés, neerlandés, alemán, francés, español, italiano y portugués.
- **Centro de segunda pantalla** con seguir jugando, juegos recientes, chips de consola, la cola y chips de batería/calor, y una breve guía de la app.
- **Configurar una consola portátil nueva**: una lista de pasos desde la instalación nueva hasta una portátil lista (ajustes, carpeta de juegos, emuladores, juegos que faltan, partidas, lanzador); cada paso abre la pantalla que lo hace.
- **Copia y restauración** de ajustes, fuentes, favoritos, colecciones y lista de descargas en un solo archivo.

## Usar un mando

| Botón | Qué hace |
|---|---|
| D-pad | Moverte |
| **A** | Elegir / abrir la página del juego; allí descargar o jugar |
| **B** | Volver un paso atrás |
| **X** | Información del juego |
| **Y** | Buscar |
| **Select** | Marcar o desmarcar un juego con estrella |
| **LB / RB** | Cambiar entre los filtros y la lista |
| **ZL / ZR** | Sección anterior / siguiente |
| **R3** | Ocultar o mostrar el panel de filtros |

Todo funciona también con la pantalla táctil. Las ayudas solo se muestran mientras hay un mando conectado.

## Conviene saber

- Los juegos eliminados van a la papelera. Restaura o vacía en Herramientas → Papelera y recuperación. El espacio se libera al vaciarla. El explorador también permite recuperar por defecto; debes elegir la eliminación definitiva en la confirmación.
- **Un archivo de copia de seguridad contiene tus claves de cuenta** (TorBox, Real-Debrid, RomM). Mantenlo en privado.
- **La ventana de información del juego se queda vacía en las descargas que se ofrecen aquí**, porque necesita una clave gratuita de una base de datos de juegos que se añade cuando se crea la app.
- Valkyr no busca juegos por su cuenta. Solo lee las fuentes que **tú** añades.
- **¿Algo no funciona?** *Ajustes → Aplicación y actualizaciones → Informar de un problema* crea un informe privado que puedes pegar en una incidencia de GitHub; antes se quitan rutas, direcciones de servidor, claves y tokens.

## Para desarrolladores y usuarios avanzados

- **[TECHNICAL.md](TECHNICAL.md)**: cómo está hecha la app, cómo decide el buscador de duplicados, estructura de carpetas, deep links y tecnologías (en inglés).
- **[FRONTENDS.md](FRONTENDS.md)**: configurar ES-DE, iiSU, Daijishō y Pegasus.
- **[Hoja de ruta](docs/ROADMAP.md)**: ideas para futuras versiones.
- **[Versiones](https://github.com/Tufein/Valkyr/releases)**: descargas y sumas de comprobación.

## Créditos

Valkyr está hecho por [Tufein](https://github.com/Tufein). Se basa en estos proyectos de código abierto y fuentes de datos:

| Qué | Para qué |
|---|---|
| [Jetpack Compose / AndroidX](https://developer.android.com/jetpack/androidx), [Kotlin coroutines](https://github.com/Kotlin/kotlinx.coroutines), [Dagger Hilt](https://github.com/google/dagger) | La propia app (Apache-2.0) |
| [Gson](https://github.com/google/gson), [Coil](https://github.com/coil-kt/coil), [jsoup](https://jsoup.org), [ZXing](https://github.com/zxing/zxing) | JSON, imágenes, lectura de directorios web, códigos QR |
| [7-Zip-JBinding](https://github.com/borisbrodski/sevenzipjbinding), [libtorrent4j](https://github.com/aldenml/libtorrent4j) | Descomprimir archivos, torrents |
| [Space Mono](https://fonts.google.com/specimen/Space+Mono), [Manrope](https://fonts.google.com/specimen/Manrope) | Tipografías (SIL Open Font License) |
| [RetroAchievements](https://retroachievements.org), [RAWG](https://rawg.io), [TheGamesDB](https://thegamesdb.net), [SteamGridDB](https://www.steamgriddb.com), [IGDB](https://www.igdb.com), [ScreenScraper](https://www.screenscraper.fr), [libretro-thumbnails](https://github.com/libretro-thumbnails/libretro-thumbnails) | Logros, información de juegos y portadas |
| [No-Intro](https://no-intro.org), [Redump](http://redump.org), [TOSEC](https://www.tosecdev.org) | Comprobaciones DAT |
| [RomM](https://github.com/rommapp/romm), [ES-DE](https://es-de.org), [Libretro](https://www.libretro.com), [ScummVM](https://www.scummvm.org), [Homebrew Hub](https://hh.gbdev.io) | Servidor, lanzador y contenido gratuito con los que funciona la app |

## Aviso legal

Esta app es solo para fines educativos. Eres responsable de asegurarte de que tienes derecho legal a descargar cualquier contenido.
