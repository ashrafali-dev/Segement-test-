# ToonSensei — design system & screen specification

Version 1.0 · applies to every screen of groups A–I (see `TODO.md`).
Implementation: `ToonTheme.kt` (tokens) + `ToonComponents.kt` (components). This document and those two
files are the only sanctioned sources of colour, type, spacing, radius, motion and component anatomy.
Anything not listed here is a bug, not a style choice.

---

## 1. Identity: “Paper & Seal”

The reference mockups (paper, seal red, serif reader type, mascot) are the starting point, not the
destination. The finished UI keeps three ownable ideas:

1. **Editorial paper surface.** Warm off-white pages, hairline rules, serif for reading content, sans for
   chrome. No cards-on-gradient, no purple glow, no glassmorphism.
2. **Seals, not gradients.** Every mode and every state has one flat seal colour used only where meaning
   lives: the active mode, progress fill, destructive action, success dot.
3. **Marginalia.** Secondary labels sit in the margin in small caps letterspaced type (e.g. “CONTINUE
   READING”, “SETTINGS FOR”), like a printed book's marginal notes.

Dark mode is the same book at night: the seal shifts from ink-red to clay, paper becomes charcoal, ink
becomes warm bone. Not a neon-on-black theme.

---

## 2. Colour tokens

All values are `toonSdk.Color`-free (plain ARGB ints). `ToonTheme.colors(dark: Boolean)` returns a
`ToonColors` record; every component reads from it. **No literal colour in screen code except
`Color.TRANSPARENT` and content supplied by the web page itself.**

### 2.1 Day (paper)

| Token | Hex | Use |
|---|---|---|
| `bg` | `#F7F4EE` | window background |
| `surface` | `#FBFAF6` | cards, sheets, rows, search pill |
| `surfaceAlt` | `#E9E4D8` | URL pill, segmented track, disabled field |
| `line` | `#E3DED3` | hairlines, dividers |
| `lineStrong` | `#CFC8BA` | focused field border, selected chip border |
| `title` | `#1F1D1A` | headings, primary text |
| `body` | `#3D3832` | long-form body on reader pages |
| `text` | `#4F4A44` | list rows, buttons |
| `subtext` | `#5A544B` | secondary lines |
| `muted` | `#6B665E` | meta, captions, chevrons |
| `disabled` | `#8F897D` | disabled icon/text (never for body copy) |
| `accent` | `#2C6650` | focus ring, links, toggles ON track |
| `onAccent` | `#FFFFFF` | text over `accent` |
| `novel` | `#9C3D2E` | Novel mode seal |
| `scan` | `#2E6B47` | Scanning mode seal |
| `live` | `#8C2C1D` | Real-time mode seal |
| `manual` | `#1A4FB4` | Manual Lite mode seal |
| `success` | `#2C6650` | progress done, idle activity dot |
| `warnBg` / `warnInk` | `#F6E8C9` / `#5E3F0B` | slow-connection banner, caution |
| `dangerBg` / `dangerInk` | `#F5E3DE` / `#A33A28` | destructive button, error text |
| `infoBg` / `infoInk` | `#E6ECF8` / `#1A4FB4` | info banner |
| `ink` | `#1F1D1A` | primary filled button background |

### 2.2 Night (charcoal)

| Token | Hex | Use |
|---|---|---|
| `bg` | `#121211` | window background |
| `surface` | `#1B1B1A` | cards, sheets, rows |
| `surfaceAlt` | `#242322` | URL pill, segmented track |
| `line` | `#2E2C29` | hairlines |
| `lineStrong` | `#3C3934` | focused border |
| `title` | `#E9E5DC` | headings |
| `body` | `#E8E3D9` | reader body |
| `text` | `#C9C3B6` | rows |
| `subtext` | `#BDB7AA` | secondary |
| `muted` | `#9B9488` | meta |
| `disabled` | `#7A7469` | disabled |
| `accent` | `#E07A5F` | focus ring, links, toggles ON |
| `onAccent` | `#121211` | text over accent |
| `novel` | `#E8A08E` | |
| `scan` | `#9CC5A1` | |
| `live` | `#FF8A75` | |
| `manual` | `#7FA6F0` | |
| `success` | `#9FE0B5` | |
| `warnBg` / `warnInk` | `#2A2213` / `#F0D6A8` | |
| `dangerBg` / `dangerInk` | `#2A1C18` / `#F2CFC4` | |
| `infoBg` / `infoInk` | `#1A2333` / `#A9C4F5` | |
| `ink` | `#E9E5DC` | primary filled button background |

