# Koharu YOLO26s segmentation model notice

Novel Studio downloads this ONNX model on user request; the weights are not bundled in the APK.

- Model export: [ShiniShiho/koharu-yolo26s-onnx](https://huggingface.co/ShiniShiho/koharu-yolo26s-onnx)
- Pinned revision: `e8d0110da6281b93ee6f6509c741931c49ace9fe`
- File: `koharu-yolo26s.onnx`
- Exact file size: `41,604,574` bytes
- SHA-256: `25a0b06c4ce7ee7c247068a6b3633f98d0f5e913c26ef1cbd06bec3407e914f4`
- Source model: [mayocream/koharu-yolo26s](https://huggingface.co/mayocream/koharu-yolo26s)
- Classes: `frame`, `dialogue_text`, `balloon`, `onomatopoeia_text`.

## Verified graph contract

These are read from the pinned artifact itself (CI: `scripts/koharu_model_check.py`), not assumed
from the model card:

- Input `images`: `float32 [batch, 3, height, width]`, RGB 0..1, letterboxed with grey 114,
  height/width multiples of 32 (dynamic).
- Output `output0`: `[batch, instances, 38]` — each row is `x1 y1 x2 y2 confidence class_id`
  followed by 32 mask coefficients, in input-image pixels. For a 512x384 input this is `[1,300,38]`.
  It is an end-to-end head: no NMS, but a few same-class duplicates are present.
- Output `output1`: `[batch, 32, height/4, width/4]` mask prototypes (sigmoid already applied).
  For a 512x384 input this is `[1,32,128,96]`.

## Why this repository and not `Liiesl/bubble-segment-onnx`

A similarly named static 1024x1024 export exists at
[`Liiesl/bubble-segment-onnx`](https://huggingface.co/Liiesl/bubble-segment-onnx). It ships the same
weights (`mayocream/koharu-yolo26s`) but its model card's sample code reads four per-class
probabilities at `det[5:9]`, which does not match what the pinned graph actually emits (a single
class id). Its card and its graph also disagree on the anchor count (`[1,300,38]` in the card vs
`[1,684,38]` in the graph). The app therefore pins the `ShiniShiho` export above, whose declared
graph matches the decoder in `KoharuSegCore.kt` and is validated end to end in CI.

## License and upstream data

The ONNX repository lists its license as `other`; its model card says the exported graph is stamped with AGPL-3.0. The upstream model card also requires compliance with the applicable Manga109, MangaSegmentation, COO and Ultralytics terms. The project's MIT license does not grant rights to this third-party model or training data. Review the [ONNX model card](https://huggingface.co/ShiniShiho/koharu-yolo26s-onnx), [source model card](https://huggingface.co/mayocream/koharu-yolo26s), and linked dataset/model terms before redistributing the weights or using them commercially.
