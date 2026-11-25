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
| `overlay/` | Tamamen **yeni** dosyalar (`core/dub/*`, `DubAudioRows`, `StreamSidecarAudio`, `PlayerRuntimeControllerDub*` …). Dublaj mantığının neredeyse tamamı burada; upstream'de olmadığı için çakışmaz. |
| `append/` | Upstream sınıflarının **sonuna eklenen** üyeler (`PlayerRuntimeController`, `PlayerMediaSourceFactory`). Dosyanın son `}` karakteri değişmediği sürece çakışmaz. |
| `scripts/apply-hooks.py` | Upstream dosyalarına dokunan birkaç **tek satırlık kanca** + sınıf sonuna ekleme. Her kanca fonksiyon/sınıf adıyla kapsamlanır, satır numarasına bağlı değildir. |
| `apply.sh` | overlay + kancalar + güncelleyiciyi bu depoya yönlendirme + sürüm numarası |
| `scripts/make-keystore.sh` | Sabit imza anahtarı üretir |
| `UPSTREAM_BASE` | Eklentinin son doğrulandığı Nuvio commit'i (bilgi amaçlı) |

.github/workflows/dub-sync-release.yml` hepsini çalıştıran iş akışıdır.

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

## Altyazı gecikmesi = ses gecikmesi
Dublaj senkronu uygulandığında (elle ölçüm, otomatik ölçüm, **kayıtlı sonuç** ya da tarifteki sabit gecikme) altyazı gecikmesi de
ses gecikmesiyle **aynı değere** (aynı sayı, aynı işaret) çekilir: ses -2300 ms ise altyazı da -2300 ms olur. Video kapatılıp açılınca
kayıtlı senkron yeniden devreye girdiğinde altyazı da yeniden aynı değere gelir. Orijinal sese dönersen altyazının önceki gecikmesi geri gelir (arada elle değiştirdiysen dokunulmaz), dublaja dönünce yeniden eşitlenir.
Altyazı değeri ayrıca saklanmaz; sonraki oturumda
dublajın kayıtlı gecikmesinden yeniden hesaplanır. Sonradan altyazıyı elle değiştirirsen o değer geçerli kalır, bir sonraki senkrona kadar.
Kod: `mirrorAudioDelayToSubtitle()` (`PlayerRuntimeControllerDubSync.kt`). Dublajın dilindeki gömülü altyazılar zaten dublaj
kaymasını Nuvio'nun kendi mantığıyla alır; çift sayılmasın diye o kısım düşülür. Kapatmak için `applyMeasuredDubDelay` ve
`applyDubAudioDelay` içindeki `mirrorAudioDelayToSubtitle()` çağrılarını silmek yeter.

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

Bağlantı noktaları: `createMediaSource` sarmalayıcısı (`mergeDubAudios`) ve `PlayerRuntimeControllerInitialization` içindeki tek satırlık ses çıkışı kancası. Upstream bunları değiştirirse `apply-hooks.py` net bir hatayla durur.

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
Nuvio, kancaların bağlandığı fonksiyonu/satırı değiştirirse iş akışı **durur, APK yayınlamaz** ve bir issue açar
(eski APK çalışmaya devam eder). Log'da hangi kancanın yerleşmediği açıkça yazar:
```
::error::kanca YERLEŞMEDİ  PlayerRuntimeControllerStreams  -> streamSubtitles = stream.subtitles: 0 eşleşme (1 bekleniyordu)
```
Düzeltmek için:
```bash
git clone https://github.com/NuvioMedia/NuvioTV nuvio && cd nuvio
/yol/bu-depo/dub-addon/apply.sh .      # hangi kanca tutmuyor gösterir
# scripts/apply-hooks.py içinde o kancanın (hook_*) desenini yeni koda göre güncelle
```
Kancalar tek satırlıktır; çoğu zaman yalnızca bir regex'i ya da eklenen satırdaki bir ismi düzeltmek yeter.
Derleme hatası olursa (ör. upstream bir alanı yeniden adlandırdı) genelde overlay/append içindeki isim düzeltilir.
Bu işi Claude'a da verebilirsin: log'daki hata satırı ve upstream commit'i yeterli.

**Erken uyarı:** `.github/workflows/dub-precheck.yml` her gün Nuvio'nun `main` dalına karşı kancaları dener;
uyumsuzluk varsa Nuvio yeni sürüm yayınlamadan önce issue açar.

## Eklenti nasıl bağlanıyor? (kısa mimari)
- **Veri taşıma:** `Stream`/`LocalScraperResult` sınıflarına alan eklenmez. Eklenti çalıştırıcısı bulduğu dublajları
  oynatma URL'siyle `StreamSidecarAudio` kayıt defterine yazar (1 kanca), oynatıcı aynı URL'yle okur.
- **Oynatıcı durumu:** `streamExternalAudios` ve iş (job) alanları `append/PlayerRuntimeController.members.txt` ile sınıfın
  sonuna eklenir. Alan değişince `mediaSourceFactory.dubExternalAudios` da güncellenir; bu yüzden `createMediaSource`
  çağrılarına argüman eklemek gerekmez.
- **Kaynak birleştirme:** `apply-hooks.py`, upstream'in `createMediaSource(...)` imzasını okuyup onu `createBaseMediaSource`
  olarak yeniden adlandırır ve **aynı imzayla** bir sarmalayıcı üretir (dublajı `MergingMediaSource` ile ekler).
  Upstream yeni parametre eklese de sarmalayıcı onu otomatik taşır.
- **Menü:** `AudioSelectionOverlay` içinde 2 satır (`DubAudioRows`), olaylar `DubUi` köprüsüyle doğrudan controller'a gider.
- **Kalan kancalar:** motor seçimi, ilk `STATE_READY`, ses çıkışı sarmalayıcısı, `applyStreamMetadata`, ses parçası etiketi.

## Sınırlar (bilmen gerekenler)
- Güncelleme "kod birleştirme" değil **APK yenileme**dir: derleme GitHub'da olur, uygulama sadece hazır APK'yı indirir.
- Yamalar derlenip sınanmadan yayınlanmaz ama dublaj davranışını otomatik test eden bir test yok;
  Nuvio büyük bir oynatıcı yeniden yazımı yaparsa eklenti çalışmayabilir (derleme hatası olarak görürsün).
- `dublaj_kaynaklari.json` ayrı `nuvioooooo/dub` deposundan çekilir (adres `core/dub/DubSourceManager.kt` içinde). Site değişince orayı güncellemek yeter, APK gerekmez.
