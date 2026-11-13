#!/usr/bin/env bash
# Downloads Korean TrueType fonts (SIL OFL, google/fonts pinned commit) for `rrnmask synth`.
# PDFBox can embed these; macOS system Korean fonts lack tables it needs.
# Usage: scripts/fetch-fonts.sh [DIR]   (default: fonts)
set -euo pipefail

base=https://raw.githubusercontent.com/google/fonts/35e77c3854970a550d9894a85aba21420813a019/ofl
dir="${1:-fonts}"
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

fetch nanumgothic/NanumGothic-Regular.ttf 76f45ef4a6bcff344c837c95a7dcc26e017e38b5846d5ae0cdcb5b86be2e2d31
fetch nanummyeongjo/NanumMyeongjo-Regular.ttf 7ed9e8653a8ed04285d51dc343ffea6eb3d9c73afc27383ea8929ee4ffd03205
fetch gowunbatang/GowunBatang-Regular.ttf 466c593e7147412e748af4856d5ad14709b5a860bdf62b9c2546f2c5874e9849
