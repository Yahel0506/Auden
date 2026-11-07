# Auden Web — plan para llevar Auden al reproductor web

El reproductor web (`web/`) llegó de BitChord y no tiene ningún cambio de Auden:
`git log HEAD ^upstream/main -- web` da 0 commits. Este plan recorre **cada cambio que
Auden hizo sobre BitChord en Android** (77 commits propios, de `ce182619` a `1a0b83ae`)
y dice cómo se lleva a la web, qué se adapta y qué no tiene sentido allí.

Igual que `AUDEN_ROADMAP.md`, las fases van por dependencias, no por importancia, y cada
una deja la web usable y desplegable.

---

## 0. Punto de partida en la web

| Área | Estado en `web/app` | Consecuencia |
|---|---|---|
| Persistencia | Solo `zustand/persist` (localStorage) en algunos ajustes, e IndexedDB (`idb`) solo para el análisis de Automix (`playback/automix/db.ts`). | No hay base de datos de biblioteca. Va primero. |
| Biblioteca | `data/music/library.ts`: secciones de la cuenta de YouTube Music. Sin sesión no hay biblioteca. | Biblioteca local nueva. |
| Fuentes de audio | `Track` exige `videoId`. El motor (`playback/youtube/engine.ts`) solo reproduce YouTube (Shaka + googlevideo). | El motor necesita una segunda fuente (archivo local / clip del DJ). |
| Historial | `historyReporting.ts` informa a YouTube; no guarda nada localmente. No hay Replay ni estadísticas. | Hace falta un registro de escuchas propio (lo usan Home y el DJ). |
| Letras | 18 proveedores, orden y activación por ajuste (`lyrics/settings.ts`), selector de proveedor en el panel. Sin guardado por canción ni offset por canción. | Se amplía, no se rehace. |
| Spotify | Vía extensión: playlists y sus pistas, emparejado con `data/match/trackMatcher.ts` en cada apertura. | Faltan álbumes, artistas, errores claros y emparejados recordados. |
| Extensión | WXT, Chromium + Firefox, nombre y orígenes de BitChord (`bitchord.in`, `bitchord-8bw.pages.dev`). | Rebrand y orígenes propios. |
| Despliegue | Cloudflare Pages (`pnpm build:site`). | Pages Functions sirven de backend mínimo (clave de Fish Audio). |

### Principios
- **Código de Auden aparte:** todo lo nuevo en `web/app/src/auden/` (db, library, dj, home…),
  con cambios mínimos y localizados en los archivos de upstream. Así un merge de
  BitChord sigue siendo manejable, como en Android.
- **Misma forma de datos que Android:** el esquema de IndexedDB copia `AudenDb` v8 y la
  copia de seguridad usa el mismo `auden-backup-FECHA.json`. Un backup del móvil se puede
  importar en la web y al revés.
- **Lógica pura portada con sus tests:** `DjPlanner`, `DjWriter`, `DjTaste`,
  `DjRequestParser`, `HomeComposer`, `LocalShelves`, `PlaylistFiles`, `PlaylistMatcher` y
  la separación de artistas se traducen a TypeScript con sus tests (`DjTest.kt`,
  `HomeTest.kt`, `PlaylistFilesTest.kt`… → Vitest).
- **Rendimiento:** el equipo de pruebas es un iGPU a 120 Hz donde los `blur` grandes y los
  canvas cuestan. Un solo canvas por efecto, DPR limitado, pausa con la pestaña oculta y
  blur reutilizado en vez de apilado.
- **Equivalencias de plataforma:** WorkManager → Web Worker o tarea al volver a la pestaña;
  notificación → toast en la app (y `Notification` solo si el usuario la permite);
  "datos móviles" → `navigator.connection.saveData`/`type` donde exista.

---

## 1. Inventario: cada cambio de Auden y su destino en la web

Leyenda: **Sí** = se porta tal cual · **Adaptado** = misma idea, otra implementación ·
**No** = no aplica a la web.

| Cambio en Android (commit) | Web | Fase |
|---|---|---|
| Rebrand a Auden, `AudenDb` (`ce182619`) | Sí (nombre, logo, IndexedDB) | 0 |
| Logo A-waveform, iconos Minimal / Liquid Glass (`0ae0ff17`) | Adaptado: favicon, logo, iconos de la extensión, manifest PWA | 0 |
| Release y actualizador siguen el repo de Auden (`9aaf0e13`) | Adaptado: despliegue propio y aviso de versión de la extensión | 0, 11 |
| README de Auden (`2dff21da`) | Sí: `web/README.md` | 11 |
| Playlists locales sin sesión (`aefd3523`, `e7dd997a`) | Sí | 3 |
| Indexador incremental, pestañas Géneros y Carpetas (`754a061b`) | Adaptado: File System Access + Worker | 2 |
| Importar/exportar M3U8, XSPF, Auden JSON (`0a2c451f`) | Sí | 3 |
| Quitar canciones, selector con pertenencia, progreso al guardar (`f9dd732d`) | Sí (progreso como toast) | 3 |
| Enlazar canciones de streaming con archivos del dispositivo (`f02d435d`, `43697f3c`) | Sí | 2 |
| Identificar música local, separar artistas, duplicar playlists, portadas mosaico (`94c46945`, `fdcf8fb0`) | Sí | 2, 3 |
| Álbum guardado y descargado sale una vez; marca de descargada en playlists (`9e033c69`) | Adaptado: marca "en el dispositivo" para archivos locales | 2 |
| Mantener playlists guardadas al día (`11a4164d`) | Adaptado: al volver a la pestaña, cada 6 h | 4 |
| Spotify: errores claros, emparejados recordados y corrección manual, álbumes y artistas (`de181456`) | Sí (nuevos métodos en la extensión) | 6 |
| Spotify: no destruir la WebView del login (`9a7c791f`) | No (la web no tiene WebView) | — |
| Letras: modelo `LyricsArtifact`, variantes por proveedor, elección y offset por canción, búsqueda por lotes (`876da771`, `541f3c52`) | Sí | 5 |
| Gestionar letras desde la hoja de proveedor (`7be71595`, `152cb265`) | Sí | 5 |
| Elegir proveedor muestra sus letras; solo "guardar" las conserva (`21bd8140`) | Sí | 5 |
| Seguir el orden de proveedores en descargas y re-buscar letras de playlists (`d62cc66d`) | Sí (orden: verificar; re-búsqueda por lotes) | 5 |
| Letras offline desde sidecar (`1a382b4a`, `717bb878`, `77bf46e0`) | Adaptado: leer `.lrc`/`.ttml` junto a archivos locales y letras embebidas | 2, 5 |
| Home híbrido (`db5ad883`) | Sí | 7 |
| Auden DJ completo (`7f44f390` … `1a0b83ae`) | Adaptado: lógica igual, voz por proxy | 8 |
| DJ: voces sherpa-onnx, R8, build de TV (`299873fd`, `47ad64ac`, `0363919b`) | No (ya eliminado en Android; la web usa Fish Audio) | — |
| Reproductor: fondo con el mismo blur en reproductor, letras y cola; velo más suave; disolución más larga; mantener la imagen anterior hasta tener la siguiente; portada animada en miniaturas; vídeo con fundido (`5c18c707` … `1b6e9e24`) | Adaptado al reproductor estilo Apple Music web | 9 |
| Reproductor: huecos, transporte junto al scrubber, la portada crece (`c0620266`, `3782e630`) | Adaptado solo donde encaje en el diseño web | 9 |
| Quitar la opción de mesh clásico (`4740c313`) | No aplica si la web no la tiene (verificar en Fase 9) | 9 |
| Toques mientras se cierra el reproductor, rebote iOS (`fix(player)`, `fix(overscroll)`) | Revisar el equivalente en móvil web | 9 |
| Backup con biblioteca, letras y emparejados (`d300ebc2`) | Sí, compatible con Android | 10 |
| Portadas de playlist, quitar en sync de Spotify, descargas borradas, dónde vive una playlist nueva (`9d51eeda`) | Sí, salvo lo de descargas | 3, 4 |
| Descargas: cola persistente, carpetas por plantilla, tags, letras y `cover.jpg` al lado, AAC sin Opus, auditoría (`d8159e5c`, `3653f1b3`, `5ae18f98`, `c408513a`, `70c00563`…) | No tal cual. Ver "Descargas" al final | 12 |
| Arreglos de compilación, lint, regex de Android, helpers de Copilot | No | — |

---

## Fase 0 — Cimientos

### 0.1 Rebrand de la web y la extensión
- Nombre visible "Auden" en el título, el shell, la página de la extensión y los textos.
  Logo A-waveform en `app/public/favicon.svg` (de él salen los iconos de la extensión con
  `pnpm --dir web/extension icons`).
- Extensión: `name` y `description` en `extension/wxt.config.ts`, **clave e id propios**
  (`dev-key/` nuevo) para poder instalarla junto a la de BitChord, y
  `BITCHORD_SITE_ORIGINS` con el dominio de Auden.
- `VITE_ANDROID_APP_URL` → releases de `Yahel0506/Auden`.
- Las variables `BITCHORD_*` y el paquete `@bitchord/*` **no** se renombran, por la misma
  razón que el paquete Kotlin: cada merge de upstream sería un conflicto.

### 0.2 `AudenDb` en IndexedDB
- `auden/db/` sobre `idb`, con versiones y migraciones como Room. Stores que copian
  `Entities.kt` (v8): `track` (índices `videoId`, `localKey`, `spotifyId`), `playlist`
  (con `coverUrl`), `playlist_entry` (orden estable, `entryId` propio), `lyrics_variant`
  (`[songKey, provider]`), `song_lyrics_pref`, `play_event`, `spotify_match`.
- DAOs finos + hooks de TanStack Query con invalidación por store (y `BroadcastChannel`
  para que dos pestañas se enteren).
- `navigator.storage.persist()` al crear la primera playlist o la primera carpeta, para
  que el navegador no la borre.

### 0.3 Registro de escuchas
- `auden/stats/recorder.ts`: un `play_event` por reproducción (con el umbral de Android:
  una escucha cuenta a partir de cierto tiempo), enganchado al estado de `state/player.ts`.
  Lo necesitan el Home híbrido y el DJ.

**Entregable:** web con marca Auden, extensión instalable junto a la de BitChord y base de
datos vacía lista.

> **Estado de la Fase 0: HECHA** (sin commitear). `web/app/src/auden/{db,stats}`. Decisiones: la escucha vive en `listening` (buckets mensuales `StoredBucket`, como Android), no en `play_event` (declarado, nunca escrito); filas con los nombres de campo de Room (`localUri` = `auden-file://<rootId>/<ruta>`); una pausa no reinicia el progreso de la pista (Android sí); canal del puente `auden-bridge`/`auden-events` e id de Firefox `extension@auden.app`. Antes de subir `DB_VERSION` a 2, algo debe suscribirse a `onDbBlocked` y ofrecer recargar.

---

## Fase 1 — Motor con varias fuentes

Requisito de los archivos locales (Fase 2) y de las locuciones del DJ (Fase 8).

- `Track` pasa a tener una fuente: `youtube` (como hoy), `local` (archivo indexado) y
  `clip` (audio generado, el DJ). Lo que hoy lee `videoId` sigue igual para `youtube`.
- Un `FileDeck` junto al deck de Shaka: `HTMLAudioElement` con `blob:` URL del archivo,
  conectado al mismo grafo de Web Audio que usa Automix, para que el crossfade, el volumen
  y el ecualizador funcionen igual.
- Cola, historial, Discord y Listen Together distinguen la fuente: un archivo local no se
  comparte en una fiesta (la regla de Android para el Direct Source Stream) y no se
  informa a YouTube.
- Al reproducir una pista con archivo enlazado y `videoId`, gana el archivo local.

