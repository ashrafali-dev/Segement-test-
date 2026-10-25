# Reader View source adapters — Phase 1

## Goal

Keep Novel Studio's existing browser behavior unchanged while creating a safe foundation for an optional Reader View hand-off on supported sites.

## Current code found in this branch

The app already has a small adapter architecture:

- `SiteAdapter` defines extraction, chapter navigation, prefetch and translation insertion.
- `SiteRegistry` currently registers WebNovel, WTR-LAB, Qidian and Novel543 adapters.
- Each source has its own navigator/extractor/writer implementation.
- `Chapter` is the shared extracted-content model.
- `ReaderActivity` currently reads saved text chapters from `Store`; it is not yet the image/chapter Reader View requested for browser pages.

Phase 1 does not replace or rewire these existing systems.

## Phase 1 contract

1. Validate that a candidate URL is an ordinary HTTP(S) web URL before handing it to source routing.
2. Ask the existing registry whether a known adapter matches the URL's domain.
3. Treat a domain match as a *candidate only*. A site can also show a home page, search results, catalog, paywall or login page.
4. Offer Reader View only after the adapter has successfully extracted a real chapter and its content is usable.
5. If validation or source matching fails, leave the current WebView navigation alone.
6. Keep extraction and translation behind the existing `SiteAdapter` contract so the next phase can pass extracted content to a dedicated reader without creating a second translation engine.

## Future flow

`WebView URL -> ReaderSourceUrl validation -> SiteRegistry match -> adapter extraction -> user confirms Open in Reader View -> reader presentation -> existing translation pipeline`

The UI hand-off and chapter/image reader are intentionally out of scope for Phase 1.

## Plugin / extension compatibility

Keep source adapters behind a stable contract and keep the registry as the single lookup point. That allows a future extension system to add source definitions without changing the browser fallback. User-installed plugins are **not** enabled by this phase.

A future plugin system should use explicit manifests, version/API compatibility checks, source permissions, trusted installation sources, and clear removal/update controls. Do not execute arbitrary downloaded Kotlin/DEX code or grant plugins unrestricted access to cookies, local files, chatbot credentials, or page contents.

## Planned library compatibility

The later Home / Library feature should store a source-neutral item (title, source ID, canonical page URL, chapter URL, last-read position, and optional cached content metadata). Opening a saved item should route through the same source registry and reader hand-off. Do not make the library depend directly on any single source's extractor class.

## Validation

Unit tests cover HTTP(S) URL validation, malformed URLs, unsupported schemes, and host normalization. A full Android build and device-level source extraction test must still be run before calling the feature production-ready.
