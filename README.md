# 🛰️ Gök Radar (Sky Radar)

Telefonun arka kamerasını gökyüzüne doğrultun: uygulama gökyüzündeki hareketli
cisimleri (uçak, İHA/drone, kuş, balon…) **gerçek zamanlı tespit eder**, köşeli
parantezle işaretleyip kimlik (ID) atar, **yönünü ve açısal hızını** ölçer ve
**önümüzdeki saniyelerde nerede olacağını** kesikli çizgiyle gösterir.

Sağ üstte gerçek bir hava radarı gibi çalışan, **kuzeye/bakış yönüne göre
dönen gök-kubbe radar ekranı** bulunur: merkez zenit (tepe noktası), kenar
ufuk çizgisidir; tespit edilen her hedef üzerinde iz bırakarak ilerler.

> Her şey cihaz üzerinde çalışır — internet bağlantısı, kayıt veya veri
> gönderimi yoktur.

## Özellikler

- 📡 **Gerçek zamanlı tespit** — kamera görüntüsünün parlaklık kanalında,
  gökyüzü arka planından sapan küçük lekeler bulunur (ML modeli yok, saf
  görüntü işleme; eski/ucuz cihazlarda bile akıcı çalışır).
- 🌲 **Yer karmaşası filtresi** — ağaç, bina, çatı gibi dokulu bölgeler
  "clutter" haritasıyla elenir; yalnızca açık gökyüzündeki cisimler işaretlenir.
- 🎯 **Çoklu hedef takibi** — her cisme ID atanır; alfa-beta filtresiyle konum
  ve hız kestirimi yapılır, kısa süreli kayıplarda hedef "söndürülmeden"
  tahminle taşınır (coasting).
- 🧭 **Sensör füzyonu** — takip, ekran koordinatında değil **gök
  koordinatında** (azimut/yükseliş) yapılır. Rotation-vector sensörü sayesinde
  siz telefonu oynatsanız bile hedefin gerçek hareketi ile telefonun hareketi
  birbirine karışmaz.
- ➡️ **Yön ve hız vektörü** — her hedefin üzerinde hareket yönünü gösteren ok
  ve °/sn cinsinden açısal hız etiketi.
- 🔮 **Konum tahmini** — mevcut hıza göre +1…+4 saniye sonraki konumlar
  kesikli çizgi ve noktalarla çizilir.
- 🟢 **Mini radar ekranı** — heading-up gök kubbesi: kamera FOV huzmesi, dönen
  tarama kolu, yön harfleri (K/D/G/B), hedef izleri.
- ⛰️ **AR ufuk çizgisi** — kamera görüntüsünün üzerine ufuk ve pusula yönleri
  yansıtılır; ekran dışına çıkan hedefler kenar oklarıyla gösterilir.
- 🎞️ **Video içe aktarma** — internetten indirdiğiniz veya galerinizdeki drone/uçuş
  videolarını seçip aynı tespit, takip, radar ve tahmin katmanını video üzerinde
  çalıştırabilirsiniz.

## Nasıl çalışır?

1. **CameraX ImageAnalysis** akışından yalnızca Y (parlaklık) düzlemi alınır
   ve ~200 px genişliğe indirgenir.
2. Geniş yarıçaplı **box-blur** ile "gökyüzü arka plan modeli" çıkarılır;
   arka plandan belirgin sapan pikseller aday kabul edilir.
3. Mutlak sapmanın bulanıklaştırılmış hali **clutter haritası**dır: değerin
   yüksek olduğu bölgeler (ağaç, bina) gökyüzü sayılmaz ve elenir.
4. Aday pikseller **connected-components** ile lekelere dönüştürülür; boyut,
   doluluk ve uzunluk filtreleri bulut kenarlarını ayıklar.
5. Her leke, kameranın görüş açısı (FOV) ve **ROTATION_VECTOR** sensör matrisi
   kullanılarak piksel düzleminden **(azimut, yükseliş)** gök koordinatına
   çevrilir (`SkyGeometry`).
6. **SkyTracker**, en yakın komşu eşleştirme + alfa-beta filtreyle hedefleri
   izler, açısal hızı kestirir ve geleceğe doğru tahmin üretir.