> **Estado de la Fase 1: HECHA** (sin commitear). `playback/{source,deck,fileEngine,clips,superseded}.ts`. Decisiones: un `MediaDeck` por elemento de audio (YouTube o archivo, nunca los dos); una pista de YouTube con archivo enlazado sigue siendo `youtube` (mismo `videoId`, se informa al historial) y el archivo gana al cargar, salvo en una fiesta; archivos y clips no se comparten ni se informan; Automix solo mezcla dos pistas de YouTube (con Automix activado cualquier otro par es un corte; con Automix apagado rige el fundido simple); un clip es siempre un corte. Costuras: `setLocalResolver()` (Fase 2; debe responder "sin enlace" en ~1 ms desde un índice en memoria y solo puede llamar a `queryPermission`), `registerClip()/releaseClip()` (Fase 8). Antes de la Fase 2 cerrar: Automix + archivo enlazado (analizar los bytes del blob o dejar `current()` en null cuando el deck esté en modo archivo).

---

## Fase 2 — Biblioteca local (estilo Namida)

### 2.1 Carpetas e indexador
- **Chromium:** `showDirectoryPicker()`; los handles se guardan en IndexedDB y en cada
  sesión se vuelve a pedir permiso con un toque ("Reconectar carpeta").
- **Firefox:** no tiene File System Access. Alternativa: `<input webkitdirectory>` por
  sesión (sin persistencia del acceso; el índice sí se conserva y se re-vincula por
  ruta, tamaño y fecha) u, opcionalmente, copiar archivos a OPFS. Se documenta la
  diferencia en la página de ajustes.
- Indexador en un Web Worker, incremental por `lastModified` + tamaño, reanudable, que
  marca nuevos, cambiados y borrados. Tags con `music-metadata` (versión de navegador),
  incluidas letras embebidas y `.lrc`/`.ttml` junto al archivo.
- Pestañas en la Biblioteca (sidebar): Canciones, Artistas, Álbumes, Géneros y Carpetas,
  disponibles **sin sesión** de YouTube Music.
- Separación de artistas con los mismos separadores y la misma lista de nombres que no
  se parten que en Android.

### 2.2 Identificación y enlaces
- Cascada de `TrackIdentifier`: id de YouTube en el nombre del archivo (con las mismas
  protecciones contra palabras y contadores), luego búsqueda en YouTube Music juzgada por
  `trackMatcher.ts` (ya existe en la web).
- Coincidencias dudosas en una hoja de revisión al estilo Apple; progreso en un banner
  (`IdentifyBanner`).
- Enlaces streaming ↔ archivo por id y por metadatos: marca "en el dispositivo" en
  álbumes y playlists, reproducción local, y un álbum guardado y local sale una sola vez.

> **Estado de la Fase 2: HECHA** (sin commitear). `web/app/src/auden/library/` (`artistSplit`, `youtubeIdInName`, `identity`, `groups`, `roots`, `linkIndex`, `localResolver`, `scan/`, `identify/`, `ui/`), `pages/LocalLibraryPage.tsx` (ruta `/local`, "On This Device" en la barra lateral, también sin sesión), `auden/db/{libraryDao,schema}.ts` (**`DB_VERSION` = 2**: stores `library_root` y `local_art`; `upgradeAudenDb` extraída y probada desde un v1 congelado; `DbReloadBanner` en el shell). Decisiones: el índice lo hace un Worker (`music-metadata`, `parseBlob`, sin leer el archivo entero) que nunca escribe ni pide permisos; la página escribe por lotes (búsqueda y escritura en una transacción, id estable `auden:local:<id>`); un recorrido incompleto, vacío o (Firefox) que no se parece a la carpeta indexada **no borra nada**; una fila con `videoId` se desvincula en vez de borrarse. Chromium guarda el handle y re-pide permiso con un toque ("Reconectar carpeta", `requestPermission` solo desde el click); Firefox pierde el acceso al recargar pero conserva el índice y se re-vincula por ruta/tamaño/fecha (documentado en Ajustes). Añadir una carpeta nunca se une a una raíz por nombre (en Firefox "Reconectar" exige el mismo nombre). Enlaces por `videoId` y por metadatos (`sameSong`, como `SongIdentity`); `hasLink` es síncrono (mapas en memoria) y solo es cierto si la carpeta se puede leer ahora; la marca "en el dispositivo" sale aunque la carpeta esté desconectada. **Automix + archivo enlazado:** `current()` es null si el deck está en modo archivo, `next()` es null si la pista sonará desde un archivo y `loadBack` fuerza YouTube (`preferStream`): con Automix activado, una pista enlazada a un archivo es un corte; una biblioteca totalmente enlazada no mezcla con Automix (analizar los bytes del archivo queda fuera). Identificación: nombre del archivo con id, luego búsqueda en YouTube Music juzgada por `trackMatcher.best` (3 en paralelo, reanudable, cancelable, reintenta sin saltarse filas); lo dudoso va a "Review". Portadas: ≤256 px en `local_art` (bytes), filas con `auden-art://<clave>`, URLs `blob:` por sesión; las estadísticas nunca guardan `blob:`. **Fuera de esta fase (decidido):** letras incrustadas y `.lrc`/`.ttml` junto al archivo (Fase 5), mezclar los álbumes locales con la cuadrícula de álbumes de YouTube Music, y que `Play Next`/`Add to Queue` en una fiesta solo se desactivan (con explicación) para archivos. La Fase 7 (Home) debe filtrar `isSyntheticId` antes de llamar a YouTube con ids de estadísticas. Verificado en el navegador sobre OPFS y `File[]` sintéticos; **no verificado**: identificación real (sin extensión ni red), el re-permiso real de Chromium, etiquetas/portadas de MP3 o FLAC reales (solo WAV con INFO) y Media Session con portada `blob:`.

---

## Fase 3 — Playlists locales e importación

- Playlists en `AudenDb` que mezclan pistas locales y de YouTube Music: crear, renombrar,
  reordenar (arrastrar), duplicar, quitar canciones, portada mosaico 2×2 generada en un
  canvas y guardada como blob.
- Selector "Añadir a playlist" que muestra en cuáles está ya la canción.
- Guardar playlists y álbumes de YouTube Music y Spotify como copia local (`CloudSaver`).
- "Nueva playlist": con sesión de YouTube Music, elegir entre este dispositivo y YouTube
  Music, como en el menú de cristal de Android.
- Importar/exportar M3U8 (rutas relativas a la carpeta de música), XSPF y Auden JSON
  (sin pérdida), portando `PlaylistFiles`, `PlaylistMatcher` y sus tests. Exportar descarga
  el archivo; importar acepta arrastrar y soltar. Las pistas sin resolver se guardan como
  pendientes.

> **Estado de la Fase 3: HECHA** (sin commitear). `web/app/src/auden/playlists/` (`files` = formatos M3U8/XSPF/Auden JSON, `matcher`, `reconcile`, `store`, `transfer`, `covers`, `cloudSaver`, `cloudDeps`, `actions`, `ui/`), páginas `LocalPlaylistPage` (`/local/playlist/:id`) y la pestaña "Playlists" de `/local`; "Add to Playlist on This Device" en el menú de pista (funciona sin sesión y muestra con una marca las playlists que ya tienen la canción); "Save to Device" en álbum, playlist de YouTube Music y playlist de Spotify; lista de playlists locales en la barra lateral. **Sin cambio de versión de la BD** (sigue en 2). Decisiones: las operaciones son transacciones únicas sobre `playlist`/`playlist_entry`/`track`; mover es por `entryId` ("antes de esta entrada", renumeración en sitio, sin borrar y reinsertar como Android, así los `entryId` son estables y dos pestañas no se pisan); añadir y crear deduplican por identidad (misma fila o `sameSong`); una canción de YouTube sin fila recibe una (`ensureTrackRow`), con el archivo enlazado si lo hay; ids `spotify:`/`auden:clip:` se rechazan y nunca se guardan miniaturas `blob:`. **Importación:** archivos de hasta 8 MiB y 10 000 líneas, XML leído solo con etiquetas (sin DTD), `videoId` validado con `^[A-Za-z0-9_-]{11}$`, resolución por id → ruta exacta → mismo nombre de archivo → título+artista(+duración ±2 s); lo no resuelto **no se guarda** (se cuenta en el resumen del banner, como Android; las filas "pendientes" quedaron fuera); búsqueda en YouTube Music opcional (casilla), máx. 500 búsquedas, 4 a la vez, con progreso y cancelación. Exportar M3U8 usa rutas relativas a la carpeta de música (solo canciones con archivo), XSPF y Auden JSON conservan también las de streaming. Portada mosaico 2x2 de 600 px con las 4 primeras miniaturas distintas (mínimo 2), guardada en `local_art` con clave `playlist:<id>:<hash>` y reemplazada/borrada dentro de la misma transacción (`setPlaylistCover`); la portada propia de una copia de la nube manda sobre el mosaico. **Copias de la nube (`saveFromSource`):** la copia se busca por `sourceId` (YouTube sin `VL`; Spotify `spotify:playlist:<id>`) bajo un Web Lock por fuente; YouTube solo añade; Spotify añade y **quita solo lo que el original dejó de listar y lo puso antes una sincronización** (cada entrada guarda `fromSpotifyId`: así lo añadido a mano nunca se borra, ni siquiera si luego comparte fila con una pista de Spotify, que era el fallo de Android) y solo tras una lectura completa con al menos un emparejamiento; un fallo de emparejamiento nunca borra nada; una lista vacía no escribe. Un emparejamiento manual de Spotify cambia la entrada en las copias (`manualMatch`). **No verificado:** descargar/guardar de verdad desde YouTube Music o Spotify (sin extensión), CORS de las miniaturas reales de YouTube para el mosaico, arrastre con el ratón (se probó `moveEntry` y la regla `dropBefore`), crear una playlist en YouTube Music desde "New Playlist".

---

## Fase 4 — Sincronización automática de copias

- Al volver a la pestaña (`visibilitychange`), como mucho cada 6 h: las copias de
  playlists y álbumes de YouTube Music y Spotify añaden lo nuevo; nunca se escribe en esos
  servicios.
- Spotify: también se quitan las canciones que salieron de la playlist original, pero se
  conservan las añadidas a mano; se guarda la portada de la playlist.
- Un toast de resumen solo si llegó algo. Ajustes: activado y "también con ahorro de datos".

> **Estado de la Fase 4: HECHA** (sin commitear). `auden/playlists/autoSync.ts` (+ `manualMatch.ts`), montado en `AppShell` (`startAutoSync`); ajustes "Keep saved playlists up to date" (por defecto activo) y "Also when data saver is on" (por defecto apagado) en Ajustes > Local Library. Reglas (las de `PlaylistAutoSync.kt`): al volver la pestaña a primer plano se espera 15 s (si se oculta de nuevo, se cancela), y se sincroniza cada copia con `sourceId` como mucho cada 6 h; un fallo se reintenta a la hora (no en cada cambio de pestaña); una copia de Spotify sin sesión se salta **sin sellarla**; con Save-Data/conexión móvil (`navigator.connection`, solo Chromium) no se sincroniza salvo que se active la casilla; un Web Lock `auden-autosync` entre pestañas; nunca se escribe en YouTube Music ni en Spotify (los lectores son de solo lectura); un solo resumen en el banner de trabajos ("N new songs added to your saved playlists") y solo si llegaron canciones (las bajas no se anuncian). Las marcas de tiempo viven en `localStorage` (`auden:autosync:stamps`). **No verificado:** una sincronización real contra YouTube Music/Spotify (en el navegador se probó con una fuente simulada, el resumen y la regla de 6 h; el disparo por visibilidad se vio actuar sobre el lector real, que falló sin red y quedó sellado como fallo).

