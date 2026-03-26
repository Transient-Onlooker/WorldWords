package com.example.wordapp

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import kotlin.math.min
import kotlin.random.Random
import kotlinx.coroutines.delay
import com.example.wordapp.QuizType
import com.example.wordapp.isInvalidEnglishWord
import com.example.wordapp.getInitialConsonants
import com.example.wordapp.maskEnglishWord



@Composable
fun ExampleQuizHostScreen(
    modifier: Modifier,
    fileName: String,
    onFileNameChange: (String) -> Unit
) {
    val context = LocalContext.current
    var started by remember { mutableStateOf(false) }
    val questions = remember { mutableStateListOf<ExampleQuestion>() }
    var questionCount by remember { mutableIntStateOf(0) }
    var selectedFileNames by remember { mutableStateOf<List<String>>(emptyList()) }
    var showCountDialog by remember { mutableStateOf(false) }
    var tempInput by remember { mutableStateOf("") }

    val openMulti = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        if (!uris.isNullOrEmpty()) {
            val all = mutableListOf<ExampleQuestion>()
            uris.forEach { uri ->
                try {
                    context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                } catch (_: SecurityException) {}
                context.contentResolver.openInputStream(uri)?.use { input ->
                    val text = input.reader().readText()
                    all += extractExampleQuestionsFromText(text)
                }
            }
            questions.clear()
            questions.addAll(all)
            questionCount = all.size.coerceAtMost(10).coerceAtLeast(if (all.isNotEmpty()) 1 else 0)
            selectedFileNames = uris.mapNotNull { getDisplayName(context, it) }
            uris.firstOrNull()?.let {
                getDisplayName(context, it)?.let { name ->
                    onFileNameChange(name.removeSuffix(".txt").removeSuffix(".TXT"))
                }
            }
        }
    }

    if (showCountDialog) {
        val maxCount = questions.size.coerceAtLeast(1)
        AlertDialog(
            onDismissRequest = { showCountDialog = false },
            title = { Text("출제 개수 입력") },
            text = {
                OutlinedTextField(
                    value = tempInput,
                    onValueChange = { tempInput = it.filter { c -> c.isDigit() } },
                    label = { Text("개수 (1 ~ $maxCount)") },
                    singleLine = true
                )
            },
            confirmButton = {
                Button(onClick = {
                    val v = tempInput.toIntOrNull()
                    if (v != null && v in 1..maxCount) questionCount = v
                    showCountDialog = false
                }) { Text("확인") }
            },
            dismissButton = { TextButton(onClick = { showCountDialog = false }) { Text("취소") } }
        )
    }

    Column(modifier) {
        if (!started) {
            Text("예문 퀴즈 설정", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { openMulti.launch(arrayOf("text/plain", "text/*")) }) { Text("예문 파일 선택") }
            }

            if (questions.isNotEmpty()) {
                Spacer(Modifier.height(12.dp))
                val maxCount = questions.size.coerceAtLeast(1)
                Text(
                    text = "출제 개수: $questionCount / ${questions.size}",
                    modifier = Modifier.clickable {
                        tempInput = questionCount.toString()
                        showCountDialog = true
                    }
                )
                Slider(
                    value = questionCount.toFloat(),
                    onValueChange = { questionCount = it.toInt().coerceIn(1, maxCount) },
                    valueRange = 1f..maxCount.toFloat().coerceAtLeast(1f),
                    steps = (maxCount - 2).coerceAtLeast(0)
                )
            }
            Spacer(Modifier.height(8.dp))
            if (selectedFileNames.isNotEmpty()) {
                Text("선택된 파일:", fontWeight = FontWeight.Bold)
                selectedFileNames.take(5).forEach { Text("- $it", style = MaterialTheme.typography.bodySmall) }
                if (selectedFileNames.size > 5) Text("...${selectedFileNames.size - 5}개 더 있음", style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.height(6.dp))
            }
            Button(
                onClick = { started = true },
                enabled = questions.isNotEmpty() && questionCount > 0
            ) { Text("퀴즈 시작") }

            Spacer(Modifier.height(12.dp))
            HorizontalDivider()
            Spacer(Modifier.height(12.dp))
            Text("설명", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
            Text(
                "예문 속 정답 단어는 언더바(____)로 가려집니다. ‘힌트’ 버튼을 누르면 그 문장에서 쓰인 뜻이 공개됩니다. 정답은 영단어를 직접 입력하세요.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else {
            val picked = remember(questions, questionCount) {
                questions.shuffled(Random(System.currentTimeMillis())).take(questionCount)
            }
            ExampleQuiz(
                questions = picked,
                onExit = { _, _ -> started = false },
                fileName = fileName
            )
        }
    }
}

