# Auden independiente — plan por fases

Auden deja de ser un cliente de YouTube Music con extras y pasa a ser **un reproductor con
datos propios**. YouTube Music y Spotify son *orígenes de importación*: se lee de ellos
cuando el usuario lo pide, nunca se escribe en ellos sin que lo pida, y nada se mantiene
unido por detrás.

Rama de trabajo: `claude/auden-independent-f19575` (sale de `v2.0.0`, 2.0.0 sin liberar).
Este documento lo mantiene la sesión que ejecuta el plan; al final de cada fase se marca el
estado y se anota qué se decidió o se desvió.

**Estado:** plan revisado por el senior (PRE_FLIGHT = ADJUST, ajustes incorporados abajo, marcados «[senior]»). Fase 1 hecha; fase 2 por empezar.

---

## 0. Lo que ya existe y se reutiliza

| Pieza | Dónde | Papel en el plan |
|---|---|---|
| Likes en el dispositivo ("Mis favoritas") | `data/OfflinePlaylists.kt` (lista `liked`), `data/LikeSync.kt` (`ACCOUNT\|LOCAL\|ASK`) | Pasan a ser **los** likes de Auden. `LikeSync` desaparece. |
| Playlists locales | `dev/auden/library/LocalPlaylists.kt`, Room `AudenDb` v8 (`playlist`, `playlist_entry`, `track`, `play_event`, `spotify_match`) | Base de la biblioteca. `playlist.sourceId` ya guarda el id remoto (YT sin `VL`). |
| Importar playlists (YT, Spotify, archivo, enlace) | `ui/components/NewPlaylistFlow.kt`, `dev/auden/library/CloudSaver.kt`, `shared/.../SpotifyImporter.kt` | Se reorganiza dentro de "Transfiere tu música". |
| Cliente de biblioteca de cuenta | `YtMusicRepository` (`LIBRARY_FEEDS`, `syncLikedMusic`, `LIKED_MUSIC="VLLM"`), `SpotifyLibrary` (`LIKED_ID="liked"`, `albums()`, `artists()`) | Motor de importación. |
| Subir playlist a YT | `data/YtPlaylistSync.kt` (`plan()`/`orderTo`, con test) | Se mantiene como acción explícita "Exportar a YouTube Music". |
| Estadísticas locales | `data/stats/ListeningStats.kt` (agregados mensuales, 36 meses, top 600/400/400), `ListeningRecorder.kt`, `ArtistFacts.kt` | Única fuente de Inicio, historial y DJ. |
| Inicio local | `dev/auden/home/{HomeComposer,HomeLocal,LocalShelves,TasteShelves}.kt` (+`HomeTest`) | Se quita la parte remota; quedan las estanterías locales. |
| DJ | `dev/auden/dj/*` (`DjPool` ya usa biblioteca + `heardTracks()`) | Solo se corta el *fallback* a YT. |
| Hoja de cuenta, alertas | `ui/components/AccountSheet.kt`, `AlertScaffold`/`GlassAlertDialog` | Cabecera = perfil de Auden; botón "Transfiere tu música". |
| Nickname | `ListenTogether` (`KEY_NICKNAME`), `AccountAndScrobblingScreen` | Se convierte en el nombre del perfil. |

## 1. Hallazgos que condicionan el diseño

1. **La cuenta de YT es "la cuenta"** (`AuthStore` / `AccountSessions` / `SignedIn` /
   `MainViewModel._signedIn`). `isSignedIn` se deriva de la cookie. Hay *dos* fuentes de
   verdad (`authStore.isSignedIn` y `SignedIn.state`).
2. **Escrituras a YT hoy:** like/dislike (`MainViewModel.setLike`, corazón de la notificación
   en `PlaybackService`), crear/editar/borrar playlists (`MainViewModel` ~1455–1913),
   `YtPlaylistSync` (espejo; el dispositivo es la verdad y **borra** en YT lo que no está en
   local), pings de reproducción (`PlaybackTracker` → `pingWatchtime`, automático cada 30 s),
   scrobbling a Last.fm/ListenBrainz (servicios de terceros que el usuario activa). Spotify
   es de solo lectura.
