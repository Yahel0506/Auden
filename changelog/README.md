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

Write the changelog as short sections (`##` headings) of bullets that start with a bold label, so it reads the same on GitHub and in the app's update dialog. Don't include a title or install steps; both are added around it.
