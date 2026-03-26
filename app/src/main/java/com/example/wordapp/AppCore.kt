package com.example.wordapp

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import androidx.core.content.edit
import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.engine.android.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import java.io.File
import com.example.wordapp.TTSManager
import com.example.wordapp.ExtensionManager

// --- Data Classes & Enums ---

data class WordPair(val eng: String, val kor: String, val id: Long = System.nanoTime())

enum class Screen {

    WORDS, QUIZ, FLASHCARD, EXAMPLE_QUIZ, SETTINGS, EXTENSIONS, DUPLICATE_DETAILS, CLOUD_WORDBOOK

}
enum class QuizType { MC4, MATCHING, INITIAL_CONSONANT, FILL_ENGLISH }

data class InlineParseResult(val valid: List<Pair<String, String>>, val errors: List<Pair<String, String>>)

data class ExampleQuestion(
    val eng: String,
    val meaning: String,
    val sentenceMasked: String
)

object SettingsManager {
    private const val PREFS_NAME = "word_app_settings"
    private const val KEY_VIBRATION_ENABLED = "vibration_enabled"
    private const val KEY_MC4_AUTO_ADVANCE_ENABLED = "mc4_auto_advance_enabled"
    private const val KEY_MC4_AUTO_ADVANCE_DELAY = "mc4_auto_advance_delay"
    private const val KEY_PRIMARY_COLOR = "primary_color"
    private const val KEY_SECONDARY_CONTAINER_COLOR = "secondary_container_color"

    fun isVibrationEnabled(context: Context): Boolean {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getBoolean(KEY_VIBRATION_ENABLED, true)
    }

    fun setVibrationEnabled(context: Context, isEnabled: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit {
            putBoolean(KEY_VIBRATION_ENABLED, isEnabled)
        }
    }

    fun isMc4AutoAdvanceEnabled(context: Context): Boolean {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getBoolean(KEY_MC4_AUTO_ADVANCE_ENABLED, false) // Default to false
    }

    fun setMc4AutoAdvanceEnabled(context: Context, isEnabled: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit {
            putBoolean(KEY_MC4_AUTO_ADVANCE_ENABLED, isEnabled)
        }
    }

    fun getMc4AutoAdvanceDelay(context: Context): Float {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getFloat(KEY_MC4_AUTO_ADVANCE_DELAY, 1.5f) // Default to 1.5 seconds
    }

    fun setMc4AutoAdvanceDelay(context: Context, delay: Float) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit {
            putFloat(KEY_MC4_AUTO_ADVANCE_DELAY, delay)
        }
    }

    fun getPrimaryColor(context: Context): Int {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getInt(KEY_PRIMARY_COLOR, 0xFF6650a4.toInt()) // Default: M3 baseline Primary
    }

    fun setPrimaryColor(context: Context, color: Int) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit {
            putInt(KEY_PRIMARY_COLOR, color)
        }
    }

    fun getSecondaryContainerColor(context: Context): Int {
        return context.getSharedPreferences("settings", Context.MODE_PRIVATE).getInt("secondary_container_color", 0xFFE8DEF8.toInt())
    }

    fun setPrimaryDarkColor(context: Context, color: Int) {
        context.getSharedPreferences("settings", Context.MODE_PRIVATE).edit().putInt("primary_dark_color", color).apply()
    }

    fun getPrimaryDarkColor(context: Context): Int {
        return context.getSharedPreferences("settings", Context.MODE_PRIVATE).getInt("primary_dark_color", 0xFFD0BCFF.toInt())
    }

    fun setSecondaryContainerDarkColor(context: Context, color: Int) {
        context.getSharedPreferences("settings", Context.MODE_PRIVATE).edit().putInt("secondary_container_dark_color", color).apply()
    }

    fun getSecondaryContainerDarkColor(context: Context): Int {
        return context.getSharedPreferences("settings", Context.MODE_PRIVATE).getInt("secondary_container_dark_color", 0xFF381E72.toInt())
    }

    fun setThemeSetting(context: Context, theme: String) {
        context.getSharedPreferences("settings", Context.MODE_PRIVATE).edit().putString("theme_setting", theme).apply()
    }

    fun getThemeSetting(context: Context): String {
        return context.getSharedPreferences("settings", Context.MODE_PRIVATE).getString("theme_setting", "system") ?: "system"
    }

    fun setSecondaryContainerColor(context: Context, color: Int) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit {
            putInt(KEY_SECONDARY_CONTAINER_COLOR, color)
        }
    }

    fun isExtensionEnabled(context: Context, extensionId: String): Boolean {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getBoolean("ext_enabled_$extensionId", false)
    }

    fun setExtensionEnabled(context: Context, extensionId: String, isEnabled: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit {
            putBoolean("ext_enabled_$extensionId", isEnabled)
        }
    }
}



