# Auden — Roadmap

Auden es un fork personal de BitChord (base: `upstream/v1.8.1` + letras offline del
fork, rama `bitchord-main`). Une el cliente de YouTube Music con estilo Apple Music
de BitChord con la gestión de biblioteca local avanzada de un reproductor como
Namida.

Este documento ordena el trabajo por dependencias, no por importancia. Cada fase
deja la app usable y se puede publicar como versión.

---

## 0. Punto de partida (lo que ya existe)

| Área | Estado actual | Archivo clave |
|---|---|---|
| Persistencia | Todo en `SharedPreferences` / JSON. No hay base de datos. | `data/settings/*`, `download/Downloads.kt` |
| Playlists locales | `LocalPlaylistStore` existe, pero solo guarda playlists importadas de Spotify como JSON en prefs. | `data/spotify/LocalPlaylistStore.kt` |
| Música local | Escaneo de MediaStore y de una carpeta elegida (SAF), sin índice persistente. | `data/LocalMediaRepository.kt` |
| Descargas | Un nombre de archivo plano por pista dentro de `RELATIVE_PATH`; registro de colecciones en prefs. | `download/DownloadStore.kt`, `Downloads.kt` |
| Letras | 18 proveedores, artifacts + sidecar `.lrc/.ttml` + tag embebido (fork). El offset es **global** (`PlayerSettings.lyricsOffsetMs`). | `shared/.../lyrics/*`, `download/LyricsSidecarStore.kt` |
| Spotify | Login con WebView (`sp_dc`), token cosechado con otra WebView fuera de pantalla, biblioteca solo lectura. | `auth/SpotifyLoginScreen.kt`, `data/canvas/SpotifyToken.kt` |
| Estadísticas | `ListeningRecorder` / `ListeningStats` + backup. | `data/stats/*` |
| ML on-device | `onnxruntime-android` ya incluido, con `beat_this_int8.onnx` y `vocals_umxhq_int8.onnx`. | `playback/smart/*` |

**Conclusión:** casi todas las funciones nuevas necesitan una biblioteca indexada
con consultas. Por eso la base de datos va primero.

---

## Fase 0 — Cimientos (rebrand, ramas, base de datos)

### 0.1 Estrategia de ramas
- `main` del fork = Auden. Se crea desde `bitchord-main` y pasa a ser la rama por
  defecto en GitHub. El `main` antiguo se conserva como tag `archive/bitchord-main-old`.
- `upstream-sync/*`: una rama por cada versión de upstream que se fusione
  (`upstream-sync/v1.9`), con su merge y la resolución de conflictos, que luego entra en `main`.
- `feat/*` por función, con PR contra `main`.
- Se archivan como tags y se borran: `v1.5`, `v1.5.1`, `feat/lyrics-downloads` y `feat/ui-ux-refinement` (ya están en `bitchord-main`).

### 0.2 Rebrand mínimo y reversible
- Cambiar: nombre visible (`app_name` en todos los `values-*`), icono y logo,
  `applicationId` (p. ej. `dev.auden.app`, para instalarla junto a BitChord),
  User-Agent propio y textos de “Acerca de” y de soporte.
- **No** renombrar el paquete Kotlin `com.music.bitchord` por ahora: tocaría unos
  1000 archivos y cada merge de upstream sería un conflicto. Se puede revisar cuando
  Auden deje de seguir a upstream.
- Separar los flavors `dev` y `prod` por `applicationIdSuffix`.

### 0.3 Base de datos de biblioteca (`AudenDb`)
- **Room KMP** (2.7+) en `:shared`, para que la desktop pueda reutilizarla, o Room
  solo en `:app` si KMP da problemas con el build actual.
- Tablas iniciales:
  - `track` (id interno, `videoId?`, `spotifyId?`, `isrc?`, `localUri?`, ruta,
    hash/fingerprint, título, artistas, álbum, duración, número de pista,
    origen: `YTM | LOCAL | DOWNLOAD | SPOTIFY_MAP`)
  - `album`, `artist` y `track_artist` (N:M)
  - `playlist`, `playlist_entry` (orden estable, `entryId` propio)
  - `lyrics_variant` (ver Fase 3)
  - `play_event` (lo que hoy guarda `ListeningRecorder`)