3. **Lecturas automáticas** que traen cosas de la cuenta: `syncLikedMusic` (likes), `PlaylistAutoSync`
   (cada 6 h, copia local de playlists YT/Spotify), `PlaylistSync` (WorkManager de descargas),
   Inicio (`home()`, `homeRecentlyPlayed`, `Top Picks` con historial YT), `history()`.
4. **Los likes de la cuenta de YT no se guardan**: `syncLikedMusic` solo siembra `LikeState`
   en memoria. Al cortar el vínculo, esos corazones desaparecerían → la migración debe
   importarlos una vez.
5. **El origen de las playlists Spotify no se guarda** (`SpotifyImporter` no escribe
   `sourceId`); sin él no hay "Actualizar desde origen".
6. **Las estadísticas son agregados**, no un registro de eventos: solo se conoce la última
   escucha por pista y no hay géneros salvo Last.fm (`ArtistFacts`, necesita clave y red).
   `AudenDb.play_event` existe: hay que comprobar si se usa.
7. **Connect / Escuchar juntos** aceptan cualquier `userId` (1–128 caracteres) y no
   autentican. La sala de cuenta es `sha256("auden-connect:v2:<handle>")` (o email): hoy
   deriva del Google. La web calcula la misma sala (`roomId.test.ts` la fija).
8. **Dos árboles de strings** (`app/src/main/res`, `sharedUi` composeResources);
   `LocaleStringsTest` solo mira `app/src/main/res` y ya falla en 16 idiomas.
9. **`BitChordApp` en `MainActivity.kt` está al límite del método JVM:** todo lo nuevo va a
   clases/funciones aparte.

## 2. Principios

- **Local primero.** Una acción del usuario escribe solo en Auden. Ninguna ruta escribe en
  YT/Spotify salvo "Exportar a…", que es explícita, por playlist y con confirmación.
- **Importar ≠ unificar.** Importar copia una vez; "Actualizar desde origen" repite la
  lectura a petición. Nada se refresca solo.
- **Las cuentas externas son credenciales de importación**, no identidad. Cerrar sesión en
  YT no cambia el perfil, ni los likes, ni las playlists, ni la sala de Connect.
- **Las lecturas de catálogo no usan la cuenta.** Búsqueda, radio, páginas de artista y
  letras se piden de forma anónima; la cookie solo se usa para importar/exportar y, si el
  usuario lo permite, para reproducir con Premium.
- **Cada fase compila, pasa sus tests y es reversible por commit.**

## 3. Decisiones de diseño (recomendación; las marcadas ⚠ piden la palabra del usuario)