// --- Utility Functions ---

fun isInvalidEnglishWord(s: String): Boolean {
    return s.contains(Regex("[^a-zA-Z-']"))
}

fun vibrateDevice(context: Context, isCorrect: Boolean) {
    if (!SettingsManager.isVibrationEnabled(context)) return

    val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        context.getSystemService(Vibrator::class.java)
    } else {
        @Suppress("DEPRECATION")
        context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
    }
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        val effect = if (isCorrect) {
            VibrationEffect.createOneShot(50, VibrationEffect.DEFAULT_AMPLITUDE)
        } else {
            VibrationEffect.createWaveform(longArrayOf(0, 100, 50, 100), -1)
        }
        vibrator.vibrate(effect)
    } else {
        @Suppress("DEPRECATION")
        if (isCorrect) {
            vibrator.vibrate(50)
        } else {
            vibrator.vibrate(longArrayOf(0, 100, 50, 100), -1)
        }
    }
}

fun getInitialConsonants(korean: String): String {
    val textToConvert = korean.replace(Regex("\\([^)]*\\)"), "").trim()
    val initials = charArrayOf(
        'ㄱ', 'ㄲ', 'ㄴ', 'ㄷ', 'ㄸ', 'ㄹ', 'ㅁ', 'ㅂ', 'ㅃ', 'ㅅ',
        'ㅆ', 'ㅇ', 'ㅈ', 'ㅉ', 'ㅊ', 'ㅋ', 'ㅌ', 'ㅍ', 'ㅎ'
    )
    val result = StringBuilder()
    for (char in korean) {
        if (char in '가'..'힣') {
            val unicode = char.code - 0xAC00
            val initialIndex = unicode / (21 * 28)
            result.append(initials[initialIndex])
        } else if (char == ' ') {
            // No-op
        }
    }
    return result.toString()
}

fun isKoreanToken(s: String) = s.any { it in '\uAC00'..'\uD7A3' }
fun isEnglishToken(s: String) = s.any { it in 'A'..'Z' || it in 'a'..'z' }

fun normalizeForAnswer(s: String): String {
    return s
        .replace(Regex("\\(([^)]*)\\)"), "")
        .replace(Regex("（[^）]*）"), "")
        .replace("~", "")
        .replace(Regex("\\s+"), "")
        .trim()
}

fun maskEnglishWord(word: String): String {
    if (word.isEmpty()) return ""
    val chars = word.toCharArray()
    return buildString {
        append(chars[0])
        for (i in 1 until chars.size) {
            val c = chars[i]
            append(if (c.isLetter()) '_' else c)
        }
    }
}

fun normalizeEnglishAnswer(s: String): String =
    s.lowercase().filter { it in 'a'..'z' }

fun equalsRelaxed(a: String, b: String): Boolean =
    normalizeForAnswer(a) == normalizeForAnswer(b)

fun parsePairsFromInline(input: String): InlineParseResult {
    val pairsStr = input.split(Regex("[ㅣ\\n]")).filter { it.isNotBlank() }
    val valid = mutableListOf<Pair<String, String>>()
    val errors = mutableListOf<Pair<String, String>>()
    for (s in pairsStr) {
        val parts = s.trim().split(Regex("\\s+"), 2)
        if (parts.size == 2) {
            val eng = parts[0]
            var kor = parts[1]
            Regex("(?i)\\b[A-Za-z][A-Za-z-'] *\\b").find(kor)?.let { next ->
                if (next.range.first > 0) kor = kor.substring(0, next.range.first).trim()
            }
            if (!isInvalidEnglishWord(eng) && isEnglishToken(eng) && isKoreanToken(kor)) {
                valid.add(eng to kor)
            } else if (!isInvalidEnglishWord(eng)) {
                errors.add(eng to kor)
            }
        } else if (parts.isNotEmpty() && parts[0].isNotBlank()) {
            val eng = parts[0]
            if (!isInvalidEnglishWord(eng)) errors.add(eng to "")
        }
    }
    return InlineParseResult(valid, errors)
}

