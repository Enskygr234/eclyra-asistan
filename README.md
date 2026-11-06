<p align="center">
  <b style="font-size:28px">🌙 Eclyra</b><br>
  <i>Türkçe konuşan, yapay zekâ destekli Android sesli asistan</i>
</p>
<p align="center"><sub>Fikir ve tasarım: <b>Enes</b> (<a href="https://github.com/Enskygr234">@Enskygr234</a>)</sub></p>
<p align="center"><a href="../../releases/latest"><b>⬇️ APK'yı indir</b></a></p>

---

## Neler yapabilir?

| | |
|---|---|
| 🎙️ **Sesli sohbet** | Gemini ile Türkçe konuşur, cevapları sesli okur, soru sorunca seni tekrar dinler |
| 🌙 **"Hey Eclyra"** | Uygulama kapalıyken de uyandırma sözünü duyar (ses telefonda işlenir) |
| ⏰ **Alarm, sayaç, hatırlatma** | "Yarın 7'de alarm kur", "Akşam 8'de ilacımı hatırlat" |
| 📞 **Arama ve mesaj** | Rehberden kişiyi bulup arar, SMS ve WhatsApp mesajı hazırlar |
| 📍 **Konum** | "Anneme konumumu at", "Neredeyim?", yol tarifi, yakındaki yerler |
| 🌤️ **Hava durumu** | Bulunduğun yer ya da istediğin şehir için 7 güne kadar tahmin |
| 📱 **Telefon kontrolü** | Uygulama açma, fener, ses seviyesi, müzik kontrolü, ayar sayfaları |
| 📚 **Ders yardımcısı** | Sorunun fotoğrafını çek, adım adım çözsün |
| 🧠 **Hafıza** | "Bunu hatırla: …" dediğin bilgileri sonraki sohbetlerde kullanır |
| 🔎 **Güncel bilgi** | Haber, maç skoru, döviz, nöbetçi eczane… Google'da araştırıp kaynağıyla söyler |
| 🗣️ **Doğal ses** | Gemini'nin insan gibi konuşan sesi, 8 farklı ses seçeneği |
| 🔔 **Bildirimler** | "Son mesajlarımı oku", mesajlara uygulamayı açmadan sesle cevap, gelen mesajları sesli duyurma |
| 💬 **Sohbet geçmişi** | Sohbetler kaydedilir, eski sohbete dönüp devam edilebilir |

## Kurulum

1. [Releases](../../releases/latest) sayfasından `Eclyra-Asistan.apk` dosyasını indir ve aç.
2. [Google AI Studio](https://aistudio.google.com/apikey)'dan ücretsiz bir Gemini API anahtarı al, uygulamaya yapıştır.
3. İzinleri ver. "Hey Eclyra" için Ayarlar'dan uyandırma sözünü aç (ilk seferde ~35 MB Türkçe ses modeli iner).

> Anahtar yalnızca telefonunda saklanır, uygulamanın içine gömülü değildir.

## Teknik

- Kotlin + Jetpack Compose, minSdk 26 (Android 8+)
- Yapay zekâ: Gemini REST API + function calling (telefon araçları)
- Konuşma tanıma: Android SpeechRecognizer (tr-TR) · Seslendirme: TextToSpeech
- Uyandırma sözü: [Vosk](https://alphacephei.com/vosk/) küçük Türkçe modeli, cihaz üstünde
- Hava durumu: [Open-Meteo](https://open-meteo.com/) (anahtarsız)
- APK her push'ta GitHub Actions ile derlenip Releases'e yüklenir

## Yapımcılar

- **Fikir ve tasarım:** Enes ([@Enskygr234](https://github.com/Enskygr234))
- **Kodlama:** Claude ile birlikte
