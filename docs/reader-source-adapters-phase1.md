# Reader View source adapters — Phase 1 and current prototype

## Goal

Keep Novel Studio's existing browser behavior unchanged while offering an optional native Reader View for chapters that a built-in source adapter can extract. Unsupported pages must keep working in the normal WebView.

## Existing source architecture

The base branch already contains four built-in text-source adapters:

- `WebNovelSystem`
- `WtrLabSystem`
- `QidianSystem`
- `Novel543System`

They implement the existing `SiteAdapter` contract and share the `Chapter` model. This work added **zero new website adapters**. It adds URL validation and a safe registry-resolution entry point, then reuses the existing extractors.

## Work added on `feat/reader-source-adapters-phase1`

- `ReaderSourceUrl` validates ordinary HTTP(S) URLs before source routing.
- `SiteRegistry.resolve` returns a matching built-in adapter only after URL validation.
- `offerReaderViewIfSupported` runs on browser page completion, asks the matching adapter to extract the visible page, and offers **Open in Reader View** only when there is evidence that the page is a chapter.
- `SourceReaderActivity` provides a clean native text-reading screen, source-page return, font-size controls, and original/translated text switching.
- `ReaderViewBridge` sends translation requests into MainActivity's existing chatbot queue and returns a completed translation to the source reader instead of inserting it into the underlying web page when that reader is active.
- Unit tests cover HTTP(S) URL validation and host normalization.

## Current vertical-slice limits

- This prototype covers text chapters returned by the four existing adapters. It does not yet extract manga image lists or build a new image-based comic reader.
- A domain match is only a candidate. Home pages, search results, catalogues and login pages should not open Reader View unless chapter extraction and chapter-page checks succeed.
- A site not matched by `SiteRegistry` stays in the existing WebView without being redirected.
- The build and tests must pass in CI, and the source reader must be tested on-device against a real chapter before this slice should be considered verified.

## Future extension / plugin compatibility

Keep source adapters behind a stable contract and the registry as the single lookup point. A future extension system can add source definitions without changing the normal browser fallback. User-installed plugins are **not** enabled by this phase.

A future plugin system should use explicit manifests, API compatibility checks, source permissions, trusted installation sources, and clear removal/update controls. Do not execute arbitrary downloaded Kotlin/DEX code or grant plugins unrestricted access to cookies, local files, chatbot credentials, or page contents.

## Future Home / Library compatibility

The later Home / Library feature should store source-neutral metadata (title, source ID, canonical page URL, chapter URL, last-read position, and optional cache metadata). Opening a saved item should route through the same source registry and reader hand-off. The library should not depend directly on any single source's extractor class.

## Intended flow

`Google / URL -> existing WebView -> validated URL -> built-in source adapter -> chapter extraction -> user confirms Reader View -> native text reader -> existing chatbot translation`

If source matching or extraction fails, keep the normal browser behavior.
