package com.example.wordapp

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.ui.platform.LocalContext
import com.example.wordapp.SettingsManager
import com.example.wordapp.TTSManager

private enum class SessionState { IN_PROGRESS, COMPLETED, PAUSED }

@Composable
fun FlashcardScreen(
    modifier: Modifier = Modifier,
    wordList: List<WordPair>,
    generation: Int,
    fileName: String, // Required for saving unknown words
    onSaveUnknownWords: (List<WordPair>) -> Unit,
    onSaveIncompleteWords: (List<WordPair>) -> Unit,
    onStartQuizWithWords: (List<WordPair>) -> Unit
) {
    if (wordList.isEmpty()) {
        Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("단어 목록이 비어있습니다. 메뉴에서 단어 파일을 불러와주세요.")
        }
        return
    }

    var sessionKey by remember(generation) { mutableIntStateOf(0) }

    key(sessionKey) {
        var sessionState by remember(generation) { mutableStateOf(SessionState.IN_PROGRESS) }
        var shuffledList by remember(generation) { mutableStateOf(wordList.shuffled()) }
        var currentIndex by remember(generation) { mutableIntStateOf(0) }
        val unknownWords = remember(generation) { mutableStateListOf<WordPair>() }

        when (sessionState) {
            SessionState.IN_PROGRESS -> {
                FlashcardInProgressScreen(
                    modifier = modifier,
                    shuffledList = shuffledList,
                    currentIndex = currentIndex,
                    unknownWords = unknownWords,
                    onNext = { currentIndex++ },
                    onPrevious = { currentIndex-- },
                    onShuffle = {
                        shuffledList = shuffledList.shuffled()
                        currentIndex = 0
                    },
                    onMarkUnknown = {
                        if (!unknownWords.contains(it)) {
                            unknownWords.add(it)
                        }
                    },
                    onMarkKnown = { unknownWords.remove(it) },
                    onComplete = { sessionState = SessionState.COMPLETED },
                    onPause = { sessionState = SessionState.PAUSED }
                )
            }
            SessionState.PAUSED -> {
                FlashcardPausedScreen(
                    modifier = modifier,
                    currentIndex = currentIndex,
                    totalCount = shuffledList.size,
                    unknownWords = unknownWords,
                    onResume = { sessionState = SessionState.IN_PROGRESS },
                    onRetryAll = { sessionKey++ },
                    onRetryUnknown = {
                        if (unknownWords.isNotEmpty()) {
                            shuffledList = unknownWords.toMutableList().shuffled()
                            currentIndex = 0
                            unknownWords.clear()
                            sessionState = SessionState.IN_PROGRESS
                        }
                    },
                    onSaveUnknown = { onSaveUnknownWords(unknownWords.toList()) },
                    onSaveIncomplete = { onSaveIncompleteWords(shuffledList.subList(currentIndex, shuffledList.size)) },
                    onStartQuiz = { onStartQuizWithWords(unknownWords.toList()) }
                )
            }
            SessionState.COMPLETED -> {
                FlashcardCompletedScreen(
                    modifier = modifier,
                    totalCount = shuffledList.size,
                    unknownWords = unknownWords,
                    onRetryAll = { sessionKey++ },
                    onRetryUnknown = {
                        if (unknownWords.isNotEmpty()) {
                            shuffledList = unknownWords.toMutableList().shuffled()
                            currentIndex = 0
                            unknownWords.clear()
                            sessionState = SessionState.IN_PROGRESS
                        }
                    },
                    onSaveUnknown = { onSaveUnknownWords(unknownWords.toList()) },
                    onStartQuiz = { onStartQuizWithWords(unknownWords.toList()) }
                )
            }
        }
    }
}