### 2.3 Contrast (WCAG 2.1, measured)

| Pair | Value | Ratio |
|---|---|---|
| paper text ink12 | `#1F1D1A` on `#F7F4EE` | 15.32 |
| paper text ink11 (secondary) | `#4F4A44` on `#F7F4EE` | 7.99 |
| paper text ink10 (tertiary) | `#5A544B` on `#F7F4EE` | 6.82 |
| paper text on card | `#6B665E` on `#FBFAF6` | 5.45 |
| accent text on paper | `#2C6650` on `#F7F4EE` | 6.12 |
| accent text on card | `#2C6650` on `#FBFAF6` | 6.44 |
| paper on accent fill | `#FFFFFF` on `#2C6650` | 6.72 |
| novel accent seal | `#9C3D2E` on `#F7F4EE` | 6.13 |
| scan accent | `#2E6B47` on `#F7F4EE` | 5.78 |
| live accent | `#8C2C1D` on `#F7F4EE` | 7.67 |
| manual accent | `#1A4FB4` on `#F7F4EE` | 6.78 |
| success | `#2C6650` on `#F7F4EE` | 6.12 |
| danger | `#A33A28` on `#F7F4EE` | 5.99 |
| warn text | `#5E3F0B` on `#F6E8C9` | 7.90 |
| info text | `#1A4FB4` on `#E6ECF8` | 6.28 |
| disabled icon | `#8F897D` on `#F7F4EE` | 3.17 (non-text only) |
| night text ink12 | `#E9E5DC` on `#121211` | 14.91 |
| night secondary | `#BDB7AA` on `#1B1B1A` | 8.63 |
| night tertiary | `#9B9488` on `#121211` | 6.23 |
| night disabled | `#7A7469` on `#121211` | 4.04 (non-text only) |
| night accent text | `#E07A5F` on `#121211` | 6.35 |
| night on accent | `#121211` on `#E07A5F` | 6.35 |
| night novel | `#E8A08E` on `#121211` | 8.79 |
| night scan | `#9CC5A1` on `#121211` | 9.74 |
| night live | `#FF8A75` on `#121211` | 8.16 |
| night manual | `#7FA6F0` on `#121211` | 7.69 |
| night success | `#9FE0B5` on `#14231A` | 10.74 |
| night warn | `#F0D6A8` on `#2A2213` | 11.14 |
| night danger | `#F2CFC4` on `#2A1C18` | 11.35 |
| night info | `#A9C4F5` on `#1A2333` | 8.93 |

Focus ring: 2 dp `accent` + 2 dp offset against `bg`; ratio `accent`↔`bg` ≥ 6:1 both modes (use
`accent`, never `line`).

---

## 3. Typography

Faces already bundled in the APK (`app/src/assets/fonts`): Tiro (serif, Latin+Deva family style),
Galada (Bengali display), Hind (Bengali sans). System `sans-serif` for chrome keeps the APK small.

| Role | Face | Size / line | Weight | Colour | Used by |
|---|---|---|---|---|---|
| `display` | Shippori-style → `serif` (system) | 26 sp / 32 | 600, tracking +0.02 | `title` | splash wordmark, Home title |
| `title` | `sans-serif-medium` | 20 sp / 26 | 500 | `title` | sheet titles, screen names |
| `section` | `sans-serif-medium` | 12 sp / 16 | 500, tracking +0.08, all caps | `muted` | marginalia labels (“CONTINUE READING”) |
| `body` | `sans-serif` | 15 sp / 22 | 400 | `text` | rows, descriptions |
| `bodyStrong` | `sans-serif-medium` | 15 sp / 22 | 500 | `title` | emphasised row title |
| `label` | `sans-serif-medium` | 14 sp / 18 | 500 | `text` | buttons |
| `caption` | `sans-serif` | 13 sp / 18 | 400 | `muted` | meta lines |
| `micro` | `sans-serif-medium` | 11 sp / 14 | 500 | `muted` | badges, tab labels |
| `reader` | bundled reader font (B10) | 18–22 sp / ×1.9 | 400 | `body` | translated chapter text on the page |
| `readerMetric` | Georgia-like → `serif` | 17 sp / ×1.8 | 400 | `muted` | original-language lines kept in page (secondary) |