---

## Fase 5 — Letras

- **Variantes por canción** en `lyrics_variant`; la elección del proveedor en el panel se
  recuerda por canción (`song_lyrics_pref`). Elegir un proveedor solo muestra sus letras;
  el botón "Guardar" las conserva.
- **Offset por canción**, sumado al global, con selector "esta canción / todas" en el
  ajuste de sincronía.
- **Gestionar letras:** usar, previsualizar, borrar y buscar en otro proveedor, desde la
  hoja de proveedor del reproductor y desde el menú de la canción.
- **Por lotes:** "Buscar letras" para un álbum o una playlist entera, con progreso en un
  banner, respetando el orden de proveedores del usuario.
- **Proveedores extra a guardar** (ajuste "Letras adicionales"), como en Android.
- Exportar la letra activa como `.lrc` (con `[offset:]`). Sustituye al sidecar de las
  descargas.
- Verificar que la web ya sigue el orden de proveedores (el fix `d62cc66d`).

> **Estado de la Fase 5: HECHA** (sin commitear). `web/app/src/auden/lyrics/*`, `auden/jobs/*`, `shell/LyricsManage.tsx`, `lyrics/{repository,useLyrics,settings}.ts`. Decisiones: clave de la canción = `videoId` (para un archivo sin enlace, su `localUri`; con enlace, el `videoId` del enlace, como Android); el proveedor se guarda con el nombre del enum de Android (tabla en `auden/lyrics/names.ts`; los desconocidos, p. ej. `SIMP_MUSIC`, se decodifican igual); formato: TTML solo si hay sincronía por palabras, LRC para línea y texto plano (el lector TTML de Android descarta líneas sin tiempo), ENHANCED_LRC si es largo; el proveedor elegido se recuerda por canción aunque no esté guardado (con respaldo a la cadena completa si no responde); el desfase global (±5000 ms, paso 100) y el de la canción siguen la convención de Android (cursor = posición − desfase) y se exportan como `[offset:−N]`; el lote (3 a la vez, 40 s por canción, plazo de 37 s que devuelve lo mejor ya llegado, sin letras extra, `fresh` para ignorar un «no encontrado» cacheado) usa el banner de trabajos genérico `auden/jobs` (muestra el más reciente); «Letras extra a guardar» solo aplica a «Save these lyrics». Revisión de Opus: dos rondas; los hallazgos B1–B3 e I1–I5 están corregidos y cubiertos por pruebas (las de `fresh`, el plazo y el orden de hooks se comprobaron con mutaciones). Sin verificar en el navegador: «Saved Lyrics…» desde una fila de canción real, la carga de todas las páginas de una playlist, el desfase con Automix/fundido. Antes de la Fase 10: un proveedor recordado pero no guardado en la web bloquearía que una descarga de Android se haga activa.

---

## Fase 6 — Spotify

- Errores tipados como `SpotifyException`: sin sesión, sesión caducada, límite de
  peticiones y sin conexión, con reintento (token nuevo una vez, backoff en límites) y un
  mensaje que dice qué hacer.
- Emparejados recordados en `spotify_match`; los elegidos a mano nunca se reemplazan.
  "Corregir emparejado" en el menú de la fila abre una búsqueda de YouTube Music.
- Página de Spotify con playlists, álbumes guardados (se abren en YouTube Music) y
  artistas seguidos, en caché para abrir al instante. Requiere métodos nuevos en la
  extensión (`spotify.albums`, `spotify.artists`) en `extension/` y en el contrato de
  `shared/`.
- "Sincronizar playlists guardadas" a mano, además de la automática de la Fase 4.

> **Estado de la Fase 6: HECHA** (sin commitear). Puente: `web/shared/src/bridge/{protocol,client}.ts` (campo aditivo `detail` en `BridgeError` sin subir el protocolo, `SpotifyErrorKind`, capacidad `spotify.library`, `spotify.albums/artists`, `user.id` y `problemKind`); extensión: `lib/{spotify,spotifyErrors,spotifyLibrary}.ts`; app: `data/spotify/{failure,retry,matches,songJson,libraryCache,open,matchDialog,queries,api}.ts`, `shell/SpotifyMatchDialog.tsx`, `pages/Spotify{,Playlist}Page.tsx`. Decisiones: errores como Android (SIGNED_OUT/EXPIRED/RATE_LIMITED/NETWORK/UNKNOWN) pero el reintento es por página y solo para límite de peticiones (Retry-After, tope de 8 s; si pide más se muestra) y red (1 s, 2 s); la caducidad no se reintenta porque la extensión ya renueva el token una vez; un 403 solo es «caducada» en la llamada de perfil; el fallo del paso de token («temporalmente falla») no es un cierre de sesión; enfriamiento en la extensión tras un 429 (también el del token); las coincidencias se recuerdan en `spotify_match` con la forma `Stored` de Android (`isVideo` siempre booleano, estados AUTO/MANUAL/MISSING, no encontrada se reintenta a los 3 días, un fallo de búsqueda NO se guarda, la elección manual nunca se reemplaza ni siquiera por una búsqueda en curso); la caché de biblioteca vive en localStorage, de una cuenta a la vez, y solo se borra ante un cambio confirmado (desconexión, cierre de sesión del navegador, otra cuenta), nunca por un fallo pasajero (a diferencia de Android, que no la borra nunca); la extensión recuerda la última cuenta leída (resumen del `sp_dc`, no la cookie) para que un reinicio del worker o «Try Again» durante un límite no parezca un cierre de sesión. Revisión de Opus: PRE_FLIGHT y REVIEW (un bloqueante, B1, y un importante, I1, corregidos y comprobados con mutaciones; no hubo tercera revisión). Pendiente / sin verificar: la extensión real con Spotify real, las respuestas reales de Álbumes y Artistas de la web (los parsers son portes de Android probados con sus ejemplos), la semántica del 403, la ruta de éxito del buscador de «Fix match» (necesita YouTube) y Firefox. «Sincronizar playlists guardadas» a mano y la sincronización automática quedaron para la sesión de las Fases 3 y 4 (costura: `resolveSpotifyTrack` y `onManualMatch`). Antes de la Fase 10: Android guarda `songJson` sin `isVideo` cuando es falso; la web siempre lo escribe (Android lo decodifica igual).

---

## Fase 7 — Home híbrido

- Porta `HomeComposer` y `LocalShelves`: En bucle, Redescubrir, Añadido recientemente,
  Tus playlists, Mix de artista y Biblioteca aleatoria, mezclados con el feed de YouTube
  Music según un peso 0–100.
- Sin conexión, o sin sesión, solo estantes locales; los vacíos se ocultan; Home nunca
  queda vacío. Cada estante se puede apagar en Ajustes. Tocar una canción de un estante
  local reproduce ese estante.
- Depende de las Fases 0.3 (escuchas), 2 y 3.

> **Estado de la Fase 7: HECHA** (sin commitear). `web/app/src/auden/home/{shelves,composer,data,settings,useLocalHome}.ts` (+ `ui/LocalShelfView.tsx`), `lib/useOnline.ts`, `pages/HomePage.tsx` reescrita alrededor de `compose`, sección "Home" en Ajustes (deslizador 0-100 en pasos de 5, por defecto 45, y un interruptor por estantería, todas activas). Estanterías: On repeat, Rediscover, Recently added, Your playlists, Artist mix, From your library (reglas de `LocalShelves.kt`: 20 por estantería, mínimo 4, playlists mínimo 1; títulos EN/ES). `compose` es el de `HomeComposer.kt` línea a línea (sin extensión o sin red = solo lo del oyente; peso 0 con red = solo el feed; ≥75 las propias primero; "Recently Played" cuenta como la primera estantería del feed). Se portaron los 16 casos de `HomeTest.kt`. Decisiones: el azar es `mulberry32` con semilla del **día local** (Android: `Random` de Java y día UTC): mismo día, mismo orden, día siguiente distinto, pero el orden no coincide con el del móvil; la historia se lee de `listening` fusionando meses (suma jugadas, `last` máximo, unión de `days`, texto del mes más nuevo) y `plays30` sale de `days`; **guardia de ids:** solo pasan ids de YouTube (`^[A-Za-z0-9_-]{11}$`) y `auden:local:<id>` con fila existente (los `content://`, clips, `spotify:` y vacíos se descartan antes de llamar a YouTube); una fila con `videoId` se trata como esa canción de YouTube (una sola vez); sin red **o sin extensión** solo quedan canciones con archivo legible (el filtro va antes de construir, así sigue valiendo "mínimo 4"); las estanterías no se recalculan en cada volcado de la escucha (la clave no cuelga de `listening`), sí al abrir Home y al cambiar biblioteca, playlists, portadas o el índice de enlaces; mientras cargan más páginas del feed, las propias sobrantes esperan abajo para que no salten. Tocar una canción reproduce esa estantería (cola con origen `home`, conservando `source:'local'`). **No verificado:** la mezcla con un feed real o simulado en el navegador (solo en tests del compositor), el cambio a sin conexión en vivo, el aspecto de Ajustes, y un test de integración del hook (`useLocalHome`: error de BD, parpadeo del esqueleto; ambos se comprobaron a mano o por revisión).

---

## Fase 8 — Auden DJ

### 8.1 Lógica (portada tal cual)
- `auden/dj/`: `DjPlanner` (sets de petición y sorpresa, tema por hora y día, sin
  repetir artista en el set ni en dos sets, sin álbum seguido), `DjWriter` (plantillas con
  hechos, memoria de las últimas usadas, a veces no habla), `DjTaste` (artistas y
  géneros del oyente), `DjRequestParser`, `DjPool` (biblioteca + historial, tope 3000) y
  `DjHistory`, con los tests de `DjTest.kt` y `DjTasteTest.kt`.
- Frases en español e inglés exportadas de los recursos de Android a JSON.
- Peticiones que la biblioteca no entiende → búsqueda en YouTube Music siguiendo los
  géneros y artistas favoritos del oyente, salvo que la petición nombre su propio género.
- Géneros de artistas de Last.fm: la web no tiene cliente de Last.fm; se añade por el
  mismo proxy (8.2) para no exponer la clave.

### 8.2 Voz (Fish Audio por proxy)
- La clave **no puede ir en el JavaScript del sitio**. Una Pages Function
  (`web/app/functions/api/dj/voice.ts`) guarda `FISH_API_KEY` como secreto, llama a Fish
  (modelo `s2.1-pro-free`, voces Wendy para español y DJ Cara para inglés, formato PCM o
  MP3) y devuelve el audio en streaming.
- Protección contra abuso: límite de texto, rate limit por IP (regla de Cloudflare) y,
  si hace falta, Turnstile o la opción de que el usuario ponga su propia clave.
- En el cliente, el audio se decodifica a un `AudioBuffer` y entra en la cola como un
  `clip` (Fase 1), con ducking de la música. Sin voz disponible, el DJ sigue y sus
  palabras salen en pantalla.

### 8.3 Interfaz
- Tarjeta grande del DJ en Home, sobre el mesh, con el anillo.
- La pantalla del DJ es el reproductor con un anillo limpio sobre el mesh que se deforma
  con la voz (`AnalyserNode`) y deja ecos de color. Un solo canvas, DPR limitado, a 60 fps
  como máximo y en pausa con la pestaña oculta.
- Botón del DJ abajo a la derecha del reproductor: clic → menú de cristal con campo de
  texto y "más / menos / parar"; mantener pulsado → "sorpréndeme" con la pantalla
  abriéndose desde esa esquina.
- Con el DJ activo la cola y su botón se ocultan; reproducir otra cosa a mano termina el
  DJ y restaura aleatorio y AutoPlay antes de leer la nueva cola.