7. `OverlayView` (AR katmanı) ve `RadarView` (gök kubbe radarı) sonuçları çizer.
8. **Video modu** aynı luminance/blob dedektörünü `MediaMetadataRetriever` ile
   örneklenen video karelerine uygular; böylece canlı kamera ve içe aktarılan
   video aynı analiz boru hattını paylaşır.

## Proje yapısı

```
app/src/main/java/com/skyradar/app/
├── MainActivity.kt              # CameraX kurulumu, izinler, akış koordinasyonu
├── analysis/
│   ├── Detection.kt             # Kare başına tespit/sonuç modelleri
│   ├── LumaBlobDetector.kt      # Kamera ve video için ortak luminance/blob dedektörü
│   └── SkyObjectAnalyzer.kt     # CameraX frame adaptörü
├── geometry/
│   └── SkyGeometry.kt           # Piksel ↔ (azimut, yükseliş) dönüşümleri
├── sensors/
│   └── OrientationProvider.kt   # Rotation-vector → cihaz-dünya dönüş matrisi
├── tracking/
│   ├── Track.kt                 # Hedef durumu ve UI anlık görüntüsü
│   └── SkyTracker.kt            # Eşleştirme, alfa-beta filtre, tahmin
└── ui/
    ├── OverlayView.kt           # AR işaretleyiciler, ufuk, tahmin yolları
    └── RadarView.kt             # Heading-up gök kubbe radar ekranı
```

## Kurulum ve çalıştırma

**Android Studio (önerilen):** projeyi açın, Gradle senkronu bitince gerçek
bir cihazda çalıştırın (kamera gerektiği için emülatör anlamsızdır).

**Komut satırı:** JDK 17+, Android SDK 35 ve Gradle 8.9+ ile:

```bash
./gradlew :app:assembleDebug
adb install app/build/outputs/apk/debug/app-debug.apk
```

- minSdk 24 (Android 7.0), targetSdk 35
- Tek izin: **Kamera**

## Kullanım ipuçları

- En iyi sonuç **gündüz, açık veya parçalı bulutlu** gökyüzünde alınır;
  alacakaranlıkta parlak ışıklı cisimler (uçak farı) da yakalanır.
- İlk açılışta pusula doğruluğu için telefonla havada **8 çizin**
  (manyetometre kalibrasyonu).
- Alt bardaki **"Gökyüzü görüşü"** yüzdesi düşükse kadrajdaki ağaç/bina
  oranını azaltın.
- **Video içe aktar** düğmesiyle cihazdaki bir videoyu seçin. İnternetten indirdiğiniz
  lisanslı/izinli drone görüntülerini galeriye kaydedip uygulamada analiz
  edebilirsiniz; canlı akışa dönmek için **Canlı kamera** düğmesini kullanın.
- Telefonu görece sabit tutmak tespit kararlılığını artırır; takip sensör
  destekli olduğundan yavaş tarama hareketleri sorun çıkarmaz.

## Sınırlamalar (dürüst mod)

- Tek kamerayla **mesafe ölçülemez**; bu yüzden hızlar km/sa değil **açısal
  (°/sn)** cinsindendir ve tahminler gök koordinatında yapılır. (Cisim türü ve
  boyutu bilinmeden mutlak hız fiziksel olarak belirlenemez.)
- Pusula sensörü olmayan cihazlarda yönler göreceli kalır (uygulama bunu alt
  barda belirtir).
- İçe aktarılan videolarda gerçek cihaz yönelimi bilinmediği için yön/ufuk bilgisi
  sanal bir kamera geometrisiyle çizilir; tespit, takip ve tahmin mantığı canlı
  kamerayla aynıdır.
- Yoğun/karmaşık bulutlarda yanlış pozitifler görülebilir; eşikler
  `SkyObjectAnalyzer` içindeki sabitlerden ayarlanabilir.

---

*Bu depo daha önce bir Telegram airdrop botu içeriyordu; proje bu dalda
sıfırdan bir Android uygulamasına dönüştürüldü.*