Rules: never below 11 sp; never all-caps for anything longer than 3 words; line length on reader
≤ 38 em; numbers in progress use tabular figures (`FontFeature` not available pre-API 21 → use
`monospace` for the % counter only).

---

## 4. Space, radius, border, elevation

4 dp base grid; component paddings choose from {4, 8, 12, 16, 20, 24, 32}.

| Token | dp | Use |
|---|---|---|
| `s1` | 4 | icon↔label inside a control |
| `s2` | 8 | chip padding, inline gaps |
| `s3` | 12 | card inner padding (compact), list row vertical |
| `s4` | 16 | card inner padding, screen side margin (390 dp) |
| `s5` | 20 | screen side margin (≤ 360 dp) |
| `s6` | 24 | section separation |
| `s8` | 32 | splash / empty-state vertical rhythm |

Radius: `rPill` = height/2 (chips, buttons, URL pill, search), `rCard` = 16, `rSheet` = 22 (top only),
`rRowGroup` = 16, `rField` = 12, `rBadge` = 8. Border: hairline 1 dp `line`; focus 2 dp `accent`.
Elevation: 0 for everything on paper; sheets/dialogs use a scrim (`#000000` @ 40 %) and a 1 dp `line`
edge instead of shadows. In night mode scrim 60 %.

Touch: minimum 48×48 dp hit area (visible control can be 40 dp; outer `TouchDelegate` padding fills the
rest). Spacing between adjacent targets ≥ 8 dp.

---

## 5. Components

Every component ships in these states: default, pressed, focused, disabled, and where applicable
selected / loading / error. Pressed = scale 0.97 (120 ms) + ripple tint `accent` @ 12 %.

| Component | Anatomy | Notes |
|---|---|---|
| **Button primary** | height 48, pill, `ink` fill, `bg` text, `label` | one per screen |
| **Button secondary** | height 48, pill, `surface` fill, 1 dp `lineStrong` border, `title` text | |
| **Button ghost** | height 44, no fill, `text` text | toolbar actions |
| **Button destructive** | height 48, pill, `dangerBg` fill, `dangerInk` text, 1 dp `dangerInk` border | confirm sheets only |
| **Icon button** | 44 visible, 48 hit, 22 icon stroke 1.7, `title` or `muted` | contentDescription required |
| **Chip** | height 36, pill, `surfaceAlt`, `text`; selected = `title` fill + `bg` text + seal dot | site shortcuts, filters |
| **Mode chip** (home) | height 44, pill, icon 18 + label 14; active = seal fill @ 12 % + seal border + seal ink | Novel / Scanning / Real-time / Manual Lite |
| **Segmented control** | track `surfaceAlt`, height 40, selected thumb `surface` + `accent` ink, spring 1.4 | menu “Settings for”, appearance choices |
| **Card** | `surface`, `rCard`, padding `s4`, no shadow, 1 dp `line` optional | |
| **Grouped list** | `surface` rounded 16; rows 48–56 dp; 1 dp `line` inset 16 from start | settings, action lists |
| **List row** | leading 24 icon in `muted`, title `bodyStrong`, value `caption` + chevron 16 `muted` | |
| **Toggle row** | title + description; switch = 44×24 track pill, thumb 18, ON `accent` | |
| **Field** | height 48, `surface`, 1 dp `lineStrong`, `rField`; focus = 2 dp `accent`; error = 2 dp `dangerInk` + hint line | |
| **Search pill** | height 54, pill, `surface`, leading 20 icon `muted`, trailing 42 circular `title` go-button | home |
| **Sheet** | top radius 22, `surface`, grabber 36×4 `lineStrong` at top 8, title 20, side padding `s4`, bottom inset safe-area + `s4` | mode, menu, language, glossary, tabs, model |
| **Dialog** | centre, 280–400 wide, `surface`, radius 20, title, body, actions right-aligned (text buttons) or stacked full-width when destructive | confirmation, input |
| **Snackbar** | bottom, 48 min, `ink` bg, `bg` text, action `accent-neutral` = underline, radius 12, 8 dp above nav | replaces toasts |
| **Banner** | full width inside content, radius 14, `warnBg`/`infoBg`/`dangerBg`, icon + text `caption`, optional action | slow, offline, login-needed |
| **Badge** | `micro`, radius 8, 4×8 padding, neutral `surfaceAlt`/`muted`; success = `success` @ 14 % bg | counts, ON/OFF |
| **Progress line** | height 2 (top) or 6 (card), track `line`, fill `accent`; indeterminate = shimmer 30 % width | loading, translation |
| **Ring** | 3 dp, `accent` arc, 24 ticks replaced by smooth arc; determinate and indeterminate | extract button, chatbot load |
| **Mascot** | SVG paths from reference (`Main.html`, `Loading.html`), drawn via `ToonMascot` painter; sizes 84 (home), 96 (empty/error), 120 (splash) | single brand character, no emoji |
| **Skeleton** | `surfaceAlt` rounded blocks, shimmer 1.2 s, respects animator scale | |
| **Empty state** | mascot 96 + `title` + `caption` + optional secondary button, centred, padding `s8` | |
| **Error state** | mascot 96 + `title` + `caption` + primary “Retry” | |