- Ajustes "Auden DJ": idioma (es/en), voz, comentarios y tamaño del set.

> **Estado de la Fase 8: HECHA** (sin commitear). `web/app/src/auden/dj/` (lógica pura portada con sus tests: `model`, `planner` = DjPlanner, `parser` = DjRequestParser, `taste` = DjTaste, `writer` = DjWriter, frases ES/EN exportadas del XML de Android a `phrases.{en,es}.json` —45 claves, 165/174 frases, mismo juego de claves—, `pool` = DjPool/DjHistory, `djQueue`; orquestación en `session.ts`, ajustes en `settings.ts`, interfaz en `ui/`), `web/functions/api/dj/voice.ts` (proxy de voz) y la puerta `djPlayer` en `state/player.ts`. **Voz:** `POST /api/dj/voice {text, language}` → PCM 16 bits mono 24 kHz; la clave `FISH_API_KEY` solo existe como secreto del Function (nunca en el cliente: se comprobó que el `dist` no contiene `api.fish.audio` ni el nombre de la variable), modelo gratis `s2.1-pro-free` forzado en el servidor, voces Wendy (es) y DJ Cara (en); solo mismo origen (cabecera `Origin`), JSON, ≤2 KB de cuerpo, ≤400 caracteres, solo `es`/`en`, límite por IP (6/min por isolate, solo `cf-connecting-ip`), errores solo como códigos; `ALLOWED_HOST` opcional para que `*.pages.dev` no esquive una regla WAF. **El Function vive en `web/functions/` porque la raíz de Pages es `web`** (README › Deploy); `vite dev` no lo ejecuta (404 → el cliente lo trata como "sin clave" y no vuelve a pedirlo). Sin clave el DJ suena igual con las palabras en pantalla. **Pendiente de comprobar en un despliegue de prueba:** que Pages recoja `web/functions`, y añadir una regla de límite de peticiones de Cloudflare (WAF) sobre `/api/dj/voice` como respaldo real (la memoria por isolate es solo de mejor esfuerzo). **Cola:** una locución es un `clip` (corte seco: sin letras, historial ni registro de escucha) registrado con `registerClip` y puesto antes de las canciones de su serie; todo lo del DJ se sigue por `entryId` (etiqueta de origen `Auden DJ` solo para la interfaz); la serie siguiente se planifica cuando empieza cualquier entrada de la última (una serie por delante), con la regla de silencio de Android (tras 2 habladas seguidas, 1 de cada 3 sin locución; una serie a la que el oyente salta siempre se dice); el DJ guarda y apaga barajar/repetir/AutoPlay y los devuelve al parar o cuando el oyente reproduce otra cosa a mano (`beforeNewContext`, síncrono); parar quita los clips y deja las canciones (y salta un clip que esté sonando); un clip renderizado que nunca llegó a la cola se libera; el DJ se niega a empezar en una fiesta y se apaga al entrar o alojar una. El primer relato tiene 12 s de margen para la voz y luego sigue solo con palabras. Peticiones: `parseRequest` contra el pozo, "más/menos como esto" actúan sobre la canción en curso (`less` también pasa a la siguiente), y una petición que la biblioteca no cumple se busca en YouTube Music inclinada al gusto del oyente (`DjTaste`), solo si hay extensión. Si el pozo se agota, el DJ se aparta y la cola sigue con AutoPlay. **Interfaz:** tarjeta del DJ arriba en Home (único punto de arranque, directo a su pantalla), pantalla del DJ = el reproductor a pantalla completa con un solo anillo grueso y limpio sobre la malla en movimiento (sin columna de letras ni cola mientras está activo, para que la malla se vea), botón de esquina en el hueco de Up Next (toque = menú de cristal con campo de texto y más/menos/parar; pulsación larga = sorpréndeme). El anillo es UN canvas 2D (DPR ≤2, 40 fps, parado con la pestaña oculta, sin blur CSS) que se deforma con la envolvente de la locución leída en la posición de reproducción, con ecos de color; no se usa AnalyserNode porque el grafo de Automix ya es dueño de la única fuente por elemento. Ajustes: sección "Auden DJ" (voz, idioma es/en, canciones por serie 5-12). **Decisiones y huecos:** los archivos locales entran en el pozo sin mínimo de escuchas (Android pide 5 porque su fuente es todo el almacenamiento del móvil; aquí la carpeta es una elección); géneros de Last.fm **no** (el género sale de las etiquetas del archivo; sin clave de Last.fm disponible); sin "descubrimientos" por radio de YouTube Music ni modo "comentarista" (`chatty`, no se ofrece el ajuste); con muy pocos artistas la regla de descanso agota antes el pozo. **No verificado:** la API real de Fish y sus voces, el despliegue real de `functions/` en Pages, una pulsación larga real con puntero, la fiesta real, el aspecto con portadas reales (el ratón de la malla era neutro con archivos sin portada).

---

## Fase 9 — Reproductor

Los cambios de Android se hicieron sobre `NowPlayingScreen`; en la web el reproductor
copia Apple Music web (`shell/FullScreenPlayer.tsx`, `MeshBackdrop.tsx`), así que se lleva
la intención, no el layout:
- El mismo fondo de portada con blur en reproductor, letras y cola (uno compartido, no uno
  por vista), con un velo más suave en el reproductor que se oscurece en letras y cola.
- Disolución más larga y suavizada entre la portada y el fondo; el fondo conserva la
  imagen anterior hasta tener lista la siguiente y no pasa por negro en portadas animadas.
- La miniatura junto al título reproduce la portada animada y alimenta el fondo; letras y
  cola muestran la portada animada o la fija, nunca el vídeo; el vídeo entra con fundido.
- Ajustes de espaciado (transporte junto al scrubber, la portada aprovecha el alto libre)
  solo si encajan en el layout web.
- Comprobar si la web tiene una opción de mesh clásico que quitar, y el equivalente web de
  los arreglos de toques y rebote en móvil.

> **Estado de la Fase 9: HECHA** (sin commitear). `web/app/src/lib/coverBlur.ts`, `shell/{CoverBlurBackdrop,useStickyCover,FullScreenPlayer}.tsx`. Portado la intención de Android donde encaja con el diseño web (malla animada estilo Apple Music, que se conserva): velo por vista (0,25 en la principal —Android usa 0,6 sobre una portada sin tope de luminancia, pero la malla web ya está limitada a 0,38 y el mismo velo la oscurecería dos veces— y 1,0 junto a letras o cola, 360 ms); portada completa difuminada para letras y cola (96 px, 3 pasadas de caja de radio 6, difuminada UNA vez y escalada, sin desenfoque por fotograma; fundido de 320 ms que conserva la imagen anterior hasta tener la nueva y la conserva si la portada no se puede leer; caché LRU de 6 con deduplicación); la portada ya no «cae» a vacío al cambiar de pista (la anterior queda debajo hasta que la nueva termina su fundido, o se suelta de inmediato si la nueva falla); espaciado más ajustado y portada mayor; la malla se congela mientras la portada difuminada la tapa por completo (ahorro de GPU). No portado, sin equivalente web: desvanecido del banner, paso de toques al cerrar, rebote de iOS, malla clásica, sincronización de la portada animada y miniaturas animadas en letras/cola. Nota para la Fase 8: con la columna de letras abierta (por defecto) la malla queda tapada; el DJ debe ocultar las columnas (como Android oculta la cola) para que el anillo se vea sobre la malla en movimiento. Revisión de Opus: B1, I1, I2 corregidos y comprobados con mutaciones. Sin verificar: el cambio de pista con portadas de red reales, el aspecto con letras reales y en 1366x768, y el coste real de GPU.

---

## Fase 10 — Copia de seguridad

- Exportar e importar `auden-backup-FECHA.json` con el mismo esquema que Android
  (`Backup.kt`): canciones, playlists y su orden, letras guardadas con proveedor y offset,
  y emparejados de Spotify, restaurados en una sola transacción de IndexedDB.
- Un backup de BitChord se importa sin tocar la biblioteca. Las rutas de archivos locales
  del móvil no se resuelven en el ordenador: esas pistas quedan como pendientes hasta que
  el indexador encuentre el mismo archivo (por id o metadatos).
- Test de ida y vuelta Android → web → Android con un backup real.

> **Estado de la Fase 10: HECHA** (sin commitear). `web/app/src/auden/backup/{format,export,import,passthrough}.ts`, `ui/BackupSection.tsx` (sección «Backup» de Ajustes), `fixtures/*.json`, y en Android `app/src/test/java/com/music/bitchord/data/stats/BackupWebFileTest.kt`. Formato: exactamente el `BackupFile` de Android (`auden-backup-FECHA.json`, `app` «auden» o «bitchord», `version` ≤ 2, `settings`, `listening`, `library`). Exportación: todo número entero (kotlinx rechaza `5000.3` para un Long y con él el archivo entero), sin nulos donde Kotlin no los admite, ids ≥ 1, posiciones densas desde 0, cada entrada apunta a una canción y a una lista del archivo, ids de lista al formato del móvil (`local:playlist:X` → `sp_local_X`), las portadas y arte que solo viven en el navegador fuera, comprobación previa (`selfCheck`) que impide escribir un archivo que el móvil rechazaría o que le haría perder una entrada; los archivos que solo existen en una carpeta del navegador no viajan (Android los borraría en su siguiente indexado). Ajustes del móvil: la web no tiene los de Android, pero una restauración en el móvil REEMPLAZA todas sus preferencias (y sus escuchas) con lo que diga el archivo, así que se conservan tal cual los de la última copia de Android importada (`passthrough.ts`; con la fecha en que se capturaron, no la de la exportación web) y se reescriben; sin ellos, exportar exige una casilla de reconocimiento (también «Export a copy first»). Importación (Opus, TAKEOVER): validación estricta y normalización antes de abrir UNA transacción de IndexedDB (`durability: strict`, `tx.abort()` en cualquier error, comprobado con mutaciones) sobre todos los almacenes; reemplaza (no mezcla) biblioteca y escucha, conserva lo propio del navegador (archivos de sus carpetas, carpetas, portadas aún referenciadas), ids nuevos para lo importado con remapeo de entradas, los archivos del móvil se guardan en `androidFile` (no en `localUri`) para reescribirlos al exportar; un archivo BitChord solo reemplaza la escucha; los ajustes se guardan solo tras confirmar la transacción y nunca se aplican. **Verificado con el deserializador real de Android**: `BackupWebFileTest` (JUnit, ejecutado con `./gradlew --offline :app:testProdDebugUnitTest --tests '*Backup*'`, 7 pruebas en verde) decodifica con la misma configuración de Json los ejemplos de Android, el de la web y `web-export.generated.json` (producido por `roundTrip.test.ts`: Android → importar en la web → exportar; ese test falla si queda desactualizado; regenerar con `UPDATE_BACKUP_FIXTURES=1`), comprueba integridad referencial, uris únicas, posiciones y que `songJson` decodifica, y que un milisegundo con decimales sí lo rechaza el móvil. Revisión de Opus: PRE_FLIGHT, TAKEOVER (import) y REVIEW; B1 e I1-I3 corregidos. **Recortado (I3)**: una canción de archivo del móvil SIN `videoId` queda como fila no disponible (conserva su sitio en las listas y vuelve en la siguiente exportación) pero no se enlaza por metadatos con un archivo del navegador; las que tienen `videoId` se reproducen en streaming. **Seguimiento pendiente en Android (requiere una versión nueva del móvil)**: en `Backup.importFrom`, si `origin == "web"` y `settings` está vacío, no llamar a `importPrefs`, y pedir confirmación antes de reemplazar `listening`; sin ello, las advertencias de la web son la única protección. Sin verificar: una copia real exportada por el móvil (el Senior pidió que el usuario exporte una desde el S24; no se tira de adb por iniciativa propia), el comportamiento real de Chromium/Firefox con `durability`/Web Locks entre pestañas, el rendimiento con miles de canciones y la restauración destructiva sobre datos reales del navegador (cubierta por pruebas con fake-indexeddb y el rollback con mutaciones).

