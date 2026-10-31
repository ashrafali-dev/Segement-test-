# ToonSensei UI/UX Redesign — TODO

Living status file. Group → screen → sub-task (Design · Implement · Audit · Polish).
Branch, base: `in-1-minute,-later-detector`. Design specification (colors, type, spacing, components,
screen briefs): **[docs/redesign/DESIGN.md](docs/redesign/DESIGN.md)**. That file is the single source of
truth — every screen must be built from its tokens; no ad-hoc values.

Status legend: `[ ]` todo · `[~]` in progress · `[x]` done · `[-]` out of scope (with reason)

---

## Using a design-system skill (FAQ)

| Skill (as in the task text) | Status |
|---|---|
| UI/UX Pro Max (`JaimeJunr/claude-code-frontend-skills`) | **Not installed** — this XINCLI build has no `/plugin marketplace add` command, and `~/.xincli/plugins/marketplaces` is empty. Design system written by hand in `docs/redesign/DESIGN.md` following the same principles (token-first, WCAG 2.1 AA, 4/8 grid, component states). |
| Anthropic Frontend Design | Not installable CLI-side; applied as principles: one ownable identity (paper + ink + seals), no template look, no emoji in chrome. |
| Vercel Web Design Guidelines (`vercel-labs/agent-skills`) | Not fetchable offline; the actionable subset is codified as the **Audit gate** below and run per group. |
| Emil Kowalski / Impeccable (`master5d/claude-design-skills`) | Not fetchable offline; motion rules codified as **Motion tokens** in DESIGN.md (springs, 150–260 ms, honour reduced-motion). |

Everything below is written from those principles so the redesign does not depend on the plugins.

---

## Platform facts that shape the work

- No Compose, no XML layouts: every screen is programmatic `View` code.
  `MainActivity.kt` (11.4k lines) · `ReaderActivity.kt` · `Home.kt` · `Ui.kt` (Aurora Glass) ·
  `ModeUi.kt` · `ModeChip`/`ModeCapsule`/`ExtractButton`/`BookwormProgress`/`ActivityChip`/`TourOverlay`.
- 36 `AlertDialog.Builder` sites (emoji-prefixed labels, always dark, hairline-less) + 7 `Sheets`.
- Android chrome is dark-only today (`UiTheme` has no light palette); inline web content follows the
  site's own theme via `Js.kt` → needs a `prefers-color-scheme` bridge.
- Four modes confirmed by product owner: **Novel · Scanning · Real-time · Manual Lite**
  (Manual Lite = the existing `manualExtractOn()` path, one tap → one crop/OCR).

## Audit gate (every screen, before `[x]`)

1. Light **and** dark (`ToonTheme.dark`), 360 dp and ≥ 412 dp widths.
2. Loading / empty / error / offline / permission states present or explicitly N/A.
3. Contrast ≥ 4.5:1 text, ≥ 3:1 UI strokes; focused state visible (keyboard + TalkBack).
4. `contentDescription` on every icon-only control; section headings exposed.
5. Touch targets ≥ 48 dp (even if the visual is 40 dp → 4 dp invisible padding).
6. Motion on enter/exit/selection only, ≤ 260 ms, respects system animator scale 0.
7. No hard-coded colour outside `ToonTheme`; no emoji in app chrome.

## Motion tokens (Emil-style)

| Token | Use | Value |
|---|---|---|
| `tokDurFast` | press/ripple, chip toggle | 110 ms decelerate |
| `tokDurBase` | sheet slide, list reveal | 220 ms decelerate |
| `tokDurSlow` | full-screen fade, mode switch | 260 ms decelerate |
| `tokSpring` | selection thumb, per-item stagger | Overshoot 1.4, stagger 40 ms |

---

## Group S — Design system (must finish first)

Branch `ui/design-system`.
Files: `ToonTheme.kt` (tokens, day/night palettes, 4 mode accents), `ToonComponents.kt`
(button, icon button, chip, segment, card, list row, toggle row, field, sheet, dialog, snackbar,
banner, empty/error/loading/skeleton, section header, toolbar), `ToonIcon` extension of `Ic`
(calendar, pause, play, mic, download, external-link, gallery, crop, translate, history).

