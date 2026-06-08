#!/usr/bin/env sh
set -eu

ROOT_DIR="$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)"
ASSET_DIR="$ROOT_DIR/app/src/main/assets"
MODEL="$ASSET_DIR/gesture_recognizer.task"
URL="https://storage.googleapis.com/mediapipe-models/gesture_recognizer/gesture_recognizer/float16/1/gesture_recognizer.task"

mkdir -p "$ASSET_DIR"
curl -L "$URL" -o "$MODEL"
ls -lh "$MODEL"
