package com.example.wordapp

import kotlinx.serialization.Serializable

data class Extension(
    val id: String,
    val name: String,
    val description: String,
    val type: String,
    val screen: Screen
)

object ExtensionManager {
    fun getBuiltInExtensions(): List<Extension> {
        return listOf(
            Extension(
                id = "cloud_wordbook",
                name = "클라우드 단어장",
                description = "GitHub에 있는 txt 파일을 불러와 단어장을 만듭니다.",
                type = "built-in",
                screen = Screen.CLOUD_WORDBOOK
            ),
            Extension(
                id = "flashcard_tts",
                name = "플래시카드 TTS",
                description = "플래시카드에서 단어를 읽어주는 기능을 활성화합니다.",
                type = "built-in",
                screen = Screen.SETTINGS // This is a placeholder, it won't navigate anywhere
            )
        )
    }
}