- [x] Design brief + full token spec (`docs/redesign/DESIGN.md`)
- [ ] Tokens implemented (colors both modes, type scale, space 4/8, radius, border, elevation, motion)
- [ ] Component library + usage rules; state matrix (default/hover?/pressed/focused/disabled/loading)
- [ ] Compatibility shim: `UiTheme.*` deprecated aliases → `ToonTheme` so old call sites compile
- [ ] Skeleton/empty/error primitives

## Group H — Common overlays (first code group)

Branch `ui/group-h-overlays`.

| # | Screen | Source | Prio | Design | Impl | Audit | Polish |
|---|---|---|---|---|---|---|---|
| H01 | Sheet frame (handle, title, close, drag-dismiss, safe-area) | `Sheets.create` | P0 | [ ] | [ ] | [ ] | [ ] |
| H02 | Dialog frame (centre, title/message/actions, destructive variant) | `AlertDialog.Builder` ×36 | P0 | [ ] | [ ] | [ ] | [ ] |
| H03 | Action list sheet (grouped rows, chevrons, toggles, badges) | `listDialog` | P0 | [ ] | [ ] | [ ] | [ ] |
| H04 | Searchable list sheet | `searchListDialog` | P1 | [ ] | [ ] | [ ] | [ ] |
| H05 | Confirm dialog (delete novel / chapter / model) | `novelActions`, `chapterList`, `inpaintDialog` | P0 | [ ] | [ ] | [ ] | [ ] |
| H06 | Input dialog (bookmark name, rename novel) | `renameNovel`, `saveBookmark` flow | P1 | [ ] | [ ] | [ ] | [ ] |
| H07 | Prompt editor (multi-line, Save / Reset / Cancel) | `editPrompt`/`editScanPrompt`/`editRealtimePrompt` | P1 | [ ] | [ ] | [ ] | [ ] |
| H08 | Numeric / settings dialog (send-hold ms, scan direction) | `editSendHold`, `scanDirectionDialog` | P1 | [ ] | [ ] | [ ] | [ ] |
| H09 | Snackbar (single line, action, queue, error tint) | `showSnack`/`toast`/`hideSnack` | P0 | [ ] | [ ] | [ ] | [ ] |
| H10 | Banner (inline notice, amber warn/red error/blue info, dismiss) | HTML `Notice.html` — new | P1 | [ ] | [ ] | [ ] | [ ] |
| H11 | Bottom sheet: reading mode | `showModeSheet` | P0 | [ ] | [ ] | [ ] | [ ] |
| H12 | Bottom sheet: menu (mode-aware groups, advanced fold) | `menu()` | P0 | [ ] | [ ] | [ ] | [ ] |
| H13 | Bottom sheet: language (first-run + change) | `showLanguageSheet` | P0 | [ ] | [ ] | [ ] | [ ] |
| H14 | Bottom sheet: OCR engine picker | `ocrModelsDialog` | P1 | [ ] | [ ] | [ ] | [ ] |
| H15 | Bottom sheet: chatbot add / login / provider failed | `addBotDialog`, `showProviderFailedDialog`, `showChatLoginPopup` | P1 | [ ] | [ ] | [ ] | [ ] |
| H16 | Bottom sheet: tabs grid / tab options / history | `showTabGrid`, `showTabOptions`, `browserHistoryDialog` | P1 | [ ] | [ ] | [ ] | [ ] |
| H17 | Bottom sheet: glossary (list / confirm found names / add) | `glossaryDialog`, `glossaryInput`, `chapterGlossaryDialog`, `Glossary*.html` | P1 | [ ] | [ ] | [ ] | [ ] |
| H18 | Bottom sheet: site settings & ad-block rules | `siteRulesDialog`, `siteRuleEditor`, `adblockRulesDialog` | P2 | [ ] | [ ] | [ ] | [ ] |
| H19 | Bottom sheet: model download (detector / mask) | `detectorDialog`, `segModelDialog` | P2 | [ ] | [ ] | [ ] | [ ] |
| H20 | Re-scan selector overlay (crop frame, six-dot handle, “Read this area”) | `showRescanSelector` + `Rescan.html` | P0 | [ ] | [ ] | [ ] | [ ] |
| H21 | Link context menu | `showLinkMenu`, `GoogleMenu.html` | P2 | [ ] | [ ] | [ ] | [ ] |
| H22 | Update notice / community chooser / early tip | `showUpdateNotice`, `showCommunityChooser`, `maybeShowEarlyTip` | P2 | [ ] | [ ] | [ ] | [ ] |
| H23 | Activity tray (task list popover) | `ActivityChip` + `Tray.html` | P1 | [ ] | [ ] | [ ] | [ ] |
| H24 | Tour overlay (coach marks) | `Tour.kt` | P2 | [ ] | [ ] | [ ] | [ ] |

