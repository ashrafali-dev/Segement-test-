# Google Lens OCR engine — provenance and licensing notice

`LensOcrEngine.kt` / `LensProtocol.kt` let NovelStudio read text with Google Lens. They only
**extract text**; translation is done by NovelStudio's own chatbot.

## What is used, and where it came from
The upload protocol (endpoint, protobuf field numbers, enum values, response layout) is the one Chromium's
Lens overlay uses. It was studied in four open-source projects that all talk to the same endpoint:

| Project | License | How it was used |
|---|---|---|
| chrome-lens-py (Bropines) | MIT | Protocol reference (field numbers read from its generated descriptors) |
| chrome-lens-ocr | ISC | Cross-check of endpoint / key / request shape |
| owocr (AuroraWright) | GPL-3.0 | Cross-check only. **No code copied.** |
| YomiNinja | GPL-3.0 | Reviewed; it does not use Lens. **No code copied.** |

`LensProtocol.kt` and `LensOcrEngine.kt` are original Kotlin written for NovelStudio (a minimal protobuf
wire encoder/decoder, Android networking, retry/circuit-breaker). Facts that necessarily match the
projects above: the endpoint URL, protobuf field numbers/enums, and the public API-key string. Because no
GPL source was copied or adapted, NovelStudio is not made a GPL work by this feature. If you ever copy code
from owocr or YomiNinja, that changes and GPL-3.0 obligations apply.

## Important caveats (read before shipping)
* The endpoint `https://lensfrontend-pa.googleapis.com/v1/crupload` is **not a documented or supported
  public API**. It is Chromium's internal Lens endpoint. Google may change, rate-limit or block it at any
  time, and using it from a third-party app may not be covered by Google's terms. There is no SLA.
* The API key in the code is the key embedded in Chromium's Lens overlay; it is **Google's, not yours**, and can
  be rotated or revoked. It can be overridden at runtime through the `lensApiKey` / `lensEndpoint` preferences.
* Images being read are uploaded to Google. The engine picker text says so.
* The engine is opt-in (Settings → OCR Engine → Google Lens). Default stays ML Kit, and any Lens failure falls
  back to ML Kit.