| # | Tema | Recomendación (implementada por defecto) |
|---|---|---|
| D1 ⚠ | Conflicto al actualizar una playlist importada | **Combinar de tres vías** con una instantánea del origen en la última sincronización: se añade lo nuevo del origen; lo que el origen quitó se quita aquí *solo si* no lo añadiste tú; lo que quitaste tú no vuelve; el orden local gana. Alternativa por playlist: **Reemplazar por el origen**. Siempre con vista previa (+N / −M). |
| D2 | Dónde vive «Subir a YT Music» | **Ya existe** como fila por playlist («Sync to YT Music», `MainActivity.kt` ~6529) y como destino de importación de archivo (~1685); ambas son acciones del usuario, no automáticas [senior]. Se queda en el menú de la playlist y se renombra **«Exportar a YouTube Music»**, con confirmación que muestra los recuentos de `plan()` (en especial lo que **se borrará en YT**). Además, un acceso desde «Transfiere tu música». |
| D3 ⚠ | Inicio "solo con estadísticas locales" | Todas las estanterías salen de las estadísticas/biblioteca locales. Las de "Porque escuchaste X" pueden resolver su contenido con consultas **anónimas** al catálogo (radio/artista de un tema que *tú* escuchaste), sin cuenta. Se elimina el feed de YT, Recientes de la cuenta y Top Picks por historial de YT. Estricto = quitar también las anónimas. |
| D4 | Identidad y Connect | **[senior] Congelar, no inventar.** Al migrar se escribe en el perfil lo que el código devuelve hoy: `partyUserId` = `identity().userId` actual y `connectRoom` = `accountRoomId()` si existe (si no, `null`). `currentRoom()` usa `connectRoom` congelado si existe y, si no, la lógica actual (Spotify + `local/<nombre>`). Si se conecta una cuenta Google después y `connectRoom` es null, se adopta una vez. Perfil nuevo: `sha256("guest:"+deviceId)`. Sin `connectKey` aleatoria ni hash `auden-profile` hasta que exista un mecanismo de emparejado. La copia de seguridad lleva el perfil, así que un segundo dispositivo lo adopta restaurándola. La web y `ConnectRoomIdTest` no cambian. Renombrar el perfil mueve la sala *local* de quien no tiene cuenta: se acepta y se avisa en una línea. |
| D5 | Foto de perfil | Archivo local (reducido, en `filesDir`). Solo se envía a Escuchar juntos si es una URL https (la heredada de YT); si no, iniciales. |
| D6 | Dislike | Deja de escribir en YT. "No me interesa" queda como marca local que excluyen Inicio y DJ (si ya hay UI); si no, se quita. |
| D7 ⚠ | Sembrar estadísticas con el historial de YT | Opcional, dentro de "Transfiere tu música" ("Usar mi historial para personalizar"), lectura única. Fase posterior. |
| D8 | TV / Wear / Web | Compilan y no se rompen. La TV conserva su inicio de sesión propio (cookie por servidor local) como fuente hasta una fase aparte; Wear sigue al teléfono; la web no se toca (la sala heredada la mantiene compatible). |
| D9 | Reproducción con Premium | La cookie puede seguir enviándose a la petición de `player` (lectura) si el usuario dejó la cuenta conectada; los pings de seguimiento se quitan siempre. |
| D10 | Scrobbling | Last.fm/ListenBrainz son servicios elegidos por el usuario y no son YT: se mantienen tal cual. |

## 4. Modelo de datos nuevo

**Perfil** (`AudenProfile`, guardado en `bitchord_settings` para que viaje en la copia de
seguridad): `id` (UUID estable, informativo), `name`, `photo` (ruta local o URL https
heredada), `partyUserId`, `connectRoom` (D4, congelados al migrar), `createdAt`,
`migratedFrom` (`yt`/`spotify`/`none`).

**Copia de seguridad [senior]:** hoy `Backup` solo exporta `bitchord_settings`, estadísticas y
Room; **no** exporta `bitchord_offline_playlists`, donde viven los likes de Auden, el
conjunto `hidden_account_likes` y las playlists en prefs. Antes de que sean la única copia
de los likes, se añaden (aditivo, `SCHEMA_VERSION=2` se mantiene: las versiones viejas
ignoran claves desconocidas), junto con el perfil y el nickname de Escuchar juntos.

**Room v9 [senior]: puramente aditivo**, con `@AutoMigration(8, 9)` si encaja (Room la
verifica contra los esquemas exportados) y `schemas/9.json`:
- Tabla `playlist_origin(playlistId PK FK CASCADE, snapshot TEXT, syncedAt INTEGER,
  originTitle TEXT)` (o columnas nulas en `playlist`; se decide al ver cuál admite
  AutoMigration). Servicio y tipo **se derivan del prefijo de `sourceId`** al leerlo
  (`spotify:playlist:<id>` → Spotify; resto → YT; `MPREb_…` → álbum): no hay *backfill*.
  Las copias existentes quedan con `snapshot` nulo (ver regla de la fase 5).
- Tabla `followed_artist` (`id`, `name`, `thumb`, `originService`, `originId`, `addedAt`).
- `BackupDao`/`LibraryData`/`replaceAll` se amplían **en el mismo commit** (si no, una
  restauración pierde las tablas nuevas).
- Los álbumes guardados siguen siendo copias en `playlist` (como hace `CloudSaver`).
- `play_event` **no se escribe nunca** hoy: el historial local sale de `ListeningStats`
  (o se empieza a escribir en la fase 6; no se asume que ya tiene datos).
