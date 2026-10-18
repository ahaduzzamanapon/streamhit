package com.streamhit.app.util

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import androidx.annotation.OptIn
import androidx.compose.runtime.*
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import kotlinx.coroutines.*
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
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

enum class VoiceGender(val label: String, val icon: String) {
    FEMALE("Female", "👩"),
    MALE("Male", "👨")
}

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
    private var isNativeTtsSupportedForLang = false
    private var cloudPlayer: MediaPlayer? = null
    private var currentExoPlayerRef: ExoPlayer? = null

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

    var voiceGender by mutableStateOf(VoiceGender.FEMALE)
        private set

    fun setGender(gender: VoiceGender) {
        voiceGender = gender
        applyTtsLanguage(targetLanguage)
    }

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
            .connectTimeout(12, TimeUnit.SECONDS)
            .readTimeout(18, TimeUnit.SECONDS)
            .build()
    }

    fun init(context: Context) {
        if (tts != null) return

        // Prefer Google TTS engine if installed for full Bengali & Hindi voice support
        val isGoogleTtsInstalled = try {
            context.packageManager.getPackageInfo("com.google.android.tts", 0) != null
        } catch (_: Exception) {
            false
        }

        val listener = TextToSpeech.OnInitListener { status ->
            if (status == TextToSpeech.SUCCESS) {
                isTtsReady = true
                applyTtsLanguage(targetLanguage)
                tts?.setSpeechRate(speechRate)
                tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) {}
                    override fun onDone(utteranceId: String?) {
                        restoreVolume()
                    }
                    @Deprecated("Deprecated in Java")
                    override fun onError(utteranceId: String?) {
                        restoreVolume()
                    }
                })
            }
        }

        tts = try {
            if (isGoogleTtsInstalled) {
                TextToSpeech(context.applicationContext, listener, "com.google.android.tts")
            } else {
                TextToSpeech(context.applicationContext, listener)
            }
        } catch (_: Exception) {
            TextToSpeech(context.applicationContext, listener)
        }
    }

    private fun restoreVolume() {
        scope.launch(Dispatchers.Main) {
            currentExoPlayerRef?.let { p ->
                if (isAudioDucked) {
                    p.volume = 1.0f
                    isAudioDucked = false
                }
            }
        }
    }

    private fun applyTtsLanguage(langCode: String) {
        val t = tts
        isNativeTtsSupportedForLang = false
        if (t == null) return

        val localesToTry = when (langCode) {
            "bn" -> listOf(Locale("bn", "BD"), Locale("bn", "IN"), Locale("bn"))
            "hi" -> listOf(Locale("hi", "IN"), Locale("hi"))
            "ur" -> listOf(Locale("ur", "PK"), Locale("ur"))
            "es" -> listOf(Locale("es", "ES"), Locale("es", "US"), Locale("es"))
            "fr" -> listOf(Locale.FRENCH, Locale("fr", "FR"))
            "de" -> listOf(Locale.GERMAN, Locale("de", "DE"))
            "ja" -> listOf(Locale.JAPANESE, Locale("ja", "JP"))
            "ko" -> listOf(Locale.KOREAN, Locale("ko", "KR"))
            "ru" -> listOf(Locale("ru", "RU"), Locale("ru"))
            "pt" -> listOf(Locale("pt", "BR"), Locale("pt", "PT"))
            "id" -> listOf(Locale("id", "ID"), Locale("id"))
            "tr" -> listOf(Locale("tr", "TR"), Locale("tr"))
            "ar" -> listOf(Locale("ar", "SA"), Locale("ar"))
            else -> listOf(Locale.US, Locale.ENGLISH)
        }

        // 1. Check if an installed voice matches this language & gender
        try {
            val voices = t.voices
            if (!voices.isNullOrEmpty()) {
                val matchingLangVoices = voices.filter { voice ->
                    voice.locale.language.equals(langCode, ignoreCase = true)
                }
                val selectedVoice = if (voiceGender == VoiceGender.MALE) {
                    matchingLangVoices.firstOrNull { it.name.lowercase().contains("male") && !it.name.lowercase().contains("female") }
                        ?: matchingLangVoices.firstOrNull { it.name.lowercase().contains("-m") }
                        ?: matchingLangVoices.firstOrNull()
                } else {
                    matchingLangVoices.firstOrNull { it.name.lowercase().contains("female") }
                        ?: matchingLangVoices.firstOrNull { it.name.lowercase().contains("-f") }
                        ?: matchingLangVoices.firstOrNull()
                }
                if (selectedVoice != null) {
                    t.voice = selectedVoice
                    t.language = selectedVoice.locale
                    isNativeTtsSupportedForLang = true
                }
            }
        } catch (_: Exception) {}

        // 2. Try setLanguage for each candidate locale
        for (loc in localesToTry) {
            try {
                val res = t.setLanguage(loc)
                if (res != TextToSpeech.LANG_MISSING_DATA && res != TextToSpeech.LANG_NOT_SUPPORTED) {
                    isNativeTtsSupportedForLang = true
                    break
                }
            } catch (_: Exception) {}
        }

        // Apply pitch based on Male / Female voice selection
        val targetPitch = if (voiceGender == VoiceGender.MALE) 0.80f else 1.20f
        try {
            t.setPitch(targetPitch)
        } catch (_: Exception) {}
    }

    fun setTargetLang(langCode: String) {
        val changed = targetLanguage != langCode
        targetLanguage = langCode
        applyTtsLanguage(langCode)
        if (changed) {
            // Clear current cue translations so they re-translate in new target language
            cues.forEach { it.translatedText = "" }
            pretranslatedCount = 0
            lastSpokenCueId = -1
        }
        if (cues.isNotEmpty()) {
            restartPretranslation()
        }
    }

    fun toggleEnabled(enabled: Boolean) {
        isEnabled = enabled
        if (!enabled) {
            tts?.stop()
            cloudPlayer?.stop()
            lastSpokenCueId = -1
            statusMessage = "AI Audio Translator is off"
            restoreVolume()
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

    fun restartPretranslation(currentPosMs: Long = lastPositionMs) {
        pretranslateJob?.cancel()
        if (cues.isEmpty()) return

        isPretranslating = true
        statusMessage = "Pre-translating dialogues into ${targetLanguage.uppercase()}..."

        pretranslateJob = scope.launch {
            val list = cues
            if (list.isEmpty()) return@launch

            // 1. Locate cue closest to current video playback position
            val currentIdx = list.indexOfFirst { it.endMs >= currentPosMs }.let { if (it >= 0) it else 0 }

            // Priority 1: Next 20 cues ahead of current playback position
            val nextCount = (currentIdx + 20).coerceAtMost(list.size)
            val upcomingCues = list.subList(currentIdx, nextCount)
            translateCuesBatch(upcomingCues, targetLanguage)

            withContext(Dispatchers.Main) {
                val done = list.count { it.translatedText.isNotBlank() }
                pretranslatedCount = done
                statusMessage = "AI Dub Ready (${done}/${list.size} lines translated)"
            }

            // Priority 2: Remaining upcoming cues towards end
            if (nextCount < list.size) {
                val remainingForward = list.subList(nextCount, list.size)
                translateCuesInChunks(remainingForward, targetLanguage)
            }

            // Priority 3: Prior cues from 0 to currentIdx (in case user seeks back)
            if (currentIdx > 0) {
                val priorCues = list.subList(0, currentIdx)
                translateCuesInChunks(priorCues, targetLanguage)
            }

            withContext(Dispatchers.Main) {
                isPretranslating = false
                val done = list.count { it.translatedText.isNotBlank() }
                pretranslatedCount = done
                statusMessage = "100% Dialogues Pre-translated into ${targetLanguage.uppercase()}"
            }
        }
    }

    private suspend fun translateCuesInChunks(cueList: List<SubtitleCue>, target: String) {
        val untranslated = cueList.filter { it.translatedText.isBlank() }
        val chunks = untranslated.chunked(15)
        for (chunk in chunks) {
            if (!scope.isActive) break
            translateCuesBatch(chunk, target)
            withContext(Dispatchers.Main) {
                pretranslatedCount = cues.count { it.translatedText.isNotBlank() }
            }
        }
    }

    private suspend fun translateCuesBatch(batch: List<SubtitleCue>, target: String) {
        if (batch.isEmpty()) return

        val needed = mutableListOf<SubtitleCue>()
        for (c in batch) {
            val clean = c.originalText.trim()
            val cacheKey = "${target}_$clean"
            val cached = translationCache[cacheKey]
            if (!cached.isNullOrBlank()) {
                c.translatedText = cached
            } else {
                needed.add(c)
            }
        }
        if (needed.isEmpty()) return

        // 1. Try batch translation from server proxy: /api/translate-batch
        var batchSuccess = false
        try {
            val payload = JSONObject().apply {
                put("target_lang", target)
                put("source_lang", "auto")
                val arr = JSONArray()
                needed.forEach { arr.put(it.originalText.trim()) }
                put("texts", arr)
            }
            val body = payload.toString().toRequestBody("application/json; charset=utf-8".toMediaTypeOrNull())
            val req = Request.Builder()
                .url("https://nxly.online/api/translate-batch")
                .post(body)
                .build()
            val resp = httpClient.newCall(req).execute()
            if (resp.isSuccessful) {
                val respStr = resp.body?.string().orEmpty()
                resp.close()
                val obj = JSONObject(respStr)
                val transArr = obj.optJSONArray("translations")
                if (transArr != null && transArr.length() == needed.size) {
                    for (i in 0 until needed.size) {
                        val t = transArr.optString(i, "")
                        if (t.isNotBlank() && t != needed[i].originalText) {
                            needed[i].translatedText = t
                            translationCache["${target}_${needed[i].originalText.trim()}"] = t
                        }
                    }
                    batchSuccess = true
                }
            } else {
                resp.close()
            }
        } catch (_: Exception) {}

        // 2. Fallback to individual translation for any cues still empty
        if (!batchSuccess) {
            for (c in needed) {
                if (c.translatedText.isBlank()) {
                    val t = translateText(c.originalText, target)
                    if (t.isNotBlank()) {
                        c.translatedText = t
                    }
                }
            }
        }
    }

    suspend fun translateText(text: String, target: String): String {
        val clean = text.trim()
        if (clean.isBlank()) return ""
        val cacheKey = "${target}_$clean"
        translationCache[cacheKey]?.let { return it }

        // 1. Direct Google Translate Chrome client API
        try {
            val url = "https://clients5.google.com/translate_a/t?client=dict-chrome-ex&sl=auto&tl=$target&q=" + URLEncoder.encode(clean, "UTF-8")
            val req = Request.Builder().url(url).header("User-Agent", "Mozilla/5.0").build()
            val resp = httpClient.newCall(req).execute()
            if (resp.isSuccessful) {
                val str = resp.body?.string().orEmpty()
                resp.close()
                val extracted = extractTranslatedFromGoogleJson(str)
                if (extracted.isNotBlank()) {
                    translationCache[cacheKey] = extracted
                    return extracted
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
                val obj = JSONObject(str)
                val translated = obj.optString("translated", "")
                if (translated.isNotBlank()) {
                    translationCache[cacheKey] = translated
                    return translated
                }
            } else {
                resp.close()
            }
        } catch (_: Exception) {}

        return clean
    }

    private fun extractTranslatedFromGoogleJson(str: String): String {
        if (str.isBlank()) return ""
        return try {
            val json = JSONArray(str)
            val sb = StringBuilder()
            for (i in 0 until json.length()) {
                val elem = json.get(i)
                if (elem is JSONArray && elem.length() > 0) {
                    val s = elem.optString(0, "")
                    if (s.isNotBlank() && s != "null") {
                        if (sb.isNotEmpty()) sb.append(" ")
                        sb.append(s)
                    }
                } else if (elem is String && elem.isNotBlank()) {
                    if (sb.isNotEmpty()) sb.append(" ")
                    sb.append(elem)
                }
            }
            sb.toString().trim()
        } catch (_: Exception) {
            ""
        }
    }

    private fun speakCue(text: String, cueId: Int, exoPlayer: ExoPlayer) {
        if (text.isBlank()) return
        currentExoPlayerRef = exoPlayer

        // Auto-duck video volume to 20%
        exoPlayer.volume = 0.20f
        isAudioDucked = true

        if (isNativeTtsSupportedForLang && isTtsReady && tts != null) {
            val params = Bundle().apply {
                putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, 1.0f)
            }
            tts?.speak(text, TextToSpeech.QUEUE_FLUSH, params, "cue_$cueId")
        } else {
            // Seamless Google Cloud TTS streaming fallback
            speakWithCloudTts(text, targetLanguage, exoPlayer)
        }
    }

    private fun speakWithCloudTts(text: String, lang: String, exoPlayer: ExoPlayer) {
        scope.launch(Dispatchers.Main) {
            try {
                cloudPlayer?.stop()
                cloudPlayer?.release()
                cloudPlayer = null

                val encoded = URLEncoder.encode(text, "UTF-8")
                val ttsUrl = "https://translate.google.com/translate_tts?ie=UTF-8&tl=$lang&client=tw-ob&q=$encoded"

                val mp = MediaPlayer().apply {
                    setAudioAttributes(
                        AudioAttributes.Builder()
                            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .build()
                    )
                    setDataSource(ttsUrl)
                    setOnPreparedListener { player ->
                        try {
                            val targetPitch = if (voiceGender == VoiceGender.MALE) 0.82f else 1.18f
                            player.playbackParams = player.playbackParams.setPitch(targetPitch)
                        } catch (_: Exception) {}
                        player.start()
                    }
                    setOnCompletionListener {
                        it.release()
                        if (cloudPlayer === it) cloudPlayer = null
                        restoreVolume()
                    }
                    setOnErrorListener { it, _, _ ->
                        it.release()
                        if (cloudPlayer === it) cloudPlayer = null
                        restoreVolume()
                        true
                    }
                    prepareAsync()
                }
                cloudPlayer = mp
            } catch (_: Exception) {
                restoreVolume()
            }
        }
    }

    @OptIn(UnstableApi::class)
    fun syncWithPlayback(currentPositionMs: Long, exoPlayer: ExoPlayer?) {
        if (!isEnabled || exoPlayer == null || cues.isEmpty()) return
        currentExoPlayerRef = exoPlayer

        // Scrub / Jump detection: if user jumped > 1.8s, stop active utterance and ducking
        if (kotlin.math.abs(currentPositionMs - lastPositionMs) > 1800L) {
            tts?.stop()
            cloudPlayer?.stop()
            lastSpokenCueId = -1
            if (isAudioDucked) {
                exoPlayer.volume = 1.0f
                isAudioDucked = false
            }
            if (isPretranslating) {
                restartPretranslation(currentPositionMs)
            }
        }
        lastPositionMs = currentPositionMs

        // Find active cue matching current timestamp
        val activeCue = cues.firstOrNull { currentPositionMs >= it.startMs && currentPositionMs <= it.endMs }

        if (activeCue != null) {
            if (activeCue.id != lastSpokenCueId) {
                lastSpokenCueId = activeCue.id

                val speechText = if (activeCue.translatedText.isNotBlank()) {
                    activeCue.translatedText
                } else if (targetLanguage.equals("en", ignoreCase = true)) {
                    activeCue.originalText
                } else {
                    val cached = translationCache["${targetLanguage}_${activeCue.originalText.trim()}"]
                    if (!cached.isNullOrBlank()) {
                        activeCue.translatedText = cached
                        cached
                    } else {
                        // On-the-fly translate without prematurely speaking English!
                        scope.launch {
                            val trans = translateText(activeCue.originalText, targetLanguage)
                            if (trans.isNotBlank()) {
                                activeCue.translatedText = trans
                                withContext(Dispatchers.Main) {
                                    if (lastSpokenCueId == activeCue.id) {
                                        speakCue(trans, activeCue.id, exoPlayer)
                                    }
                                }
                            }
                        }
                        ""
                    }
                }

                if (speechText.isNotBlank()) {
                    speakCue(speechText, activeCue.id, exoPlayer)
                }
            }
        } else {
            // No cue active at this moment
            val isSpeakingNow = (tts?.isSpeaking == true) || (cloudPlayer?.isPlaying == true)
            if (!isSpeakingNow && isAudioDucked) {
                exoPlayer.volume = 1.0f
                isAudioDucked = false
            }
        }
    }

    fun onPause() {
        tts?.stop()
        cloudPlayer?.stop()
        restoreVolume()
    }

    fun destroy(exoPlayer: ExoPlayer? = null) {
        pretranslateJob?.cancel()
        tts?.stop()
        try {
            tts?.shutdown()
        } catch (_: Exception) {}
        tts = null
        isTtsReady = false

        cloudPlayer?.stop()
        try {
            cloudPlayer?.release()
        } catch (_: Exception) {}
        cloudPlayer = null

        if (exoPlayer != null && isAudioDucked) {
            exoPlayer.volume = 1.0f
        }
        isAudioDucked = false
        cues = emptyList()
        lastSpokenCueId = -1
        currentExoPlayerRef = null
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
        return text.replace(Regex("<[^>]*>"), "")
            .replace(Regex("\\{[^}]*\\}"), "")
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
