package com.enskygr234.eclyra

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.media.PlaybackParams
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject

/** Gemini'nin insan gibi konuşan sesi (TTS). Ses verisi PCM olarak gelir ve AudioTrack ile çalınır. */
object NaturalVoice {

    val MODELS = listOf("gemini-3.8-flash-lite-tts", "gemini-3.8-flash-tts", "gemini-2.5-flash-preview-tts")

    /** Türkçede iyi duyulan birkaç ses: ad → açıklama */
    val VOICES = listOf(
        "Kore" to "Kadın · sakin, net",
        "Leda" to "Kadın · genç, enerjik",
        "Aoede" to "Kadın · sıcak, akıcı",
        "Zephyr" to "Kadın · parlak",
        "Puck" to "Erkek · neşeli",
        "Charon" to "Erkek · tok, bilgilendirici",
        "Fenrir" to "Erkek · heyecanlı",
        "Orus" to "Erkek · sağlam",
    )

    @Volatile
    private var workingModel: String? = null

    class Audio(val pcm: ByteArray, val rate: Int)

    fun synth(apiKey: String, text: String, voice: String): Audio {
        val prompt = "Sıcak, doğal ve samimi bir tonla, akıcı Türkçe ile söyle: $text"
        val contents = JSONArray().put(
            JSONObject().put("role", "user").put("parts", JSONArray().put(JSONObject().put("text", prompt)))
        )
        val cfg = JSONObject()
            .put("responseModalities", JSONArray().put("AUDIO"))
            .put(
                "speechConfig", JSONObject().put(
                    "voiceConfig", JSONObject().put("prebuiltVoiceConfig", JSONObject().put("voiceName", voice))
                )
            )
        val order = listOfNotNull(workingModel) + MODELS.filter { it != workingModel }
        var last: Exception? = null
        for (m in order) {
            try {
                val resp = Gemini.generate(apiKey, m, null, contents, null, cfg)
                val audio = parse(resp)
                workingModel = m
                return audio
            } catch (e: GeminiException) {
                last = e
                if (e.code != 404 && e.code != 400) throw e
            }
        }
        throw last ?: GeminiException("Ses modeli bulunamadı", 404)
    }

    private fun parse(resp: JSONObject): Audio {
        val parts = resp.optJSONArray("candidates")?.optJSONObject(0)?.optJSONObject("content")?.optJSONArray("parts")
            ?: throw GeminiException("Ses gelmedi", 0)
        for (i in 0 until parts.length()) {
            val p = parts.getJSONObject(i)
            val d = p.optJSONObject("inlineData") ?: p.optJSONObject("inline_data") ?: continue
            val mime = d.optString("mimeType", d.optString("mime_type", ""))
            val bytes = Base64.decode(d.getString("data"), Base64.DEFAULT)
            var rate = Regex("rate=(\\d+)").find(mime)?.groupValues?.get(1)?.toIntOrNull() ?: 24000
            var pcm = bytes
            // WAV gelirse başlığı atla
            if (bytes.size > 44 && String(bytes, 0, 4, Charsets.US_ASCII) == "RIFF") {
                rate = le32(bytes, 24)
                var off = 12
                while (off + 8 <= bytes.size) {
                    val id = String(bytes, off, 4, Charsets.US_ASCII)
                    val len = le32(bytes, off + 4)
                    if (id == "data") {
                        pcm = bytes.copyOfRange(off + 8, minOf(bytes.size, off + 8 + len))
                        break
                    }
                    off += 8 + len
                }
            }
            return Audio(pcm, rate)
        }
        throw GeminiException("Ses gelmedi", 0)
    }

    private fun le32(b: ByteArray, o: Int) =
        (b[o].toInt() and 0xff) or ((b[o + 1].toInt() and 0xff) shl 8) or
            ((b[o + 2].toInt() and 0xff) shl 16) or ((b[o + 3].toInt() and 0xff) shl 24)
}

/** PCM sesini çalar; bitince ya da durdurulunca onDone çağrılır. */
class PcmPlayer {
    @Volatile
    private var track: AudioTrack? = null

    @Volatile
    private var stopped = false

    val playing get() = track != null

    /** Çağıran iş parçacığını çalma bitene kadar bekletir. true: sonuna kadar çaldı. */
    fun playBlocking(audio: NaturalVoice.Audio, speed: Float): Boolean {
        stop()
        stopped = false
        val minBuf = AudioTrack.getMinBufferSize(audio.rate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val t = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANT)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(audio.rate)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .build()
            )
            .setBufferSizeInBytes(maxOf(minBuf * 4, 16384))
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
        track = t
        try {
            if (speed != 1f) {
                try {
                    t.playbackParams = PlaybackParams().setSpeed(speed)
                } catch (_: Exception) {
                }
            }
            t.play()
            var off = 0
            val pcm = audio.pcm
            while (off < pcm.size && !stopped) {
                val n = t.write(pcm, off, minOf(8192, pcm.size - off))
                if (n <= 0) break
                off += n
            }
            val totalFrames = pcm.size / 2
            var guard = 0
            while (!stopped && t.playbackHeadPosition < totalFrames && guard < 600) {
                Thread.sleep(50); guard++
            }
            return !stopped
        } finally {
            try {
                t.stop()
            } catch (_: Exception) {
            }
            t.release()
            if (track === t) track = null
        }
    }

    fun stop() {
        stopped = true
        try {
            track?.pause()
            track?.flush()
        } catch (_: Exception) {
        }
    }
}
