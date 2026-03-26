package com.example.wordapp

import android.content.Context
import android.speech.tts.TextToSpeech
import java.util.Locale

object TTSManager {
    private var tts: TextToSpeech? = null
    private var isInitialized = false

    fun init(context: Context, locale: Locale) {
        if (tts == null) {
            tts = TextToSpeech(context) { status ->
                if (status == TextToSpeech.SUCCESS) {
                    val result = tts?.setLanguage(locale)
                    if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                        // Handle missing data or unsupported language
                    } else {
                        isInitialized = true
                    }
                }
            }
        }
    }

    fun speak(text: String) {
        if (isInitialized) {
            tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, null)
        }
    }

    fun shutdown() {
        tts?.stop()
        tts?.shutdown()
        tts = null
        isInitialized = false
    }
}