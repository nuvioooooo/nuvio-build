# dub-addon — Türkçe dublaj + senkron eklentisi

> Bu depo Nuvio'nun fork'u olmak zorunda değil: iş akışı Nuvio'nun kodunu kendisi çeker. Depo **public** olmalı (APK uygulama içinden indirilir).

Nuvio'nun kaynak koduna **elle dosya kopyalamadan**, otomatik uygulanan eklenti paketi.
Nuvio güncellenince GitHub Actions yeni kodu alır, eklentiyi üstüne uygular, APK derler ve bu depoda
**Release** olarak yayınlar. Uygulamadaki *Güncelleme* ekranı bu release'e bakar.

```
Nuvio (NuvioMedia/NuvioTV) ──► [her gün 03:17 UTC] ──► son kod + dub-addon uygula ──► imzalı APK
                                                                                        │
                                          uygulama içi güncelleme  ◄── Release (bu depo)
```

## Klasörler
| Yol | İçerik |
|---|---|
| `overlay/` | Tamamen **yeni** dosyalar (`core/dub/*`, `ExternalAudioDialog`, `PlayerRuntimeControllerDubSync` …). Upstream'de olmadığı için çakışmaz. |
| `patches/` | Nuvio'nun **mevcut** 15 dosyasındaki küçük değişiklikler (toplam ≈ 330 satır ekleme). `git apply --3way` ile uygulanır; Nuvio o dosyaları değiştirse bile çoğu zaman otomatik birleşir. |
| `apply.sh` | overlay + patch + güncelleyiciyi bu depoya yönlendirme + sürüm numarası |
| `scripts/export-patches.sh` | Çakışmayı çözdükten sonra yamaları yeniden üretir |
| `scripts/make-keystore.sh` | Sabit imza anahtarı üretir |
| `UPSTREAM_BASE` | Yamaların ilk çıkarıldığı Nuvio commit'i (`b700cf98e`, 23 Ekim 2026) |

`.github/workflows/dub-sync-release.yml` hepsini çalıştıran iş akışıdır.

## Bir kere yapılacak kurulum
1. **İmza anahtarı:** `dub-addon/scripts/make-keystore.sh` çalıştır (Java gerekir). Çıkan 4 değeri
   *Settings → Secrets and variables → Actions* altına **Secret** olarak ekle:
   `DUB_KEYSTORE_BASE64`, `DUB_KEY_ALIAS`, `DUB_KEY_PASSWORD`, `DUB_STORE_PASSWORD`.
   `.jks` dosyasını yedekle. Her derlemede **aynı** anahtar kullanılmalı; yoksa Android güncellemeyi reddeder.
2. **API anahtarları:** Eski `giriş için gerekli.txt` içindeki 7 değerin hepsini Secret olarak ekle: `LOCAL_PROPERTIES_BASE64`, `SUPABASE_URL`, `SUPABASE_ANON_KEY`, `AVATAR_PUBLIC_BASE_URL`, `TMDB_API_KEY`, `SIMKL_CLIENT_ID`, `MDBLIST_CLIENT_ID`.
   Not: o dosyadaki anahtarları **depoya commit'leme**, sadece Secret olarak sakla.
3. **Actions'ı aç:** Fork'larda zamanlanmış iş akışları kapalı gelir. *Actions* sekmesinden etkinleştir,
   upstream'den gelen gereksiz iş akışlarını (stale/triage) istersen kapat.
4. **İlk sürümü başlat:** Actions → *Dublaj eklentili APK* → **Run workflow**. Bittiğinde Release'te APK görünür.
5. **Telefona/TV'ye bir kez elle kur.** İmza anahtarı farklı olduğu için daha önce kurduğun test sürümünü
   önce kaldırman gerekebilir. Bundan sonrası uygulama içinden güncellenir.

## Nasıl güncellenir?
- Her gün otomatik kontrol edilir. Nuvio'da **veya** `dub-addon/` içinde yeni bir şey yoksa derleme atlanır.
- Sürüm adı: Nuvio `1.1.0-beta.9` ise bu fork `1.1.0-beta.9.dub.1`, `.dub.2` … olur. Nuvio yeni sürüme
  geçince numara onunla birlikte ilerler; güncelleyici bunu doğru sıralar.
- Elle tetiklemek için *Run workflow*.

## Çakışma olursa
Nuvio, yamalanan satırların tam yanındaki kodu değiştirirse iş akışı **durur, APK yayınlamaz** ve bir issue açar
(eski APK çalışmaya devam eder). Düzeltmek için:
```bash
git clone https://github.com/NuvioMedia/NuvioTV nuvio && cd nuvio
/yol/bu-depo/dub-addon/apply.sh .          # çakışan dosyaları gösterir
# <<<<<<< işaretlerini elle düzelt, derlendiğini kontrol et
git checkout app/build.gradle.kts           # sürüm düzenlemesini yamaya katma
/yol/bu-depo/dub-addon/scripts/export-patches.sh .
```
Sonra bu depoya `dub-addon/patches`, `overlay` ve `UPSTREAM_BASE`'i commit'le; iş akışını yeniden çalıştır.
Bu işi Claude'a da verebilirsin: çakışan dosya adı ve upstream commit'i yeterli.

## Sınırlar (bilmen gerekenler)
- Güncelleme "kod birleştirme" değil **APK yenileme**dir: derleme GitHub'da olur, uygulama sadece hazır APK'yı indirir.
- Yamalar derlenip sınanmadan yayınlanmaz ama dublaj davranışını otomatik test eden bir test yok;
  Nuvio büyük bir oynatıcı yeniden yazımı yaparsa eklenti çalışmayabilir (derleme hatası olarak görürsün).
- `dublaj_kaynaklari.json` ayrı `nuvioooooo/dub` deposundan çekilir (adres `core/dub/DubSourceManager.kt` içinde). Site değişince orayı güncellemek yeter, APK gerekmez.