---

## Fase 11 — Despliegue y documentación

- Proyecto de Cloudflare Pages propio (raíz `web`, `pnpm build:site`), dominio de Auden,
  secretos `FISH_API_KEY` y la clave de Last.fm, `VITE_PARTY_SERVER_URL`.
- La extensión publicada en el sitio con el nombre de Auden y su `extension.json`.
- `web/README.md` de Auden y CI con `typecheck`, `test` y `build` de `web/`.

> **Estado de la Fase 11: HECHA** (sin commitear; falta el despliegue real, que necesita cuentas). `web/README.md` reescrito para Auden (qué es, cómo correrlo, extensión, tabla de variables con cuáles son secretos, mapa del código, paridad con Android, lo no portado, seguimientos); `.github/workflows/web.yml` (ya existía: typecheck, test, build) ahora da `VITE_PARTY_SERVER_URL` al build (sin ella `vite build` fallaba en CI) y corre también `pnpm build:site`; `extension/package.json` recibió el script `test` (antes `pnpm -r test` no ejecutaba las 2 pruebas de la extensión: 16 tests); comentario en `extension/site.config.ts` sobre el dominio. Decisiones: **el dominio de Auden NO está decidido**, así que los orígenes (`bitchord.in`, `bitchord-8bw.pages.dev`, localhost) y la redirección `www` de `app/index.html` se quedan, y el README explica cómo fijarlos con `BITCHORD_SITE_ORIGINS`; los zips y los nombres `BITCHORD_*` siguen (son identificadores); **no hay clave de Last.fm en la web** (el DJ no la usa, Fase 8), así que el único secreto nuevo es `FISH_API_KEY` (+ `ALLOWED_HOST` opcional); el Function está en `web/functions/` (no `web/app/functions/`) porque la raíz de Pages es `web`; `VITE_PARTY_SERVER_URL` es `https://host` (el ejemplo `wss://` del encargo no funciona: la presencia usa `fetch`). Comprobado en local: `pnpm -r typecheck` limpio; `pnpm -r test` 86 archivos/809 tests (app) + 16 (extensión); `VITE_PARTY_SERVER_URL=wss://example.invalid pnpm build:site` bien (para el build vale cualquier valor; en CI se usa `https://`) (genera `app/public/downloads/` con los dos zips de 0.4.4 y `extension.json`); YAML del workflow válido. Smoke en el servidor de desarrollo y en `vite preview` del build: Home, Search, Nuevo, Biblioteca, On This Device (con pista local reproduciéndose), Spotify sin puente, Ajustes (incl. Backup), Extensión, 404 y el reproductor a pantalla completa cargan sin errores no esperados (solo `wss://example.invalid` y la falta de red/YouTube). Chunks mayores (gzip): `analysis.worker` 823 KB (essentia de Automix; ya estaba, no es de esta fase y solo carga bajo demanda), `shaka` 244 KB, `web` 163 KB, `hls` 155 KB, `index` 112 KB: nada nuevo por encima de ~1 MB. **No verificado:** despliegue real en Pages (recogida de `web/functions`, regla WAF, `FISH_API_KEY`), `pnpm build` completo de la extensión en CI, la extensión cargada con servicios reales, y `vite preview` sirve el Function como 404 (esperado). Seguimientos: la guardia del lado Android para que una copia creada en la web no borre los ajustes del móvil, y decidir dominio/origen.

---

## Fase 12 — Descargas (opcional, con prueba previa)

Las descargas de Android no se pueden copiar tal cual: un sitio no puede escribir en
`Music/` libremente, ni etiquetar archivos sin herramientas pesadas, y descargar el audio
de YouTube desde el navegador choca con SABR/UMP y con los términos del servicio. Lo que sí
se puede, en orden de coste:

1. **Ya cubierto por otras fases:** exportar playlists `.m3u8`, letras `.lrc` y portadas;
   reproducir y gestionar la música que el usuario ya tiene (Fase 2).
2. **"Disponible sin conexión" dentro del navegador:** guardar el stream de audio en OPFS
   con una cola persistente, reintentos y pausa (`QueueStore`), y reproducirlo desde ahí.
   Prueba previa: si la extensión o `googlevideo` pueden obtener un formato completo, la
   cuota de almacenamiento, y el riesgo con YouTube.
3. **Guardar en una carpeta del usuario** (Chromium) con la plantilla de carpetas, tags,
   `cover.jpg` y `.lrc` al lado: exige meter el Opus en un contenedor y escribir tags
   (WASM). Solo si la opción 2 funciona y compensa.

Recomendación: dejar esta fase fuera hasta terminar la 10, y empezar por la prueba de la
opción 2.

