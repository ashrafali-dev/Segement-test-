const fs = require("node:fs");
const assert = require("node:assert/strict");

const writer = fs.readFileSync("app/src/main/java/com/ashraf/novelstudio/WebModeWriter.kt", "utf8");
const extractor = fs.readFileSync("app/src/main/java/com/ashraf/novelstudio/WebModeExtractor.kt", "utf8");
const rescan = fs.readFileSync("app/src/main/java/com/ashraf/novelstudio/OcrRescan.kt", "utf8");
const mainActivity = fs.readFileSync("app/src/main/java/com/ashraf/novelstudio/MainActivity.kt", "utf8");
const libMatch = extractor.match(/private const val LIB = """\r?\n([\s\S]*?)\r?\n\s*"""/);
assert.ok(libMatch, "could not locate WebModeExtractor page-discovery JavaScript");
const decorativeSource = extractFunction(libMatch[1], "function decorativeUrl(s)");
const decorativeUrl = new Function(decorativeSource + "; return decorativeUrl;")();
assert.equal(decorativeUrl("https://cdn-ak-img.shonenjumpplus.com/public/uploaded-content/link_slot_abc.jpg"), true, "link-slot promotional art must not be treated as a manga panel");
assert.equal(decorativeUrl("https://cdn-ak-img.shonenjumpplus.com/public/uploaded-content/manga_page_abc.jpg"), false, "actual manga assets must remain eligible");
assert.ok(mainActivity.includes("source.lowercase().contains(\"/public/uploaded-content/link_slo\")"), "manual OCR must recognize the Shonen Jump+ link-slot false target");
assert.ok(mainActivity.includes("forceScreenOcr = forceScreenOcr"), "a link-slot selection must switch to viewport OCR");
assert.ok(extractor.includes("forceFresh = req.forceFreshOcr || req.forceScreenOcr"), "manual viewport fallback must bypass OCR cache and use thorough mode");
const forceInfoAt = extractor.indexOf("val forceInfoScript = if (req.visibleOnly)");
const forceInfoEnd = extractor.indexOf("else JS_FORCE_INFO", forceInfoAt);
assert.ok(forceInfoAt >= 0 && forceInfoEnd > forceInfoAt, "missing viewport canvas-crop path");
const forceInfoBlock = extractor.slice(forceInfoAt, forceInfoEnd);
assert.ok(forceInfoBlock.includes("cands(sc,true,true,true)"), "manual screen OCR must retain actual canvas bounds");
assert.ok(!forceInfoBlock.includes("c=[]"), "manual screen OCR must not scan unrelated whole-WebView UI text");
// Lens completeness audit must retry compact, fully missed labels (e.g. a short "Skip" button),
// rather than filtering them out with the previous 200x80-pixel floor or inspecting one region only.
assert.ok(rescan.includes("MIN_REMOTE_AUDIT_WIDTH = 32"), "Lens audit must allow short button labels");
assert.ok(rescan.includes("MIN_REMOTE_AUDIT_HEIGHT = 12"), "Lens audit must allow short button labels");
assert.ok(rescan.includes("findUncovered(bmp, base.blocks, REMOTE_AUDIT_CANDIDATE_LIMIT)"), "Lens audit must inspect multiple candidates");
assert.ok(rescan.includes("REMOTE_AUDIT_MAX_REGIONS = 2"), "Lens audit must retry up to two likely missed regions");
assert.ok(rescan.includes("{ if (it.anchor == null) 0 else 1 }"), "fully missed labels must be prioritized over partial re-reads");
const recognizeAt = extractor.indexOf("private fun recognizeInner(");
const recognizeEnd = extractor.indexOf("private fun recognizeCore(", recognizeAt);
assert.ok(recognizeAt >= 0 && recognizeEnd > recognizeAt, "could not locate OCR routing function");
const recognizeInner = extractor.slice(recognizeAt, recognizeEnd);
assert.ok(!recognizeInner.includes("wv.url"), "OCR worker must not access WebView.url");
assert.ok(recognizeInner.includes("hostOf(startUrl)"), "canvas-site detection must use the UI-thread-captured URL");
const bodyMatch = writer.match(/private const val BUBBLES = """\r?\n([\s\S]*?)\r?\n\s*"""/);
assert.ok(bodyMatch, "could not locate the embedded WebView overlay script");
new Function(bodyMatch[1]); // compile the real embedded JavaScript without executing browser APIs

function extractFunction(source, marker) {
  const start = source.indexOf(marker);
  assert.ok(start >= 0, "missing production function: " + marker);
  const open = source.indexOf("{", start);
  assert.ok(open >= 0, "missing function body: " + marker);
  let depth = 0, state = "code", quote = "", escaped = false;

  for (let i = open; i < source.length; i++) {
    const ch = source[i], next = source[i + 1];
    if (state === "line-comment") { if (ch === "\n") state = "code"; continue; }
    if (state === "block-comment") {
      if (ch === "*" && next === "/") { state = "code"; i++; }
      continue;
    }
    if (state === "string") {
      if (escaped) { escaped = false; continue; }
      if (ch === "\\") { escaped = true; continue; }
      if (ch === quote) state = "code";
      continue;
    }
    if (ch === "/" && next === "/") { state = "line-comment"; i++; continue; }
    if (ch === "/" && next === "*") { state = "block-comment"; i++; continue; }
    if (ch === "'" || ch === '"' || ch === "\`") { state = "string"; quote = ch; continue; }
    if (ch === "{") depth++;
    if (ch === "}") {
      depth--;
      if (depth === 0) return source.slice(start, i + 1);
    }
  }
  throw new Error("unterminated production function: " + marker);
}