@Composable
fun ExampleQuiz(
    questions: List<ExampleQuestion>,
    onExit: (requizList: List<WordPair>?, newQuizType: QuizType?) -> Unit,
    fileName: String
) {
    var index by remember(questions) { mutableIntStateOf(0) }
    var answer by remember(questions) { mutableStateOf("") }
    var locked by remember(questions) { mutableStateOf(false) }
    var showHint by remember(questions) { mutableStateOf(false) }
    var showExitConfirm by remember(questions) { mutableStateOf(false) }
    val wrongList = remember(questions) { mutableStateListOf<WordPair>() }
    val context = LocalContext.current

    if (showExitConfirm) {
        AlertDialog(
            onDismissRequest = { showExitConfirm = false },
            title = { Text("퀴즈 끝내기") },
            text = { Text("정말로 퀴즈를 끝내시겠습니까?") },
            confirmButton = { Button(onClick = { showExitConfirm = false; onExit(null, null) }) { Text("끝내기") } },
            dismissButton = { TextButton(onClick = { showExitConfirm = false }) { Text("취소") } }
        )
    }

    val current = questions.getOrNull(index)

    if (current == null) {
        val resultContext = LocalContext.current
        val createDocLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
            if (uri != null) {
                resultContext.contentResolver.openOutputStream(uri)?.use { output ->
                    wrongList.forEach { pair -> output.write("${pair.eng} = ${pair.kor}\n".toByteArray())
                    }
                }
            }
        }
        val correctCount = questions.size - wrongList.size
        val percent = if (questions.isEmpty()) 0 else (correctCount * 100 / questions.size)
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState())
        ) {
            Text("결과", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(12.dp))
            DonutProgress(percentage = percent)
            Spacer(Modifier.height(12.dp))
            Text("정답률: $percent%  (${correctCount} / ${questions.size})")
            Spacer(Modifier.height(12.dp))
            if (wrongList.isEmpty()) {
                Text("모두 정답입니다! 👍")
            } else {
                Text("틀린 단어", fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(6.dp))
                wrongList.forEach { Text("- ${it.eng} = ${it.kor}") }
                Spacer(Modifier.height(12.dp))
                Button(
                    onClick = {
                        val cal = java.util.Calendar.getInstance()
                        val year = cal.get(java.util.Calendar.YEAR).toString().substring(2)
                        val month = (cal.get(java.util.Calendar.MONTH) + 1).toString().padStart(2, '0')
                        val day = cal.get(java.util.Calendar.DAY_OF_MONTH).toString().padStart(2, '0')
                        val hour = cal.get(java.util.Calendar.HOUR_OF_DAY).toString().padStart(2, '0')
                        val minute = cal.get(java.util.Calendar.MINUTE).toString().padStart(2, '0')
                        val dateTime = "${year}${month}${day}_${hour}${minute}"
                        val defaultFileName = "${fileName}_${dateTime}_오답.txt"
                        createDocLauncher.launch(defaultFileName)
                    },
                    modifier = Modifier.fillMaxWidth()
                ) { Text("틀린 단어 파일로 내보내기") }
            }
            Spacer(Modifier.height(12.dp))
            Button(onClick = { onExit(null, null) }, modifier = Modifier.fillMaxWidth()) { Text("예문 퀴즈 설정으로 돌아가기") }
        }
        return
    }

    val isCorrect = normalizeEnglishAnswer(answer) == normalizeEnglishAnswer(current.eng)

    Column(Modifier.fillMaxSize()) {
        Column(Modifier.weight(1f)) {
            Text("문제 ${index + 1} / ${questions.size}", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(8.dp))
            if (showHint) {
                Text("힌트(뜻): ${current.meaning}", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(6.dp))
            }
            Text("예문:", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(4.dp))
            Text(current.sentenceMasked, style = MaterialTheme.typography.bodyLarge)

            if (locked) {
                Spacer(Modifier.height(8.dp))
                Text(if (isCorrect) "정답!" else "오답!", color = if (isCorrect) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error)
                if (!isCorrect) {
                    Spacer(Modifier.height(4.dp))
                    Text("정답: ${current.eng}", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }

            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = answer,
                onValueChange = { if (!locked) answer = it },
                label = { Text("영단어 입력") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                enabled = !locked
            )
        }

        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            OutlinedButton(
                onClick = { showHint = true },
                modifier = Modifier.weight(1f)
            ) { Text("힌트") }

            if (!locked) {
                Button(
                    onClick = {
                        if (!isCorrect) {
                            wrongList.add(WordPair(current.eng, current.meaning))
                            vibrateDevice(context, isCorrect = false)
                        }
                        locked = true
                    },
                    modifier = Modifier.weight(1f)
                ) { Text(if (answer.isBlank()) "모르겠음" else "제출") }
            } else {
                Button(
                    onClick = {
                        answer = ""
                        locked = false
                        showHint = false
                        index += 1
                    },
                    modifier = Modifier.weight(1f)
                ) { Text(if (index == questions.size - 1) "결과 보기" else "다음") }
            }
        }
        Spacer(Modifier.height(8.dp))
        OutlinedButton(onClick = { showExitConfirm = true }, modifier = Modifier.fillMaxWidth()) { Text("퀴즈 끝내기") }
    }
}

@Composable
fun SelectedFilesView(fileNames: List<String>, modifier: Modifier = Modifier, isRequiz: Boolean = false) {
    var expanded by remember { mutableStateOf(false) }
    val itemsToShow = if (expanded || fileNames.size <= 5) fileNames else fileNames.take(5)
    val titleText = if (isRequiz) "퀴즈 범위: ${fileNames.firstOrNull() ?: "다시 풀기"}" else "선택된 파일:"

    Column(modifier = modifier) {
        Text(titleText, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodyMedium)
        if (!isRequiz) {
            Spacer(Modifier.height(4.dp))
            itemsToShow.forEach { Text("- $it", style = MaterialTheme.typography.bodySmall) }
            if (fileNames.size > 5) {
                TextButton(onClick = { expanded = !expanded }) { Text(if (expanded) "접기" else "...${fileNames.size - 5}개 더 보기") }
            }
        }
    }
}

@Composable
fun QuizTypeButton(text: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    if (selected) {
        Button(onClick = onClick, modifier = modifier) { Text(text) }
    } else {
        OutlinedButton(onClick = onClick, modifier = modifier) { Text(text) }
    }
}