### 5.1 Icon set
Extend `Ic` with stroke icons on the same 24 grid, stroke 1.7, round caps:
`CALENDAR, PAUSE, PLAY, MIC, DOWNLOAD, EXTERNAL, IMAGE, CROP, TRANSLATE, HISTORY, LIBRARY(new glyph),
TABS (stacked squares), BELL, INFORMATION, WARNING, CIRCLE_CHECK, CIRCLE_CROSS`.
Chrome must not use emoji. Menu labels that begin with emoji today are split: the emoji is dropped and
the leading icon is taken from `Ic`.

---

## 6. Navigation model

```
Home ── mode chip ──▶ Reader (top bar + page + dock)
 │                        │
 │                        ├─ dock: [mode pill ▾] [◀] [bolt] [▶] [menu]
 │                        └─ tab ◀▶ chatbot ring opens split/chat
 └─ bottom nav: Library · Menu        (48 dp nav bar, 2 items, labelled)
```

- **Bottom nav** (A05): `Library`, `Menu`. Height 56 + safe-area, `bg`, top hairline. Selected =
  seal-coloured icon + label; unselected `muted`. Rationale: only two destinations exist, a rail of more
  items would be invented IA.
- **Top bar** (A06): 56 dp; back 44; URL pill 40 (`surfaceAlt`, host + short path); reload inside pill;
  tab counter 44 (rounded square 26, 2 dp border); more 44. Bars auto-hide while scrolling (existing
  behaviour) with a 2 dp progress line at the very top.
- **Dock** (B01): height 68, floats 12 dp above bottom, `surface` with 1 dp `line`, radius 34.
  Mode pill opens the mode sheet; centre bolt 60 dp inside a 74 dp hit box; next-step badge (check/skip).
- **Back semantics** unchanged: tour → home → WebView history → home.

---

## 7. Modes — visual identity

| Mode | Seal | Icon | State copy |
|---|---|---|---|
| Novel | `novel` | book | “Translate chapter text on the page.” |
| Scanning | `scan` | scan frame | “Prepare a whole comic chapter.” |
| Real-time | `live` | broadcast | “Translate as you scroll.” |
| Manual Lite | `manual` | tap-crosshair | “Read one screen when you tap. Light on battery and RAM.” |

Dock centre button changes: icon (bolt / scan frame / broadcast / crosshair), ring colour, and activity
dot colour. Long-press = auto ON/OFF toggle (existing gesture), surfaced by a 300 ms hint the first time.

---

## 8. Screen briefs

One brief per TODO item. Layout described top→bottom with token references. “State” lists the
non-happy states the screen must render.

### A — Navigation & Home

**A01 Splash.** `bg`, centred mascot 120, wordmark `display` “Toon Sensei”, below in `micro` letterspaced
“NOVEL · SCANNING · REAL-TIME · MANUAL LITE”. Fade in 260 ms; leave by fading the reader under it, no
spinner. State: none (≤ 400 ms).

