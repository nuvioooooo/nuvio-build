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

## Otomatik arama ve otomatik senkron anahtarları
Oynatıcıda **Ses** menüsünde iki satırın sağında birer anahtar (Açık/Kapalı) vardır:

| Satır | Satıra basınca | Anahtar |
|---|---|---|
| Türkçe dublaj ara | Dublajı hemen arar | Video açılınca dublajın kendiliğinden aranması (varsayılan **Açık**) |
| Dublajı otomatik senkronla | Seçili dublajı hemen ölçer | Dublaj eklenince senkronun kendiliğinden ölçülmesi (varsayılan **Kapalı**) |

Seçim cihazda saklanır (`nuvio_dub` / `auto_search_enabled`, `auto_sync_enabled`) ve sen değiştirene kadar
videolar, uygulamanın yeniden başlatılması ve güncellemeler arasında aynı kalır. Kumandada: satırda **Tamam** işlemi
çalıştırır, **Sağ** tuşu anahtara geçer, anahtarda **Tamam** açıp kapatır. Anahtar yalnızca sonraki videoları etkiler.
Uzak listedeki `autoSearch: false` ayarı hâlâ geçerlidir; otomatik aramanın çalışması için ikisi de açık olmalı.

## Senkron arama aralığı
Otomatik ölçüm dublajın orijinalden **±15 saniyeye kadar** kaymış olmasını yakalar (eskiden ±8 sn). Bunun için her
pencerede dublaj sesi, orijinalden 15 sn önce başlayıp 15 sn sonra biten, toplam 45 sn'lik bir parça olarak okunur;
orijinal taraf (ağır olan) 15 sn'lik pencere olarak kalır. Dublaj küçük bir ses dosyası olduğundan ek maliyet azdır.
Kayma sabit değilse (25 ↔ 23.976 fps gibi hız farkı) ölçüm yine güvenilir değil der.

## Hız farkı (kayma sabit değilse)
Dublaj videodan farklı hızdaysa (ör. 25 fps PAL kaynaklı dublaj, 23.976 fps video: dublaj %4,1 kısa) kayma film boyunca
büyür ve tek bir gecikmeyle düzelmez. Eklenti bunu iki yolla ayırt eder:
- **Küçük hız farkı** (24 ↔ 23.976 gibi, yaklaşık %0,1): her pencerenin kendi kayması bulunup konuma göre bir doğru
  uydurulur. Doğru eğimliyse "Kayma sabit değil" denir ve gecikme **şu anki konuma göre** ayarlanır (yalnızca menüden elle
  çalıştırınca; otomatik modda dokunulmaz). İlerledikçe yeniden senkronlamak gerekir. Doğru düzse (diğer pencereler
  gürültüymüş), normal sonuç olarak uygulanır.
- **Büyük hız farkı** (PAL %4,1 gibi): kayma ±15 sn aralığının çok dışına çıkar, ölçüm başarısız olur. Bu durumda dublajın ve
  videonun süreleri ayrıca okunur. Fark **bilinen bir hız değişimiyle** (23.976 ↔ 25 ya da 24 ↔ 25 fps) örtüşüyorsa dublaj
  otomatik olarak yavaşlatılır (ya da hızlandırılır) ve geriye kalan sabit kayma yeniden ölçülür (aşağıya bakın).
  Örtüşmüyorsa (farklı kurgu, fazladan bölüm olabilir) hiçbir şey uygulanmaz; yalnızca fark mesajda gösterilir.

## Dublajı yavaşlatma / hızlandırma (zaman uzatma)
Yalnızca menüden elle çalıştırılan senkronda (otomatik mod tahmin üzerine bir şey değiştirmez) ve süre oranı bilinen bir hız
farkıyla örtüşünce devreye girer. Üç parça birlikte çalışır:
- `DubStretchMediaSource`: dublaj kaynağının zaman çizgisini film zamanına çevirir (dublaj zamanı = film zamanı × oran).
  Sarma (seek) doğru dublaj konumuna düşer, birleşik süre filmin süresi olur. Dublajın ses biçimini `DubStretchTag` ile işaretler.
