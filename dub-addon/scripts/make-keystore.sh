#!/usr/bin/env bash
# Güncellemelerin kurulabilmesi için SABİT bir imza anahtarı üretir (bir kere yapılır).
# Çıktıyı GitHub > Settings > Secrets and variables > Actions altına ekle.
set -euo pipefail
OUT="${1:-dub-release.jks}"
ALIAS="nuviodub"
PASS="$(head -c 18 /dev/urandom | base64 | tr -dc 'A-Za-z0-9' | head -c 20)"
keytool -genkeypair -keystore "$OUT" -alias "$ALIAS" -keyalg RSA -keysize 2048 -validity 36500 \
  -storepass "$PASS" -keypass "$PASS" -dname "CN=Nuvio Dub,O=Personal,C=TR" >/dev/null
echo "Anahtar dosyası: $OUT   (YEDEKLE! kaybedersen eski kurulumlar güncellenemez, silip yeniden kurman gerekir)"
echo
echo "Secret adı              Değer"
echo "DUB_KEY_ALIAS           $ALIAS"
echo "DUB_KEY_PASSWORD        $PASS"
echo "DUB_STORE_PASSWORD      $PASS"
echo "DUB_KEYSTORE_BASE64     (aşağıdaki tek satır)"
base64 -w0 "$OUT"; echo