## Group I — States

Branch `ui/group-i-states`.

| # | State screen | Where | Prio | Design | Impl | Audit | Polish |
|---|---|---|---|---|---|---|---|
| I01 | Page loading (2 dp bar + progress %) | `BookwormProgress`, `showNavLoading`, `applyLayout` top | P0 | [ ] | [ ] | [ ] | [ ] |
| I02 | Slow-connection banner | new, `Loading.html` “Slow” | P1 | [ ] | [ ] | [ ] | [ ] |
| I03 | Page failed (mascot, message, Retry) | `showReaderFallback`, `onReceivedError` paths | P0 | [ ] | [ ] | [ ] | [ ] |
| I04 | Offline state | new | P1 | [ ] | [ ] | [ ] | [ ] |
| I05 | Skeleton loaders (home list, library, tabs) | new — `Loading.html` | P1 | [ ] | [ ] | [ ] | [ ] |
| I06 | Empty: library | `libraryNovels` (“Library is empty…toast”) | P0 | [ ] | [ ] | [ ] | [ ] |
| I07 | Empty: bookmarks | `bookmarkList` toast | P1 | [ ] | [ ] | [ ] | [ ] |
| I08 | Empty: search / tabs / history | `showTabGrid`, `browserHistoryDialog` | P2 | [ ] | [ ] | [ ] | [ ] |
| I09 | Empty: glossary | `glossaryDialog` | P2 | [ ] | [ ] | [ ] | [ ] |
| I10 | Translation progress card (stages, ring, stop) | `progressBox`, `ExtractButton` ring, `ActivityChip` dots | P0 | [ ] | [ ] | [ ] | [ ] |
| I11 | Permission states (overlay, storage/export, notifications) | `checkOverlay`, export SAF | P1 | [ ] | [ ] | [ ] | [ ] |
| I12 | Error: chatbot not logged in / provider failed | `showProviderFailedDialog`, `Notice.html` | P1 | [ ] | [ ] | [ ] | [ ] |

## Group A — Navigation & Home

Branch `ui/group-a-navigation-redesign`.

| # | Screen | Source | Prio | Design | Impl | Audit | Polish |
|---|---|---|---|---|---|---|---|
| A01 | Cold start / splash (mascot, wordmark) | new — `Splash.html` | P1 | [ ] | [ ] | [ ] | [ ] |
| A02 | Home (4 mode entry chips, recent, sites, search bar) | `Home.kt` `render()` | P0 | [ ] | [ ] | [ ] | [ ] |
| A03 | Home empty/first-run (site grid, welcome) | `HomeView.welcome`, `grid` | P0 | [ ] | [ ] | [ ] | [ ] |
| A04 | Home continue-reading hero + recent rows | `HomeView.hero`, `recentRow` | P1 | [ ] | [ ] | [ ] | [ ] |
| A05 | Bottom nav (Library · Menu) | new — `Main.html` | P0 | [ ] | [ ] | [ ] | [ ] |
| A06 | Reader top bar (back, URL pill, reload, tabs, more) | `chromeTopView` build | P0 | [ ] | [ ] | [ ] | [ ] |
| A07 | Site shortcut chips rail | `buildSiteShortcuts`, `chip()` | P1 | [ ] | [ ] | [ ] | [ ] |
| A08 | Chatbot strip (bot chips, add, login) | `buildStrip`, `botChip()` | P1 | [ ] | [ ] | [ ] | [ ] |
| A09 | Hub buttons (Taihon / LNReader) | `Hub.kt`, `hubBar()` | P1 | [ ] | [ ] | [ ] | [ ] |
| A10 | Tab thumbnails grid visuals | `renderTabs`, `tabThumbFile` | P2 | [ ] | [ ] | [ ] | [ ] |

