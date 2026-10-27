#!/usr/bin/env python3
"""Heavyweight Koharu ONNX validation — CI only.

Downloads the exact pinned artifact, verifies byte size + SHA-256, inspects the
real graph input/output contract, runs one CPU inference on a synthetic
rectangular image, and dumps the raw tensors for the Kotlin decoder test.

Nothing here runs on the phone. It runs on a GitHub-hosted x86_64 runner.

Exit codes: 0 = all checks passed, 1 = a contract or integrity check failed.
"""

import argparse
import hashlib
import json
import os
import sys
import urllib.request

import numpy as np

PINNED_URL = (
    "https://huggingface.co/ShiniShiho/koharu-yolo26s-onnx/resolve/"
    "e8d0110da6281b93ee6f6509c741931c49ace9fe/koharu-yolo26s.onnx"
)
PINNED_SHA256 = "25a0b06c4ce7ee7c247068a6b3633f98d0f5e913c26ef1cbd06bec3407e914f4"
PINNED_BYTES = 41_604_574

# The decoder contract this repository implements (KoharuSegCore.kt).
ROW_SIZE = 38
PROTO_CHANNELS = 32
CLASS_NAMES = {0: "frame", 1: "dialogue_text", 2: "balloon", 3: "onomatopoeia_text"}


def fail(msg):
    print(f"FAIL: {msg}", file=sys.stderr)
    sys.exit(1)


def download(url, dest):
    if os.path.isfile(dest) and os.path.getsize(dest) == PINNED_BYTES:
        print(f"reuse cached artifact {dest}")
        return
    print(f"downloading {url}")
    with urllib.request.urlopen(url, timeout=180) as r, open(dest, "wb") as f:
        total = 0
        while True:
            chunk = r.read(1 << 20)
            if not chunk:
                break
            f.write(chunk)
            total += len(chunk)
    print(f"downloaded {total} bytes")


def sha256(path):
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()


def inspect_contract(model_path):
    """Read the graph's declared input/output names and shapes without running it."""
    import onnx

    model = onnx.load(model_path, load_external_data=False)
    graph = model.graph

    def shape_of(vi):
        dims = []
        for d in vi.type.tensor_type.shape.dim:
            dims.append(
                d.dim_param if d.HasField("dim_param") and d.dim_param
                else (d.dim_value if d.HasField("dim_value") else None)
            )
        return dims

    inputs = {vi.name: shape_of(vi) for vi in graph.input}
    outputs = {vi.name: shape_of(vi) for vi in graph.output}
    meta = {p.key: p.value for p in model.metadata_props}

    print("INPUTS :", json.dumps(inputs))
    print("OUTPUTS:", json.dumps(outputs))
    print("NAMES  :", meta.get("names"))
    print("STRIDE :", meta.get("stride"), "END2END:", meta.get("end2end"))

    if "images" not in inputs:
        fail(f"input tensor 'images' missing; have {list(inputs)}")
    if set(outputs) != {"output0", "output1"}:
        fail(f"expected output0/output1, have {list(outputs)}")
    if "frame" not in (meta.get("names") or ""):
        fail("graph metadata does not describe the four Koharu classes")

    return inputs, outputs, meta


def letterbox(rgb, size_hw):
    """Resize keeping aspect, pad to stride-aligned rectangle with grey 114, like prepareTile()."""
    h, w = rgb.shape[:2]
    in_h, in_w = size_hw
    scale = min(in_w / w, in_h / h)
    rw, rh = max(1, round(w * scale)), max(1, round(h * scale))
    # nearest-neighbour resize is enough for a synthetic smoke image
    ys = (np.arange(rh) * (h / rh)).astype(np.int64).clip(0, h - 1)
    xs = (np.arange(rw) * (w / rw)).astype(np.int64).clip(0, w - 1)
    resized = rgb[ys][:, xs]
    canvas = np.full((in_h, in_w, 3), 114, dtype=np.uint8)
    pad_y = (in_h - rh) // 2
    pad_x = (in_w - rw) // 2
    canvas[pad_y:pad_y + rh, pad_x:pad_x + rw] = resized
    # Match prepareTile(): scaleX = resizedW / tileW, scaleY = resizedH / tileH (after rounding).
    return canvas, dict(scale_x=rw / w, scale_y=rh / h, pad_x=pad_x, pad_y=pad_y, rw=rw, rh=rh)