- Los likes se quedan en `OfflinePlaylists.liked` (clave `localUri ?: videoId`); los Spotify
  sin coincidencia en YT se reportan y no se importan.

**Fuentes** (`MusicSource`, delgada, sin registro): `YtMusicSource` y `SpotifySource` con
`likedSongs()`, `playlists()`, `albums()`, `artists()`, `playlistTracks(id)`. Reconocen la
lista de likes (YT `LM`/`VLLM`; Spotify `liked`) y la excluyen de las playlists importables.

## 5. Fases

Cada fase: Sonnet implementa (Haiku para barridos y validación), el senior revisa el diff
real, se compila (`:app:compileDevDebugKotlin`; `installDevDebug` solo en el S24 y con poco
adb), se prueba (`:app:testDevDebugUnitTest`; los fallos de `LocaleStringsTest` son
anteriores), se actualiza `changelog/2.0.0/{en,es}.md` y se hace un commit en la rama del
worktree. Nada de push ni merge. Checkpoint del senior tras las fases 1, 3 y 5.

### Fase 1 — Perfil de Auden e identidad (Senior TAKEOVER en el núcleo)
- `AudenProfile` + `ProfileStore` (clase aparte; nada nuevo en `BitChordApp`).
- **Copia de seguridad ampliada primero** (ver sección 4): likes, hidden, playlists en
  prefs, perfil y nickname.
- Migración con **regla de congelado (D4)**: nombre = nickname actual → nombre del perfil YT
  activo → «Yo»; `partyUserId` y `connectRoom` = lo que el código devuelve hoy. Sin pérdidas.
- `ListenTogether.identity()`/`guestIdentity()` y `ConnectSync.currentRoom()` leen del perfil.
- `AccountSheet`: cabecera con el perfil; edición en `AccountAndScrobblingScreen` (el
  nickname pasa a ser el nombre). Onboarding (`ui/onboarding`, **no** `ui/welcome`): solo el
  cableado de `onNameChosen` (`MainActivity.kt` ~6821).
- Strings en/es. Tests: `ProfileMigrationTest` (con sesión YT, sin sesión, solo Spotify,
  restaurar en un segundo dispositivo), `ConnectRoomIdTest` **sin cambios**, ida y vuelta de
  la copia de seguridad.
- Puertas: tras actualizar, `partyUserId` y `currentRoom()` dan **exactamente** lo mismo que
  antes en los cuatro estados de cuenta; cerrar sesión en YT no cambia sala ni userId.

### Fase 2 — Cortar las escrituras *implícitas* a YT
- `setLike` y el corazón de la notificación: siempre semántica `LOCAL` (ya existe:
  `hidden_account_likes`). Se elimina `LikeSync` y su ajuste; se quita `LikesMergeAlert` /
  `uploadDeviceLikes` (`MainActivity.kt` ~6981–7003). Dislike según D6.
- Crear playlist: solo local. **Se mantienen** las ediciones explícitas sobre las páginas de
  YT mientras existan (hasta la fase 4) y **todos** los llamadores de `YtPlaylistSync`.
- Se quitan los pings de reproducción (`PlaybackTracker`); `player` sigue igual (D9).
- `PlaylistAutoSync` y `PlaylistSync` **no** se tocan aquí (pasan a la fase 5).
- Puertas: grep de que ningún `rate`/`pingWatchtime`/`createPlaylist` se alcanza fuera de las
  acciones explícitas; `YtPlaylistSyncTest` intacto; prueba de reproducción en el S24.

### Fase 3 — Orígenes, motor y migración de likes (Senior TAKEOVER en Room y likes)
- Room v9 (sección 4) + `BackupDao`; comprobar si un `MigrationTestHelper` corre en la JVM
  del host (Room 2.8.5 + driver); si no, validar con el JSON de esquema y **una vez** con una
  instalación que tenga una base v8 real (S24, con tu OK) comparando recuentos.