## Group B — Novel Reader

Branch `ui/group-b-novel-reader-redesign`.

| # | Screen | Source | Prio | Design | Impl | Audit | Polish |
|---|---|---|---|---|---|---|---|
| B01 | Dock bar (mode pill, prev, extract, next, menu, chatbot ring) | `applyLayout` dock, `Reader.html` | P0 | [ ] | [ ] | [ ] | [ ] |
| B02 | Extract button (bolt, 24-tick ring, activity dot, states) | `ExtractButton` | P0 | [ ] | [ ] | [ ] | [ ] |
| B03 | Mode capsule → segmented mode pill with label | `ModeCapsule`, `refreshModeChip` | P0 | [ ] | [ ] | [ ] | [ ] |
| B04 | Immersive reading (bars hidden, floating bolt) | `setBarsHidden`, `Working.html` | P0 | [ ] | [ ] | [ ] | [ ] |
| B05 | Offline reader (chapters, TOC, font size, 3 themes) | `ReaderActivity.kt` | P0 | [ ] | [ ] | [ ] | [ ] |
| B06 | Reader font/theme sheet (reader tray) | new — `Appearance.html` | P1 | [ ] | [ ] | [ ] | [ ] |
| B07 | Bookmark save + list | `saveBookmark`, `bookmarkList` | P1 | [ ] | [ ] | [ ] | [ ] |
| B08 | Split view (page | chatbot) + pane size cycle | `setMode`, `applyLayout`, `Cycle.html` | P1 | [ ] | [ ] | [ ] | [ ] |
| B09 | Chapter transition / mode-switch animation | `ModeSwitchOverlay`, `beginModeTransition` | P2 | [ ] | [ ] | [ ] | [ ] |
| B10 | Inline translated paragraph styling (web) | `WebNovelWriter`, `Js.kt` | P1 | [ ] | [ ] | [ ] | [ ] |

## Group C — Scanning Mode

Branch `ui/group-c-scanning-redesign`.

| # | Screen | Source | Prio | Design | Impl | Audit | Polish |
|---|---|---|---|---|---|---|---|
| C01 | Scanning dock state (mode = Scan, stop/pause) | `setReadMode`, `README` scan | P0 | [ ] | [ ] | [ ] | [ ] |
| C02 | Scan start options sheet (direction, accurate, ask) | `scanDirectionDialog`, `askScanOptions` | P1 | [ ] | [ ] | [ ] | [ ] |
| C03 | Scan progress card (panels done/total, stage, ETA-ish) | `webModePct`, `progressTv`, `ScanMode.Progress` | P0 | [ ] | [ ] | [ ] | [ ] |
| C04 | OCR result overlay on page (bubble placement) | `WebModeWriter`, `WebModeLayout` | P0 | [ ] | [ ] | [ ] | [ ] |
| C05 | Re-scan an area (crop frame + handle) | H20 reused + `Rescan.html` | P0 | [ ] | [ ] | [ ] | [ ] |
| C06 | OCR language + engine settings | `menuOcrLangLabel`, `ocrModelsDialog` | P1 | [ ] | [ ] | [ ] | [ ] |
| C07 | Scan failed / provider failed / retry missing bubbles | `showProviderFailedDialog`, `retryMissingScanBubbles`, `handleScanFallback` | P1 | [ ] | [ ] | [ ] | [ ] |
| C08 | Chapter glossary FAB + sheet | `glossaryFab`, `chapterGlossaryDialog` | P1 | [ ] | [ ] | [ ] | [ ] |
| C09 | Bubble detector / mask model download | `detectorDialog`, `segModelDialog` | P2 | [ ] | [ ] | [ ] | [ ] |

