package com.steptracker.app

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.Locale

/**
 * Spoken progress cues during a workout, using the device's text-to-speech engine so
 * there are no audio assets and the numbers can be generated on the fly.
 *
 * English only by design: it keeps the phrasing natural and works on every device that
 * has a TTS engine at all. Speech ducks music rather than pausing it, and silently does
 * nothing when no engine is installed.
 */
class VoiceCoach(context: Context) {

    private val appContext = context.applicationContext
    private val prefs = UserPrefs(appContext)

    private var tts: TextToSpeech? = null
    @Volatile private var ready = false

    private val audioManager =
        appContext.getSystemService(Context.AUDIO_SERVICE) as? AudioManager

    private val speechAttrs = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_MEDIA)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()

    private val focusRequest: AudioFocusRequest =
        AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
            .setAudioAttributes(speechAttrs)
            .setWillPauseWhenDucked(false)
            .build()

    init {
        try {
            tts = TextToSpeech(appContext) { status ->
                if (status != TextToSpeech.SUCCESS) return@TextToSpeech
                val engine = tts ?: return@TextToSpeech
                engine.setAudioAttributes(speechAttrs)
                val locale = listOf(Locale.US, Locale.UK, Locale.ENGLISH).firstOrNull {
                    val r = engine.isLanguageAvailable(it)
                    r == TextToSpeech.LANG_AVAILABLE ||
                        r == TextToSpeech.LANG_COUNTRY_AVAILABLE ||
                        r == TextToSpeech.LANG_COUNTRY_VAR_AVAILABLE
                }
                if (locale != null) {
                    engine.language = locale
                    engine.setSpeechRate(0.98f)
                    engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                        override fun onStart(utteranceId: String?) {}
                        override fun onDone(utteranceId: String?) = releaseFocus()
                        @Deprecated("Deprecated in Java")
                        override fun onError(utteranceId: String?) = releaseFocus()
                    })
                    ready = true
                }
            }
        } catch (_: Exception) {
            tts = null
        }
    }

    /** Speaks [text] if voice cues are on and an engine is available. Never queues up a backlog. */
    fun say(text: String) {
        if (!prefs.voiceCuesEnabled || !ready) return
        val engine = tts ?: return
        try {
            audioManager?.requestAudioFocus(focusRequest)
            engine.speak(text, TextToSpeech.QUEUE_FLUSH, null, "ywalk-cue")
        } catch (_: Exception) {
            releaseFocus()
        }
    }

    private fun releaseFocus() {
        try { audioManager?.abandonAudioFocusRequest(focusRequest) } catch (_: Exception) {}
    }

    fun shutdown() {
        releaseFocus()
        try { tts?.stop(); tts?.shutdown() } catch (_: Exception) {}
        tts = null
        ready = false
    }
}
