package com.streamhit.app.util

import android.content.Context
import android.media.AudioManager
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import androidx.annotation.OptIn
import androidx.compose.runtime.*
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import java.io.BufferedReader
import java.io.StringReader
import java.net.URLEncoder
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

data class SubtitleCue(
    val id: Int,
    val startMs: Long,
    val endMs: Long,
    val originalText: String,
    var translatedText: String = ""
)

data class SupportedLanguage(
    val code: String,
    val name: String,
    val nativeName: String,
    val flag: String = ""
)

object SubtitleTtsManager {

    val SUPPORTED_LANGUAGES = listOf(
        SupportedLanguage("bn", "Bengali", "বাংলা", "🇧🇩"),
        SupportedLanguage("hi", "Hindi", "हिन्दी", "🇮🇳"),
        SupportedLanguage("en", "English", "English", "🇺🇸"),
        SupportedLanguage("ur", "Urdu", "اردو", "🇵🇰"),
        SupportedLanguage("es", "Spanish", "Español", "🇪🇸"),
        SupportedLanguage("ar", "Arabic", "العربية", "🇸🇦"),
        SupportedLanguage("fr", "French", "Français", "🇫🇷"),
        SupportedLanguage("de", "German", "Deutsch", "🇩🇪"),
        SupportedLanguage("ja", "Japanese", "日本語", "🇯🇵"),
        SupportedLanguage("ko", "Korean", "한국어", "🇰🇷"),
        SupportedLanguage("ru", "Russian", "Русский", "🇷🇺"),
        SupportedLanguage("pt", "Portuguese", "Português", "🇧🇷"),
        SupportedLanguage("id", "Indonesian", "Bahasa Indonesia", "🇮🇩"),
        SupportedLanguage("tr", "Turkish", "Türkçe", "🇹🇷")
    )

    private var tts: TextToSpeech? = null
    private var isTtsReady = false

    // State observables for UI
    var isEnabled by mutableStateOf(false)
        private set

    var targetLanguage by mutableStateOf("bn")
        private set

    var isPretranslating by mutableStateOf(false)
        private set

    var pretranslatedCount by mutableIntStateOf(0)
        private set

    var totalCuesCount by mutableIntStateOf(0)
        private set

    var statusMessage by mutableStateOf("AI Audio Translator is off")
        private set

    var speechRate by mutableFloatStateOf(1.05f)

    // Internal cues and caches
    private var cues: List<SubtitleCue> = emptyList()
    private val translationCache = ConcurrentHashMap<String, String>()
    private var lastSpokenCueId = -1
    private var lastPositionMs = 0L
    private var isAudioDucked = false

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var pretranslateJob: Job? = null