@Composable
fun QuizHostScreen(
    modifier: Modifier,
    wholeList: List<WordPair>,
    fileName: String,
    onFileNameChange: (String) -> Unit,
    quizOverrideList: List<WordPair>?,
    onConsumeOverrideList: () -> Unit
) {
    var quizType by remember { mutableStateOf(QuizType.MC4) }
    var selectedFileNames by remember { mutableStateOf<List<String>>(emptyList()) }
    val quizPool = remember { mutableStateListOf<WordPair>() }
    var questionCount by remember { mutableIntStateOf(min(10, wholeList.size.coerceAtLeast(1))) }
    var showQuestionCountInputDialog by remember { mutableStateOf(false) }
    var tempQuestionCountInput by remember { mutableStateOf("") }
    val context = LocalContext.current
    var started by remember { mutableStateOf(false) }
    var isRequizMode by remember { mutableStateOf(false) }
    var quizSessionId by remember { mutableIntStateOf(0) }

    val handleQuizExit = remember<(List<WordPair>?, QuizType?) -> Unit> {
        { requizList, newQuizType ->
            if (requizList != null && requizList.isNotEmpty() && newQuizType != null) {
                quizPool.clear()
                quizPool.addAll(requizList)
                quizType = newQuizType
                questionCount = requizList.size
                selectedFileNames = listOf("틀린 문제 다시 풀기")
                isRequizMode = true
                quizSessionId++ // Increment key to force recomposition
                started = true
            } else {
                started = false
                isRequizMode = false
            }
        }
    }

    LaunchedEffect(quizOverrideList) {
        quizOverrideList?.let {
            quizPool.clear()
            quizPool.addAll(it)
            questionCount = it.size
            selectedFileNames = listOf("모르는 단어 다시 풀기")
            isRequizMode = true
            onConsumeOverrideList()
        }
    }

    LaunchedEffect(wholeList, started) {
        if (!started && quizPool.isEmpty() && wholeList.isNotEmpty() && !isRequizMode) {
            quizPool.addAll(wholeList)
            questionCount = min(10, quizPool.size.coerceAtLeast(1))
            selectedFileNames = emptyList()
        }
    }

    LaunchedEffect(quizPool.size, started) {
        if (!started && !isRequizMode) {
            val max = quizPool.size.coerceAtLeast(1)
            if (questionCount > max) questionCount = max
            else if (questionCount < 1 && max > 0) questionCount = 1
            else if (max == 0) questionCount = 0
        }
    }

    val openMultiLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        if (uris != null && uris.isNotEmpty()) {
            val merged = mutableListOf<WordPair>()
            val names = mutableListOf<String>()
            uris.forEach { uri ->
                try {
                    context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                } catch (_: SecurityException) { } 
                context.contentResolver.openInputStream(uri)?.use { input ->
                    val reader = java.io.BufferedReader(java.io.InputStreamReader(input))
                    reader.forEachLine { line -> parseLineToPair(line)?.let { (e, k) -> merged.add(WordPair(e, k)) } }
                }
                getDisplayName(context, uri)?.let { name -> names.add(name) }
            }
            val filtered = merged.filterNot { isInvalidEnglishWord(it.eng) }
            quizPool.clear()
            quizPool.addAll(filtered)
            selectedFileNames = names
            isRequizMode = false
            uris.firstOrNull()?.let {
                getDisplayName(context, it)?.let { name ->
                    onFileNameChange(name.removeSuffix(".txt").removeSuffix(".TXT"))
                }
            }
        }
    }

    if (showQuestionCountInputDialog) {
        val maxCount = quizPool.size.coerceAtLeast(1)
        AlertDialog(
            onDismissRequest = { showQuestionCountInputDialog = false },
            title = { Text("출제 개수 입력") },
            text = {
                OutlinedTextField(
                    value = tempQuestionCountInput,
                    onValueChange = { newValue -> tempQuestionCountInput = newValue.filter { it.isDigit() } },
                    label = { Text("개수 (1 ~ $maxCount)") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true
                )
            },
            confirmButton = {
                Button(onClick = {
                    val newCount = tempQuestionCountInput.toIntOrNull()
                    if (newCount != null && newCount in 1..maxCount) {
                        if (questionCount != newCount) {
                            questionCount = newCount
                            vibrateDevice(context, isCorrect = true)
                        }
                    }
                    showQuestionCountInputDialog = false
                }) { Text("확인") }
            },
            dismissButton = { TextButton(onClick = { showQuestionCountInputDialog = false }) { Text("취소") } }
        )
    }

    Column(modifier = modifier) {
        if (!started) {
            Text("퀴즈 설정", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(8.dp))
            val quizTypeItems = listOf(
                "4지선다" to QuizType.MC4,
                "매칭 표" to QuizType.MATCHING,
                "초성" to QuizType.INITIAL_CONSONANT,
                "영단어 완성" to QuizType.FILL_ENGLISH
            )

            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                quizTypeItems.chunked(3).forEach { rowItems ->
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        rowItems.forEach { (label, type) ->
                            QuizTypeButton(
                                text = label,
                                selected = (quizType == type),
                                onClick = { quizType = type },
                                modifier = Modifier.weight(1f)
                            )
                        }
                        repeat(3 - rowItems.size) {
                            Spacer(Modifier.weight(1f))
                        }
                    }
                }
            }

            Spacer(Modifier.height(12.dp))
            Text("퀴즈 범위", fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Button(onClick = {
                    quizPool.clear()
                    if (wholeList.isNotEmpty()) quizPool.addAll(wholeList.filterNot { isInvalidEnglishWord(it.eng) })
                    selectedFileNames = emptyList()
                    isRequizMode = false
                }) { Text("현재 리스트 사용") }
                OutlinedButton(onClick = { openMultiLauncher.launch(arrayOf("text/plain", "text/*")) }) { Text("파일에서 범위 선택") }
            }
            Spacer(Modifier.height(8.dp))
            if (isRequizMode) {
                Text("퀴즈 범위: ${selectedFileNames.firstOrNull() ?: "다시 풀기"} (${quizPool.size} 단어)")
            } else {
                Text("현재 범위: ${quizPool.size} 단어" + if (selectedFileNames.isNotEmpty()) " (파일)" else "")
            }
            if (selectedFileNames.isNotEmpty() && !isRequizMode) {
                Spacer(Modifier.height(8.dp))
                SelectedFilesView(fileNames = selectedFileNames, modifier = Modifier.padding(start = 8.dp), isRequiz = isRequizMode)
            }
            Spacer(Modifier.height(12.dp))
            val maxCount = quizPool.size.coerceAtLeast(1)
            val previousQuestionCountForSlider = remember { mutableIntStateOf(questionCount) }
            Text(
                text = "출제 개수: $questionCount / ${quizPool.size}",
                modifier = Modifier.clickable(enabled = quizPool.isNotEmpty() && !isRequizMode) { 
                    tempQuestionCountInput = questionCount.toString()
                    showQuestionCountInputDialog = true
                }
            )
            Slider(
                value = questionCount.toFloat(),
                onValueChange = { v -> if (quizPool.isNotEmpty()) questionCount = v.toInt().coerceIn(1, maxCount) },
                onValueChangeFinished = {
                    if (quizPool.isNotEmpty() && questionCount != previousQuestionCountForSlider.intValue) {
                        vibrateDevice(context, isCorrect = true)
                        previousQuestionCountForSlider.intValue = questionCount
                    }
                },
                valueRange = 1f..maxCount.toFloat().coerceAtLeast(1f),
                steps = (maxCount - 2).coerceAtLeast(0),
                enabled = quizPool.isNotEmpty() && !isRequizMode
            )
            Spacer(Modifier.height(12.dp))

            val canStart = when (quizType) {
                QuizType.MC4 -> (quizPool.size >= 4 || (isRequizMode && quizPool.isNotEmpty())) && questionCount > 0
                QuizType.MATCHING, QuizType.INITIAL_CONSONANT, QuizType.FILL_ENGLISH -> quizPool.isNotEmpty() && questionCount > 0
            }

            Button(onClick = { started = true }, enabled = canStart) { Text("퀴즈 시작") }
            Spacer(Modifier.height(12.dp))
            HorizontalDivider()
            Spacer(Modifier.height(12.dp))
        }

        if (started) {
            key(quizSessionId) {
                val questionsForThisSession = remember(quizPool, questionCount, quizType) {
                    if (questionCount > 0 && quizPool.isNotEmpty()) {
                        quizPool.shuffled(Random(System.currentTimeMillis())).take(questionCount)
                    } else {
                        emptyList<WordPair>()
                    }
                }
                val initialQuizPoolForCurrentSession = remember(quizPool, quizType) { quizPool.toList() }

                if (questionsForThisSession.isNotEmpty()) {
                    when (quizType) {
                        QuizType.MC4 -> MC4Quiz(questionsForThisSession, initialQuizPoolForCurrentSession, onExit = handleQuizExit, fileName = fileName)
                        QuizType.MATCHING -> MatchingQuiz(questionsForThisSession, onExit = handleQuizExit, fileName = fileName)
                        QuizType.INITIAL_CONSONANT -> InitialConsonantQuiz(questionsForThisSession, onExit = handleQuizExit, fileName = fileName)
                        QuizType.FILL_ENGLISH -> FillEnglishQuiz(questionsForThisSession, onExit = handleQuizExit, fileName = fileName)
                    }
                } else {
                    Text("퀴즈를 시작하기에 단어 수가 충분하지 않거나 출제 개수가 0입니다. 설정을 확인해주세요.")
                    Button(onClick = { handleQuizExit(null, null) }) { Text("설정으로 돌아가기") }
                }
            }
        } else {
            Text("퀴즈를 시작하려면 범위를 고르고 ‘퀴즈 시작’을 누르세요.")
        }
    }
}