- Migración de una sola vez desde prefs/JSON: `LocalPlaylistStore`, metadatos de
  `Downloads`, colecciones y stats. Se mantiene la lectura de lo antiguo durante
  una versión, por seguridad.

**Entregable:** Auden instalable junto a BitChord, con los mismos datos migrados a
la base nueva y sin cambios visibles.

---

## Fase 1 — Corrección del crash de Spotify (puede ir en paralelo con la Fase 0)

Todavía no se ha reproducido. Primer paso: `adb logcat` con el crash para saber
dónde ocurre. Hipótesis, de más a menos probable:

1. `onConnected` se llama dentro de `WebViewClient.onPageFinished`. Eso cambia el
   estado de Compose, el `AndroidView` sale de la composición y `onRelease` llama a
   `WebView.destroy()` mientras la WebView sigue dentro de su propio callback.
   **Arreglo:** publicar el token con `view.post {}` o con un `LaunchedEffect` y
   destruir la WebView después (`stopLoading`, `removeAllViews`, `destroy`).
2. Justo después del login, la Biblioteca lanza `SpotifyLibrary.playlists()`, que
   crea **otra** WebView fuera de pantalla (`SpotifyToken.harvestViaWebView`)
   mientras la del login todavía se está destruyendo. Dos WebView, cookies
   compartidas y `WebStorage.deleteAllData()` a la vez. **Arreglo:** serializar
   ambas o reutilizar la misma WebView del login para cosechar el primer token.
3. Una excepción en el `LaunchedEffect` fuera del `runCatching` (por ejemplo
   `SpotifyToken.init` no llamado, o `IllegalStateException` en el hilo principal).

Después: test instrumentado del flujo `login → token → playlists`.

---

## Fase 2 — Biblioteca local de primera clase (estilo Namida)

> **Estado:** implementada en `feat/auden-library`.
> - 2.1 Indexador incremental (nuevos/cambiados/borrados), pestañas Canciones, Artistas (separados por `&`, `,`, `;`…, con lista de nombres que no se parten), Álbumes, Géneros y Carpetas.
> - 2.2 Identificación: id de YouTube en el nombre del archivo, búsqueda en YouTube Music con `TrackMatcher`, sugerencias dudosas en una hoja de revisión, progreso en notificación. Los archivos identificados enlazan con sus canciones de streaming (marca de descargada, reproducción local, “Administrar descarga”).
> - 2.3 Playlists locales sin sesión sobre `AudenDb`: añadir, quitar, reordenar, duplicar, portada en mosaico, guardar playlists y álbumes de YouTube Music y Spotify con sincronización.
> - 2.4 Importar/exportar M3U8, XSPF y Auden JSON.
> - Pendiente: huella acústica (Chromaprint/AcoustID), importar CSV, ajustes de filtros de indexado (duración y tamaño mínimos, carpetas excluidas).

### 2.1 Indexador
- `LibraryIndexer` (WorkManager): recorre MediaStore y las carpetas SAF elegidas,
  lee tags con el parser que ya existe (`EmbeddedLyrics`/`MediaTagger`) y guarda en
  `track`. Es incremental (por `DATE_MODIFIED` y tamaño) y se puede reanudar.
- Vistas de Canciones, Álbumes, Artistas, Carpetas y Géneros sobre la base de
  datos, con ordenación y filtros rápidos.

### 2.2 Identificación de música ya descargada
Cascada barata → cara, deteniéndose en la primera coincidencia con confianza
suficiente:
1. **Tags propios:** archivos de BitChord/Auden que llevan `videoId` en un tag
   privado (se añade a `MediaTagger`). Coincidencia exacta.
2. **ISRC** en tags → `BiniLyrics.identify` / búsqueda en YT Music.
3. **Texto + duración:** título y artista normalizados ±2 s contra la búsqueda de
   YT Music (`YtMusicRepository`) y LRCLIB, que ya se usan. Puntuación con
   Jaro-Winkler y la duración.
4. **Fingerprint (opcional, después):** Chromaprint (NDK) + AcoustID →
   MusicBrainz, para archivos sin tags.
- Resultado: vincular `track.videoId` y descargar portada, álbum, letras y artista.
  Nunca se reescribe el archivo sin confirmación; la opción “Escribir tags” va aparte.
