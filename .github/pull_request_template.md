<!--
  Please include a summary of the change and which issue is fixed.
  Also make sure you've tested your code and also done a self-review of it.
  Don't forget to check all base themes and tablet mode for relevant changes.
  
  If your changes are visual, please provide images below:

### Images
| Image 1 | Image 2 |
| ------- | ------- |
| ![](https://github.githubassets.com/images/modules/logos_page/Octocat.png) | ![](https://github.githubassets.com/images/modules/logos_page/Octocat.png) |
-->

---

## Checklist

- [ ] `./gradlew spotlessApply spotlessCheck` passes, and `./gradlew testDebugUnitTest` passes.
- [ ] Upstream code edits are wrapped in the correct `// KUN` / `// KMK` / `// SY` markers, and new strings live only in `i18n-kmk` base (`KMR`).
- [ ] If a GPL-free seam file or a module dependency changed: `./gradlew verifyGplFree` passes (see the **`gpl-boundary`** skill).
- [ ] If the Yomitan corpus changed or the pinned Yomitan commit moved: refreshed via the **`yomitan-corpus-sync`** skill (and `YomitanCorpusTest` is green).
- [ ] If a vendored dependency moved (the `hoshidicts` submodule, the `webgpuviewer` fork AAR, or the Paddle OCR artifact): followed the **`immersion-deps-sync`** / **`ocr-model-release`** skills.
- [ ] If `.sq` / `.sqm` changed: `./gradlew :data:verifyDebugDatabaseMigration` passes.

---

Add a :+1: [reaction] to [pull requests you find important].

[reaction]: https://github.blog/2016-03-10-add-reactions-to-pull-requests-issues-and-comments/
[pull requests you find important]: https://github.com/komi-kun/komi-kun/pulls?q=is%3Aopen+sort%3Areactions-%2B1-desc
