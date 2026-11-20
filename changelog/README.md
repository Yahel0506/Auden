# Release changelogs

Each release has a folder named after its version (`bitchordVersionName` in `build.gradle.kts`, without any beta suffix), holding one Markdown file per language:

```
changelog/
  1.12.0/
    en.md    # required: the main changelog
    es.md
```

- `en.md` is mandatory. The release workflow stops, before building anything, if it is missing.
- The GitHub release body is `en.md` plus the install instructions the workflow appends.
- The app does not read the release body. When an update is found it fetches `changelog/<version>/<language>.md` from the tagged commit, using the language set in the app, and falls back to `en.md` when there is no file for that language. Only if neither can be fetched does it show the release body.
- To add a language, add `<code>.md` (the two-letter code, e.g. `fr.md`) to the version's folder. Nothing else needs to change.

Write the changelog as sections (`##` headings) of bullets that start with a bold label, one section per **module of the app**, so it reads the same on GitHub and in the app. In the app each section is a dropdown, closed until its row is pressed. Don't include a title or install steps; both are added around it.

Use these module headings, in this order, and leave out the ones a release does not touch (the Spanish name goes in `es.md`):

| Heading (`en.md`) | Heading (`es.md`) | What goes in it |
| --- | --- | --- |
| Player | Reproductor | the player, lyrics, Automix, playback and sources, audio output |
| Home | Inicio | Home, Top Picks, recommendations |
| Library | Biblioteca | the Library, playlists, downloads, likes, history, stats |
| Import & Transfer | Importar y transferir | bringing music in from other services and files, keeping and updating copies |
| Auden DJ | Auden DJ | the DJ |
| Account | Cuenta | the account sheet, the Auden profile, backups |
| Settings | Ajustes | Settings and its pages |
| Look & Glass | Imagen y cristal | the logo, glass, bars, menus, alerts, icons |
| Connect | Connect | Connect, Listen Together, the web player |
| Updates | Actualizaciones | update notices and the update page |
| Fixes | Correcciones | fixes that belong to no module |

A line or two of text before the first heading is shown as it is, above the dropdowns.
