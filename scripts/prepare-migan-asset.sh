#!/usr/bin/env bash
set -euo pipefail

# Build-time preparation only. The installed Android app never downloads this model.
ASSET="app/src/main/assets/migan_fp16.tflite"
PART="${ASSET}.part"
URL="https://huggingface.co/litert-community/MI-GAN-512-Places2-LiteRT/resolve/179a35d8e8a518f6253f9c42fc9b412c6438cf7c/migan_fp16.tflite"
EXPECTED_BYTES="16312640"
EXPECTED_SHA256="ef53f8dca69e5ce29629441128322c4d9a0a527b2a043703e0ab497f2463c25f"

mkdir -p "$(dirname "$ASSET")"
if [ -s "$ASSET" ] &&
   [ "$(wc -c < "$ASSET" | tr -d ' ')" = "$EXPECTED_BYTES" ] &&
   [ "$(sha256sum "$ASSET" | awk '{print $1}')" = "$EXPECTED_SHA256" ]; then
  echo "MI-GAN asset already present and checksum-valid."
  exit 0
fi
rm -f "$PART"
curl --fail --location --retry 3 --connect-timeout 20 --max-time 180 "$URL" --output "$PART"
ACTUAL_BYTES="$(wc -c < "$PART" | tr -d ' ')"
ACTUAL_SHA256="$(sha256sum "$PART" | awk '{print $1}')"
if [ "$ACTUAL_BYTES" != "$EXPECTED_BYTES" ] || [ "$ACTUAL_SHA256" != "$EXPECTED_SHA256" ]; then
  echo "::error::Pinned MI-GAN asset verification failed (bytes=$ACTUAL_BYTES sha256=$ACTUAL_SHA256)"
  rm -f "$PART"
  exit 1
fi
mv "$PART" "$ASSET"
echo "Bundled MI-GAN: bytes=$EXPECTED_BYTES sha256=$EXPECTED_SHA256"
