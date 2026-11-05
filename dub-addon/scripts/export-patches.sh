#!/usr/bin/env bash
# Çakışmayı elle çözdükten sonra eklenti yamalarını YENİDEN üretir.
#
#   1) temiz bir upstream klasöründe:   dub-addon/apply.sh .     (çakışırsa dosyaları elle düzelt)
#   2) git checkout app/build.gradle.kts   (sürüm/güncelleyici düzenlemesini yamaya katma)
#   3) dub-addon/scripts/export-patches.sh <o klasör>
#   4) yeni dub-addon/patches ve overlay dosyalarını fork'a commit'le
set -euo pipefail
ADDON="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
SRC="${1:?kullanım: export-patches.sh <çözülmüş-upstream-klasörü>}"
cd "$SRC"

git add -A app/src
rm -rf "$ADDON/patches" "$ADDON/overlay"
mkdir -p "$ADDON/patches" "$ADDON/overlay"

i=0
while IFS= read -r f; do
  i=$((i+1))
  git diff --cached HEAD -- "$f" > "$ADDON/patches/$(printf '%04d' "$i")-$(basename "$f" .kt).patch"
done < <(git diff --cached --name-only --diff-filter=M HEAD -- app/src | sort)

while IFS= read -r f; do
  mkdir -p "$ADDON/overlay/$(dirname "$f")"
  cp "$f" "$ADDON/overlay/$f"
done < <(git diff --cached --name-only --diff-filter=A HEAD -- app/src | sort)

git rev-parse HEAD > "$ADDON/UPSTREAM_BASE"
echo "yama: $(ls "$ADDON/patches" | wc -l)   yeni dosya: $(find "$ADDON/overlay" -type f | wc -l)   taban: $(cat "$ADDON/UPSTREAM_BASE")"