@Composable
fun FillEnglishQuiz(
    questions: List<WordPair>,
    onExit: (requizList: List<WordPair>?, newQuizType: QuizType?) -> Unit,
    fileName: String
) {
    var index by remember(questions) { mutableIntStateOf(0) }
    var answer by remember(questions) { mutableStateOf("") }
    var locked by remember(questions) { mutableStateOf(false) }
    val wrongList = remember(questions) { mutableStateListOf<WordPair>() }
    var showExitConfirmDialog by remember(questions) { mutableStateOf(false) }
    val context = LocalContext.current

    if (showExitConfirmDialog) {
        AlertDialog(
            onDismissRequest = { showExitConfirmDialog = false },
            title = { Text("퀴즈 끝내기") },
            text = { Text("정말로 퀴즈를 끝내시겠습니까?") },
            confirmButton = { Button(onClick = { showExitConfirmDialog = false; index = questions.size }) { Text("끝내기") } },
            dismissButton = { TextButton(onClick = { showExitConfirmDialog = false }) { Text("취소") } }
        )
    }

    val current = questions.getOrNull(index)

    if (current == null) {
        val resultContext = LocalContext.current
        val createDocLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
            if (uri != null) {
                resultContext.contentResolver.openOutputStream(uri)?.use { output ->
                    wrongList.forEach { pair -> output.write("${pair.eng} = ${pair.kor}\n".toByteArray()) }
                }
            }
        }
        val correctCount = questions.size - wrongList.size
        val percent = if (questions.isEmpty()) 0 else (correctCount * 100 / questions.size)
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
            Text("결과", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(12.dp))
            DonutProgress(percentage = percent)
            Spacer(Modifier.height(12.dp))
            Text("정답률: $percent%  (${correctCount} / ${questions.size})")
            Spacer(Modifier.height(12.dp))
            if (wrongList.isEmpty()) {
                Text("모두 정답입니다! 👍")
            } else {
                Text("틀린 단어", fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(6.dp))
                wrongList.forEach { Text("- ${it.eng} = ${it.kor}") }
                Spacer(Modifier.height(12.dp))
                Button(
                    onClick = {
                        val cal = java.util.Calendar.getInstance()
                        val year = cal.get(java.util.Calendar.YEAR).toString().substring(2)
                        val month = (cal.get(java.util.Calendar.MONTH) + 1).toString().padStart(2, '0')
                        val day = cal.get(java.util.Calendar.DAY_OF_MONTH).toString().padStart(2, '0')
                        val hour = cal.get(java.util.Calendar.HOUR_OF_DAY).toString().padStart(2, '0')
                        val minute = cal.get(java.util.Calendar.MINUTE).toString().padStart(2, '0')
                        val dateTime = "${year}${month}${day}_${hour}${minute}"
                        val defaultFileName = "${fileName}_${dateTime}_오답.txt"
                        createDocLauncher.launch(defaultFileName)
                    },
                    modifier = Modifier.fillMaxWidth()
                ) { Text("틀린 단어 파일로 내보내기") }
                Spacer(Modifier.height(6.dp))
                OutlinedButton(onClick = { onExit(wrongList.toList(), QuizType.MC4) }, modifier = Modifier.fillMaxWidth()) { Text("오답으로 4지선다 퀴즈") }
                Spacer(Modifier.height(6.dp))
                OutlinedButton(onClick = { onExit(wrongList.toList(), QuizType.MATCHING) }, modifier = Modifier.fillMaxWidth()) { Text("오답으로 매칭 표 퀴즈") }
                Spacer(Modifier.height(6.dp))
                OutlinedButton(onClick = { onExit(wrongList.toList(), QuizType.INITIAL_CONSONANT) }, modifier = Modifier.fillMaxWidth()) { Text("오답으로 초성 퀴즈") }
            }
            Spacer(Modifier.height(12.dp))
            Button(onClick = { onExit(null, null) }, modifier = Modifier.fillMaxWidth()) { Text("퀴즈 설정으로 돌아가기") }
        }
        return
    }

    val masked = remember(current) { maskEnglishWord(current.eng) }
    val korMeanings = remember(current) { current.kor.split(',').map { it.trim() }.filter { it.isNotEmpty() } }

    val full = remember(current) { normalizeEnglishAnswer(current.eng) }
    val tail = remember(current) { normalizeEnglishAnswer(current.eng.drop(1)) }
    val user = normalizeEnglishAnswer(answer)
    val isCorrect = user.isNotEmpty() && (user == full || user == tail)

    Column(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.weight(1f)) {
            Text("문제 ${index + 1} / ${questions.size}", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(8.dp))
            Text("뜻: ${korMeanings.joinToString(", ")}", style = MaterialTheme.typography.bodyLarge)
            Spacer(Modifier.height(6.dp))
            Text("힌트: $masked", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
            Text("글자 수: ${current.eng.length}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

            if (locked) {
                Spacer(Modifier.height(8.dp))
                Text(if (isCorrect) "정답!" else "오답!", color = if (isCorrect) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error)
                if (!isCorrect) {
                    Spacer(Modifier.height(4.dp))
                    Text("정답: ${current.eng}", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }

            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = answer,
                onValueChange = { if (!locked) answer = it },
                label = { Text("영단어 입력 (전체 또는 남은 부분)") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                enabled = !locked
            )
        }

        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            OutlinedButton(
                onClick = {
                    if (!locked) {
                        wrongList.add(current)
                        vibrateDevice(context, isCorrect = false)
                    }
                    answer = ""
                    locked = false
                    index += 1
                },
                enabled = index < questions.size - 1,
                modifier = Modifier.weight(1f)
            ) { Text("스킵") }

            if (!locked) {
                Button(
                    onClick = {
                        if (!isCorrect) {
                            wrongList.add(current)
                            vibrateDevice(context, isCorrect = false)
                        }
                        locked = true
                    },
                    enabled = answer.isNotBlank(),
                    modifier = Modifier.weight(1f)
                ) { Text("제출") }
            } else {
                Button(
                    onClick = {
                        answer = ""
                        locked = false
                        index += 1
                    },
                    modifier = Modifier.weight(1f)
                ) { Text(if (index == questions.size - 1) "결과 보기" else "다음") }
            }
        }
        Spacer(Modifier.height(8.dp))
        OutlinedButton(onClick = { showExitConfirmDialog = true }, modifier = Modifier.fillMaxWidth()) { Text("퀴즈 끝내기") }
    }
}

@Composable
fun MC4Quiz(
    questions: List<WordPair>,
    pool: List<WordPair>,
    onExit: (requizList: List<WordPair>?, newQuizType: QuizType?) -> Unit,
    fileName: String
) {
    var index by remember(questions) { mutableIntStateOf(0) }
    var selectedIdx by remember(questions) { mutableIntStateOf(-1) }
    var locked by remember(questions) { mutableStateOf(false) }
    val wrongList = remember(questions) { mutableStateListOf<WordPair>() }
    var showExitConfirmDialog by remember(questions) { mutableStateOf(false) }
    val context = LocalContext.current

    val autoAdvanceEnabled = remember { SettingsManager.isMc4AutoAdvanceEnabled(context) }
    val autoAdvanceDelay = remember { (SettingsManager.getMc4AutoAdvanceDelay(context) * 1000).toLong() }

    LaunchedEffect(locked, index) {
        if (locked && autoAdvanceEnabled && index < questions.size - 1) {
            delay(autoAdvanceDelay)
            selectedIdx = -1
            locked = false
            index += 1
        }
    }

    if (showExitConfirmDialog) {
        AlertDialog(
            onDismissRequest = { showExitConfirmDialog = false },
            title = { Text("퀴즈 끝내기") },
            text = { Text("정말로 퀴즈를 끝내시겠습니까?") },
            confirmButton = { Button(onClick = { showExitConfirmDialog = false; onExit(null, null) }) { Text("끝내기") } },
            dismissButton = { TextButton(onClick = { showExitConfirmDialog = false }) { Text("취소") } }
        )
    }

    val currentQuestion = questions.getOrNull(index)

    val build = remember(currentQuestion, pool) { 
        if (currentQuestion == null) null else run {
            val meanings = currentQuestion.kor.split(',').map { it.trim() }.filter { it.isNotEmpty() }
            val correct = meanings.random()
            val distractors = pool
                .filter { it.id != currentQuestion.id }
                .flatMap { it.kor.split(',').map { m -> m.trim() } }
                .distinct()
                .filter { m -> !meanings.contains(m) }
                .shuffled(Random(System.nanoTime()))
                .take(3)
            val options = (distractors + correct).shuffled(Random(System.nanoTime()))
            Triple(meanings, correct, options)
        }
    }

    val correctMeanings = build?.first ?: emptyList()
    val correctOption = build?.second ?: ""
    val options = build?.third ?: emptyList()

    if (currentQuestion == null) {
        val resultContext = LocalContext.current
        val createDocLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
            if (uri != null) {
                resultContext.contentResolver.openOutputStream(uri)?.use { output ->
                    wrongList.forEach { pair -> output.write("${pair.eng} = ${pair.kor}\n".toByteArray())
                    }
                }
            }
        }
        val correctCount = questions.size - wrongList.size
        val percent = if (questions.isEmpty()) 0 else (correctCount * 100 / questions.size)
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState())
        ){
            Text("결과", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(12.dp))
            DonutProgress(percentage = percent)
            Spacer(Modifier.height(12.dp))
            Text("정답률: $percent%  (${correctCount} / ${questions.size})")
            Spacer(Modifier.height(12.dp))
            if (wrongList.isEmpty()) {
                Text("모두 정답입니다! 👍")
            } else {
                Text("틀린 단어", fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(6.dp))
                wrongList.forEach { Text("- ${it.eng} = ${it.kor}") }
                Spacer(Modifier.height(12.dp))
                Button(
                    onClick = {
                        val cal = java.util.Calendar.getInstance()
                        val year = cal.get(java.util.Calendar.YEAR).toString().substring(2)
                        val month = (cal.get(java.util.Calendar.MONTH) + 1).toString().padStart(2, '0')
                        val day = cal.get(java.util.Calendar.DAY_OF_MONTH).toString().padStart(2, '0')
                        val hour = cal.get(java.util.Calendar.HOUR_OF_DAY).toString().padStart(2, '0')
                        val minute = cal.get(java.util.Calendar.MINUTE).toString().padStart(2, '0')
                        val dateTime = "${year}${month}${day}_${hour}${minute}"
                        val defaultFileName = "${fileName}_${dateTime}_오답.txt"
                        createDocLauncher.launch(defaultFileName)
                    },
                    modifier = Modifier.fillMaxWidth()
                ) { Text("틀린 단어 파일로 내보내기") }
                Spacer(Modifier.height(6.dp))
                OutlinedButton(onClick = { onExit(wrongList.toList(), QuizType.MC4) }, modifier = Modifier.fillMaxWidth()) { Text("틀린 문제로 4지선다 퀴즈") }
                Spacer(Modifier.height(6.dp))
                OutlinedButton(onClick = { onExit(wrongList.toList(), QuizType.MATCHING) }, modifier = Modifier.fillMaxWidth()) { Text("틀린 문제로 매칭 표 퀴즈") }
                Spacer(Modifier.height(6.dp))
                OutlinedButton(onClick = { onExit(wrongList.toList(), QuizType.INITIAL_CONSONANT) }, modifier = Modifier.fillMaxWidth()) { Text("틀린 문제로 초성 퀴즈") }
            }
            Spacer(Modifier.height(12.dp))
            Button(onClick = { onExit(null, null) }, modifier = Modifier.fillMaxWidth()) { Text("퀴즈 설정으로 돌아가기") }
        }
        return
    }

    Column(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.weight(1f)) {
            Text("문제 ${index + 1} / ${questions.size}", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(12.dp))
            Text("영어: ${currentQuestion.eng}", style = MaterialTheme.typography.bodyLarge)

            if (locked) {
                val userChoice = options.getOrNull(selectedIdx)
                val isCorrect = userChoice != null && (equalsRelaxed(userChoice, correctOption) || correctMeanings.any { equalsRelaxed(it, userChoice!!) })
                Text(
                    if (isCorrect) "정답!" else "오답!",
                    color = if (isCorrect) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
                )
            }

            Spacer(Modifier.height(8.dp))
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                options.forEachIndexed { i, text ->
                    val isSelected = selectedIdx == i
                    val isCorrectOpt = equalsRelaxed(text, correctOption)

                    val colors = if (locked) {
                        when {
                            isSelected && isCorrectOpt -> ButtonDefaults.buttonColors(
                                containerColor = Color(0xFF2E7D32),
                                contentColor = Color.White
                            )
                            isSelected && !isCorrectOpt -> ButtonDefaults.buttonColors(
                                containerColor = Color(0xFFC62828),
                                contentColor = Color.White
                            )
                            !isSelected && isCorrectOpt -> ButtonDefaults.buttonColors(
                                containerColor = Color(0xFF2E7D32),
                                contentColor = Color.White
                            )
                            else -> ButtonDefaults.buttonColors()
                        }
                    } else {
                        ButtonDefaults.buttonColors()
                    }

                    Button(
                        onClick = {
                            if (locked) return@Button
                            selectedIdx = i
                            locked = true
                            val userChoseCorrectly = 
                                equalsRelaxed(text, correctOption) || correctMeanings.any { equalsRelaxed(it, text) }
                            
                            vibrateDevice(context, userChoseCorrectly)

                            if (!userChoseCorrectly) {
                                wrongList.add(currentQuestion)
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                        colors = colors
                    ) { Text(text) }
                }
            }
        }
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            OutlinedButton(
                onClick = {
                    if (!locked) {
                        wrongList.add(currentQuestion)
                        vibrateDevice(context, isCorrect = false)
                        locked = true
                    }
                },
                enabled = index < questions.size - 1,
                modifier = Modifier.weight(1f)
            ) { Text("스킵") }
            Button(
                onClick = {
                    selectedIdx = -1
                    locked = false
                    index += 1
                },
                enabled = locked,
                modifier = Modifier.weight(1f)
            ) { Text(if (index == questions.size - 1 && locked) "결과 보기" else "다음") }
        }
        Spacer(Modifier.height(8.dp))
        OutlinedButton(onClick = { showExitConfirmDialog = true }, modifier = Modifier.fillMaxWidth()) { Text("퀴즈 끝내기") }
    }
}