fun parseLineToPair(line: String): Pair<String, String>? {
    val t = line.trim()
    if (t.isEmpty()) return null
    val pair = if (t.contains("=")) {
        val p = t.split("=", limit = 2).map { it.trim() }
        if (p.size == 2 && p[0].isNotEmpty() && p[1].isNotEmpty()) p[0] to p[1] else null
    } else {
        val p = t.split(Regex("\\s+"), 2)
        if (p.size == 2 && p[0].isNotEmpty() && p[1].isNotEmpty()) p[0] to p[1] else null
    }
    return pair?.let { if (isInvalidEnglishWord(it.first)) null else it }
}

fun saveWordList(folderName: String, fileName: String, list: List<Pair<String, String>>, baseDir: File) {
    val folder = File(baseDir, folderName)
    if (!folder.exists()) folder.mkdirs()
    val file = File(folder, "${fileName}.txt")
    file.printWriter().use { out -> list.forEach { (e, k) -> out.println("$e = $k") } }
}

fun loadWordList(folderName: String, fileName: String, baseDir: File): List<Pair<String, String>> {
    val file = File(File(baseDir, folderName), "${fileName}.txt")
    if (!file.exists()) return emptyList()
    val result = mutableListOf<Pair<String, String>>()
    file.forEachLine { parseLineToPair(it)?.let(result::add) }
    return result
}

fun getDisplayName(context: Context, uri: android.net.Uri): String? {
    val cursor = context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
    cursor?.use {
        if (it.moveToFirst()) {
            val idx = it.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (idx >= 0) return it.getString(idx)
        }
    }
    return null
}

fun guessFolderNameFromUri(uri: android.net.Uri): String? {
    return try {
        val docId = DocumentsContract.getDocumentId(uri)
        val pathPart = docId.substringAfter(':', "")
        if (pathPart.contains("/")) {
            val parent = pathPart.substringBeforeLast("/")
            parent.substringAfterLast("/")
        } else null
    } catch (_: Exception) { null }
}

fun maskUnderscoredWordInSentence(sentence: String): String {
    val pattern = Regex("_(.+?)_")
    return pattern.replace(sentence) { m ->
        val inner = m.groupValues[1]
        "_".repeat(inner.count { it.isLetter() })
    }
}

fun extractExampleQuestionsFromText(raw: String): List<ExampleQuestion> {
    val lines = raw.split(Regex("[\\r\\n]+")).map { it.trim() }
    var currentEng: String? = null
    var currentMeaning: String? = null
    val result = mutableListOf<ExampleQuestion>()

    for (line in lines) {
        when {
            line.isNotEmpty()
                    && !line.startsWith("=")
                    && !Regex("^\\d+\\.\\s").containsMatchIn(line)
                    && line.any { it.isLetter() } -> {
                currentEng = line.substringBefore(" ").trim()
                currentMeaning = null
            }
            line.startsWith("=") -> {
                currentMeaning = line.removePrefix("=").trim()
            }
            Regex("^\\d+\\.\\s").containsMatchIn(line) -> {
                val eng = currentEng
                val meaning = currentMeaning
                if (eng != null && meaning != null) {
                    val sentence = line.replace(Regex("^\\d+\\.\\s*", RegexOption.IGNORE_CASE), "")
                    val masked = maskUnderscoredWordInSentence(sentence)
                    result += ExampleQuestion(eng = eng, meaning = meaning, sentenceMasked = masked)
                }
            }
            else -> { /* No-op */ }
        }
    }
    return result.filter { it.sentenceMasked.contains('_') }
}

fun extractWordPairsFromText(rawInput: String): List<Pair<String, String>> {
    if (rawInput.isBlank()) return emptyList()
    // Stub, original implementation was missing from context
    return emptyList()
}