**A02 Home.** Title row: mascot 84 + wordmark `display` (left), “?” help icon button (right) → tour.
Search pill `s4` margins. Mode chips wrap: four `mode chip`s, 8 dp gaps, one per mode; active chip shows
its description line under the group (`caption`, animated height 180 ms). Marginalia `section` label.
Then either *Continue reading* hero (card: cover 68×90 coloured by title hash using seals at 18 %, tag
`section`, title `bodyStrong` 2 lines, meta `caption`, primary “Continue”), or *Recents* grouped list.
Then *Sites* 3-column tile grid (54 icon, 12 label) and *Webtoons* chips row. Sticky bottom nav.
State: first-run (welcome + site grid), loading (4 skeleton tiles), empty search result.

**A03 Home empty/first-run.** as A02 without hero; `Welcome` title + `caption` “Pick a site to start
reading, or search below.”

**A04 Hero + recents.** described above; empty recents = A03; single recent = hero only.

**A05 Bottom nav.** 2 items, `Library` (new library glyph) and `Menu` (three lines). 48 dp icon box,
`micro` label, selected state seal; ripple on the item, not the label.

**A06 Reader top bar.** described §6; state: loading (progress line), slow (banner under bar), failed
(close button in pill becomes reload).

**A07 Shortcut rail.** horizontal chips 36 dp, `surfaceAlt`, `text`, horizontal scroll, no scrollbar;
long-press → remove.

**A08 Chatbot strip.** Above the chat pane: segmented bot chips (selected = `surface` + `title` + seal
dot), trailing “＋” 44 dp. Loading state: chip shows a 12 dp ring instead of the dot.

**A09 Hub buttons.** Two secondary buttons 44 dp inside the menu “LIBRARY” group, not on Home: “Taihon”
and “LNReader”, each with a download icon; tap opens or downloads (existing `Hub`).

**A10 Tab grid.** see F06.

### B — Novel Reader

**B01 Dock.** §6. Left: mode pill (mode glyph 16 + name `label` + chevron 14), tap → G02 mode sheet;
long-press → cycle. Then prev 44, bolt, next 44 (badge 14 dp `success` disc with check when next chapter
prefetched), menu 44. Chatbot ring 36 at far right: arc = chapter progress, tap → split view.
State: busy (bolt ring indeterminate, activity dot pulsing), stopped (bolt outline only).

**B02 Extract button.** 74 dp hit; disc uses mode seal fill (flat, not gradient); ring = 3 dp arc,
determinate while scanning, indeterminate (1 s rotation) while translating; activity dot 9 dp at NE
edge with `success` idle / mode seal busy. Pressed scale 0.92 110 ms. contentDescription
“Extract chapter. {state}”.

**B03 Mode capsule → segmented pill with label.** Replaces icon-only capsule: segmented control 40 dp
`surfaceAlt`, three segments Novel / Scan / Live at the dock, each glyph + 12 sp label when width
allows (≥ 360 dp the dock shows glyph only and the pill label). Selected thumb `surface`, seal ink,
spring 1.4. Manual Lite not in this control (it is a sub-toggle of Scan, see E01) — but appears in the
mode sheet as a fourth option that sets scan + manualExtract.

**B04 Immersive.** bars slide out 220 ms; a 52 dp floating bolt sits bottom-right 24 dp with a hint ring
on first use. Top 2 dp reading progress stays.

**B05 Offline reader.** Top bar (back, novel name `label`, count chip), 2 dp progress line, body
`reader` face with `lineSpacing 1.45`, bottom capsule: ◀, A−, A+, theme (sun/moon), ▶ — 48 dp each,
`surface` pill. Theme sheet B06. States: empty chapter (mascot + “This chapter has no text yet”), end
of novel (next/prev disabled `disabled`).

**B06 Reader appearance sheet.** Groups: *Theme* segmented Paper / Sepia / Charcoal / Night; *Text size*
A− slider A+ with live sample; *Line spacing* Tight / Normal / Relaxed; *Font* (bundled list). Same
sheet used by settings G04 for app theme (shared component).

**B07 Bookmarks.** Save = snackbar “Bookmark saved” + undo action; list = grouped rows with title +
last chapter `caption`, long-press delete confirm.

