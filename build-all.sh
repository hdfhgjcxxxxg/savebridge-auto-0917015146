#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")" && pwd)"
OUT="$ROOT/deliverables"
mkdir -p "$OUT"
echo "SaveBridge3: source is ready."
if command -v docker >/dev/null 2>&1; then
  docker run --rm -v "$ROOT/ctr:/work" -w /work devkitpro/devkitarm:latest make
else
  echo "Docker/devkitARM is required for the 3DS rebuild." >&2
  exit 2
fi
if ! command -v makerom >/dev/null 2>&1; then
  echo "Install Project_CTR makerom and put it on PATH." >&2
  exit 2
fi
makerom -f cia -o "$OUT/SaveBridgeMulti-v0.3.cia" -elf "$ROOT/ctr/SaveBridgeMulti.elf" -rsf "$ROOT/ctr/app.rsf" -icon "$ROOT/ctr/SaveBridgeMulti.smdh" -ver 3
if ! command -v java >/dev/null 2>&1; then echo "JDK 17 is required." >&2; exit 2; fi
echo "Use Android SDK build-tools 34+ and compile android/src with a Java compiler, then package with aapt2/d8/apksigner."
echo "See BUILD.md for the exact APK packaging command."
sha256sum "$OUT"/* 2>/dev/null > "$OUT/SHA256SUMS.txt" || true
echo "Done: $OUT"