- `DubStretchAudioSink`: işaretli sesi oranın tersiyle, perdeyi koruyarak (Media3 `SonicAudioProcessor`) uzatır. Gerçek ses
  çıkışına yalnızca film uzunluğunda ses girer; saat ve video bozulmaz. Başka hiçbir ses etkilenmez. Yalnızca 16-bit PCM
  uzatılır; AC-3/E-AC-3 dublaj bu durumda geçişle değil PCM'e çözülerek çalınır.
- `DubSpeed.knownRatio`: oranı yalnızca bilinen hız çiftlerinden birine uyuyorsa verir.

Oran video + dublaj çifti için cihazda hatırlanır (`dub_stretch_memory`); sonraki açılışta dublaj baştan uzatılmış gelir.
Uzatılmış dublajın ölçümünde dublaj penceresi kendi zamanında okunur ve film zaman ızgarasına yeniden örneklenir
(`DubStretch.toFilmTime`), böylece analiz kodu değişmeden çalışır.

Bağlantı noktaları `apply.sh` içinde (adım 2b) tam metin değişimiyle yapılır: `PlayerMediaSourceFactory` ve
`PlayerRuntimeControllerInitialization` içinde birer satır. Upstream bu satırları değiştirirse derleme net bir hatayla durur.

**Senkron süresi.** Hız farkı artık ilk pencerede anlaşılır: dublajın süresi (küçük bir başlık okuması, ilk pencereyle paralel)
ile ilk pencerenin zaten bildiği video süresi karşılaştırılır; bilinen bir oranla örtüşüyorsa kalan pencereler hiç okunmaz,
dublaj uzatılıp kalan kayma ölçülür. Ek video açılışı ya da ek indirme yoktur. Uzatmadan sonra ölçüm güvenilmezse uzatma
geri alınır. Sarmada `MergingMediaPeriod`'un istediği tam konum eşitliği `DubStretchMediaSource` içinde korunur.

Sorun giderme (Logcat `DubStretch`): `dub format tagged` satırı var ama `stretching dub` yoksa etiket ses çıkışına ulaşmıyor
(ör. FFmpeg çözücüsü); `foreign customData` satırı bunu gösterir.

Kapsam dışı: yalnızca ExoPlayer'da çalışır; oran elle girilemez; hatırlanan oran sonraki açılışlarda da uygulanır.

## Nasıl güncellenir?
- Her gün otomatik kontrol edilir. İş akışı Nuvio'nun **en son yayınlanan sürümüne** (Releases) bakar.
  Nuvio yeni bir sürüm yayınlamadıysa ve `dub-addon/` değişmediyse derleme atlanır. Ara commit'ler yeni APK üretmez.
- Sürüm adı: Nuvio `1.1.0-beta.9` ise bu depo `1.1.0-beta.9.dub.1`, `.dub.2` … üretir. Nuvio `beta.10`'a geçince
  `1.1.0-beta.10.dub.1` çıkar ve uygulama içinde güncelleme bildirimi görünür.
- Elle tetiklemek için *Run workflow* (isteğe bağlı *force*).
- GitHub, 60 gün hareketsiz public depolarda zamanlanmış iş akışlarını kapatır. İş akışı ayda bir `keepalive` dalına
  boş bir commit atarak bunu önlemeye çalışır (ana dal geçmişine karışmaz). Yine de Actions sekmesinde iş akışı
  "disabled" görünürse elle yeniden etkinleştir.

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

## Gömülü altyazı ve dublaj senkronu

Dublaj senkronu videoyu `audioDelayUs` kadar kaydırır (ses saati ana saat olduğu için video ters yönde kaydırılır); altyazı renderer'ları aynı değerle videoyla birlikte kayar. Gömülü altyazı videoya kilitli olduğu için ek ofset gerekmez:

- `apply.sh` (2b): upstream `StreamDelayScope.appliesToSubtitle` her zaman `false` döner. Upstream, akış dilindeki gömülü altyazıya ayrıca `streamSourceOffsetMs` ofseti ekliyordu; bu, dublajda kaymayı geri alıyordu.
- `PlayerRuntimeControllerDubSync.kt` `resetEmbeddedSubtitleDelayForDubSync()`: senkron uygulanınca (yeni ölçüm ve kayıtlı sonuç) elle ayarlanmış altyazı gecikmesi, oynatıcı izlerine dokunmadan, sıfırlanır ve video başına kaydı silinir. Eklenti altyazısı seçiliyse dokunulmaz.