**B08 Split view.** Drag handle 24 dp between panes with grabber; sizes 30/50/70 % cycle on handle tap
(Cycle.html). Chat pane header = chatbot strip A08. 

**B09 Chapter transition.** 180 ms cross-fade page; mode switch = existing centre animation restyled
to seal-on-paper: paper page writes lines (Novel), scan frame + beam (Scan), ripple dot (Live), tap
crosshair (Manual). 1.4 s total, non-blocking.

**B10 Inline translated text (web).** Injected CSS: font-family from settings, font-size from reader
size, line-height 1.9, colour `body`, original line above in `readerMetric` muted; “sound effect” markers
in `caption` italic. Dark mode: same CSS variables flipped.

### C — Scanning

**C01 Dock in Scan state.** Mode pill shows “Scan”; bolt disc = `scan` seal with scan-frame icon; badge
shows panel count done/total while running; long-press bolt toggles auto-scan.

**C02 Scan start sheet.** If `askBefore` is on: sheet with direction segmented (Scroll ↓ / RTL →
LTR →), “Accurate” toggle row + description, primary “Start scanning”, secondary “Don't ask again”.
State: remembered (skip sheet).

**C03 Scan progress card.** Floats top (8 dp under bar), `surface` radius 16, 1 dp `line`: line 1
“Scanning panels” + “12 / 64” tabular; line 2 stage `caption` (“reading”, “translating”, “filling gaps”,
“retrying 2”); progress line 6 dp `accent`; stop icon 40 right. Collapses to a 4 dp line under the top bar
when the user scrolls. States: running, retry (warn ink), paused, done (check + auto-dismiss 1.2 s).

**C04 Overlay bubbles (web).** White bubble `#FFFFFF` with `ink` text and 1 px `rgba(31,29,26,.12)`
border, radius 10, padding 6×8, font from B10, `text-shadow: none`. Dark mode `surface`/`title`.
Placement unchanged (analysis-driven). No stroke around original lettering.

**C05 Re-scan an area.** Full-screen crop overlay: dim 55 %, selected rect clear with 2 dp `accent`
border and six-dot handle 22 dp at bottom-centre; bottom sheet 1 row “Read this area” primary 48 + hint
caption “Drag the dots over the bubble you want to re-read.” Cancel = X 44 top-left, scrim tap.

**C06 OCR language & engine.** Grouped list rows with value + chevron; engine picker sheet lists
engines with radio + `caption` description.

**C07 Failures.** Provider-failed dialog (H02 destructive neutral): title “Chatbot didn't answer”,
body = reason, actions “Retry now” primary, “Change provider” secondary, “Close” ghost. Scan-fallback
banner: “OCR found no text — showing panel image” with Retry.

**C08 Chapter glossary.** FAB-style tool rail (D03) entry; sheet with search, entries (term → term,
context `caption`), add button; empty state I09.

**C09 Model download sheet.** title, size, description, progress line while downloading, Cancel.

### D — Real-time

**D01 Dock in Live state.** Mode pill “Live”; centre button = Pause/Resume (48 pill) replacing bolt
while running: `dangerBg`/`dangerInk` “Pause”, then primary “Resume”. Ring shows translation backlog
(panels done / visible).

**D02 Bubbles on panels.** Same as C04 but translation appears progressively; loading bubble = skeleton
with shimmer 1.2 s sized to the original region; error bubble = `dangerBg` with “Tap to retry”.

**D03 Tool rail.** Right side, 8 dp from edge, vertical pill `surface` with 1 dp `line`, 44 dp items:
re-scan (crop icon), chapter glossary (book icon), separator 1 dp. Appears only in Scan/Live. Dots:
activity indicator stays in the dock bottom-left chip (H23).

**D04 Start notice.** Banner under top bar: “Real-time is on — scroll slowly; translations appear over
the panels.” auto-dismiss 4 s, or “Chatbot needs you to log in” with “Log in” action (persistent).

**D05 Ahead chip.** Small pill bottom-left 28 dp: bolt + “Ahead ON/OFF”, `surfaceAlt`; ON = seal ink +
`success` dot.

**D06 Real-time settings sheet.** rows: Auto OCR toggle, Batch size, Prompt, Debug; descriptions as
`caption` under each row title (two-line rows, 64 dp).

### E — Manual Lite

