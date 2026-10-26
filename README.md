<div align="center">

# 🎌 Novel Studio — Overlay Engine

**Read any webtoon, manga or web novel in your own language — without scraping a single page.**

[![Kotlin](https://img.shields.io/badge/Kotlin-100%25-7F52FF?style=for-the-badge&logo=kotlin&logoColor=white)](https://kotlinlang.org)
[![Android](https://img.shields.io/badge/Android-7.0%2B-3DDC84?style=for-the-badge&logo=android&logoColor=white)](https://developer.android.com)
[![OCR](https://img.shields.io/badge/OCR-Google%20Lens%20%2B%20ML%20Kit-4285F4?style=for-the-badge&logo=google&logoColor=white)](https://developers.google.com/ml-kit)
[![Speed](https://img.shields.io/badge/150%20panels-under%2050s-FF6B6B?style=for-the-badge)](.#-performance)
[![No Scraping](https://img.shields.io/badge/Scraping-Zero-success?style=for-the-badge)](#-the-middleman-concept)

[English](README.md) · [বাংলা](README_bn.md)

</div>

---

## ✨ What is this?

The **extended (advanced) version of Novel Studio** — a non-destructive, on-the-fly **OCR + translation overlay** for webtoons, manga and web novels.

It does not download chapters. It does not rewrite a site's database. It does not host pirated content.
It sits *on top* of the page you are already reading and paints your language over the original lettering, in the exact spot the original text sat.

> **Think of it as a translator standing behind the page, whispering into your ear — the page itself is untouched.**

---

## 🧠 The "Middleman" Concept

The app is a **broker between two browsers**.

```
┌──────────────────────────────────────────────────────────────┐
│                        YOUR PHONE                            │
│                                                              │
│   ┌────────────────────────────────────────────────────┐     │
│   │  WebView #1  — the page you read                   │     │
│   │                                                    │     │
│   │   ┌──────────┐   ┌──────────┐   ┌──────────┐       │     │
│   │   │ panel 1  │   │ panel 2  │   │ panel 3  │       │     │
│   │   │  漫画の   │   │  テキスト │   │  SFX!!   │       │     │
│   │   │  テキスト │   │          │   │          │       │     │
│   │   └────┬─────┘   └────┬─────┘   └────┬─────┘       │     │
│   └────────┼──────────────┼──────────────┼─────────────┘     │
│            │  pixels      │              │                   │
│            ▼              ▼              ▼                   │
│   ┌────────────────────────────────────────────────────┐     │
│   │        OCR   →   text boxes + geometry             │     │
│   │        Google Lens  (remote, primary)              │     │
│   │        ML Kit       (local, pre-filter + fallback) │     │
│   └────────────────────┬───────────────────────────────┘     │
│                        │  "this line lives at x, y, w, h"    │
│                        ▼                                     │
│   ┌────────────────────────────────────────────────────┐     │
│   │  WebView #2 — your chatbot / translation provider  │     │
│   └────────────────────┬───────────────────────────────┘     │
│                        │  translated text                    │
│                        ▼                                     │
│   ┌────────────────────────────────────────────────────┐     │
│   │   HTML OVERLAY  →  painted back onto WebView #1    │     │
│   │   〔 これが翻訳です 〕  ← same box, same bubble      │     │
│   └────────────────────────────────────────────────────┘     │
└──────────────────────────────────────────────────────────────┘
```

**Top → bottom:** the app reads the comic, asks the translator, and writes the answer back onto the page.

**Bottom → top:** the same bridge runs in reverse — text the chatbot produces can be read and placed too.

### 🔒 Non-Destructive HTML Overlay

| Traditional scraper | Novel Studio Overlay |
|---|---|
| Downloads and rehosts the chapter | ❌ Never downloads a chapter |
| Breaks when the site changes markup | ✅ Uses the site's own DOM to place text |
| Images no longer match the original | ✅ Same page, same scroll, same zoom |
| Needs mirrors and storage | ✅ Zero copies on disk |

Because placement uses the **page's own HTML and JS**, the translation scrolls with the artwork, resizes with the viewport, and lands inside the original bubble — not in a separate reader view.

---

## ⚡ Performance

| Mode | What it does | Measured on SM-A136B (3 GB RAM) |
|---|---|---|
| 🚀 **Fast Scan (Batch)** | Whole chapter, pipelined OCR + parallel Lens requests | **64 panels in 52 s** |
| 🎯 **Realtime** | Instant translation as you scroll | ~0.2–1.5 s per visible panel |
| 🧠 **Ahead (Pre-fetch)** | Translates the *next* chapters while you read | **7+ chapters** pre-translated |
| ✋ **Manual Lite** | One tap = one crop = highest-quality read | Sub-second, zero background work |

### Why a whole chapter no longer takes five minutes

```
fetch (6 threads) ─┐
                   ├─► ML Kit pre-filter (~40 ms) ──► no text? skip Lens
                   │                                  └─ text? ──► Google Lens
                   └─► Semaphore(3) ─────────────────────────────► parallel replies
```

* **Pipelining** — panel #2 downloads while panel #1 is being read.
* **Local pre-filter** — silent art panels never cost a network round trip.
* **Strict rate limiting** — three Lens requests in flight, 200 ms apart. One 429 costs more time than the parallelism saves.
* **Honest failures** — a bug in our own code never silently downgrades the whole chapter to the local engine.

---

## 📱 Built for real phones

| Device class | What you get |
|---|---|
| 💎 **4 GB+ RAM** | **AI Inpainting (aiPainter)** — the model rebuilds the artwork under erased lettering. Invisible seams. |
| 📱 **Budget / 2–3 GB** | **SFX-aware masking** — lettering is detected, painted out with sampled page colour, and the translation is fitted inside the bubble. No heavy model, no OOM kill. |

Both paths keep the same overlay, the same translation quality and the same chapter position. Only the *erasing technique* changes.

---

## 🔬 Engine stack

| Role | Engine | Why |
|---|---|---|
| Primary OCR | **Google Lens** | Best accuracy on dense CJK columns and stylised lettering |
| Local pre-filter | **ML Kit (on-device)** | Free, ~40 ms, answers "is there text at all?" before a network call |
| Fallback OCR | **ML Kit (thorough)** | Runs on the *same crop* when Lens fails or finds nothing |
| Bubble geometry | **BubbleFit** (pixel) + optional **ONNX detector** | Finds the bubble interior so text is fitted, not just covered |
| Translation | **Your chatbot / provider** | Any LLM web UI you already use — it stays the translator |

**The chatbot is the translation engine.** The app drives it through the same WebView you see, reads the reply, parses the numbered markers, and maps each answer back to the exact OCR box it came from.

---

## 📖 Novel Mode

Web novels in Chinese, Korean, Japanese or any other language — read them in yours.

* Extracts chapter text straight from the reader DOM (fast path, no OCR needed).
* Falls back to OCR only when a site renders its text as images.
* Same overlay, same translation provider, same glossary behaviour.

---

## 🗺️ Roadmap

| Status | Feature |
|---|---|
| 🚧 Planned | **Community Glossaries** — upload a chapter's term list; anyone can apply it in one tap so names stay consistent across groups |
| 🚧 Planned | **Story / Chapter Communities** — discuss characters and plot with readers of the same chapter |
| 🚧 Planned | **Mihon-style Extensions** — a library of per-site extensions instead of one generic engine |
| ✅ Shipped | Fast Scan, Realtime, Ahead pre-fetch, Manual Lite, Novel Mode, AI inpainting, SFX masking |

---

## 🛠 Tech stack

```
Kotlin · Android (minSdk 24) · WebView · JavaScript bridge
Google Lens OCR (HTTP)      ML Kit Text Recognition (4 script models)
ONNX Runtime (optional)     LaMa inpainting (optional)
PixelCopy · Canvas overlay  Custom HTML overlay renderer
ExecutorService + Semaphore · LRU caches · bounded memory
```

---

## 🙏 Credits

This project is the **extended (advanced) version of Novel Studio**.

**Developer:** **Ashraf Ali** — from 🇧🇩 **Bangladesh**

**Special thanks** to the open-source community and to the many ready-made sources, libraries and reference implementations that made this possible. Standing on the shoulders of people who share their work.

---

## 📌 A note on use

This is a **personal reading aid**. It does not host, mirror or distribute any comic, manga or novel. All content stays on the site you visit; the app only draws text on top of what your browser already displays.

Please respect the terms of the sites you read and the authors who create the work.

<div align="center">

**Made for readers who just want to understand the story.**

⭐ Star the repo if this saved you a chapter of guessing.

</div>
