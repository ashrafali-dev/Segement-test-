# Japanese Manga Engine — third-party notices

This optional engine is separate from NovelStudio's existing OCR/Webtoon translation pipeline. OCR weights and the runtime are downloaded only after the user opts in, into app-private storage; they are not bundled in the APK.

## PP-OCRv6_manga v0.2
- Model card: https://huggingface.co/Kellenok/PP-OCRv6_manga
- License declared by the model card: Apache-2.0
- Files used: det/manga_det_v0.2.onnx, rec/manga_rec_v0.2.onnx, ppocrv6_dict.txt.

## ONNX Runtime Web 1.23.2
- Project: https://github.com/microsoft/onnxruntime
- Package: https://www.npmjs.com/package/onnxruntime-web/v/1.23.2
- License: MIT
- Files used: ort.wasm.min.js, ort-wasm-simd-threaded.mjs, ort-wasm-simd-threaded.wasm.

## Limitations
The model can still miss or misread tiny furigana, rotated effect lettering, blurred panels and unusual fonts. This initial integration is an isolated OCR/bounding-box test screen. Live translation and placement inside speech bubbles are not connected yet.