- `MusicSource` + `YtMusicSource` + `SpotifySource`; reconocer la lista de likes; artistas
  seguidos y álbumes guardados; guardar origen también para el enlace de Spotify pegado.
- `ImportEngine` puro: likes → likes de Auden, playlist/álbum → copia local, artista →
  `followed_artist`; deduplicación; informe de «sin coincidencia»; progreso.
- **Migración de likes de la cuenta de YT (una vez):** resta `hidden_account_likes`, es
  idempotente, conserva el orden, escribe en lotes (el JSON de prefs se reserializa entero),
  y marca «hecho» solo al terminar toda la cadena de continuación; reanudable.
- Las colecciones descargadas de YT que `PlaylistSync` relee se convierten en copias locales
  con origen, para que no se congelen.
- Las copias existentes con `sourceId` `LM`/`VLLM`/`spotify:playlist:liked` **no se borran**:
  se marcan y la fase 4 ofrece «convertir en Me gusta».
- Puertas: la migración conserva recuentos; la importación es idempotente y se reanuda; la
  lista de likes nunca se importa como playlist.

### Fase 4 — «Transfiere tu música» y biblioteca local
- Botón en `AccountSheet` → hoja de transferencia con tarjetas de origen (YouTube Music,
  Spotify, archivo, enlace), estado de conexión y categorías con recuentos (Me gusta,
  Playlists, Álbumes, Artistas). Reutiliza `NewPlaylistFlow`, `GlassAlertDialog` y filas de
  cristal; `spring(dampingRatio = 1f, stiffness = 300f)`.
- Usuarios con sesión de YT: **una tarjeta única** (no importación automática) que abre la
  transferencia con su biblioteca (playlists propias, álbumes guardados, artistas) ya
  marcada. Los likes ya están a salvo desde la fase 3.
- Biblioteca/Home dejan de leer la biblioteca de la cuenta en vivo; `LibraryScreen` muestra
  solo datos de Auden. Se retiran las páginas de YT con edición y `syncLikedMusic`/`LikeState`.
- Puertas: ninguna lectura en vivo de la cuenta en Biblioteca/Inicio; la tarjeta sale una vez;
  las descargas siguen funcionando sobre las copias convertidas.

### Fase 5 — Sincronización manual y exportar (Senior TAKEOVER en la mezcla)
- `PlaylistRefresher` **sustituye en el mismo commit** a la mezcla de `CloudSaver.saveNow` y a
  `PlaylistAutoSync`/`PlaylistSync` (se quitan `autoSyncPlaylists` y el WorkManager de
  lectura de YT). `SaveFromSourceTest` se actualiza a propósito, no se borra.
- Función pura de mezcla de tres vías + tests de la tabla de verdad: origen añade, origen
  quita, local añade, local quita, ambos, **instantánea nula**.
- **Regla de instantánea nula:** la primera actualización solo añade y luego registra la
  instantánea (ids: Spotify `track.spotifyId`, YT `videoId`).
- UI: «Actualizar desde origen» en el menú de cada playlist importada, con estado, vista
  previa (+N / −M) y dos botones: **Combinar** (recomendado) y **Reemplazar por el origen**
  (D1); «Actualizar todas» en Transfiere.
- «Exportar a YouTube Music» (D2) con confirmación.
- Puertas: tabla de verdad; la confirmación de exportar muestra los borrados en YT.

### Fase 6 — Estadísticas locales, Inicio, historial y DJ (CONSULT antes de `AccountScope`)
- **Primer paso: `AccountScope`** (catálogo anónimo por defecto; la cookie solo para
  importar/exportar y `player` si D9), con CONSULT del senior antes de escribir código.
- Inicio: fuera `home()`/`homeRecentlyPlayed`/suplementos, Recientes de la cuenta y Top Picks
  por historial YT; `homeComposed` depende de `HomeLocal` + `TasteShelves` (D3).
- Historial local desde `ListeningStats` (y `play_event` si se empieza a escribir).
- Estado vacío («Empieza a escuchar o transfiere tu música») y umbrales para usuarios nuevos;
  D7 opcional.