> **Estado de la Fase 12: HECHA** (opción 2 y la versión reducida de la opción 3; sin commitear). **Opción 2:** "guardar para escuchar sin conexión", detrás de un interruptor *experimental* apagado por defecto en Ajustes > "Offline copies". **Opción 3 reducida (12b):** "Save to Folder" en Chromium (`showDirectoryPicker`, oculto donde no existe): escribe los archivos tal como YouTube los sirve en una carpeta elegida, con plantilla de nombres, `.lrc` y portada. **Fuera, con motivo: las etiquetas incrustadas** (Opus/WebM/AAC): no hay escritor de etiquetas ni de contenedores (la única dependencia de metadatos es `music-metadata`, solo lectura) y meterlas exigiría WASM; la interfaz y el README lo dicen. La opción 1 ya la cubrían las fases 2/3/5 (M3U8, `.lrc`, portadas).
>
> **Prueba previa (evidencia).** (a) *Se puede obtener el archivo completo:* `playback/automix/fetchAudio.ts` ya bajaba el audio entero de la misma URL directa que reproduce Shaka (VISIONOS, itag 251 Opus/140 AAC, cifrado resuelto por YouTube.js, rangos de 1 MiB, 403 → URL nueva misma itag) para el análisis de Automix; la descarga reutiliza ese código (extraído a `streamTrackAudio`) en vez de uno nuevo. Los clientes web solo dan SABR y se cortan a ~70 s (docs/youtube-playback.md): **se rechazan**, igual que "solo SABR" (fallo permanente con motivo); no se toca SABR ni PO tokens más allá de lo que ya hace el reproductor. Necesita la extensión (reglas DNR de Origin/Referer/CORS de googlevideo, `rules.ts`): reproducir una copia **no** la necesita, descargar sí. (b) *Almacenamiento, medido en el navegador del panel (Chromium):* `navigator.storage.estimate()` cuota 178 819 MB, uso 26 MB; `persisted()` false y `persist()` **denegado** (sin interacción suficiente; se pide al encolar y la página lo muestra con un botón "Ask it to keep them"); OPFS `createWritable`: 32 MiB en ~86 ms (370 MB/s), tamaño exacto; `createSyncAccessHandle` no existe en el hilo principal (no se usa). Sin `persist()` el navegador puede borrar todo el origen (copias y AudenDb juntos, así que quedan coherentes). Safari sin `createWritable` queda fuera (`opfsSupported()` oculta la función). (c) **Sin red en este entorno:** la descarga real contra googlevideo **no se ha ejecutado nunca**; lo comprobado es el código, los tests con un flujo falso y un flujo de prueba en el navegador.
>
> **Qué hay.** `web/app/src/auden/offline/`: `store` (IndexedDB propia `auden-offline`: `job`, `item`, `meta`; sin tocar `DB_VERSION` de AudenDb ni el backup), `files` (OPFS plano `auden-offline/<videoId>.<ext>`, un solo `createWritable` que solo existe al hacer `close()`: sin `.part`; un fallo hace `abort()` y no deja nada; `memoryFiles()` para tests), `download` (escribe al `FileStore`, comprueba que es audio —WebM/MP4 por cabecera, no HTML— y que los bytes son los declarados; si no, no queda nada), `queue` (semántica de QueueStore/Downloads de Android: trabajos persistentes, concurrencia 2, arranques espaciados 3 s, reintentos con espera 5/15/45 s guardada como `notBefore` y 3 reintentos, fallo permanente inmediato —solo SABR, cliente web, 404/410, no disponible—, y **pausa de toda la cola** sin gastar reintentos para: sin conexión (se reanuda sola con `online`), disco lleno, y el control anti-bot de YouTube —que llega como "age gate"—; pausa/reanudar/cancelar/reintentar/limpiar fallidos; un trabajo en marcha vuelve a la cola tras reiniciar), `runtime` (un Web Lock `auden-offline-runner` hace corredor a una sola pestaña; las demás solo escriben en el store y avisan por BroadcastChannel; índice en memoria `kept.ts` que el reproductor, Home y el DJ consultan sin cargar el descargador), `lyrics` (al terminar, las letras por el almacén de la Fase 5 en un lote silencioso, sin el banner, que además se niega a empezar si hay otro lote), `audit` (huérfanos y registros sin archivo o de otro tamaño; limpiar; uso por álbum/artista; **sin** auditoría de duplicados: la clave es el `videoId`), `settings`, `actions`, `hooks`, `ui/{OfflineButton,OfflineSection}`, `pages/OfflinePage.tsx` (ruta `/offline`: espacio y persistencia, Downloading con progreso/pausa/reintento/motivo, copias por álbum o artista con tamaño, reproducir/quitar, comprobar archivos), "Save for Offline"/"Remove Offline Copy" en `TrackMenu` junto a Find Lyrics (que sigue igual), botón en álbum y playlist (esta última, las páginas ya cargadas), fila "Offline" en la barra lateral solo con el interruptor activo. **Reproducción:** `playback/source.ts` tiene un `OfflineProvider` **aparte** de `LocalResolver` (no se mezcló en el resolvedor local, por consejo de Opus: habría hecho que una copia fuese "archivo enlazado" y desactivado Automix aun con red); orden en `resolveMedium`: fiesta/`preferStream` → YouTube; archivo enlazado → copia → YouTube (cada paso cae al siguiente si no se puede leer); con Automix activado **y red** se sigue transmitiendo (Automix analiza la transmisión) y sin red la copia suena siempre; la pista conserva su `videoId` (historial, letras, fiesta), y suena sin extensión (con Automix activado, red y extensión ausente la copia también suena). Home y el DJ cuentan una copia como "archivo" (`hasOfflineCopy`), pero **no** se recalculan al terminar una descarga hasta abrir Home de nuevo.
>
> **Verificación.** Tests nuevos (`offline/{queue,download,audit}.test.ts`, `source.test.ts`, `TrackMenu.test.tsx`): 14 de cola (concurrencia, espaciado, back-off persistido y rendición con motivo, permanente vs reintento, pausas por control anti-bot / disco lleno / sin conexión con reanudación, pausa del usuario y cancelación, reinicio, solo el corredor transmite, archivo huérfano si falla el registro, borrar), 6 de descarga, 4 de auditoría/uso, 5 de la costura de reproducción. En el servidor de desarrollo, con un flujo falso (WAV de 60 s inyectado con el gancho solo-dev `__audenOffline.setStream`): 3 canciones bajadas con progreso (12 %…), 1,8 MB "Stub Album" + 0,9 MB "Singles" por álbum, una pista con `videoId` de YouTube **suena desde un `blob:` del OPFS sin extensión ni red** (duración 60, `currentTime` avanzando), fallo "solo transmite" con su motivo y Retry/Remove, pausa que aborta lo activo y reanudar que termina, auditoría que encontró un archivo suelto y "Clean up" que lo borró, "Remove all" que dejó OPFS y registros vacíos, el interruptor de Ajustes y la fila "Offline". Un documento antiguo del panel (anterior a un arreglo del Web Lock) siguió ejecutando trabajos con el flujo real y los hizo fallar con "Failed to fetch" entre medias; se cerró la pestaña y la prueba se rehízo con la copia nueva; la carrera entre dos corredores no se reprodujo con una sola pestaña. Chunks: sin cambios (índice 112 KB gzip; la página va en su propio trozo; la cola y el almacén (~4,7 KB gzip) se cargan con el shell porque `TrackMenu` los importa, y solo la fuente de bytes (YouTube.js, BotGuard) es una importación perezosa; el gancho solo-dev no está en el build).
>
> **Revisión de Opus: APROBADA** tras una ronda: un hallazgo importante (una cancelación cerca del final podía ignorarse o el trabajo volver; corregido con dos pruebas, de las que solo la de la portada falla al quitar el arreglo: la del fallo tardío ya la cubría la comprobación de `jobs.has`), y arreglos menores aplicados (lock del corredor liberable al limpiar, `mixing` exige extensión, el aviso `changed` solo compara valores conocidos, guarda de orden en `reload`, comentarios). Pendiente menor sin hacer: "Clean up" actúa sobre la instantánea de la comprobación. Una ejecución de `pnpm -r test` falló 1 test (no identificado) y no se reprodujo en 6 ejecuciones más (851/851); sospecha: las esperas de las pruebas de cola bajo carga. > 
> **Fase 12b: guardar en una carpeta (`auden/offline/folder/`).** `layout` (port de `DownloadLayout.kt`: plantilla con `{title} {artist} {albumArtist} {album} {track} {videoId}` y `{year} {disc}` aceptados y vacíos; por defecto `{album}/{artist} - {title}`; validación sin `..`, `\\`, `//`, `/` al inicio o final y con nombre; cada valor es UN segmento —un `/` en un nombre no crea carpeta—, sin caracteres ilegales, sin puntos finales ni iniciales, nombres de dispositivo de Windows con `_`, topes en **bytes UTF-8** por caracteres completos, `Singles`/`Unknown Artist` de respaldo, clave de colisión NFC + minúsculas), `dirs` (la porción de la API de directorios que usa el escritor + un directorio en memoria con fallos inyectables, permisos y comparación de mayúsculas), `writer` (nunca sobrescribe: un nombre ocupado por cualquier cosa que no sea el propio guardado anterior de esa canción recibe " (2)", " (3)"…, se sondea sin `create`; un solo `createWritable` por archivo leído a trozos desde la copia y verificado por tamaño, y un fallo borra lo parcial; `.lrc` junto al audio solo si hay letra **sincronizada** (la plana saldría con `[00:00.00]` en cada línea) y sin reemplazar uno existente; una portada `cover.<ext>` por carpeta de álbum real, nunca en `Singles`, en la raíz de una plantilla plana ni sobre una existente; el lote entero bajo un Web Lock propio `auden-offline-folder`, distinto del corredor; permiso retirado / carpeta desaparecida / disco lleno **detienen el lote** y dejan el resto pendiente, un fallo de una canción no), `access` (patrón de `library/roots.ts` con `readwrite`: el permiso solo se *consulta* sin gesto y se *pide* como primer `await` de un clic; carpeta que coincide con una carpeta de biblioteca = aviso, porque el indexador listaría lo guardado como canciones locales), `state`/`hooks`/`report` (handle estructurado-clonado en IndexedDB **sin subir la versión de la BD**: va en `meta['folder']`, lo guardado en `OfflineItem.folder` y el deseo en `toFolder`/`folderPending`, porque una pestaña v1 abierta bloquearía una v2 y dejaría sin proveedor offline a la pestaña nueva), `FolderSettings`. **Flujo:** "Save to Folder" en el menú de pista, en álbum y playlist y "Save N to folder" en Offline: lo ya guardado se escribe ya, lo que viene de camino recibe el deseo, el resto se encola con él; al terminar la descarga se escribe si el permiso está (si no, queda pendiente y la página lo dice con "Allow access"; tras reabrir el navegador suele tocar volver a pulsarlo); el `.lrc` se completa solo cuando termina el lote silencioso de letras. `OfflineSong` ganó `trackNumber`/`albumArtist`. **Verificación:** tests nuevos (`layout`, `writer`, `state`, cola, menú; 883 en la app). Mutaciones comprobadas, todas fallan al quitar: sondeo de colisión, no-sobrescribir un `.lrc`, portada solo con álbum real, rama "ya guardado", parada por permiso, borrado de parcial, rechazo de `..`, nombres reservados y clave NFC (la `claimed` interna resultó redundante y se quitó). En el navegador del panel (`showDirectoryPicker` existe, pero un selector no se puede manejar) se usó **un directorio real de OPFS como carpeta elegida** mediante el gancho solo-dev `__audenOffline.folder(handle)` (con `createWritable` reales): 3 canciones con el deseo de carpeta se bajaron con el flujo falso y se escribieron como `Proof Album/Proof Artist - Proof One (2).webm` (el archivo propio de 3 bytes del listener con el nombre original quedó intacto), `Proof Two Slash.webm` (el `/` del título sin crear carpeta) y `Singles/…`, `folderPending` borrado y el registro con la ruta; después, con letra sincronizada guardada, "Save N to folder" no reescribió nada (`already: 3`) y creó `Proof Two Slash.lrc` con `[ti:]`, `[ar:]` y las líneas; la tarjeta mostró "3 songs saved there" y Ajustes el selector de plantilla con vista previa. **Revisión de Opus de la 12b: APROBADA** tras una ronda (un bloqueante y cinco importantes, todos corregidos con prueba y comprobados con mutaciones: el deseo de carpeta que llega mientras la canción se descarga ya no se pierde; un `createWritable` que falla ya no deja un archivo vacío; una parada al escribir el `.lrc` o la portada ya no deja el audio sin registrar —se registra nada más verificar el audio—; cada canción se vuelve a leer **dentro** del Web Lock, así que dos lotes solapados escriben un solo archivo y una canción borrada se salta; la carpeta se relee del almacén en cada guardado, de modo que olvidarla o cambiarla en otra pestaña se respeta y las demás pestañas se enteran por el bus; y algo ya presente en la ruta guardada cuenta como guardado aunque el listener lo haya etiquetado y cambiado de tamaño). Menores anotados sin hacer: el mensaje de "Allow access" cuando la carpeta ya no existe, `AUX.algo` reservado en Windows, y que quitar la copia del navegador olvida la ruta del archivo en la carpeta (volver a guardar daría " (2)"). **No verificado:** una carpeta real del disco (mayúsculas/minúsculas, Windows, `.crswap`, carpetas de sistema que Chrome rechaza, Safe Browsing al cerrar), ni el selector ni el permiso persistente reales; Chrome en Android no se probó (la detección de funciones lo cubre o lo oculta); la portada con una miniatura real (solo tests); y, como en toda la fase, ninguna descarga real de YouTube.
>
> **Riesgos y límites (por escrito).** (1) **Términos de servicio:** copiar el audio de YouTube a un navegador choca con los términos del servicio aunque sea por usuario y por petición; por eso es experimental, apagado por defecto, avisa en Ajustes, y solo usa la misma transmisión que ya reproduce el reproductor. Si algún día se publica la extensión en una tienda, esta función es un riesgo de revisión. (2) Sin *service worker* (no existía): **recargar sin conexión no carga el sitio**; la copia suena en una pestaña abierta (por eso se llama "offline listening", no "available offline"); un service worker queda como seguimiento aparte. (3) La descarga real nunca se probó: falta una prueba con red que baje N pistas y compruebe longitud, reproducción y búsqueda; y qué hace googlevideo con ráfagas (el control anti-bot pausa la cola, pero el umbral es desconocido). (4) Una reanudación tras reiniciar empieza de cero (Chromium solo confirma la escritura al cerrar). (5) Las portadas sin CORS no se guardan (las vistas sin red usan la URL remota). (6) Las listas de reproducción largas guardan solo lo cargado. (7) Una copia nunca entra en una fiesta ni en la mezcla de Automix con red. (8) Guardar en carpeta duplica el espacio (la copia del navegador se queda) y no incrusta etiquetas; la portada es la miniatura de YouTube.

---

> **Cierre de la Fase 12.** El Senior (Opus) revisó la fase y la aprobó tras una ronda de arreglos. Verificación final del conjunto: `pnpm -r typecheck` limpio, 851 tests (91 archivos), build correcto. La opción 3 queda descartada de forma definitiva (no hay escritor de etiquetas y escribir en una carpeta elegida solo existe en Chromium); la prueba de descarga real contra googlevideo requiere red y una extensión instalada, y queda como verificación manual del usuario.

---

## Estado de v1.12.0 (novedades de Android 971c3b10 llevadas a la web; sin commitear)