**E01 Dock in Manual state.** Reached from mode sheet (4th option) or when auto-OCR is off. Mode pill
“Lite”; centre button `manual` seal with crosshair, label “Read” 15 sp; tap = capture current screen,
show crop overlay E02; long-press = read whole screen immediately.

**E02 Tap-to-crop.** Same anatomy as C05 but starts from the tapped point: initial 60 % rect, 8 corner
handles 12 dp, dim 55 %, bottom sheet: primary “Read this area”, secondary “Whole screen”, caption
“Tap outside to cancel”. After confirm: shimmer over the region, then E03.

**E03 Result card.** Bottom sheet 60 % or inline popover above the tapped region: original text
`caption` muted, separator, translation `bodyStrong` 17 sp selectable, actions: Copy (ghost), Re-read
(secondary), Close (X). Copy → snackbar “Copied”.

**E04 No text / failed.** Inline card `warnBg`: “No text found in that area.” + “Try again” / “Choose
another area”.

**E05 Manual history (optional P2).** List of taps this session: thumbnail 40, time, preview text,
tap to open E03.

### F — Library & Search

**F01 Library.** Screen: title “Library” `display`, search field 48, list of novel cards (cover 52×72
seal-tinted with initial, title `bodyStrong`, “128 chapters · 42 translated” `caption`, chevron).
Empty: I06. Loading: skeletons. Row tap → F02; long-press → F02 sheet.

**F02 Novel actions sheet.** Header with cover + title; rows: Chapter list, Export TXT, Rename, Delete
(`dangerInk` text). Delete → H05 confirm.

**F03 Chapter list.** Search field, grouped by volume if present, rows: chapter label + “translated”
check. Long-press → delete confirm. Header shows count and “Read” primary → B05.

**F04 Bookmarks.** rows title + lastTitle `caption`; empty I07.

**F05 History.** grouped by day (`section` labels “TODAY”, “YESTERDAY”); rows with favicon 20; clear-all
in overflow (H02).

**F06 Tabs grid.** 2-column cards: thumbnail 16:10 `surfaceAlt` (favicon fallback), title 2 lines,
site `caption`, X 32 top-right; “New tab” dashed-border card; search field; overflow “Close all”.

**F07 Tab options sheet.** Close, Close others, Close all (`dangerInk`), Reload, Share URL.

**F08 Home search states.** Empty (icon search), URL detected (globe icon, go button enabled), query
(search icon), typed but whitespace (disabled go). Clear X appears when non-empty.

**F09 Export.** SAF picker; snackbar “Exported {name}.txt” / error snackbar with Details action.

### G — Settings

**G01 Settings home.** Title “Menu” `display`; “SETTINGS FOR” `section` + segmented Novel / Scan /
Live (changes which groups are visible); grouped lists: READER (theme, text size, bars), TRANSLATION
(chatbot, language, prompt, glossary), OCR (engine, language, direction), BROWSING (bookmarks, history,
ad block, site settings), APP (about, updates, community), and one “Advanced” expandable header →
collapsed technical rows. Every row: title + current value `caption` + chevron. Search field at top
(filters rows by title).

**G02 Mode sheet.** Title “Reading mode”; four option cards (seal icon 28, title, description
`caption`, selected = seal border + check); “View” segmented Split / Novel / Chat at bottom.

**G03 Language sheet.** Two grouped rows (From, To) opening searchable lists (H04); font download row
appears when the target script needs a bundled font, with progress + size; primary “Continue” (first
run) / “Done”.

**G04 Appearance.** Groups: Theme (Follow system / Paper / Charcoal) segmented; Reader theme (shared
B06); Accent (4 seals + “Use mode colour” default); Web reader preview card with live sample text
(target-language sample from `Langs.target`).

**G05 OCR settings.** Engine picker (H14), language (H04), scan direction (H08), re-scan toggle,
debug rows under Advanced.

**G06 Translation settings.** Chatbot (segmented + add), target language, prompts (three editors H07),
glossary (H17), batch size + send-hold (H08), auto paste/send toggles.

**G07 Browsing settings.** Bars mode segmented (Float / Fixed / Auto-hide), force-load images toggle,
ad block toggle + rule editor rows (H18), site settings (H18).