    private val httpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .build()
    }

    fun init(context: Context) {
        if (tts != null) return
        tts = TextToSpeech(context.applicationContext) { status ->
            if (status == TextToSpeech.SUCCESS) {
                isTtsReady = true
                applyTtsLanguage(targetLanguage)
                tts?.setSpeechRate(speechRate)
                tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) {}
                    override fun onDone(utteranceId: String?) {
                        // Volume restoration is also guarded in syncWithPlayback
                    }
                    @Deprecated("Deprecated in Java")
                    override fun onError(utteranceId: String?) {}
                })
            }
        }
    }

    private fun applyTtsLanguage(langCode: String) {
        val t = tts ?: return
        val locale = when (langCode) {
            "bn" -> Locale("bn", "BD")
            "hi" -> Locale("hi", "IN")
            "ur" -> Locale("ur", "PK")
            "es" -> Locale("es", "ES")
            "fr" -> Locale.FRENCH
            "de" -> Locale.GERMAN
            "ja" -> Locale.JAPANESE
            "ko" -> Locale.KOREAN
            "ru" -> Locale("ru", "RU")
            "pt" -> Locale("pt", "BR")
            "id" -> Locale("id", "ID")
            "tr" -> Locale("tr", "TR")
            "ar" -> Locale("ar")
            else -> Locale.ENGLISH
        }
        try {
            t.language = locale
        } catch (_: Exception) {}
    }

    fun setTargetLang(langCode: String) {
        targetLanguage = langCode
        applyTtsLanguage(langCode)
        if (cues.isNotEmpty()) {
            restartPretranslation()
        }
    }

    fun toggleEnabled(enabled: Boolean) {
        isEnabled = enabled
        if (!enabled) {
            tts?.stop()
            lastSpokenCueId = -1
            statusMessage = "AI Audio Translator is off"
        } else {
            statusMessage = if (cues.isNotEmpty()) "Live AI Audio Translator Active ($targetLanguage)" else "Ready. Waiting for subtitle track..."
        }
    }

    fun setRate(rate: Float) {
        speechRate = rate
        tts?.setSpeechRate(rate)
    }

    fun loadCaptions(rawSubtitleText: String) {
        cues = parseSubtitleContent(rawSubtitleText)
        totalCuesCount = cues.size
        pretranslatedCount = 0
        lastSpokenCueId = -1
        if (cues.isNotEmpty()) {
            restartPretranslation()
        }
    }

    fun loadCaptionsFromUrl(url: String) {
        scope.launch {
            try {
                val fullUrl = when {
                    url.startsWith("http://") || url.startsWith("https://") -> url
                    url.startsWith("/") -> "https://nxly.online$url"
                    else -> "https://nxly.online/$url"
                }
                val req = Request.Builder().url(fullUrl).header("User-Agent", "Mozilla/5.0").build()
                val resp = httpClient.newCall(req).execute()
                if (resp.isSuccessful) {
                    val body = resp.body?.string().orEmpty()
                    resp.close()
                    withContext(Dispatchers.Main) {
                        loadCaptions(body)
                    }
                } else {
                    resp.close()
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    private fun restartPretranslation() {
        pretranslateJob?.cancel()
        isPretranslating = true
        statusMessage = "Pre-translating dialogue into ${targetLanguage.uppercase()}..."

        pretranslateJob = scope.launch {
            val list = cues
            var count = 0

            // Step 1: Rapid priority translation for first 12 cues
            val priorityCues = list.take(12)
            for (cue in priorityCues) {
                if (!isActive) break
                cue.translatedText = translateText(cue.originalText, targetLanguage)
                count++
                withContext(Dispatchers.Main) {
                    pretranslatedCount = count
                }
            }

            withContext(Dispatchers.Main) {
                statusMessage = "AI Dub Ready (${count}/${list.size} lines translated)"
            }

            // Step 2: Progressive background translation of remaining cues
            for (i in 12 until list.size) {
                if (!isActive) break
                val cue = list[i]
                cue.translatedText = translateText(cue.originalText, targetLanguage)
                count++
                if (count % 5 == 0 || count == list.size) {
                    withContext(Dispatchers.Main) {
                        pretranslatedCount = count
                    }
                }
            }

            withContext(Dispatchers.Main) {
                isPretranslating = false
                statusMessage = "100% Dialogues Pre-translated into ${targetLanguage.uppercase()}"
            }
        }
    }

    private suspend fun translateText(text: String, target: String): String {
        val clean = text.trim()
        if (clean.isBlank()) return ""
        val cacheKey = "${target}_$clean"
        translationCache[cacheKey]?.let { return it }

        // 1. Google Translate direct fast client
        try {
            val url = "https://clients5.google.com/translate_a/t?client=dict-chrome-ex&sl=auto&tl=$target&q=" + URLEncoder.encode(clean, "UTF-8")
            val req = Request.Builder().url(url).header("User-Agent", "Mozilla/5.0").build()
            val resp = httpClient.newCall(req).execute()
            if (resp.isSuccessful) {
                val str = resp.body?.string().orEmpty()
                resp.close()
                val json = JSONArray(str)
                if (json.length() > 0) {
                    val first = json.getJSONArray(0)
                    val translated = first.getString(0)
                    if (!translated.isNullOrBlank()) {
                        translationCache[cacheKey] = translated
                        return translated
                    }
                }
            } else {
                resp.close()
            }
        } catch (_: Exception) {}

        // 2. Server proxy fallback
        try {
            val fallbackUrl = "https://nxly.online/api/translate?text=" + URLEncoder.encode(clean, "UTF-8") + "&target_lang=" + target
            val req = Request.Builder().url(fallbackUrl).build()
            val resp = httpClient.newCall(req).execute()
            if (resp.isSuccessful) {
                val str = resp.body?.string().orEmpty()
                resp.close()
                val obj = org.json.JSONObject(str)
                val translated = obj.optString("translated", clean)
                if (!translated.isNullOrBlank()) {
                    translationCache[cacheKey] = translated
                    return translated
                }
            } else {
                resp.close()
            }
        } catch (_: Exception) {}

        return clean
    }

    @OptIn(UnstableApi::class)
    fun syncWithPlayback(currentPositionMs: Long, exoPlayer: ExoPlayer?) {
        if (!isEnabled || !isTtsReady || exoPlayer == null || cues.isEmpty()) return

        // Scrub / Jump detection: if user jumped > 1.8s, stop active utterance and reset
        if (kotlin.math.abs(currentPositionMs - lastPositionMs) > 1800L) {
            tts?.stop()
            lastSpokenCueId = -1
            if (isAudioDucked) {
                exoPlayer.volume = 1.0f
                isAudioDucked = false
            }
        }
        lastPositionMs = currentPositionMs

        // Find active cue matching current timestamp
        val activeCue = cues.firstOrNull { currentPositionMs >= it.startMs && currentPositionMs <= it.endMs }

        if (activeCue != null) {
            if (activeCue.id != lastSpokenCueId) {
                lastSpokenCueId = activeCue.id

                val speechText = if (activeCue.translatedText.isNotBlank()) activeCue.translatedText else activeCue.originalText
                if (speechText.isNotBlank()) {
                    // Auto-duck video volume to 20% so AI speech is prominent & crystal clear
                    exoPlayer.volume = 0.20f
                    isAudioDucked = true

                    val params = Bundle().apply {
                        putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, 1.0f)
                    }
                    tts?.speak(speechText, TextToSpeech.QUEUE_FLUSH, params, "cue_${activeCue.id}")
                }
            }
        } else {
            // No cue active at this moment
            val isSpeakingNow = tts?.isSpeaking == true
            if (!isSpeakingNow && isAudioDucked) {
                exoPlayer.volume = 1.0f
                isAudioDucked = false
            }
        }
    }

    fun onPause() {
        tts?.stop()
    }

    fun destroy(exoPlayer: ExoPlayer? = null) {
        pretranslateJob?.cancel()
        tts?.stop()
        try {
            tts?.shutdown()
        } catch (_: Exception) {}
        tts = null
        isTtsReady = false
        if (exoPlayer != null && isAudioDucked) {
            exoPlayer.volume = 1.0f
        }
        isAudioDucked = false
        cues = emptyList()
        lastSpokenCueId = -1
    }

    // Robust WebVTT and SRT subtitle parser
    private fun parseSubtitleContent(content: String): List<SubtitleCue> {
        val list = mutableListOf<SubtitleCue>()
        if (content.isBlank()) return list

        val reader = BufferedReader(StringReader(content))
        var line: String?
        var cueIndex = 1

        var currentStart = -1L
        var currentEnd = -1L
        val textLines = StringBuilder()

        while (reader.readLine().also { line = it } != null) {
            val l = line?.trim() ?: ""
            if (l.contains("-->")) {
                // If previous cue was being built, commit it
                if (currentStart >= 0L && textLines.isNotBlank()) {
                    val cleanText = sanitizeSubtitleText(textLines.toString())
                    if (cleanText.isNotBlank()) {
                        list.add(SubtitleCue(cueIndex++, currentStart, currentEnd, cleanText))
                    }
                    textLines.clear()
                }

                val arrowIdx = l.indexOf("-->")
                val startPart = l.substring(0, arrowIdx).trim()
                val endPart = l.substring(arrowIdx + 3).trim().split(" ").firstOrNull() ?: ""
                currentStart = parseTimestampToMs(startPart)
                currentEnd = parseTimestampToMs(endPart)
            } else if (l.isNotBlank() && !l.startsWith("WEBVTT") && !l.all { it.isDigit() }) {
                if (currentStart >= 0L) {
                    if (textLines.isNotEmpty()) textLines.append(" ")
                    textLines.append(l)
                }
            } else if (l.isBlank()) {
                if (currentStart >= 0L && textLines.isNotBlank()) {
                    val cleanText = sanitizeSubtitleText(textLines.toString())
                    if (cleanText.isNotBlank()) {
                        list.add(SubtitleCue(cueIndex++, currentStart, currentEnd, cleanText))
                    }
                    currentStart = -1L
                    currentEnd = -1L
                    textLines.clear()
                }
            }
        }

        if (currentStart >= 0L && textLines.isNotBlank()) {
            val cleanText = sanitizeSubtitleText(textLines.toString())
            if (cleanText.isNotBlank()) {
                list.add(SubtitleCue(cueIndex++, currentStart, currentEnd, cleanText))
            }
        }

        return list
    }

    private fun sanitizeSubtitleText(text: String): String {
        return text.replace(Regex("<[^>]*>"), "") // strip HTML tags
            .replace(Regex("\\{[^}]*\\}"), "") // strip ASS/SSA style tags
            .replace("\n", " ")
            .trim()
    }

    private fun parseTimestampToMs(ts: String): Long {
        val clean = ts.replace(',', '.')
        val parts = clean.split(":")
        return when (parts.size) {
            3 -> {
                val h = parts[0].toLongOrNull() ?: 0L
                val m = parts[1].toLongOrNull() ?: 0L
                val sParts = parts[2].split(".")
                val s = sParts[0].toLongOrNull() ?: 0L
                val ms = if (sParts.size > 1) sParts[1].padEnd(3, '0').take(3).toLongOrNull() ?: 0L else 0L
                (h * 3600 + m * 60 + s) * 1000 + ms
            }
            2 -> {
                val m = parts[0].toLongOrNull() ?: 0L
                val sParts = parts[1].split(".")
                val s = sParts[0].toLongOrNull() ?: 0L
                val ms = if (sParts.size > 1) sParts[1].padEnd(3, '0').take(3).toLongOrNull() ?: 0L else 0L
                (m * 60 + s) * 1000 + ms
            }
            else -> 0L
        }
    }
}