- DJ: fuera el *fallback* a charts/Quick picks de YT; alternativa con biblioteca/estadísticas;
  autoplay con `quickPicks` anónimos.
- Puertas: sin sesión, Inicio, DJ e historial se pintan solo con datos locales; el DJ tiene
  alternativa sin red.

### Fase 7 — Limpieza, TV/Wear, documentación y revisión final
- Barrido de `isSignedIn`/`SignedIn`/`requireSignIn` (significan «fuente conectada»); TV y
  Wear compilan (D8); textos en/es; changelog final; `FINAL_GATE` del senior (migración,
  identidad y todo camino de borrado).

## 6. Riesgos

| Riesgo | Mitigación |
|---|---|
| Pérdida de datos al migrar (likes de cuenta que solo viven en memoria; playlists YT que se veían en vivo) | Importación única de likes al migrar + tarjeta; migración de Room con `MigrationTestHelper` y copia de seguridad previa del JSON; ninguna fase borra datos locales. |
| Identidad: la sala de Connect y el userId derivan del Google | Congelar `partyUserId`/`connectRoom` en el perfil (D4); `ConnectRoomIdTest` sin cambios; las salas no autentican (token firmado = mejora aparte). |
| Los likes y playlists en prefs no estaban en la copia de seguridad | Ampliar `Backup` en la fase 1, antes de cualquier migración. |
| Cortar una escritura mientras se siguen mostrando páginas de YT editables las deja inertes | Fase 2 solo quita escrituras implícitas; las páginas se retiran en la fase 4. |
| `Innertube.cookie` es global; "anónimo por defecto" toca muchas llamadas | Un único punto de decisión (`AccountScope`) en la capa que construye la petición; fase 2/4/6 lo van estrechando; test que fije qué endpoints usan la cuenta. |
| Pings de reproducción desactivados pueden cambiar el comportamiento de `player` | Prueba de reproducción en el S24 al final de la fase 2 (adb mínimo). |
| No hay `MigrationTestHelper` en el host | Validar el JSON de esquema y una instalación con base v8 real, una sola vez, con tu OK. |
| `BitChordApp` al límite del método JVM | Toda la lógica nueva en clases/funciones fuera de `MainActivity.kt`. |
| Estadísticas pobres para usuarios nuevos o con cola larga (top 600 por mes) | Estado vacío claro, importación como siembra (D7), umbrales revisados. |
| Strings: nuevas claves fallan `LocaleStringsTest` en 16 idiomas | Solo en/es (preexistente, no se "arregla" con fallbacks). |
| Cambios concurrentes del usuario en UI en la sesión principal | Cambios de UI mínimos y reutilizando componentes; no tocar `ui/welcome` ni `ui/update`. |
| TV mantiene inicio de sesión YT propio | D8: fase aparte; no se rompe. |

## 7. Registro de estado

| Fase | Estado | Commit | Notas |
|---|---|---|---|
| 0 Base y plan | **hecho** (plan aprobado con ajustes) | — | worktree en `v2.0.0` (`21536f4d`); PRE_FLIGHT del senior: ADJUST incorporado |
| 1 Perfil e identidad | **hecha** | ver `git log` | Núcleo por el senior (TAKEOVER): `dev/auden/profile/*`, congelado de `partyUserId`/`connectRoom`, copia de seguridad ampliada (likes, playlists en prefs, nickname, perfil). UI: cabecera de `AccountSheet` y `ProfileEditGroup` (nombre + foto local). Añadido `nameIsDefault` para que la fiesta siga viendo «Guest». Probado: compila, `ProfileMigrationTest` (16), `ConnectRoomIdTest` sin cambios, Backup. No probado en dispositivo. |
| 2 Cortar escrituras | pendiente | — | |
| 3 Orígenes y motor | pendiente | — | |
| 4 Transfiere tu música | pendiente | — | |
| 5 Sync manual + exportar | pendiente | — | |
| 6 Estadísticas, Inicio, DJ | pendiente | — | |
| 7 Limpieza y cierre | pendiente | — | |
