# Reader View source adapters — Phase 1 / Step 2

## Goal

Keep Novel Studio's existing browser behavior unchanged while offering optional Reader Views for pages that a built-in source adapter can parse. Unsupported pages stay in the normal WebView.

## Existing source architecture

The base branch contains four built-in text-source adapters:

- `WebNovelSystem`
- `WtrLabSystem`
- `QidianSystem`
- `Novel543System`

They implement the existing `SiteAdapter` contract and share the `Chapter` model. This feature adds **one image-source adapter** for Naver Webtoon episode detail pages; it does not add 30–40 sources at once.

## Implemented foundation

- `ReaderSourceUrl` validates HTTP(S) URLs before source routing.
- `SiteRegistry.resolve` returns a matching built-in text adapter only after URL validation.
- `SourceReaderActivity` is a clean native text reader that can request translation from MainActivity's existing chatbot queue.
- `ReaderViewBridge` returns completed text translations to the matching reader rather than inserting them into the underlying page when the reader is active.
- Unit tests cover HTTP(S) URL validation and host normalization.

## Step 2: Naver image chapter prototype

- `NaverComicImageAdapter` recognizes Naver comic episode-detail URLs with `titleId` and `no` query values.
- The adapter reads page URLs from Naver's chapter-specific viewer elements (including `.wt_viewer img` and `.toon_view_lst img.toon_image`), handles common lazy-image attributes, removes duplicate/invalid URLs, and preserves DOM page order.
- `ComicReaderActivity` creates a clean image-only reader from those URLs; the normal browser page remains underneath and can be revisited with Back/Source.
- Pressing **OCR + Translate** sends the clean reader WebView to the existing `WebModeExtractor`, then sends the recognized chapter text through the existing chatbot queue.
- The OCR metadata bridge preserves the original source URL/title so bubble geometry and translation alignment remain keyed to the source chapter.
- The translated bubbles are drawn over the images in the clean reader. Normal browser real-time scanning is paused while the native comic reader is active and scheduled again when it closes.
- Unit tests cover Naver episode URL matching and image URL validation, deduplication and ordering.

## Current limits

- This is the first image-source vertical slice. Real-device testing against a logged-in / accessible Naver episode is still required; build and JVM tests cannot verify a site's live DOM, image hotlink protection, or visual overlay alignment.
- The adapter is intentionally restricted to chapter-detail URLs and the source's known reader containers. If Naver changes its markup, the adapter will need an update.
- The prototype does not yet cover canvas-only sources that never expose image URLs, arbitrary websites, or all comic platforms.
- The 4 built-in text adapters and this 1 image adapter are not a full 30–40 site catalogue.
- User-installed plugins/extensions are **not** enabled yet.

## Future extension / plugin compatibility

Keep source adapters behind explicit contracts and keep registries as the single lookup points. A future extension system can add source definitions without changing the browser fallback. A production plugin system should use a signed/validated manifest, API compatibility checks, permission boundaries, trusted installation sources, and clear removal/update controls. Do not execute arbitrary downloaded Kotlin/DEX code or give plugins unrestricted access to cookies, local files, chatbot credentials, or page contents.

## Future Home / Library compatibility

The later Home / Library feature should store source-neutral metadata (title, source ID, canonical page URL, chapter URL, last-read position, and optional cache metadata). Opening a saved item should route through the same source registry and Reader View hand-off. The library should not depend directly on a particular source adapter.

## Intended flow

`Google / URL -> existing WebView -> built-in adapter -> chapter image URLs -> user confirms Comic Reader -> clean image-only WebView -> existing OCR -> existing chatbot translation -> aligned bubble overlay`

If source matching or extraction fails, keep the normal browser behavior.