def run_inference(model_path, input_w, input_h, fixture_dir):
    import onnxruntime as ort

    # Deterministic synthetic page: a white field with black rectangles that read as
    # "panels". We do not require specific detections (the model was trained on manga),
    # only that the *decoder contract* holds for whatever it returns.
    rng = np.random.default_rng(7)
    page = np.full((512, 384, 3), 255, dtype=np.uint8)
    page[40:200, 40:200] = 0
    page[240:470, 60:330] = 20
    page = (page.astype(np.int16) + rng.integers(-8, 8, page.shape, dtype=np.int16)).clip(0, 255).astype(np.uint8)

    canvas, lb = letterbox(page, (input_h, input_w))
    chw = canvas.astype(np.float32).transpose(2, 0, 1)[None] / 255.0

    so = ort.SessionOptions()
    so.intra_op_num_threads = 2
    sess = ort.InferenceSession(model_path, sess_options=so, providers=["CPUExecutionProvider"])
    names = [o.name for o in sess.get_outputs()]
    out = sess.run(None, {"images": chw})
    pred, proto = out[names.index("output0")], out[names.index("output1")]

    print(f"input {chw.shape} -> output0 {pred.shape} output1 {proto.shape}")

    # ---- contract checks -------------------------------------------------------
    if pred.ndim != 3 or pred.shape[0] != 1:
        fail(f"output0 must be 3-D [1,...], got {pred.shape}")
    if pred.shape[-1] == ROW_SIZE:
        rows = pred[0]  # [instances, 38]
        orientation = "instances-major"
    elif pred.shape[1] == ROW_SIZE:
        rows = pred[0].T  # [38, instances] -> [instances, 38]
        orientation = "channel-major"
    else:
        fail(f"output0 last-two dims {pred.shape[1:]} are neither [*,38] nor [38,*]")
    print(f"output0 row orientation: {orientation}, instances={rows.shape[0]}")

    if proto.ndim != 4 or proto.shape[0] != 1:
        fail(f"output1 must be 4-D [1,32,H/4,W/4], got {proto.shape}")
    if proto.shape[1] != PROTO_CHANNELS:
        fail(f"output1 channel dim {proto.shape[1]} != {PROTO_CHANNELS}")
    if proto.shape[2] != input_h // 4 or proto.shape[3] != input_w // 4:
        fail(f"output1 spatial {proto.shape[2:]} != ({input_h // 4},{input_w // 4})")

    # Row semantics: [x1,y1,x2,y2,score,class_id, 32 coeffs]
    if rows.shape[0] > 0:
        scores = rows[:, 4]
        if not np.all(np.isfinite(scores)):
            fail("non-finite confidence in output0")
        if np.any(scores < 0) or np.any(scores > 1.0001):
            fail(f"confidence outside [0,1]: min={scores.min()} max={scores.max()}")
        classes = np.rint(rows[:, 5]).astype(int)
        bad = sorted({c for c in classes.tolist() if c not in CLASS_NAMES})
        if bad:
            fail(f"class ids outside 0..3 present: {bad} (decoder would silently drop them)")
        # Confidence must be non-increasing if the head sorts by confidence.
        if rows.shape[0] > 1 and not np.all(np.diff(scores) <= 1e-4):
            print("note: output0 is not confidence-sorted; decoder must not rely on order")
        print("class histogram:", {CLASS_NAMES.get(c, c): int((classes == c).sum()) for c in np.unique(classes)})

    # ---- dump a fixture for the Kotlin decoder test ----------------------------
    # Raw little-endian float32 so the JVM test can read it without numpy/Python.
    os.makedirs(fixture_dir, exist_ok=True)
    rows.astype("<f4").tofile(os.path.join(fixture_dir, "pred.f32"))
    proto[0].astype("<f4").tofile(os.path.join(fixture_dir, "proto.f32"))
    props = dict(
        input_w=input_w,
        input_h=input_h,
        proto_w=int(proto.shape[3]),
        proto_h=int(proto.shape[2]),
        scale_x=lb["scale_x"],
        scale_y=lb["scale_y"],
        pad_x=lb["pad_x"],
        pad_y=lb["pad_y"],
        content_w=lb["rw"],
        content_h=lb["rh"],
        tile_x=0,
        tile_y=0,
        tile_w=int(page.shape[1]),
        tile_h=int(page.shape[0]),
        orientation=orientation,
    )
    with open(os.path.join(fixture_dir, "meta.properties"), "w") as f:
        for k, v in props.items():
            f.write(f"{k}={v}\n")
    print(f"fixture written to {fixture_dir}")
    return rows, proto, props


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--model", default="koharu-yolo26s.onnx")
    ap.add_argument("--fixture-dir", default="build/koharu-fixture")
    ap.add_argument("--input-w", type=int, default=384)
    ap.add_argument("--input-h", type=int, default=512)
    args = ap.parse_args()

    download(PINNED_URL, args.model)
    size = os.path.getsize(args.model)
    if size != PINNED_BYTES:
        fail(f"artifact size {size} != pinned {PINNED_BYTES}")
    digest = sha256(args.model)
    if digest.lower() != PINNED_SHA256:
        fail(f"artifact SHA-256 {digest} != pinned {PINNED_SHA256}")
    print(f"artifact verified: {size} bytes, sha256={digest}")

    inspect_contract(args.model)
    run_inference(args.model, args.input_w, args.input_h, args.fixture_dir)
    print("OK: Koharu contract and inference validated")


if __name__ == "__main__":
    main()