## Group D — Real-Time Mode

Branch `ui/group-d-realtime-redesign`.

| # | Screen | Source | Prio | Design | Impl | Audit | Polish |
|---|---|---|---|---|---|---|---|
| D01 | Live dock state (mode = Live, Pause/Resume) | `enableRealtimeMode`, `Comic.html` | P0 | [ ] | [ ] | [ ] | [ ] |
| D02 | Translation bubbles over panels (web overlay styling) | `DeepSeekRealtimeTranslation`, `WebModeWriter` | P0 | [ ] | [ ] | [ ] | [ ] |
| D03 | Activity dots + tool rail (re-scan, glossary) | `activityDotStatuses`, `updateActivityChip`, `reOcrBtn`, `glossaryFab` | P1 | [ ] | [ ] | [ ] | [ ] |
| D04 | Real-time start/refresh notice | `Notice.html`, `enableRealtimeMode` toasts | P1 | [ ] | [ ] | [ ] | [ ] |
| D05 | Ahead prefetch chip | `prefetchChip`, `setPrefetchEnabled` | P2 | [ ] | [ ] | [ ] | [ ] |
| D06 | Settings: real-time prompt, auto-OCR, batch size | `editRealtimePrompt`, `menuRealtimeAutoOcrLabel` | P2 | [ ] | [ ] | [ ] | [ ] |

## Group E — Manual Lite

Branch `ui/group-e-manual-lite-redesign`.

| # | Screen | Source | Prio | Design | Impl | Audit | Polish |
|---|---|---|---|---|---|---|---|
| E01 | Manual Lite dock state (Read button) | `manualExtractOn`, `extractCopy`, `Comic2.html` | P0 | [ ] | [ ] | [ ] | [ ] |
| E02 | Tap-to-crop overlay (region select, mask, confirm) | `ManualExtract/*`, `captureManualRegionLive` | P0 | [ ] | [ ] | [ ] | [ ] |
| E03 | Result card (translation + copy / re-run) | new from existing bubble insert | P1 | [ ] | [ ] | [ ] | [ ] |
| E04 | Manual OCR failed / no text | `toast` paths, `OcrDiag` | P1 | [ ] | [ ] | [ ] | [ ] |
| E05 | Manual history (recent taps this chapter) | new (optional) | P2 | [ ] | [ ] | [ ] | [ ] |

## Group F — Library & Search

Branch `ui/group-f-library-search-redesign`.

| # | Screen | Source | Prio | Design | Impl | Audit | Polish |
|---|---|---|---|---|---|---|---|
| F01 | Library home (novels list, covers, chapter count) | `libraryNovels` | P0 | [ ] | [ ] | [ ] | [ ] |
| F02 | Novel actions (chapter list / export / rename / delete) | `novelActions` | P0 | [ ] | [ ] | [ ] | [ ] |
| F03 | Chapter list (long-press delete) | `chapterList` | P0 | [ ] | [ ] | [ ] | [ ] |
| F04 | Bookmarks list | `bookmarkList` | P1 | [ ] | [ ] | [ ] | [ ] |
| F05 | Browser history | `browserHistoryDialog` | P2 | [ ] | [ ] | [ ] | [ ] |
| F06 | Tabs grid (search, thumbnails, close) | `showTabGrid`, `TabsGrid.html` | P1 | [ ] | [ ] | [ ] | [ ] |
| F07 | Tab options (close others / all, reload) | `showTabOptions`, `TabsSheet.html` | P2 | [ ] | [ ] | [ ] | [ ] |
| F08 | Home search bar states (URL vs query, clear, go) | `HomeView.buildSearchBar` | P1 | [ ] | [ ] | [ ] | [ ] |
| F09 | Export flow (SAF, success/fail snack) | `exportAll`, `onActivityResult` | P2 | [ ] | [ ] | [ ] | [ ] |

## Group G — Settings

Branch `ui/group-g-settings-redesign`.