const resolverSource = extractFunction(bodyMatch[1], "function resolve(L)");
global.window = { innerWidth: 420 };
const resolve = new Function("window", resolverSource + "; return resolve;")(global.window);

function box(x, y, w, h, text, source = "same-image", imageLeft = 0, imageRight = 420, angle = 0) {
  return {
    b: { textContent: text, __nsReqHRatio: 0 },
    s: source, ow: w, oh: h, rw: imageRight - imageLeft,
    ox0: x, ox1: x + w, oy0: y, oy1: y + h,
    x0: x, x1: x + w, y0: y, y1: y + h,
    il: imageLeft, ir: imageRight, angle: angle, rot: angle !== 0, needH: 0, hidden: false,
  };
}
function intersects(a, b, gap = 2.9) {
  return a.x0 < b.x1 + gap && a.x1 + gap > b.x0 &&
    a.y0 < b.y1 + gap && a.y1 + gap > b.y0;
}
function assertCollisionFree(items, message) {
  const live = items.filter(x => !x.hidden);
  for (let i = 0; i < live.length; i++) for (let j = i + 1; j < live.length; j++) {
    assert.equal(intersects(live[i], live[j]), false,
      message + ": boxes " + i + " and " + j + " overlap");
  }
}

// Overlapping translations must move sideways and retain their original Y centres.
{
  const a = box(100, 100, 110, 48, "First translation");
  const b = box(150, 118, 104, 48, "Second translation");
  const ax = (a.ox0 + a.ox1) / 2, ay = (a.oy0 + a.oy1) / 2;
  const bx = (b.ox0 + b.ox1) / 2, by = (b.oy0 + b.oy1) / 2;
  resolve([a, b]);
  assertCollisionFree([a, b], "pair placement");
  assert.equal((a.x0 + a.x1) / 2, ax, "first label must retain its source X anchor");
  assert.equal((b.x0 + b.x1) / 2, bx, "second label must retain its source X anchor");
  assert.equal((a.y0 + a.y1) / 2, ay, "first label must retain its source Y anchor");
  assert.equal((b.y0 + b.y1) / 2, by, "second label must retain its source Y anchor");
  assert.ok(a.x1 <= b.x0 || b.x1 <= a.x0, "same-image boxes must shrink instead of being moved sideways");
}

// A transitive three-box conflict is packed as a group without render-order overlap.
{
  const items = [
    box(100, 80, 120, 50, "A"),
    box(145, 103, 120, 50, "B"),
    box(190, 126, 120, 50, "C"),
  ];
  const anchors = items.map(x => ({x:(x.ox0+x.ox1)/2, y:(x.oy0+x.oy1)/2}));
  resolve(items);
  assertCollisionFree(items, "three-box group");
  items.forEach((x, i) => {
    assert.equal((x.x0 + x.x1) / 2, anchors[i].x, "box " + i + " must not move sideways");
    assert.equal((x.y0 + x.y1) / 2, anchors[i].y, "box " + i + " must not move down");
  });
}

// If the row is too wide, boxes shrink into available horizontal space rather than overlap.
{
  const items = [
    box(20, 100, 125, 42, "long text A", "narrow-image", 0, 185),
    box(55, 111, 125, 42, "long text B", "narrow-image", 0, 185),
    box(80, 122, 125, 42, "long text C", "narrow-image", 0, 185),
  ];
  resolve(items);
  assertCollisionFree(items, "overfull row");
  for (const item of items) {
    assert.ok(item.x0 >= 0 && item.x1 <= 185, "packed boxes should remain within image bounds");
    assert.ok(item.x1 - item.x0 < item.ow, "overfull rows must shrink instead of stacking");
  }
}

// Cross-image overlaps are resolved, while an exact duplicate at the same location is drawn once.
{
  const a = box(130, 110, 90, 46, "shared", "image-A");
  const b = box(130, 110, 90, 46, "shared", "image-A");
  const c = box(132, 112, 90, 46, "other", "image-B");
  resolve([a, b, c]);
  assert.equal([a, b].filter(x => x.hidden).length, 1, "exact duplicate should be suppressed");
  assertCollisionFree([a, b, c], "cross-image collision");
}

assert.ok(bodyMatch[1].includes("coverLayer") && bodyMatch[1].includes("textLayer"),
  "background fills and translated text must occupy separate z-layers");
assert.ok(bodyMatch[1].includes("overflowWrap='anywhere'"),
  "last-resort wrapping must keep long text visible");
assert.ok(!bodyMatch[1].includes("text-overflow:ellipsis") && !bodyMatch[1].includes("TruncateAt.END"),
  "overlay must never ellipsize translations");

console.log("WebMode overlay JavaScript syntax, collision, horizontal-only and no-truncation checks passed.");
