#!/usr/bin/env bash
# Downloads PaddleOCR ONNX models (converted and hosted by RapidOCR, Apache-2.0) and verifies sha256.
# Usage: scripts/fetch-models.sh [DIR]   (default: models)
set -euo pipefail

base=https://www.modelscope.cn/models/RapidAI/RapidOCR/resolve/v3.10.0/onnx
dir="${1:-models}"
mkdir -p "$dir"

sha256() {
  if command -v sha256sum >/dev/null; then sha256sum "$1" | cut -d' ' -f1; else shasum -a 256 "$1" | cut -d' ' -f1; fi
}

fetch() {
  local path=$1 want=$2 name=${1##*/}
  if [[ -f "$dir/$name" && "$(sha256 "$dir/$name")" == "$want" ]]; then
    echo "ok      $name"
    return
  fi
  curl -fsSL --retry 3 -o "$dir/$name.part" "$base/$path"
  if [[ "$(sha256 "$dir/$name.part")" != "$want" ]]; then
    rm -f "$dir/$name.part"
    echo "sha256 mismatch: $name" >&2
    exit 1
  fi
  mv "$dir/$name.part" "$dir/$name"
  echo "fetched $name"
}

# Recognition: Korean + English, the only Korean recognizer (PP-OCRv6 has none).
fetch PP-OCRv5/rec/korean_PP-OCRv5_rec_mobile.onnx cd6e2ea50f6943ca7271eb8c56a877a5a90720b7047fe9c41a2e541a25773c9b
# Detection candidates.
fetch PP-OCRv5/det/ch_PP-OCRv5_det_mobile.onnx 4d97c44a20d30a81aad087d6a396b08f786c4635742afc391f6621f5c6ae78ae
fetch PP-OCRv5/det/ch_PP-OCRv5_det_server.onnx 0f8846b1d4bba223a2a2f9d9b44022fbc22cc019051a602b41a7fda9667e4cad
fetch PP-OCRv6/det/PP-OCRv6_det_tiny.onnx f42c0fbd294d95eac1a550e131b277dac97462c8025fa4b6c3cec1b7894bd3d5
fetch PP-OCRv6/det/PP-OCRv6_det_small.onnx 090f04abcd9d9a7498bc4ebf677e4cb9bdce1fe4197ddb7e529f1ef44e1ff94f
fetch PP-OCRv6/det/PP-OCRv6_det_medium.onnx 92078b7355007ccfffcd4c8cd441a3afd4538904d06881b29a155e1e679907c2