> **Hecho.** Se leyó el Kotlin de `971c3b10` (no el árbol, que está en v1.11.0) antes de cada punto. Lo decidido, en orden de alcance:
>
> 1. **Fondo del reproductor** (`shell/playerLook.ts`, clave `auden:look`, selector en Ajustes > Home como en Android): *Continuation* (por defecto), *Colors* (la malla de antes, **idéntica**: mismo shader y mismo velo 0,25 de 0,34-0,64) y *Blur* (`CoverBlurBackdrop` también en la vista principal; sin malla montada). *Continuation* = la misma malla WebGL con un uniforme `uSoft`: el alcance de cada mancha crece hasta ~2x hacia la mitad inferior (`smoothstep(0, 0.5, y)`, el equivalente del desenfoque 1→3 con pico en 0,5 de Android) por el precio de una multiplicación por píxel, sin capa extra ni `backdrop-filter` (el canvas 2D de reserva ensancha por mancha), más un velo CSS 0,04→**0,46** (Android 0,58: la malla web ya está tope de luminancia 0,38, así que 0,58 la oscurecía dos veces; ajustado mirando capturas). La malla sigue congelada cuando no se ve; en *Blur* ni se monta, y el DJ se queda siempre en la malla (su aro). *Blur* usa `solid`: la capa es el suelo opaco desde el primer fotograma (lo encontró la revisión: sin portada o antes de tener el desenfoque se veía la página debajo).
> 2. **Desvanecido de la portada:** `lib/heroFade.ts` (FRACTION 0,52, smootherstep `1 - t^3(t(6t-15)+10)`, 16 pasos) como máscara CSS en estilo en línea (ambos nombres de propiedad, por lightningcss) y como superposición de color. Donde la web disuelve algo de verdad: el banner de **ArtistPage** (antes una rampa casi recta al color de la página, ahora esa curva). La portada del reproductor y de los detalles son cuadrados redondeados, no se disuelven: no hay nada que cambiar allí. **Portadas a pantalla completa** (ajuste nuevo, apagado como en Android; solo `< 768px`): `DetailHeader` (álbum, playlist de YouTube, playlist de Spotify) con la portada a ras, alto máx. 62 dvh, desvanecido 0,42 y el título solapando 44 px; el clip de Apple (`MotionArtwork`) va dentro de la misma máscara (`rounded-[inherit]`).
> 3. **Controles más juntos:** en `FullScreenPlayer` la separación título→barra→transporte baja ~20 px (`clamp(14,2.4vh,24)`, `mt-1.5`, transporte `h-12`) y el presupuesto de la portada `--art` devuelve esos 20 px (300→280 / 330→310). **N/A:** *Hide mini lyrics* y *Hide volume bar* (la web no tiene línea de letra sobre la barra ni barra de volumen en línea: el volumen es un botón en la esquina), *Disable blend effects* (la web no tiñe iconos con el color de la portada).
> 4. **Ajustes de Home:** `hideRecents` (quita Recently Played **y** la copia del feed, que si no volvía), `hideVideos` (`auden/home/filter.ts`: `withoutVideos`, port de `HomeVideoFilterTest`) y `squareVideoThumbnails` (los `VideoLockup` en 1:1 y las baldas de vídeo en las columnas cuadradas). Las tres entran en `partialize` **y** en el `merge` a medida del store (sin eso se perdían al recargar: lo cazó el Senior). **N/A:** *Hide Top Picks* y *Hide Find Your Mood* (baldas solo de Android; la web no las tiene). Los ajustes se ven en Ajustes > Home (la sección ya existía).
> 5. **Arte en movimiento sobre datos móviles** (`useClipNetwork`, clave `bitchord:clips`, apagado): un solo interruptor para *Animated Artwork* y *Spotify Canvas*; los dos interruptores siguen. `clipsAllowedOn(overCellular, connection)`: **Save-Data siempre lo para** (el permiso solo levanta `type === 'cellular'`, decisión tras el Senior); `metered()` queda para el sync de playlists, sin cambios.
> 6. **Playlists descargadas que se actualizan** (`auden/offline/keepUpdated*.ts`): la cola de la Fase 12 no conocía las playlists, así que se guardan **seguidas** en la IndexedDB de offline (`meta['followed']`, sin subir versión): al pulsar *Save for Offline* / *Save to Folder* en una playlist de YouTube (que ahora lee **toda** la lista antes: `known` no se siembra con las páginas cargadas) o en una playlist del dispositivo (botón nuevo en `LocalPlaylistPage`). Ajuste *Keep playlists updated* (por defecto **sí**; solo cuenta con copias activadas). Corre como **pasada propia** dentro del `runOnce` de `autoSync` (misma cadencia de 6 h, 15 s de espera y cerrojo; sellos propios `offline:`), tras las copias en la nube y **con sus propias puertas** (`enabled && keepUpdated && !metered()`, no las del sync de copias). Solo añade: `planSync` (puro) saca lo nuevo contra `known`; lo que el listener quitó de offline no vuelve; **más de 50 nuevas sin permiso no se piden ni se registran** (la pregunta vive en IDB, se ve en la página Offline con *Download them* / *Not now*, que no pierde nada); el registro se relee bajo un Web Lock antes de escribir (un *unfollow* durante la pasada no lo resucita, otra pestaña no se pisa); una playlist borrada se olvida, una ilegible se reintenta a la hora; un fallo de una no frena a las demás. *Save to Folder* viaja como `toFolder` del trabajo, así que se escribe sin clic. Lista *Kept up to date* con *Stop updating* en Offline (sin ella solo se paraba con el interruptor global).
> 7. **AutoMix.** (a) `analysis/analysisQueue.ts` + `analyzer.retain()`: la pasada de `autodj` nombra [sonando, siguiente] **antes** del `return` temprano; lo pendiente de otras canciones se descarta (vuelve como nuevo si regresa), y la que ya corre se abandona en tres puntos de control (tras bajar el audio, tras decodificar, antes de enviar al worker). El worker no se interrumpe. Se mantuvo el orden por rango (el sonando antes que el siguiente) y lo no nombrado va detrás. (b) `engine/easeBack.ts`: el ritmo de la entrante vuelve a 1 desde el **70 %** del mezclado (`EASE_BACK_FROM`), un paso por pulso (500 ms sin rejilla), `max(ceil(|s|/0,02), min(ceil(|s|/0,0075), pulsos que caben))` pasos; antes empezaba 3 s *después* del mezclado en 8 pasos de 3 s. (No se tocó el `LOCK_UNTIL` 0,9→0,7 de Android: el servo de la web sigue la línea de tiempo programada, que ya lleva los cambios de ritmo.) La cancelación a medias es segura: `Deck.cancel()` borra los cambios pendientes. (c) `loopMix.ts`: `SungSpans` pasa a `[inicio, fin, certainUntil?]`; una línea veta un bucle solo si su fin es conocido (`certainUntil >= fin`); las líneas con solo inicio (la mayoría de LRC) las decide la máscara vocal (`checkedBy: 'lyrics+mask'`, sin máscara el bucle se descarta: nunca más permisivo que antes). Pruebas espejo de `LoopMixTest`. (d) *Automix overview*: **N/A**, la web no tiene una vista del plan de la transición ni un gancho barato para sacarla del menú de pista.
> 8. **Music only** (lo implementó el Senior en TAKEOVER; `playback/musicOnly.ts`, `audioVersionOf`/`audioCutIn` en `data/music/api.ts`, 5 puntos en `state/player.ts`; clave `auden:musiconly`, apagado): la web no marca los vídeos en `Track` (solo `Candidate.isVideo`), así que se intenta solo si la radio lo dijo o si no hay álbum; salta archivos, copias offline, DJ, clips y fiestas. La actual se resuelve antes de cargar (15 s de espera, luego el vídeo con su audio y sin imagen), la siguiente en segundo plano mientras sea realmente la siguiente y no la tenga ya un mezclado/crossfade. Al activarlo con una pista sonando, esa sigue (solo se oculta la imagen); en Android se cambia al vuelo, aquí reiniciaría la canción.
>
> **No aplicable (por escrito):** sonda de longitud de *Direct Source Stream*, bit-perfect, diálogo de actualización y changelog localizado, escarcha de Liquid Glass, arreglo del servidor integrado de Listen Together, y los arreglos solo de Android.
>
> **Verificación.** `pnpm -r typecheck` limpio; `pnpm --dir app test` **106 archivos / 985 tests** (antes 94 / 889; dos pasadas seguidas verdes); `VITE_PARTY_SERVER_URL=https://party.invalid pnpm --dir app build` correcto (índice 115 KB gzip, antes 112; el sync offline y su UI son `import()` perezosos). Un test, `useLyrics.test.tsx` "serves the song's chosen saved variant without asking anyone", falló 2 de ~8 pasadas completas bajo carga y pasa solo (la "falla sin identificar" de la Fase 12 probablemente era esta; no es de esta fase y no se tocó). **Mutaciones**, todas comprobadas (cada una rompe al menos una prueba): máscara (0,52, la curva, 16 pasos), `clipsAllowedOn` (Save-Data, cellular, valor por defecto), filtro de vídeos (balda de vídeo, balda vacía), persistencia de los tres ajustes de Home, tema y su valor por defecto, `keepUpdated` por defecto y su `merge`, bucles (`certainUntil`, certeza de líneas abiertas, la etiqueta, la rama de la máscara), cola de análisis (descarte, rango, "ya no se quiere", lo no nombrado), `easeBack` (70 %, tope por pulso, paso sin rejilla), `planSync` (tope de 50, no registrar lo preguntado), registro releído bajo cerrojo, `known` fusionado, filtrado a canciones con audio, `toFolder` conservado, puerta de vencimiento, puertas del pase offline (`!metered`, aislado del fallo, independiente del interruptor del sync de copias), `solid` del suelo de *Blur*, pregunta caducada, aislamiento de errores por playlist; las 23 de *Music only* son del Senior (queda sin cubrir la guarda `mixLoaded`: el arnés no tiene grafo de Automix). **En el servidor de desarrollo** (Playwright, Chromium con WebGL por swiftshader y `requestAnimationFrame`; el navegador del panel carece de `requestAnimationFrame`): las siete filas nuevas de Ajustes (tema, portadas a pantalla completa, ocultar Recently Played y vídeos, miniaturas cuadradas, datos móviles, Music only) se mueven y **sobreviven a la recarga** en `localStorage`; las tres temas del reproductor con una portada de prueba (*Continuation*: arriba más viva, abajo más oscura y suave; *Colors*: la de siempre; *Blur*: sin `<canvas>`, portada desenfocada) en 1280×800, 1280×600 y 390×740 sin desbordes; el encabezado a pantalla completa a 390 px (portada de 390 px a ras, máscara aplicada, título solapado) y que a 1100 px no cambia.
>
> **No verificado.** Nada contra YouTube/Apple/Spotify reales (sin red ni extensión en este entorno): ni la lectura de playlists de YouTube del sync, ni *Music only* con respuestas reales, ni Home con un feed real (se probó el filtro con filas hechas a mano), ni el encabezado a pantalla completa en una página de álbum real (se montó `DetailHeader` suelto en una página con los mismos proveedores y se retiró el arnés); el escenario de la pasada offline de punta a punta (varias horas de cadencia, pestaña en segundo plano) solo con pruebas de unidades; el oído: el nuevo `easeBack` y la regla de bucles con la máscara no se escucharon (sin audio), solo se probó la aritmética y el calendario de `setRate`; WebGL real de una GPU (swiftshader), así que el coste en la iGPU de 120 Hz del usuario no se midió (por diseño es una multiplicación por píxel y se congela oculto). **Pendiente menor:** las miniaturas cuadradas en Browse/Library salen grandes (rejilla ancha); el rango de `easeBack` usa el pulso de la pista, no el de salida (<2 %).
>
> **Revisión de Opus: APROBADA CON ARREGLOS**, aplicados: el suelo opaco de *Blur* (importante), la lista *Stop updating* (importante), la pregunta que sobrevivía a su aprobación, el fallo de una playlist que paraba la pasada, el rango de lo no nombrado en la cola de análisis, comentarios viejos de `autodj.ts` y el texto del interruptor de datos del sync de copias.

---

## Estado del DJ (estilo y comportamiento)

Segunda pasada sobre la Fase 8, contra el Kotlin real (`DjVisuals.kt`, `DjNowPlayingScreen.kt`, `MeshGradient.kt`, `MainActivity.kt` `djScreen`; Android 971c3b10). Sin commitear.

**Qué se llevó, fiel al Kotlin.**
- **Visualizador** (`auden/dj/ui/ringShape.ts` + `DjRing.tsx`, canvas 2D): 140 puntos; radio `s·0,30·(1+0,03·l+0,012·sin 1,4t)`, trazo `s·0,062`, empuje `0,035+0,19·l`; onda de cuatro armónicos (2, 3, 4, 5; 0,55/0,32/0,22/0,13); tres ecos (retraso 0,34 `#6EC8FF` α0,42; 0,20 `#FF8FC4` α0,52; 0,09 `#6FE3C0` α0,62) con deriva `s·0,045·(0,4+l)·lag/0,34`, trazo ×0,96; brillo blanco α0,16 ×2,1; anillo con barrido cónico `#FFF,#FFD6EC,#FFF,#D3E8FF,#FFF` que gira 22°/s (con el trazo, como `rotate` en Compose). Nivel: media de los compases `at-1..at+2` de la envolvente leída en el playhead, subida 0,4 / bajada 0,14 por fotograma de 60 Hz (convertido a tiempo: independiente de la tasa), respiración 0,14+0,08·sin 2,2t mientras elige, `talking` (0,14+0,6·|sin 2,7t·sin(1,1t+0,6)|) en la tarjeta de Home. "Reducir animación" detiene el reloj, no el nivel.
- **Fondo** (`MeshBackdrop` con `colors`, `fadeMs`, `pace`): las cuatro paletas pastel de `DjMeshPalettes` en ciclo cada 3600 ms con fundido de 3600 ms, pasadas por `Color.tuned()` (saturación ×1,35, luminosidad 0,28–0,58) y repartidas por cuadrantes a la rejilla 4×4 (anclas de Android: sup-izq, sup-der, inf-der, inf-izq); velas de 0,10 a 0,38. Siempre en movimiento mientras la pantalla del DJ está arriba. Una sola instancia de `MeshBackdrop`: al pasar a una canción se funde a los colores de la portada.
- **Comportamiento** (`isDjScreen`, `useDjScreen`): la pantalla del DJ (`DjScreen`: nombre, anillo, tema o lo pedido, palabras, transporte con Anterior inactivo, botón del DJ abajo a la derecha) es `pending || (phase==='on' && lo que suena es un clip)`, como `djScreen` de Android. Mientras suena una canción desaparece y queda el reproductor normal, tal cual (portada, título, columna de letras, cola, controles); vuelve con la siguiente voz. Nuevo estado `pending`/`request` en `useDj` (también en un steer, que antes no cambiaba nada), `wordsOnly` para los sets sin voz (sus palabras salen un momento sobre el reproductor normal). Mientras no ha cargado nada y el DJ está encendido, la pantalla del DJ guarda el sitio (no se auto-cierra ni parpadea). La tarjeta de Home usa la misma malla en ciclo con el anillo hablando, quieta si está fuera de pantalla o tapada por el reproductor.