- Pantalla de revisión: “N canciones identificadas, M dudosas”, con aceptar o
  corregir.

### 2.3 Playlists locales sin sesión
- Playlists en `AudenDb`, que mezclan pistas locales y de YT Music (una entrada
  apunta a `track.id`, que puede tener `localUri`, `videoId` o ambos).
- Al reproducir: archivo local si existe, si no streaming.
- Crear, renombrar, reordenar, duplicar, portada automática en mosaico y fijar en
  Inicio.
- `LocalPlaylistStore` (Spotify) pasa a ser una fuente de importación más.

### 2.4 Importar / exportar playlists
- **Exportar:** `.m3u8` (rutas relativas a la carpeta de música, para que otros
  reproductores lo lean), `.xspf` y `.json` de Auden (con `videoId`/ISRC, sin pérdida).
- **Importar:** `.m3u/.m3u8` (resuelve rutas y, si no existen, busca por
  `#EXTINF` título/artista), `.xspf`, `.json` de Auden, CSV (Exportify/Spotify) y
  playlists de YT Music y Spotify por URL.
- Usa el mismo motor de coincidencia que 2.2. Las pistas sin resolver se guardan
  como “pendientes”, no se descartan.

---

## Fase 3 — Descargas organizadas y compatibles

### 3.1 Estructura en disco (configurable por plantilla)
```
Music/Auden/
  {AlbumArtist}/{Year} - {Album}/
    {Disc}{Track:02} - {Title}.{ext}
    {Disc}{Track:02} - {Title}.lrc        ← letras sincronizadas (proveedor activo)
    {Disc}{Track:02} - {Title}.ttml       ← opcional, palabra a palabra
    cover.jpg                             ← portada del álbum, 1000px+
  Playlists/
    {Playlist}.m3u8                       ← rutas relativas: ../{AlbumArtist}/...
  Singles/ (o "Sin álbum")
```
- Plantilla con tokens (`{artist}`, `{album}`, `{track}`…), saneamiento de nombres
  para FAT32/SAF y previsualización en Ajustes.
- Tags completos en todos los formatos (`MediaTagger`): album artist, número de
  pista y disco, año, género, ISRC, portada embebida, `videoId` privado y letras.
- Al descargar una playlist o un álbum se genera y mantiene su `.m3u8`.

### 3.2 Gestor de descargas
- Cola persistente en la base de datos (hoy está en memoria y prefs): prioridad,
  pausa, reintento con backoff, límite de concurrencia y solo Wi-Fi por cola.
- Pantalla “Descargas”: activas, fallidas (con motivo), completadas y espacio usado
  por álbum o artista.
- **Migración** de descargas existentes a la estructura nueva: mover o renombrar
  por lotes con vista previa y deshacer, actualizando `localUri` en la base.
- Detección de huérfanos (archivo sin registro y viceversa) y de duplicados.

---

## Fase 4 — Gestión avanzada de letras

Sobre lo que ya hace el fork (artifacts, sidecar y tag embebido):

- **Variantes por canción:** tabla `lyrics_variant(trackId, provider, format,
  content, fetchedAt, isActive, offsetMs)`. Se pueden descargar varias.
- **Cambiar proveedor:** hoja “Letras” en `SongActionsSheet`: lista de variantes
  guardadas, “buscar en…” (cada proveedor por separado, usando los callbacks
  `onSourceResult` que ya existen en `LyricsRepository`), vista previa y “usar esta”.
- **Descarga múltiple:** ajuste “proveedores a guardar al descargar” (p. ej. el
  mejor sincronizado por sílaba + LRCLIB como respaldo). Al disco solo va la activa
  (`.lrc` compatible); las demás se guardan en la base o en `.auden/lyrics/`.
- **Offset por canción:** `lyrics_variant.offsetMs` se suma al offset global de
  `PlayerSettings.lyricsOffsetMs`. `LyricsOffsetSheet` gana un selector
  “esta canción / global”. Exportar el offset como `[offset:]` en el `.lrc`.
- **Editor ligero:** corregir texto y ajustar marcas de tiempo tocando al ritmo
  (tap-to-sync) para guardarlo como variante “manual”.