**G08 Advanced fold.** Existing technical rows regrouped under: TRANSLATION EXPERIMENTS, WEBTOON
TOOLS, DIAGNOSTICS. All labels de-emojified, each with a `caption` explanation line.

**G09 About.** Version, update check row, community links (Telegram/Reddit) as secondary buttons,
licences (system dialog), privacy note.

**G10 First-run.** Order: prompt choice (H02-style full sheet with two large options: “Use default
prompt” primary / “Write my own”) → language (G03) → tour (H24 restyled: spotlight + `surface` bubble
with title/description/“Next” 2-step counter).

---

## 9. Overlays catalogue (group H)

Every sheet/dialog uses the components above; anatomy already specified per screen in §8. Shared rules:
- Sheets: open with 220 ms slide-up + scrim fade; close by scrim tap, back button, grabber swipe down
  (> 30 % travel). Title left, close X right (44). Content scrolls inside the sheet, footer actions
  pinned with `s4` + safe-area.
- Dialogs: scale 0.96→1 + fade 180 ms. Actions: destructive = filled destructive button, cancel = ghost.
- Toast replacement: one snackbar at a time; queue max 2; errors persist 6 s with dismiss.
- Banners: max one per slot (under top bar); info auto-dismiss, warn/error persistent with action.

Dialog inventory (all 36 `AlertDialog.Builder` sites get mapped): confirm-delete novel, confirm-delete
chapter, confirm-delete AI model, rename novel, bookmark name, save bookmark, prompt editor ×3,
send-hold ms, scan direction, OCR engine, add chatbot, provider failed, community chooser, update
notice, early tip, ad-block rules, site rules, rule editor ×2 (add/edit), glossary list/add/confirm,
detector download, mask download, sensitive-content notice, link menu, tab options.

---

## 10. Planned screens (design kept for later)

- **Link → Translate** (`LinkSoon.html`, `LinkRun.html`): entry on Home as a fifth chip with “Soon”
  badge is *not* shown in v1 (no feature). When built: progress card variant with steps
  Fetch → Extract → Translate → Save, and a “Open result” primary.
- **Preview reader** (`Preview.html`): reuse B05 layout with a “Draft” badge.

---

## 11. Motion

| Interaction | Motion |
|---|---|
| Press | scale 0.97, 110 ms decelerate |
| Chip select | thumb spring 1.4, 220 ms |
| Sheet | slide 220 ms decelerate + scrim 200 ms |
| Dialog | fade + scale from 0.96, 180 ms |
| List reveal | fade+8 dp rise, 220 ms, stagger 40 ms, max 5 items |
| Mode switch | centre animation 1.4 s, sealed to paper palette |
| Progress | determinate 420 ms decelerate; indeterminate 1 s linear |
| Skeleton | shimmer 1.2 s linear loop |
| Bars hide/show | 220 ms translate (existing behaviour kept) |

All animations stop when `Settings.Global.ANIMATOR_DURATION_SCALE == 0` (reduced motion): set durations
to 0 and skip spring; never gate functionality on animation completion.

---

## 12. Accessibility contract

- TalkBack: every sheet has a title view as the accessibility pane title; every row is one focusable
  target with title + value spoken; custom Canvas views (`ModeCapsule`, `ExtractButton`, `GlowBar`)
  expose state through `contentDescription` + `accessibilityLiveRegion` for progress.
- Keyboard/D-pad: focus ring visible on all controls; tab order equals visual order; buttons respond
  to ENTER/DPAD_CENTER.
- Text scaling: layout must survive `fontScale` 1.3 (no fixed-height rows with single-line text
  without `ellipsize`); reader text is exempt (user-controlled).
- Colour is never the only signal: activity states add shape (pulse/dot/spinner), modes add label text.
- Announce async results (“Scan complete, 64 panels”, “Translation ready”) via
  `TYPE_ANNOUNCEMENT` once.

---

## 13. Audit checklist template (per group, pasted into branch summary)

| # | Screen | Light | Dark | 360 dp | ≥412 dp | Loading | Empty | Error | Contrast | Focus | 48 dp | Motion | a11y desc |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| A02 | Home | ☐ | ☐ | ☐ | ☐ | ☐ | ☐ | ☐ | ☐ | ☐ | ☐ | ☐ | ☐ |