**Diferencias, y por qué.**
- Durante una canción **no hay botón del DJ** ni cola oculta (Android: cola oculta y botón del DJ en su sitio): el encargo pedía la interfaz normal de la canción. Para no dejar al DJ sin mando, la tarjeta de Home trae, con el DJ encendido, "Open DJ" y su menú (preguntar, más/menos, parar), y el menú mantiene la pantalla del DJ mientras está abierto. Volver al botón de Android son dos líneas en `FullScreenPlayer.tsx` (`dj ? DjButton : Up Next` pasa a `djOn`).
- Sin el círculo de revelado (`CornerReveal`, 650 ms): en Android solo nace al mantener pulsado el botón del DJ desde el reproductor de canciones, que aquí ya no lo tiene. El cambio clip a canción es un fundido de la malla, no el corte duro de Android.
- Malla WebGL propia de la web (rejilla 4×4, no 4 manchas desenfocadas con RenderEffect): `pace`×3 iguala la velocidad de deriva (Android: vuelta completa cada 24 s); la base es la media de las celdas, no la 1.ª paleta atenuada.
- El nivel de voz viene de la envolvente precalculada en el playhead, como en Android (el AnalyserNode de la tarea no se usa: el grafo de Automix posee el único `MediaElementSource` de las cubiertas y un segundo lanza error). Anillo a 40 fps con DPR ≤ 2, parado con la pestaña oculta o el anillo fuera de pantalla; `requestAnimationFrame` con respaldo por temporizador (`lib/frames.ts`).

**Verificación.** `pnpm -r typecheck` limpio; `pnpm --dir app test` 109 archivos / 1024 tests verdes; `VITE_PARTY_SERVER_URL=https://auden-listen-together.onrender.com pnpm --dir app build` correcto. Mutaciones comprobadas (cada una rompe al menos un test): `isDjScreen` (sin `pending`, sin la espera de "nada cargado", solo clip), constantes del anillo (0,4 de subida, ventana `at+2`, 0,012, 0,34 de radio, 140 puntos, subida/bajada cruzadas, respiración 0,14), ciclo 3600, `tuned` (saturación, topes 0,58/0,28), cuadrantes de la paleta, `pending` del steer (subida y bajada). En el servidor de desarrollo (Playwright, swiftshader, voz falsa en WAV por la cola real con envolvente conocida): DJ hablando (el anillo se deforma con la voz, la malla pasa del magenta al verde-dorado), canción (reproductor normal con letras en 390 px y 1280 px), pendiente sobre una canción ("Choosing your set…" con lo pedido), y la tarjeta de Home en 1280 y 390 px. **Sin verificar:** voz real de Fish (sin red), coste de GPU en el iGPU a 120 Hz (la malla va a 30 fps y el anillo a 40, sin medir), `createConicGradient` en navegadores viejos (cae a blanco liso).

## Estado de Auden Connect (espejo)

La web de Auden Connect pasa de «barra remota aparte» a un **espejo exacto**: mientras otro dispositivo es el activo, el reproductor normal de este navegador (barra, mini, pantalla completa, Up Next, letras, botón de favorito, menú de pista, Media Session) muestra la misma sesión, sin sonido aquí. Sin commitear. Protocolo ya desplegado: `PlaybackState.order?` y las acciones `playNext`, `addToQueue`, `remove`, `move`, `clearUpcoming`.

**Qué hay.**
- **Modo espejo en el store** (`state/player.ts`): `mirror` + `mirrorVolume`, `mirrorPlayer.show/leave` (cola con `entryId` estables `mirror:<videoId>:<n>`, filas y cola reutilizadas si no cambian, `nowPlaying` con `medium: null`, `status` por `isPlaying`, shuffle y repeat del activo), `getPlayhead()` devuelve la posición remota extrapolada con `paused` (el scrubber, el LCD, las letras sincronizadas, la pantalla completa y Media Session la leen sin cambios; `useScrubber` y `LyricsPanel` ganaron la rama sin elemento). Sin audio, sin cargas, sin motor, sin Automix, AutoPlay, cruce ni conversión a «solo música». El volumen local no se toca: el control muestra y manda el volumen del dispositivo remoto (`volumeOf`, atajos y rueda incluidos). Shuffle y repeat propios se guardan al entrar y se devuelven al salir (lo que se persiste mientras tanto es lo del usuario).
- **Controles** (`MirrorHooks`, `auden/connect/session.ts`): togglePlay, next, previous, seek, shuffle, repeat, volumen, silencio, jumpTo y TODAS las ediciones de cola (playNext, addToQueue solo con pistas compartibles, removeAt, move, clearUpNext) son comandos Connect; la pantalla responde al instante con `optimistic.ts` (función pura, probada) y el siguiente estado del activo manda. Empezar algo nuevo aquí (playTracks, playTrack/playVideo, arrancar el DJ, la cola de una fiesta, un archivo local) **sale del espejo y reclama la sesión**: se vacía la cola ajena, se envía `claim` (vacío: lo que suene aquí se publica enseguida) y el otro dispositivo se libera; los estados del móvil que aún vuelan no vuelven a reflejarse.
- **Cambio de salida**: elegir este navegador estando en espejo hace `transfer` y el `takeover` carga la misma cola/índice/posición (ahora con filas de contexto y sin etiqueta «Listen Together», y **sin pisar el volumen local**) y termina el espejo; elegir otro dispositivo estando activo manda el estado fresco y luego `transfer`; al ser liberado, este navegador sigue siendo espejo de lo que sonaba, sin saltos.
- **Orden de shuffle**: se publica `order` (videoIds en el orden canónico, `canonicalOrderOf`) mientras shuffle está activo, y al recibir una sesión se adopta (`adoptShuffleOrder`), de modo que quitar shuffle aquí, desde el espejo (reordena en local con `order`) o por comando cae en la misma lista en todos. Un comando `shuffle` remoto usa el `toggleShuffle` nativo.
- **Efectos laterales en silencio con espejo** (`isMirrored()`): historial de YouTube (`historyReporting`, cierra lo oído y no abre nada), Discord (baja la tarjeta), estadísticas de escucha (`recorder`), fiesta (`partySync.publish`), recolector de clips, `convertNextRow`/`followVideo` (solo música), AutoPlay y su vigilante, cruce/Automix, DJ (se termina al entrar), eventos del elemento de audio (`playing`/`pause`/`ended`/`timeupdate`) y Media Session local. Nunca se reclama por un cambio guiado por el espejo (`claimIfPlaying`).
- `RemoteBar` eliminado; `AppShell` solo usa `PlayerBar`. `DevicePicker` sigue en la barra y en la pantalla completa. Móvil: sin cambios de maquetación.
- Dev: `window.__bcConnect.start(room, deviceId, name)` entra en una sala con una identidad dada (el navegador integrado no tiene extensión ni sesión).

**Decisiones por defecto (sin preguntar).** (1) El volumen es del dispositivo: un `takeover` ya no copia el del otro. (2) `clearUpcoming` en la web activa vacía TODO lo que sigue a la pista (lo que significa «Clear» desde un mando), no solo lo encolado a mano; el espejo lo hace igual en su respuesta inmediata. (3) El takeover carga la cola como contexto (`context`), así no se podan ni se reordenan pistas ya sonadas al saltar; el espejo las pinta todas como `user` para que Up Next ofrezca «Clear». (4) Un archivo local abierto desde un espejo reclama con una cola vacía (suelta al móvil, no le cuenta la cola); una fiesta también suelta al otro dispositivo pero su cola nunca se publica. (5) Si el dispositivo activo desaparece, lo mostrado queda como cola en pausa y el espejo termina. (6) Sonido propio sin reclamar (archivo local, fiesta) conserva el suelo: no se entra en espejo encima y se reclama como antes. (7) Con una fiesta activa no hay espejo.

**Verificación.** `pnpm -r typecheck` limpio; `pnpm --dir app test` 112 archivos / 1077 tests verdes; `VITE_PARTY_SERVER_URL=https://auden-listen-together.onrender.com pnpm --dir app build` correcto. Pruebas nuevas: `auden/connect/mirror.test.ts` (store real con cubiertas falsas: hidratación desde welcome/state, claves estables, `getPlayhead`, cada control y cada edición de cola, solo pistas compartibles, salir y reclamar con playTracks/playTrack/archivo local, transferencias en ambos sentidos, liberación, publicar/adoptar `order`, comandos remotos sobre la web activa), `auden/connect/optimistic.test.ts`, `state/mirrorPlayer.test.ts` (rutas del store, AutoPlay, solo música, clips, eventos del elemento, salida por playTracks/playTrack/playVideo/DJ/fiesta, orden), y casos de espejo en historial, Discord, estadísticas y fiesta. **Mutaciones comprobadas** (cada una rompe al menos un test; se restauró todo): puerta de eventos del elemento, de AutoPlay, de `convertNextRow`, del recolector de clips, `leaveMirror` en playTracks, rama espejo de `getPlayhead`, enrutado de `removeAt`, guarda de `claimIfPlaying`, publicar y adoptar `order`, filtro de pistas compartibles, y las puertas de historial, Discord, estadísticas y fiesta. **En navegador** (dev server de la web + `wrangler dev` local + un guion de Node como móvil, sala aleatoria): la web refleja cola, índice, posición que avanza y volumen del móvil; una edición de cola desde la web (quitar + «play next») llega al móvil y vuelve; pausa/play y volumen desde la barra llegan al móvil sin tocar el volumen local; «This browser» carga la misma cola/índice y suelta al móvil; «Pixel» devuelve la sesión y la web vuelve a ser espejo; `playTrack` en la web mientras refleja suelta al móvil. Capturas en el directorio de scratchpad de la sesión (`connect-*.png`). **No verificado:** un móvil real, YouTube real ni audio (el `takeover` sin extensión termina en el error «YouTube playback needs the Auden extension» y no conserva la posición al volver a ceder; con audio real la posición sale del elemento), y el comportamiento del móvil ante `order`, `clearUpcoming` y los comandos de cola (es la implementación de Android la que decide sus semánticas).

## Orden y dependencias

```
0 Cimientos ─┬─ 1 Motor ─┬─ 2 Biblioteca local ─ 3 Playlists ─ 4 Sync
             │           └─ 8 DJ (voz)
             ├─ 5 Letras
             ├─ 6 Spotify ─ (4 Sync)
             └─ 9 Reproductor
2+3+0.3 ─ 7 Home ─ 8 DJ (pool y Home)
todo ─ 10 Backup ─ 11 Despliegue ─ (12 Descargas)
```

Las Fases 5, 6 y 9 no dependen de la biblioteca local y se pueden hacer en paralelo con
la 1–3.

## Decisiones abiertas
- Dominio y nombre del proyecto de Pages para Auden.
- Voz del DJ en un sitio público: proxy con límite (coste para ti) o clave propia de
  cada usuario.
- Firefox y carpetas locales: aceptar la re-selección por sesión o copiar a OPFS.
- Si se mantiene la sincronización con BitChord web (merges periódicos de `web/`) o la web
  de Auden se separa del todo.