- **Por lotes:** “Buscar letras para toda la biblioteca / álbum / playlist”, en
  segundo plano y con informe.

---

## Fase 5 — Spotify al nivel de YT Music

Después de la Fase 1:
- Biblioteca: playlists, canciones guardadas, álbumes y artistas seguidos, con
  caché en la base de datos (`track.spotifyId`).
- Mapeo Spotify → YT Music con el motor de la Fase 2.2, persistente y corregible a
  mano (“esta no es, elegir otra”).
- Sincronización: importar como playlist local que se mantiene actualizada.
  Escribir de vuelta en Spotify solo como fase posterior y opcional.
- Inicio: secciones de Spotify (Hechos para ti, Recientes) si la API web interna
  lo permite.
- Unificar la sesión: un solo `SpotifySession` con su ciclo de vida, en lugar de
  WebViews sueltas, y estados de error claros (caducada o rate-limit).

---

## Fase 6 — Inicio híbrido (algoritmo + local)

- Nuevo `HomeComposer` que mezcla shelves de YT Music (las de ahora) con shelves
  locales calculadas desde `play_event` y `track`:
  - *En bucle* (reproducción local de los últimos 30 días)
  - *Redescubre* (muy escuchado hace más de 90 días y nada desde entonces)
  - *Añadido recientemente* (descargas e índice)
  - *Tus playlists* (fijadas, locales)
  - *Mix de artista* (pistas locales y de YTM de un artista frecuente)
  - *Aleatorio de biblioteca* sin conexión
- Reglas de orden: alternar fuentes; sin red, solo local; las shelves vacías se ocultan.
- Ajustes: activar o desactivar cada shelf y el peso de “algoritmo ↔ local”.
- Funciona sin iniciar sesión en YT Music; Inicio nunca queda vacío.

---

## Fase 7 — DJ con IA local ligera

Por capas, cada una útil por sí sola:

1. **Selector (sin LLM):** puntuación por similitud (artista, álbum,
   co-reproducción en `play_event`, “saltada”/“terminada”), BPM y beat del
   modelo `beat_this` que ya está incluido, energía e idioma. Genera la cola y
   respeta “más de esto / menos de esto”.
2. **Transiciones:** usar beats/downbeats (`TrackAnalyzer`) con el
   `CrossfadeController` que ya existe para mezclar a tempo.
3. **Voz del DJ (opcional):** un LLM pequeño on-device (Gemma 3 1B o Qwen 2.5
   0.5B cuantizado, vía MediaPipe LLM Inference o llama.cpp) que redacta una
   frase corta entre bloques (“Ahora, algo más tranquilo de…”), y Android
   `TextToSpeech` para leerla con *ducking* del audio.
   El modelo se descarga bajo demanda (no va en el APK) y todo funciona sin él.
4. **Peticiones en lenguaje natural:** “algo para concentrarme sin letra” → el LLM
   lo traduce a filtros del selector (energía, instrumental, género). Nunca elige
   canciones directamente.

---

## Orden propuesto y dependencias

```
Fase 0 (ramas, rebrand, AudenDb) ──┬─> Fase 2 (biblioteca) ─┬─> Fase 3 (descargas)
Fase 1 (crash Spotify) ────────────┘                        ├─> Fase 4 (letras)
                                                            ├─> Fase 5 (Spotify)
                                                            └─> Fase 6 (Inicio) ─> Fase 7 (DJ)
```
- Las fases 3, 4 y 5 son independientes entre sí una vez existe la Fase 2.
- Después de cada fase: fusionar la última versión de upstream en `upstream-sync/*`,
  para que la divergencia no se acumule.

## Riesgos
- **Divergencia de upstream:** la mayoría del código nuevo va en archivos y módulos
  nuevos (`auden/...`), con los cambios en los archivos de upstream reducidos a
  puntos de enganche.
- **APIs no oficiales** (YT Music, Spotify, proveedores de letras): todo detrás de
  interfaces con *fallback*, sin depender de una sola.
- **SAF / almacenamiento scoped:** mover archivos y escribir tags en carpetas del
  usuario requiere permiso persistente de árbol; se pide de forma explícita.
- **Tamaño del APK:** modelos de IA y fingerprint solo bajo demanda.
