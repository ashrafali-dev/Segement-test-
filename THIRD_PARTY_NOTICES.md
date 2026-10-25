# Third-party notices

## Keiyoushi community extensions-source — Naver Webtoon parsing reference

Novel Studio's Naver image-source adapter follows the reader-page selector precedence and lazy-image parsing behavior documented in the project's Naver source implementation. The Novel Studio implementation is adapted to its own constrained `ImageChapterAdapter` API; it does not import or execute Mihon extension code.

- Project: https://github.com/keiyoushi/extensions-source
- Referenced file: https://github.com/keiyoushi/extensions-source/blob/main/src/ko/navercomic/src/eu/kanade/tachiyomi/extension/ko/navercomic/NaverComic.kt
- License: Apache License 2.0
- License text: `third_party/licenses/Apache-2.0-Mihon-extensions-source.txt`

Changes for Novel Studio include URL validation, lazy-image fallback handling, page URL deduplication, source-specific routing, and compatibility selectors. The upstream source is credited as a behavior and parsing reference; no upstream extension APK or runtime code is bundled.