| # | Screen | Source | Prio | Design | Impl | Audit | Polish |
|---|---|---|---|---|---|---|---|
| G01 | Settings / menu home (mode-aware groups) | `menu()` daily/advanced | P0 | [ ] | [ ] | [ ] | [ ] |
| G02 | Reading mode sheet (4 modes, description each) | `showModeSheet`, `ModeSheet.html` | P0 | [ ] | [ ] | [ ] | [ ] |
| G03 | Language sheet (source/target, download font) | `showLanguageSheet`, `downloadLanguageFont` | P0 | [ ] | [ ] | [ ] | [ ] |
| G04 | Appearance (theme Paper/Charcoal/Custom, accent, text spacing) | `applyDark`, `darkMode`, `Appearance.html` | P0 | [ ] | [ ] | [ ] | [ ] |
| G05 | OCR settings (engine, language, scan dir, rescan, debug) | `chooseOcrEngine`, `scanDirectionDialog` | P1 | [ ] | [ ] | [ ] | [ ] |
| G06 | Translation settings (chatbot, prompt, glossary, batch, hold) | `addBotDialog`, prompts, `editSendHold` | P1 | [ ] | [ ] | [ ] | [ ] |
| G07 | Browsing settings (bars mode, force-load, ad block, site settings) | `applyFloatingBars`, `siteRulesDialog`, `AdBlock` | P1 | [ ] | [ ] | [ ] | [ ] |
| G08 | Advanced / diagnostics fold | `menu()` advanced | P2 | [ ] | [ ] | [ ] | [ ] |
| G09 | About / updates / community | `showUpdateNotice`, `showCommunityChooser`, `Updater` | P2 | [ ] | [ ] | [ ] | [ ] |
| G10 | First-run setup (prompt choice → language → tour) | `showFirstPromptChoice`, `startTour` | P1 | [ ] | [ ] | [ ] | [ ] |

## Group P — Planned screens (out of scope)

| # | Screen | Reference | Status |
|---|---|---|---|
| P01 | Link → Translate (Soon + running) | `LinkSoon.html`, `LinkRun.html` | `[-]` — feature does not exist in source (`Hub.urlFromIntent` only opens URLs). Design kept in DESIGN.md §10 for later. |
| P02 | Preview reader | `Preview.html` | `[-]` — no preview mode in source. |
| P03 | Chatbot pane size cycle | `Cycle.html` | Merged into B08 (split view states). |

---

## Branch plan

| Order | Branch | Base | Deliverable |
|---|---|---|---|
| 1 | `ui/design-system` | target branch | tokens + components + DESIGN.md |
| 2 | `ui/group-h-overlays` | design-system | all dialogs/sheets/snackbar/banners |
| 3 | `ui/group-i-states` | group-h | loading/empty/error/offline/skeletons |
| 4 | `ui/group-a-navigation-redesign` | group-i | splash, home, bottom nav, top bar |
| 5 | `ui/group-b-novel-reader-redesign` | group-a | dock, extract button, immersive, offline reader |
| 6 | `ui/group-c-scanning-redesign` | group-b | scan flow, progress, rescan |
| 7 | `ui/group-d-realtime-redesign` | group-c | live overlays, tool rail |
| 8 | `ui/group-e-manual-lite-redesign` | group-d | manual crop + result |
| 9 | `ui/group-f-library-search-redesign` | group-e | library, tabs, search |
| 10 | `ui/group-g-settings-redesign` | group-f | settings, appearance, language |
| 11 | `ui/final-polish` | group-g | global audit, FINAL_REPORT.md |

Each group closes with: baseline notes committed → implementation commits (one per screen) →
Vercel-style audit table in the branch summary → push (no PR).

## Open decisions

- [x] Architecture: View-layer refactor (no Compose migration).
- [x] Four visible modes on Home: Novel · Scanning · Real-time · Manual Lite.
- [ ] Manual Lite placement in `ReadMode` enum vs derived from `manualExtractOn()` — decide in group E
      (prefer derived, no enum change → no persistence migration).
- [ ] Light theme for app chrome requires a theme switch UI (G04) and web `prefers-color-scheme` bridge
      (B10). Decide: follow system by default, override in Appearance.
