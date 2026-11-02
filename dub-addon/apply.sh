#!/usr/bin/env bash
# Türkçe dublaj eklentisini, TEMİZ bir Nuvio (upstream) çalışma klasörüne uygular.
#   kullanım:  dub-addon/apply.sh <upstream-klasörü>
# Ortam değişkenleri (hepsi isteğe bağlı):
#   DUB_RELEASE_OWNER / DUB_RELEASE_REPO : uygulama içi güncelleyicinin bakacağı depo
#   DUB_REV                              : bu upstream sürümü için eklenti sürüm sayacı (1,2,3…)
set -euo pipefail

ADDON="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
TARGET="${1:?kullanım: apply.sh <upstream-klasörü>}"
cd "$TARGET"

OWNER="${DUB_RELEASE_OWNER:-nuvioooooo}"
REPO="${DUB_RELEASE_REPO:-Nuviaaa}"
REV="${DUB_REV:-1}"

# --- 1) Yeni dosyalar (overlay): upstream'de aynı yolda dosya varsa dur -------------
while IFS= read -r -d '' f; do
  rel="${f#"$ADDON/overlay/"}"
  if git ls-files --error-unmatch "$rel" >/dev/null 2>&1; then
    echo "::error::OVERLAY ÇAKIŞMASI: upstream artık '$rel' dosyasını içeriyor. Eklentideki kopya yerine upstream'inkiyle birleştirilmeli (bkz. dub-addon/README.md)."
    exit 3
  fi
  mkdir -p "$(dirname "$rel")"
  cp "$f" "$rel"
done < <(find "$ADDON/overlay" -type f -print0)
echo "overlay: $(find "$ADDON/overlay" -type f | wc -l) yeni dosya eklendi"

# --- 2) Mevcut dosyalardaki küçük değişiklikler (patch, 3-way) ------------------------
failed=0
for p in "$ADDON"/patches/*.patch; do
  if git apply --3way "$p" >/dev/null 2>&1; then
    echo "patch OK   $(basename "$p")"
  else
    echo "::error::patch ÇAKIŞTI  $(basename "$p")"
    failed=1
  fi
done
if [ "$failed" -ne 0 ] || grep -rIl --include='*.kt' -E '^(<<<<<<<|>>>>>>>) ' app/src >/dev/null 2>&1; then
  echo "::error::Eklenti yamaları güncel Nuvio koduyla birleşemedi. Elle çözüm gerekli."
  git diff --name-only --diff-filter=U || true
  exit 2
fi

# --- 3) Güncelleyiciyi bu forka yönlendir + sürüm numarası ----------------------------
GRADLE=app/build.gradle.kts
base_name="$(sed -nE 's/^[[:space:]]*versionName = "([^"]+)".*/\1/p' "$GRADLE" | head -1)"
base_code="$(sed -nE 's/^[[:space:]]*versionCode = ([0-9]+).*/\1/p' "$GRADLE" | head -1)"
[ -n "$base_name" ] && [ -n "$base_code" ] || { echo "::error::versionName/versionCode bulunamadı"; exit 4; }

case "$base_name" in
  *-*) new_name="${base_name}.dub.${REV}" ;;   # 1.1.0-beta.9  -> 1.1.0-beta.9.dub.1
  *)   new_name="${base_name}-dub.${REV}"  ;;   # 1.1.0         -> 1.1.0-dub.1
esac
new_code=$(( base_code * 1000 + (REV % 1000) ))

# Güncelleyicinin bakacağı depo (BuildConfig.GITHUB_OWNER / GITHUB_REPO)
sed -i "/\"GITHUB_OWNER\"/ s/NuvioMedia/${OWNER}/" "$GRADLE"
sed -i "/\"GITHUB_REPO\"/ s/NuvioTV/${REPO}/" "$GRADLE"
sed -i -E "s|^([[:space:]]*versionName = )\"[^\"]+\"|\1\"${new_name}\"|" "$GRADLE"
sed -i -E "s|^([[:space:]]*versionCode = )[0-9]+|\1${new_code}|" "$GRADLE"

grep "GITHUB_OWNER" "$GRADLE" | grep -q "${OWNER}" \
  && grep "GITHUB_REPO" "$GRADLE" | grep -q "${REPO}" \
  && grep -q "versionName = \"${new_name}\"" "$GRADLE" \
  || { echo "::error::build.gradle.kts düzenlemesi doğrulanamadı"; exit 5; }

echo "VERSION_NAME=${new_name}"
echo "VERSION_CODE=${new_code}"
echo "UPSTREAM_VERSION=${base_name}"