@Composable
fun DonutProgress(percentage: Int, size: Int = 140, stroke: Int = 18) {
    val sweep = (percentage.coerceIn(0, 100) / 100f) * 360f
    val trackColor = MaterialTheme.colorScheme.surfaceVariant
    val progressColor = MaterialTheme.colorScheme.primary
    Box(Modifier.size(size.dp), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            drawArc(
                color = trackColor, startAngle = -90f, sweepAngle = 360f,
                useCenter = false, style = Stroke(width = stroke.dp.toPx(), cap = StrokeCap.Round)
            )
            drawArc(
                color = progressColor, startAngle = -90f, sweepAngle = sweep,
                useCenter = false, style = Stroke(width = stroke.dp.toPx(), cap = StrokeCap.Round)
            )
        }
        Text("$percentage%")
    }
}

@Composable
fun MatchingQuiz(
    questions: List<WordPair>,
    onExit: (requizList: List<WordPair>?, newQuizType: QuizType?) -> Unit,
    fileName: String
) {
    val shuffledQuestions = remember(questions) { questions.shuffled(Random(System.currentTimeMillis())) }
    val userAnswers = remember(questions) {
        mutableStateListOf<String>().apply { repeat(shuffledQuestions.size) { add("") } }
    }
    var submitted by remember(questions) { mutableStateOf(false) }
    var showExitConfirmDialog by remember(questions) { mutableStateOf(false) }
    val wrongList = remember(questions) { mutableStateListOf<WordPair>() }

    if (showExitConfirmDialog) {
        AlertDialog(
            onDismissRequest = { showExitConfirmDialog = false },
            title = { Text("퀴즈 끝내기") },
            text = { Text("정말로 퀴즈를 끝내시겠습니까?") },
            confirmButton = { Button(onClick = { showExitConfirmDialog = false; onExit(null, null) }) { Text("끝내기") } },
            dismissButton = { TextButton(onClick = { showExitConfirmDialog = false }) { Text("취소") } }
        )
    }

    val context = LocalContext.current
    val createDocLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
        if (uri != null) {
            context.contentResolver.openOutputStream(uri)?.use { output ->
                wrongList.forEach { pair -> output.write("${pair.eng} = ${pair.kor}\n".toByteArray())
                }
            }
        }
    }

    fun markResults() {
        wrongList.clear()
        shuffledQuestions.forEachIndexed { index, wordPair ->
            val answerRaw = userAnswers[index]
            val correctList = wordPair.kor.split(',').map { it.trim() }.filter { it.isNotEmpty() }
            val isCorrect = correctList.any { equalsRelaxed(it, answerRaw) }
            if (!isCorrect) wrongList.add(wordPair)
        }
    }

    Column(Modifier.fillMaxSize()) {
        LazyColumn(modifier = Modifier.weight(1f), contentPadding = PaddingValues(bottom = 12.dp)) {
            items(shuffledQuestions.size) { index ->
                val item = shuffledQuestions[index]
                val correctList = remember(item) { item.kor.split(',').map { it.trim() }.filter { it.isNotEmpty() } }

                Column(modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            item.eng,
                            modifier =
                                Modifier
                                    .weight(1f)
                                    .padding(8.dp)
                                    .background(MaterialTheme.colorScheme.surfaceVariant),
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        OutlinedTextField(
                            value = userAnswers[index],
                            onValueChange = { userAnswers[index] = it },
                            label = { Text("뜻 입력") },
                            modifier = Modifier.weight(1f),
                            singleLine = true,
                            enabled = !submitted
                        )
                    }

                    if (submitted) {
                        val ans = userAnswers[index]
                        val matched = correctList.firstOrNull { equalsRelaxed(it, ans) }
                        val isCorrect = matched != null
                        Spacer(Modifier.height(6.dp))
                        if (isCorrect) {
                            Text("정답!", color = MaterialTheme.colorScheme.primary)
                            val others = correctList.filterNot { equalsRelaxed(it, ans) }
                            if (others.isNotEmpty()) {
                                Spacer(Modifier.height(2.dp))
                                Text("다른 뜻: ${others.joinToString(", ")}", color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        } else {
                            Text("오답!", color = MaterialTheme.colorScheme.error)
                            Spacer(Modifier.height(2.dp))
                            Text("정답: ${correctList.joinToString(", ")}", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }

                if (index < shuffledQuestions.size - 1) HorizontalDivider()
            }
        }

        if (!submitted) {
            Button(
                onClick = {
                    submitted = true
                    markResults()
                },
                modifier = Modifier.fillMaxWidth()
            ) { Text("제출하기") }
            Spacer(Modifier.height(6.dp))
            OutlinedButton(onClick = { showExitConfirmDialog = true }, modifier = Modifier.fillMaxWidth()) { Text("퀴즈 끝내기") }
        } else {
            val correctCount = shuffledQuestions.size - wrongList.size
            Text(
                "정답: $correctCount / ${shuffledQuestions.size}",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(vertical = 8.dp)
            )
            if (wrongList.isNotEmpty()) {
                Button(
                    onClick = {
                        val cal = java.util.Calendar.getInstance()
                        val year = cal.get(java.util.Calendar.YEAR).toString().substring(2)
                        val month = (cal.get(java.util.Calendar.MONTH) + 1).toString().padStart(2, '0')
                        val day = cal.get(java.util.Calendar.DAY_OF_MONTH).toString().padStart(2, '0')
                        val hour = cal.get(java.util.Calendar.HOUR_OF_DAY).toString().padStart(2, '0')
                        val minute = cal.get(java.util.Calendar.MINUTE).toString().padStart(2, '0')
                        val dateTime = "${year}${month}${day}_${hour}${minute}"
                        val defaultFileName = "${fileName}_${dateTime}_오답.txt"
                        createDocLauncher.launch(defaultFileName)
                    },
                    modifier = Modifier.fillMaxWidth()
                ) { Text("틀린 단어 파일로 내보내기") }
                Spacer(Modifier.height(6.dp))
                OutlinedButton(onClick = { onExit(wrongList.toList(), QuizType.MC4) }, modifier = Modifier.fillMaxWidth()) { Text("오답으로 4지선다 퀴즈") }
                Spacer(Modifier.height(6.dp))
                OutlinedButton(onClick = { onExit(wrongList.toList(), QuizType.MATCHING) }, modifier = Modifier.fillMaxWidth()) { Text("오답으로 매칭 표 다시 풀기") }
                Spacer(Modifier.height(6.dp))
                OutlinedButton(onClick = { onExit(wrongList.toList(), QuizType.INITIAL_CONSONANT) }, modifier = Modifier.fillMaxWidth()) { Text("오답으로 초성 퀴즈") }
            } else {
                Text("모두 정답입니다! 👍", modifier = Modifier.padding(vertical = 4.dp))
            }
            Spacer(Modifier.height(8.dp))
            OutlinedButton(onClick = { showExitConfirmDialog = true }, modifier = Modifier.fillMaxWidth()) { Text("퀴즈 끝내기") }
        }
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
fun InitialConsonantQuiz(
    questions: List<WordPair>,
    onExit: (requizList: List<WordPair>?, newQuizType: QuizType?) -> Unit,
    fileName: String
) {
    var index by remember(questions) { mutableIntStateOf(0) }
    var answer by remember(questions) { mutableStateOf("") }
    var locked by remember(questions) { mutableStateOf(false) }
    val wrongList = remember(questions) { mutableStateListOf<WordPair>() }
    var showExitConfirmDialog by remember(questions) { mutableStateOf(false) }
    val context = LocalContext.current

    if (showExitConfirmDialog) {
        AlertDialog(
            onDismissRequest = { showExitConfirmDialog = false },
            title = { Text("퀴즈 끝내기") },
            text = { Text("정말로 퀴즈를 끝내시겠습니까?") },
            confirmButton = { Button(onClick = { showExitConfirmDialog = false; index = questions.size }) { Text("끝내기") } },
            dismissButton = { TextButton(onClick = { showExitConfirmDialog = false }) { Text("취소") } }
        )
    }

    val currentQuestion = questions.getOrNull(index)

    if (currentQuestion == null) {
        val resultContext = LocalContext.current
        val createDocLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
            if (uri != null) {
                resultContext.contentResolver.openOutputStream(uri)?.use { output ->
                    wrongList.forEach { pair -> output.write("${pair.eng} = ${pair.kor}\n".toByteArray())
                    }
                }
            }
        }
        val correctCount = questions.size - wrongList.size
        val percent = if (questions.isEmpty()) 0 else (correctCount * 100 / questions.size)
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState())){
            Text("결과", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(12.dp))
            DonutProgress(percentage = percent)
            Spacer(Modifier.height(12.dp))
            Text("정답률: $percent%  (${correctCount} / ${questions.size})")
            Spacer(Modifier.height(12.dp))
            if (wrongList.isEmpty()) {
                Text("모두 정답입니다! 👍")
            } else {
                Text("틀린 단어", fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(6.dp))
                wrongList.forEach { Text("- ${it.eng} = ${it.kor}") }
                Spacer(Modifier.height(12.dp))
                Button(
                    onClick = {
                        val cal = java.util.Calendar.getInstance()
                        val year = cal.get(java.util.Calendar.YEAR).toString().substring(2)
                        val month = (cal.get(java.util.Calendar.MONTH) + 1).toString().padStart(2, '0')
                        val day = cal.get(java.util.Calendar.DAY_OF_MONTH).toString().padStart(2, '0')
                        val hour = cal.get(java.util.Calendar.HOUR_OF_DAY).toString().padStart(2, '0')
                        val minute = cal.get(java.util.Calendar.MINUTE).toString().padStart(2, '0')
                        val dateTime = "${year}${month}${day}_${hour}${minute}"
                        val defaultFileName = "${fileName}_${dateTime}_오답.txt"
                        createDocLauncher.launch(defaultFileName)
                    },
                    modifier = Modifier.fillMaxWidth()
                ) { Text("틀린 단어 파일로 내보내기") }
                Spacer(Modifier.height(6.dp))
                OutlinedButton(onClick = { onExit(wrongList.toList(), QuizType.MC4) }, modifier = Modifier.fillMaxWidth()) { Text("틀린 문제로 4지선다 퀴즈") }
                Spacer(Modifier.height(6.dp))
                OutlinedButton(onClick = { onExit(wrongList.toList(), QuizType.MATCHING) }, modifier = Modifier.fillMaxWidth()) { Text("틀린 문제로 매칭 표 퀴즈") }
                Spacer(Modifier.height(6.dp))
                OutlinedButton(onClick = { onExit(wrongList.toList(), QuizType.INITIAL_CONSONANT) }, modifier = Modifier.fillMaxWidth()) { Text("틀린 문제로 초성 퀴즈") }
            }
            Spacer(Modifier.height(12.dp))
            Button(onClick = { onExit(null, null) }, modifier = Modifier.fillMaxWidth()) { Text("퀴즈 설정으로 돌아가기") }
        }
        return
    }

    val meanings = remember(currentQuestion) {
        currentQuestion.kor.split(',').map { it.trim() }.filter { it.isNotEmpty() }
    }
    val chosenMeaning = remember(currentQuestion) { meanings.randomOrNull().orEmpty() }
    val initials = remember(currentQuestion) { getInitialConsonants(chosenMeaning) }

    val matchedMeaning = remember(locked, answer, meanings) {
        val a = answer.trim()
        meanings.firstOrNull { equalsRelaxed(it, a) }
    }
    val isCorrect = matchedMeaning != null

    Column(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.weight(1f)) {
            Text("문제 ${index + 1} / ${questions.size}", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(12.dp))
            Text("영어: ${currentQuestion.eng}", style = MaterialTheme.typography.bodyLarge)
            Spacer(Modifier.height(6.dp))
            Text("초성: $initials", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.height(8.dp))

            if (locked) {
                Text(if (isCorrect) "정답!" else "오답!", color = if (isCorrect) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error)
                Spacer(Modifier.height(6.dp))
                if (isCorrect) {
                    val others = meanings.filterNot { equalsRelaxed(it, matchedMeaning!!) }
                    if (others.isNotEmpty()) {
                        Text("다른 뜻: ${others.joinToString(", ")}", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                } else {
                    Text("정답: ${meanings.joinToString(", ")}", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }

            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = answer,
                onValueChange = { if (!locked) answer = it },
                label = { Text("뜻을 그대로 입력") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                enabled = !locked
            )
        }

        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            OutlinedButton(
                onClick = {
                    if (!locked) {
                        wrongList.add(currentQuestion)
                        vibrateDevice(context, isCorrect = false)
                        locked = true
                    }
                },
                enabled = index < questions.size - 1,
                modifier = Modifier.weight(1f)
            ) { Text("스킵") }

            if (!locked) {
                Button(
                    onClick = {
                        if (!isCorrect) {
                            wrongList.add(currentQuestion)
                            vibrateDevice(context, isCorrect = false)
                        }
                        locked = true
                    },
                    enabled = answer.isNotBlank(),
                    modifier = Modifier.weight(1f)
                ) { Text("제출") }
            } else {
                Button(
                    onClick = {
                        answer = ""
                        locked = false
                        index += 1
                    },
                    modifier = Modifier.weight(1f)
                ) { Text(if (index == questions.size - 1) "결과 보기" else "다음") }
            }
        }
        Spacer(Modifier.height(8.dp))
        OutlinedButton(onClick = { showExitConfirmDialog = true }, modifier = Modifier.fillMaxWidth()) { Text("퀴즈 끝내기") }
    }
}
