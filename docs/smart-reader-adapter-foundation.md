# Smart Reader adapter foundation

## Phase 1 scope

This phase adds an independent URL/source registry for the planned Reader View flow:

1. Parse only HTTP(S) URLs.
2. Match a hostname on an exact domain or a real subdomain boundary.
3. Classify known pages as home, search, series, chapter, or other.
4. Report adapter capabilities separately from detection, so a matched chapter is not automatically treated as Reader View-ready.
5. Provide a registration hook for trusted in-process adapter modules and unit tests.

The registry is deliberately separate from the existing SiteRegistry, which is already used by the live-WebView text extraction and translation path. This phase does not change navigation, show a Reader View button, add an external source, or alter the existing OCR pipeline.

## Initial source definitions

The first registry entries recognize the four text-novel domains already handled by the app's current source-specific extraction/navigation classes:

- WebNovel
- Qidian
- WTR-LAB
- Novel543

These definitions currently advertise text extraction and chapter navigation as available, but nativeReaderReady remains false. The UI must not offer Reader View until a later phase implements and verifies the hand-off and reader content loading.

Image-page extraction is represented in the capability model for future comic adapters; it is not implemented by this phase.

## Planned follow-up work

1. Implement one source-specific chapter content provider and tests for its real page output.
2. Build a minimal native Reader prototype for text and/or image pages.
3. Connect supported chapter URLs to a user-triggered Reader View action; unmatched sites continue in the existing WebView.
4. Reuse the existing chatbot/OCR translation path where possible.
5. Add Library records that can reopen a saved source/chapter in Reader View.
6. If needed, build a user-facing extension installer with a versioned manifest, compatibility checks, clear permissions, and an update/removal UI.

The registration API only accepts already-loaded trusted adapter implementations. It is not a dynamic APK/JAR/code loader, and user-installable plugins are not enabled yet.