@Composable
private fun FlashcardInProgressScreen(
    modifier: Modifier = Modifier,
    shuffledList: List<WordPair>,
    currentIndex: Int,
    unknownWords: List<WordPair>,
    onNext: () -> Unit,
    onPrevious: () -> Unit,
    onShuffle: () -> Unit,
    onMarkUnknown: (WordPair) -> Unit,
    onMarkKnown: (WordPair) -> Unit,
    onComplete: () -> Unit,
    onPause: () -> Unit
) {
    val currentWord = shuffledList.getOrNull(currentIndex)

    if (currentWord == null) {
        Column(modifier.fillMaxSize(), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
            Text("표시할 단어가 없습니다.")
            Spacer(Modifier.height(16.dp))
            Button(onClick = onComplete) { Text("완료 화면으로 돌아가기") }
        }
        return
    }

    key(currentWord) {
        var isFlipped by remember { mutableStateOf(false) }
        val rotation = remember { Animatable(0f) }
        val interactionSource = remember { MutableInteractionSource() }

        LaunchedEffect(isFlipped) {
            rotation.animateTo(if (isFlipped) 180f else 0f, animationSpec = tween(400))
        }

        val isCurrentlyUnknown = currentWord in unknownWords

        Column(
            modifier = modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            // Card
            Box(
                modifier = Modifier.fillMaxWidth().height(300.dp).clickable(
                    interactionSource = interactionSource,
                    indication = null,
                    onClick = { isFlipped = !isFlipped }
                ),
                contentAlignment = Alignment.Center
            ) {
                Card(
                    modifier = Modifier.fillMaxWidth(0.8f).fillMaxHeight().graphicsLayer {
                        rotationY = rotation.value
                        cameraDistance = 8 * density
                    },
                    elevation = CardDefaults.cardElevation(defaultElevation = 8.dp)
                ) {
                    val isCardBack = rotation.value > 90f
                    val context = LocalContext.current
                    Box(
                        modifier = Modifier.fillMaxSize()
                            .background(if (isCardBack) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.primaryContainer)
                            .padding(16.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = if (isCardBack) currentWord.kor else currentWord.eng,
                                fontSize = 32.sp,
                                color = if (isCardBack) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onPrimaryContainer,
                                textAlign = TextAlign.Center,
                                modifier = Modifier.graphicsLayer { rotationY = if (isCardBack) 180f else 0f },
                            )
                            if (!isCardBack && SettingsManager.isExtensionEnabled(context, "flashcard_tts")) {
                                IconButton(onClick = { TTSManager.speak(currentWord.eng) }) {
                                    Icon(Icons.Filled.VolumeUp, contentDescription = "단어 읽어주기")
                                }
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // "I don't know" / "I know" Button
            Button(onClick = {
                if (isCurrentlyUnknown) {
                    onMarkKnown(currentWord)
                    isFlipped = false // Auto-flip back
                } else {
                    onMarkUnknown(currentWord)
                    isFlipped = true // Auto-flip
                }
            }) {
                Text(if (isCurrentlyUnknown) "알겠음" else "모르겠음")
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Progress Indicator
            Text("${currentIndex + 1} / ${shuffledList.size}", style = MaterialTheme.typography.bodyLarge)

            Spacer(modifier = Modifier.height(16.dp))

            // Navigation Buttons
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Button(onClick = onPrevious, enabled = currentIndex > 0) { Text("이전") }
                OutlinedButton(onClick = onPause) { Text("일시중지") }
                IconButton(onClick = onShuffle) { Text("🔀") }

                val isLastCard = currentIndex == shuffledList.size - 1
                Button(onClick = { if (isLastCard) onComplete() else onNext() }) {
                    Text(if (isLastCard) "완료" else "다음")
                }
            }
        }
    }
}

@Composable
private fun FlashcardPausedScreen(
    modifier: Modifier = Modifier,
    currentIndex: Int,
    totalCount: Int,
    unknownWords: List<WordPair>,
    onResume: () -> Unit,
    onRetryAll: () -> Unit,
    onRetryUnknown: () -> Unit,
    onSaveUnknown: () -> Unit,
    onSaveIncomplete: () -> Unit,
    onStartQuiz: () -> Unit
) {
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
        contentPadding = PaddingValues(vertical = 16.dp)
    ) {
        item {
            Text("$currentIndex / $totalCount 완료. 일시정지됨", style = MaterialTheme.typography.headlineSmall)
            Spacer(modifier = Modifier.height(24.dp))
        }

        if (unknownWords.isNotEmpty()) {
            item {
                Text("모르겠는 단어 :", style = MaterialTheme.typography.titleMedium)
                Spacer(modifier = Modifier.height(8.dp))
            }
            items(unknownWords.size) { index ->
                val word = unknownWords[index]
                Text(
                    text = "- ${word.eng} = ${word.kor}",
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp)
                )
            }
            item {
                Spacer(modifier = Modifier.height(24.dp))
            }
        }

        item {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onResume) { Text("재개하기") }
                Spacer(modifier = Modifier.height(16.dp))
                OutlinedButton(onClick = onSaveUnknown, enabled = unknownWords.isNotEmpty()) {
                    Text("모르겠음 저장하기")
                }
                OutlinedButton(onClick = onSaveIncomplete) {
                    Text("미완료 파일 저장하기")
                }
                OutlinedButton(onClick = onRetryAll) {
                    Text("처음부터 다시하기")
                }
                if (unknownWords.isNotEmpty()) {
                    OutlinedButton(onClick = onRetryUnknown) {
                        Text("모르는 단어만 다시 하기")
                    }
                    OutlinedButton(onClick = onStartQuiz) {
                        Text("모르는 단어들로 퀴즈 보기")
                    }
                }
            }
        }
    }
}

@Composable
private fun FlashcardCompletedScreen(
    modifier: Modifier = Modifier,
    totalCount: Int,
    unknownWords: List<WordPair>,
    onRetryAll: () -> Unit,
    onRetryUnknown: () -> Unit,
    onSaveUnknown: () -> Unit,
    onStartQuiz: () -> Unit
) {
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
        contentPadding = PaddingValues(vertical = 16.dp)
    ) {
        item {
            Text("$totalCount 개의 플래시카드를 모두 완료했습니다!", style = MaterialTheme.typography.headlineSmall)
            Spacer(modifier = Modifier.height(24.dp))
        }

        if (unknownWords.isNotEmpty()) {
            item {
                Text("모르겠는 단어 :", style = MaterialTheme.typography.titleMedium)
                Spacer(modifier = Modifier.height(8.dp))
            }
            items(unknownWords.size) { index ->
                val word = unknownWords[index]
                Text(
                    text = "- ${word.eng} = ${word.kor}",
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp)
                )
            }
            item {
                Spacer(modifier = Modifier.height(24.dp))
            }
        }

        item {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    Button(onClick = onRetryAll) {
                        Text("전체 다시 하기")
                    }
                    Button(onClick = onSaveUnknown, enabled = unknownWords.isNotEmpty()) {
                        Text("모르겠음 저장하기")
                    }
                }
                if (unknownWords.isNotEmpty()) {
                    Button(onClick = onRetryUnknown) {
                        Text("모르는 단어만 다시 하기")
                    }
                    Button(onClick = onStartQuiz) {
                        Text("모르는 단어들로 퀴즈 보기")
                    }
                }
            }
        }
    }
}